"""Thermal printer driver for the iDPRT/HPRT S1 (48mm / 384-dot).

Drop-in replacement for Mo's serial ESC/POS printer.py. Same public surface:

    print_image(image_path, device=None, card_text=None)
    print_text(text, device=None)

The S1 does not speak ESC/POS. Protocol recovered from HerePrint's own code
(HPRTAndroidSDK / com.poooli.printersdk):

    1D 76 30 30                 magic
    <row_bytes lo> <hi>         48 for a 384-dot head
    <rows lo> <hi>              rows in THIS band
    <len 4 bytes LE>            compressed payload length
    <LZO1X payload>             1bpp raster, MSB-first, bit set = black

Bands are 30 rows. Other commands:

    1D 73 65 74 63 <n>          density  ("GS set c"), 0-255
    1D 73 65 74 73 <n>          speed    ("GS set s")
    1B 1B 01 <lo> <hi>          feed n dots
    1B 1B 00                    test page

Transport is Bluetooth Classic SPP, not BLE. HerePrint uses BLE only to
discover the printer and sends print data over RFCOMM; BLE accepts small jobs
but drops the link partway through a full-height image regardless of pacing.
On Windows a paired S1 appears as a "Standard Serial over Bluetooth link" COM
port; on Linux, bind it with rfcomm to get /dev/rfcomm0.

The payload is LZO1X. Rather than require a native LZO library, this emits a
valid LZO1X stream made of one extended literal run - no compression, but the
printer's decompressor accepts it. Verified against liblzo2.

Environment:
    MO_PRINTER_DEVICE    COM12, or /dev/rfcomm0  (required)
    MO_PRINTER_DENSITY   0-255, default 75 (vendor app uses 55/75/95)
    MO_PRINTER_FEED      dots fed after each image, default 100
"""

import os
import threading
import time
from pathlib import Path

import serial
from PIL import Image, ImageDraw, ImageFont

PRINTER_WIDTH_DOTS = 384
ROW_BYTES = PRINTER_WIDTH_DOTS // 8
ROWS_PER_BAND = 30

PRINTER_DEVICE = os.environ.get("MO_PRINTER_DEVICE")
PRINTER_DENSITY = int(os.environ.get("MO_PRINTER_DENSITY", "75"))
PRINTER_FEED = int(os.environ.get("MO_PRINTER_FEED", "100"))

BAUD = 115200
WRITE_TIMEOUT = 60
READ_TIMEOUT = 5

# ser.flush() returns once the OS has handed the bytes to the Bluetooth stack,
# not once the printer has consumed them. Closing the port at that moment
# discards whatever is still in flight, which truncates the tail of a long
# job. Hold the port open afterwards, scaled to the job size.
DRAIN_BASE = float(os.environ.get("MO_PRINTER_DRAIN", "3.0"))
DRAIN_PER_KB = float(os.environ.get("MO_PRINTER_DRAIN_PER_KB", "0.25"))

END_MARKER = bytes([0x11, 0x00, 0x00])

# BLE allows one connection and SPP one open handle, so serialise prints.
# Without this, two browser tabs printing at once fail on a port-busy error.
_print_lock = threading.Lock()


# ----------------------------------------------------------------- LZO1X

def _lzo1x_literal_only(data):
    """A valid LZO1X stream holding the payload as one literal run.

    LZO1X cannot chain literal runs without intervening matches, so everything
    goes in a single extended run.
    """
    n = len(data)
    if n == 0:
        return END_MARKER

    out = bytearray()
    if 4 <= n <= 18:
        out.append(n - 3)
    elif n <= 238:
        out.append(17 + n)
    else:
        rem = n - 18
        k, b = divmod(rem, 255)
        if b == 0:
            k -= 1
            b = 255
        out.append(0x00)
        out += bytes(k)
        out.append(b)

    out += data
    out += END_MARKER
    return bytes(out)


# ----------------------------------------------------------------- raster

def _image_to_raster_data(img):
    """Convert an image to 1bpp rows, MSB-first, bit set = black."""
    if img.mode in ("RGBA", "LA", "P"):
        img = img.convert("RGBA")
        flat = Image.new("RGBA", img.size, (255, 255, 255, 255))
        flat.paste(img, mask=img.split()[-1])
        img = flat

    if img.width != PRINTER_WIDTH_DOTS:
        ratio = PRINTER_WIDTH_DOTS / img.width
        img = img.resize((PRINTER_WIDTH_DOTS, max(1, int(img.height * ratio))),
                         Image.LANCZOS)

    if img.mode != "1":
        img = img.convert("1")

    pixels = img.load()
    raster = bytearray(ROW_BYTES * img.height)

    for y in range(img.height):
        base = y * ROW_BYTES
        for x in range(PRINTER_WIDTH_DOTS):
            if pixels[x, y] == 0:
                raster[base + (x >> 3)] |= 0x80 >> (x & 7)

    return bytes(raster), img.height


# ---------------------------------------------------------------- commands

def _cmd_density(n):
    return bytes([0x1D, 0x73, 0x65, 0x74, 0x63, n & 0xFF])


def _cmd_speed(n):
    return bytes([0x1D, 0x73, 0x65, 0x74, 0x73, n & 0xFF])


