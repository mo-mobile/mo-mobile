"""Raster routes: raw printer byte streams for external print bridges.

The Android app is a WebView around this site plus a Bluetooth SPP writer. It
does not know the printer protocol - it asks for a finished byte stream here
and writes it to the socket verbatim. That keeps all protocol knowledge on the
server, so a protocol fix never requires reinstalling the app.

    GET  /api/print-data/token/<token_id>
    GET  /api/print-data/card/<card_type>/<cmc>/<filename>
    GET  /api/print-data/avatar?name=momir_avatar
    POST /api/print-data/decklist        {"cards": [...]}  -> plan, not bytes

Single-image routes return application/octet-stream: density command, image
bands, feed.

The decklist route returns JSON rather than one enormous stream. A 300-card
list would be hundreds of megabytes in a single response, and the phone needs
to print each card as a separate job anyway - one long continuous stream is
exactly what the printer chokes on.
"""

from pathlib import Path
from urllib.parse import quote

from flask import Blueprint, Response, jsonify, request
from PIL import Image

from ..helpers import VALID_CARD_TYPES
from ..printer import (
    PRINTER_DENSITY,
    PRINTER_FEED,
    _cmd_density,
    _cmd_feed,
    _cmd_image_bands,
    _image_to_raster_data,
)
from ..utils import get_local_image_for_token
from ..paths import DATA_ROOT

bp = Blueprint('printdata', __name__)


def _root():
    return DATA_ROOT


def _build_stream(image_path, density=None, feed=None):
    """Assemble the full byte stream for one image."""
    img = Image.open(str(image_path))
    raster, height = _image_to_raster_data(img)

    density = PRINTER_DENSITY if density is None else density
    feed = PRINTER_FEED if feed is None else feed

    payload = bytearray()
    payload += _cmd_density(density)
    for packet in _cmd_image_bands(raster, height):
        payload += packet
    payload += _cmd_feed(feed)

    return bytes(payload)


def _find_card_image(card_type, cmc, filename):
    """Locate a card image, preferring the dithered copy."""
    safe_type = Path(card_type).name
    safe_name = Path(filename).name

    for images_dir in (_root() / 'images_dithered' / safe_type / str(cmc),
                       _root() / 'images' / safe_type / str(cmc)):
        for ext in ('.bmp', '.jpg'):
            path = images_dir / f'{safe_name}{ext}'
            if path.exists():
                return path
    return None


def _int_arg(name):
    value = request.args.get(name)
    if value is None:
        return None
    try:
        parsed = int(value)
    except ValueError:
        return None
    return max(0, min(65535, parsed))


def _stream_response(payload, filename):
    return Response(
        payload,
        mimetype='application/octet-stream',
        headers={
            'Content-Disposition': f'attachment; filename="{filename}"',
            'Content-Length': str(len(payload)),
            'X-Printer-Bytes': str(len(payload)),
        },
    )


@bp.route('/api/print-data/token/<token_id>')
def print_data_token(token_id):
    image_path = get_local_image_for_token(token_id)
    if image_path is None:
        return jsonify({'error': 'Token image not found'}), 404

    payload = _build_stream(image_path, _int_arg('density'), _int_arg('feed'))
    return _stream_response(payload, f'{token_id}.bin')


@bp.route('/api/print-data/card/<card_type>/<int:cmc>/<filename>')
def print_data_card(card_type, cmc, filename):
    if card_type not in VALID_CARD_TYPES:
        return jsonify({'error': 'Invalid card type'}), 400

    path = _find_card_image(card_type, cmc, filename)
    if path is None:
        return jsonify({'error': 'Image not found'}), 404

    payload = _build_stream(path, _int_arg('density'), _int_arg('feed'))
    return _stream_response(payload, f'{Path(filename).name}.bin')


@bp.route('/api/print-data/avatar')
def print_data_avatar():
    name = Path(request.args.get('name', 'momir_avatar')).name
    path = _root() / 'images_dithered' / 'avatar' / f'{name}.bmp'
    if not path.exists():
        return jsonify({'error': 'Avatar image not found'}), 404

    payload = _build_stream(path, _int_arg('density'), _int_arg('feed'))
    return _stream_response(payload, f'{name}.bin')


@bp.route('/api/print-data/decklist', methods=['POST'])
def print_data_decklist():
    """Expand a decklist into an ordered list of print-data URLs.

    Returns the plan, not the bytes. The client fetches each URL in turn and
    prints them as separate jobs.
    """
    data = request.get_json(silent=True) or {}
    cards = data.get('cards', [])

    if not isinstance(cards, list) or len(cards) > 300:
        return jsonify({'error': 'Invalid cards list'}), 400

    jobs = []
    missing = []

    for entry in cards:
        if not isinstance(entry, dict):
            continue

        card_type = entry.get('card_type', '')
        if card_type not in VALID_CARD_TYPES:
            continue

        try:
            cmc = int(entry.get('cmc', 0))
        except (TypeError, ValueError):
            continue

        filename = entry.get('filename', '')
        if not filename:
            continue

        try:
            quantity = max(1, min(int(entry.get('quantity', 1)), 20))
        except (TypeError, ValueError):
            quantity = 1

        if _find_card_image(card_type, cmc, filename) is None:
            missing.append(filename)
            continue

        for _ in range(quantity):
            jobs.append({
                'name': filename,
                'url': (f'/api/print-data/card/{card_type}/{cmc}/'
                        f'{quote(filename, safe="")}'),
            })

    return jsonify({
        'success': True,
        'count': len(jobs),
        'jobs': jobs,
        'missing': missing,
    })
