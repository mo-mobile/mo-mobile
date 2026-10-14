#!/usr/bin/env python3
"""Download token metadata and images from Scryfall."""

import io
import json
import re
import os
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests


SCRYFALL_SEARCH_URL = 'https://api.scryfall.com/cards/search'
BATCH_SIZE = 70
DELAY_BETWEEN_BATCHES = 0.5
# Scryfall rejects requests without a descriptive User-Agent/Accept header (HTTP 400).
SCRYFALL_HEADERS = {'User-Agent': 'MomirPrinter/1.0', 'Accept': '*/*'}
# Image files come from Scryfall's CDN (cards.scryfall.io), which isn't rate
# limited like the API, so fetch a few in parallel.
IMAGE_WORKERS = 6


def _paused():
    """The setup page's Pause button drops this file; stop between batches."""
    root = Path(os.environ.get('MO_DATA_ROOT') or '.')
    return (root / 'app' / 'data' / 'pause').exists()

_local = threading.local()


def _session():
    if not hasattr(_local, 'session'):
        _local.session = requests.Session()
        _local.session.headers.update(SCRYFALL_HEADERS)
    return _local.session


def _normalize(text):
    """Simple normalization for search_text field."""
    text = str(text).lower().replace("'", '').replace('\u2019', '')
    return re.sub(r'[^a-z0-9]+', ' ', text).strip()
OUTPUT_DIR = Path('images/token')
OUTPUT_JSON = Path('app/data/token_data.json')

# Configurable image size: 'small', 'normal', 'large', etc.
IMAGE_SIZE = 'normal'  # Change to 'large' or 'small' if needed


def get_image_url(card, size=IMAGE_SIZE):
    """Return the best available image url for a Scryfall card object, with configurable size."""
    image_uris = card.get('image_uris') or {}
    if image_uris.get(size):
        return image_uris[size]

    card_faces = card.get('card_faces') or []
    for face in card_faces:
        face_uris = face.get('image_uris') or {}
        if face_uris.get(size):
            return face_uris[size]

    return None


def fetch_tokens():
    """Fetch all token printings from Scryfall, then pick the first printing for each oracle_id."""
    params = {
        'q': 'type:token lang:en -promo',  # Only English, exclude promos
        'unique': 'prints',  # Get all printings
        'order': 'released', # Oldest first
    }
    url = SCRYFALL_SEARCH_URL
    print('📥 Fetching all token printings from Scryfall...')
    all_prints = []
    while url:
        response = requests.get(url, params=params if url == SCRYFALL_SEARCH_URL else None, timeout=15, headers=SCRYFALL_HEADERS)
        response.raise_for_status()
        payload = response.json()
        all_prints.extend(payload.get('data', []))
        url = payload.get('next_page') if payload.get('has_more') else None
        params = None

    # Group all printings by oracle_id
    from collections import defaultdict
    by_oracle = defaultdict(list)
    for card in all_prints:
        oracle_id = card.get('oracle_id')
        if not oracle_id:
            continue
        by_oracle[oracle_id].append(card)

    tokens = []
    for oracle_id, cards in by_oracle.items():
        # Sort by released_at (oldest first), fallback to empty string if missing
        cards_sorted = sorted(cards, key=lambda c: c.get('released_at') or '')
        for card in cards_sorted:
            image_url = get_image_url(card)
            if not image_url:
                continue
            keywords = card.get('keywords', [])
            name = card.get('name', 'Unknown Token')
            type_line = card.get('type_line', 'Token')
            search_text = _normalize(f"{name} {type_line} {' '.join(keywords)}")
            tokens.append({
                'id': card.get('id'),
                'oracle_id': oracle_id,
                'name': name,
                'type_line': type_line,
                'image_url': image_url,
                'search_text': search_text,
                'colors': card.get('colors', []),
                'power': card.get('power', ''),
                'toughness': card.get('toughness', ''),
                'keywords': keywords,
                'oracle_text': card.get('oracle_text', ''),
            })
    print(f'   Indexed {len(tokens)} token printings ({len(by_oracle)} unique oracle_ids)')
    return tokens


def download_token_images(tokens, dither=False):
    """Download token images in batches and save local metadata."""
    # With dither=True each image is dithered in memory and only the BMP is kept.
    out_dir = Path('images_dithered/token') if dither else OUTPUT_DIR
    ext = '.bmp' if dither else '.jpg'
    out_dir.mkdir(parents=True, exist_ok=True)

    with OUTPUT_JSON.open('w', encoding='utf-8') as handle:
        json.dump(tokens, handle, indent=2)

    downloaded = 0
    skipped = 0
    failed = 0

    print(f'💾 Saved token metadata to {OUTPUT_JSON}')
    print(f'🌐 Downloading {len(tokens)} token images...\n')

    for start in range(0, len(tokens), BATCH_SIZE):
        if _paused():
            print('\nPaused.', flush=True)
            sys.exit(3)
        batch = tokens[start:start + BATCH_SIZE]
        batch_end = min(start + BATCH_SIZE, len(tokens))
        print(f'Batch {start // BATCH_SIZE + 1} ({start + 1}-{batch_end} of {len(tokens)})...', end=' ')

        def fetch(token):
            image_path = out_dir / f"{token['id']}{ext}"
            if image_path.exists():
                return 'skipped'
            try:
                for attempt in range(3):  # the CDN occasionally times out
                    try:
                        response = _session().get(token['image_url'], timeout=20)
                        response.raise_for_status()
                        break
                    except requests.RequestException:
                        if attempt == 2:
                            raise
                        time.sleep(2 * (attempt + 1))
                if dither:
                    from dither_for_printer import dither_image
                    ok = dither_image(io.BytesIO(response.content), str(image_path), brightness_boost=15)
                    return 'downloaded' if ok else 'failed'
                image_path.write_bytes(response.content)
                return 'downloaded'
            except requests.RequestException:
                return 'failed'

        with ThreadPoolExecutor(IMAGE_WORKERS) as pool:
            results = list(pool.map(fetch, batch))
        downloaded += results.count('downloaded')
        skipped += results.count('skipped')
        failed += results.count('failed')

        print('✓', flush=True)

    print('\n✅ Token download complete!')
    print(f'   Downloaded: {downloaded}')
    print(f'   Skipped:    {skipped}')
    print(f'   Failed:     {failed}')
    print(f'   Images:     {out_dir}/')
    print(f'   Metadata:   {OUTPUT_JSON}')


def main():
    tokens = fetch_tokens()
    download_token_images(tokens, dither='--dither' in sys.argv)


if __name__ == '__main__':
    main()