def _cmd_feed(dots):
    return bytes([0x1B, 0x1B, 0x01, dots & 0xFF, (dots >> 8) & 0xFF])


def _cmd_testpage():
    return bytes([0x1B, 0x1B, 0x00])


def _cmd_image_bands(raster, height):
    band_bytes = ROW_BYTES * ROWS_PER_BAND
    packets = []

    for start in range(0, len(raster), band_bytes):
        band = raster[start:start + band_bytes]
        rows = len(band) // ROW_BYTES
        if rows == 0:
            continue
        payload = _lzo1x_literal_only(band)
        n = len(payload)
        header = bytes([
            0x1D, 0x76, 0x30, 0x30,
            ROW_BYTES & 0xFF, (ROW_BYTES >> 8) & 0xFF,
            rows & 0xFF, (rows >> 8) & 0xFF,
            n & 0xFF, (n >> 8) & 0xFF, (n >> 16) & 0xFF, (n >> 24) & 0xFF,
        ])
        packets.append(header + payload)

    return packets


# --------------------------------------------------------------- transport

def _resolve_printer_device(device=None):
    port = device or PRINTER_DEVICE
    if not port:
        raise RuntimeError(
            "No printer device. Set MO_PRINTER_DEVICE to the S1's SPP port, "
            "e.g. COM12 on Windows or /dev/rfcomm0 on Linux."
        )
    return port


def _send_raw(segments, device=None):
    """Write to the printer's SPP port, one segment per write.

    Writing the whole job as a single ser.write() stalls near the end on
    Windows' Bluetooth SPP stack; writing each band separately and flushing
    after each one does not. Matches how the standalone tester behaves.
    """
    port = _resolve_printer_device(device)

    if isinstance(segments, (bytes, bytearray)):
        segments = [bytes(segments)]

    with _print_lock:
        with serial.Serial(port, BAUD,
                           timeout=READ_TIMEOUT,
                           write_timeout=WRITE_TIMEOUT) as ser:
            total = 0
            for segment in segments:
                ser.write(segment)
                ser.flush()
                total += len(segment)

            drain = DRAIN_BASE + DRAIN_PER_KB * (total / 1024.0)
            deadline = time.monotonic() + drain
            while time.monotonic() < deadline:
                if ser.in_waiting:
                    ser.read(ser.in_waiting)
                time.sleep(0.2)

    return True


# ------------------------------------------------------------------- API

def print_image(image_path, device=None, card_text=None):
    """Print an image file, optionally followed by text."""
    image_path = Path(image_path)
    if not image_path.exists():
        raise FileNotFoundError(f"Image not found: {image_path}")

    img = Image.open(image_path)
    raster, height = _image_to_raster_data(img)

    segments = [_cmd_density(PRINTER_DENSITY)]
    segments += _cmd_image_bands(raster, height)

    if card_text:
        text_raster, text_height = _image_to_raster_data(_render_text(card_text))
        segments += _cmd_image_bands(text_raster, text_height)

    segments.append(_cmd_feed(PRINTER_FEED))

    _send_raw(segments, device)
    return True


def print_text(text, device=None):
    """Print text. The S1 has no text mode, so the text is rendered to a bitmap."""
    raster, height = _image_to_raster_data(_render_text(text))

    segments = [_cmd_density(PRINTER_DENSITY)]
    segments += _cmd_image_bands(raster, height)
    segments.append(_cmd_feed(PRINTER_FEED))

    _send_raw(segments, device)
    return True


def print_test_page(device=None):
    """Printer's built-in self test. Useful for checking the link."""
    _send_raw(_cmd_density(PRINTER_DENSITY) + _cmd_testpage(), device)
    return True


def feed(dots=100, device=None):
    """Advance the paper."""
    _send_raw(_cmd_feed(dots), device)
    return True


def _render_text(text, font_size=20, margin=8, line_spacing=4):
    """Lay text out into a 384-wide 1-bit image."""
    font = None
    for candidate in (
        "C:/Windows/Fonts/consola.ttf",
        "C:/Windows/Fonts/arial.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ):
        try:
            font = ImageFont.truetype(candidate, font_size)
            break
        except OSError:
            continue
    if font is None:
        font = ImageFont.load_default()

    max_width = PRINTER_WIDTH_DOTS - 2 * margin
    probe = ImageDraw.Draw(Image.new("1", (1, 1), 1))

    lines = []
    for paragraph in str(text).split("\n"):
        if not paragraph.strip():
            lines.append("")
            continue
        current = ""
        for word in paragraph.split():
            trial = f"{current} {word}".strip()
            if probe.textlength(trial, font=font) <= max_width or not current:
                current = trial
            else:
                lines.append(current)
                current = word
        if current:
            lines.append(current)

    ascent, descent = font.getmetrics()
    line_height = ascent + descent + line_spacing
    height = max(line_height, len(lines) * line_height + 2 * margin)

    img = Image.new("1", (PRINTER_WIDTH_DOTS, height), 1)
    draw = ImageDraw.Draw(img)
    y = margin
    for line in lines:
        draw.text((margin, y), line, font=font, fill=0)
        y += line_height

    return img
