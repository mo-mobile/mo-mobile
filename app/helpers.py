"""Shared state, constants and helper functions used across all route blueprints."""

import re
import random
import threading
from pathlib import Path

from .utils import (
    load_token_data,
    get_local_image_for_card,
    get_random_local_card_image,
    get_random_local_card_image_any_cmc,
    get_local_image_for_token,
    search_tokens,
)
from .printer import print_image, print_text
from .paths import APP_DATA, DATA_ROOT

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

VALID_CARD_TYPES = {
    'creature', 'instant', 'sorcery', 'artifact',
    'enchantment', 'land', 'planeswalker', 'battle',
}

# ---------------------------------------------------------------------------
# Lazy-loaded data
# ---------------------------------------------------------------------------

# Cached JSON data, reloaded whenever the file changes. The card data can be
# built while the server is already running (first start in Docker, or on a
# phone), so a file that was missing at first must still be picked up later.
_CACHE = {}


def _load_cached(path, label):
    try:
        mtime = path.stat().st_mtime
    except OSError:
        return []
    cached = _CACHE.get(path)
    if cached and cached[0] == mtime:
        return cached[1]
    data = load_token_data(str(path))
    if not isinstance(data, list):
        data = []
    if data:
        print(f"✓ Loaded {len(data)} {label}")
    _CACHE[path] = (mtime, data)
    return data


def get_token_data():
    return _load_cached(APP_DATA / 'token_data.json', 'tokens')


def get_card_text_index():
    return _load_cached(APP_DATA / 'card_text_index.json', 'card text index entries')


def has_local_token_images():
    image_roots = [
        DATA_ROOT / 'images_dithered' / 'token',
        DATA_ROOT / 'images' / 'token',
    ]
    return any(path.exists() and any(path.iterdir()) for path in image_roots)


# ---------------------------------------------------------------------------
# Card name / image helpers
# ---------------------------------------------------------------------------

def normalize_card_name_for_image(name):
    return (name or '').replace('/', '').replace('"', '').replace("'", '').strip().lower()


def find_card_by_image_name(image_stem, cmc, card_type='creature'):
    normalized_stem = normalize_card_name_for_image(image_stem)
    if not normalized_stem:
        return None
    card_index = get_card_text_index()
    if not card_index:
        return None
    matches = [
        card for card in card_index
        if card.get('manaValue', -1) == cmc
        and card.get('card_type', 'creature') == card_type
        and normalize_card_name_for_image(card.get('name', '')) == normalized_stem
    ]
    if not matches:
        return None
    with_rules = [card for card in matches if (card.get('text') or '').strip()]
    return random.choice(with_rules or matches)


def find_card_image_stem(name, cmc, card_type='creature'):
    root = DATA_ROOT
    image_roots = [
        root / 'images_dithered' / card_type / str(cmc),
        root / 'images' / card_type / str(cmc),
    ]
    for images_dir in image_roots:
        for ext in ('.bmp', '.jpg'):
            if (images_dir / f"{name}{ext}").exists():
                return name
    normalized_name = normalize_card_name_for_image(name)
    for images_dir in image_roots:
        if not images_dir.exists():
            continue
        for f in images_dir.iterdir():
            if normalize_card_name_for_image(f.stem) == normalized_name:
                return f.stem
    return None


def get_random_card_image_with_subtype(cmc, card_type, subtype):
    card_index = get_card_text_index()
    root = DATA_ROOT
    subtype_lower = subtype.lower()
    subtype_stems = set()
    if card_index:
        for card in card_index:
            if card.get('manaValue', -1) != cmc:
                continue
            if card.get('card_type') != card_type:
                continue
            if subtype_lower in (card.get('type') or '').lower():
                subtype_stems.add(normalize_card_name_for_image(card.get('name', '')))
    image_roots = [
        root / 'images_dithered' / card_type / str(int(cmc)),
        root / 'images' / card_type / str(int(cmc)),
    ]
    for images_dir in image_roots:
        if not images_dir.exists():
            continue
        candidates = [
            path for path in images_dir.iterdir()
            if path.is_file() and path.suffix.lower() in {'.bmp', '.jpg'}
            and subtype_stems and normalize_card_name_for_image(path.stem) in subtype_stems
        ]
        if candidates:
            return random.choice(candidates)
    return None


# ---------------------------------------------------------------------------
# Print helpers
# ---------------------------------------------------------------------------

def sanitize_for_printer(text):
    text = re.sub(r'\{([^}]*)\}', r'(\1)', text)
    text = text.replace('\u2014', '-')
    text = text.replace('\u2013', '-')
    text = text.replace('\u2019', "'")
    text = text.replace('\u2018', "'")
    text = text.replace('\u201c', '"')
    text = text.replace('\u201d', '"')
    text = text.replace('\u2022', '*')
    text = text.replace('\u00ae', '(R)')
    text = text.replace('\u2122', '(TM)')
    text = text.encode('ascii', errors='replace').decode('ascii')
    return text


def format_card_text(card_data):
    text = (card_data.get('text') or '').strip()
    if not text:
        return ''
    return '\n' + sanitize_for_printer(text)


def auto_print_card(image_path, card_data, card_type='creature'):
    def _do_print():
        try:
            card_text = format_card_text(card_data)
            cmc = card_data.get('cmc', 0)
            dithered = DATA_ROOT / 'images_dithered' / card_type / str(cmc) / f"{image_path.stem}.bmp"
            print_path = str(dithered) if dithered.exists() else str(image_path)
            print_image(print_path, card_text=card_text)
        except Exception as e:
            print(f"Auto-print failed: {e}")
    threading.Thread(target=_do_print, daemon=True).start()
