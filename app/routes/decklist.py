"""Decklist lookup route: /api/decklist/lookup"""

from flask import Blueprint, jsonify, request

from ..helpers import get_card_text_index, normalize_card_name_for_image, find_card_image_stem

bp = Blueprint('decklist', __name__)


@bp.route('/api/decklist/lookup', methods=['POST'])
def decklist_lookup():
    data = request.get_json(silent=True) or {}
    names = data.get('names', [])
    if not isinstance(names, list) or len(names) > 300:
        return jsonify({'error': 'Invalid names list'}), 400

    card_index = get_card_text_index()

    # Build normalized-name → card mapping.
    # For double-faced cards ("Front // Back"), also index by front face alone.
    name_map = {}
    for card in card_index:
        full_name = card.get('name', '')
        norm = normalize_card_name_for_image(full_name)
        if norm not in name_map:
            name_map[norm] = card
        if ' // ' in full_name:
            front = full_name.split(' // ')[0]
            norm_front = normalize_card_name_for_image(front)
            if norm_front not in name_map:
                name_map[norm_front] = card

    results = []
    for name in names:
        norm = normalize_card_name_for_image(name)
        card = name_map.get(norm)
        if card:
            card_type = card.get('card_type', 'creature')
            cmc = int(card.get('manaValue', 0))
            raw_name = card.get('name', name)
            filename = raw_name.replace('/', '').replace('"', '').replace("'", '').strip()
            stem = find_card_image_stem(filename, cmc, card_type)
            results.append({
                'name':      raw_name,
                'found':     stem is not None,
                'imageUrl':  f'/api/image/{card_type}/{cmc}/{stem}' if stem else '',
                'card_type': card_type,
                'cmc':       cmc,
                'filename':  stem or filename,
                'text':      card.get('text', ''),
                'type':      card.get('type', ''),
            })
        else:
            results.append({
                'name':      name,
                'found':     False,
                'imageUrl':  '',
                'card_type': '',
                'cmc':       0,
                'filename':  '',
                'text':      '',
                'type':      '',
            })

    return jsonify({'results': results})


@bp.route('/api/decklist/fetch-card', methods=['POST'])
def decklist_fetch_card():
    """Download one missing card from Scryfall, dither it for the printer and
    save it with the other card images. Used by Deck Print's download buttons."""
    import io
    import re
    import requests
    from PIL import Image
    from ..paths import DATA_ROOT
    try:
        from scripts.build.dither_for_printer import dither_image
    except ImportError:  # running from a checkout without scripts on sys.path
        import sys
        from ..paths import CODE_ROOT
        sys.path.insert(0, str(CODE_ROOT / 'scripts' / 'build'))
        from dither_for_printer import dither_image

    data = request.get_json(silent=True) or {}
    card_type = data.get('card_type') or ''
    filename = data.get('filename') or ''
    name = data.get('name') or ''
    try:
        cmc = int(data.get('cmc', 0))
    except (TypeError, ValueError):
        cmc = 0
    if not card_type or not filename or not name:
        return jsonify({'error': "Not a card Mo knows, so it can't be downloaded."}), 400
    if not re.match(r'^[a-z]+$', card_type) or '/' in filename or '..' in filename or not 0 <= cmc <= 20:
        return jsonify({'error': 'Invalid card'}), 400

    headers = {'User-Agent': 'MoMobile/1.0', 'Accept': '*/*'}
    try:
        r = requests.get('https://api.scryfall.com/cards/named', params={'exact': name},
                         headers=headers, timeout=20)
        r.raise_for_status()
        card = r.json()
        faces = card.get('card_faces') or []
        if faces and all(f.get('image_uris', {}).get('normal') for f in faces):
            urls = [f['image_uris']['normal'] for f in faces]
        else:
            urls = [card['image_uris']['normal']]
        images = []
        for url in urls:
            ri = requests.get(url, headers=headers, timeout=20)
            ri.raise_for_status()
            images.append(Image.open(io.BytesIO(ri.content)).convert('RGB'))
    except Exception as e:
        return jsonify({'error': f'Download failed: {e}'}), 502

    img = images[0]
    if len(images) > 1:  # double-faced: stack the faces, like the full download does
        img = Image.new('RGB', (images[0].width, sum(i.height for i in images)))
        y = 0
        for face in images:
            img.paste(face, (0, y))
            y += face.height
    buf = io.BytesIO()
    img.save(buf, 'PNG')
    buf.seek(0)
    out = DATA_ROOT / 'images_dithered' / card_type / str(cmc) / f'{filename}.bmp'
    if not dither_image(buf, str(out), brightness_boost=15):
        return jsonify({'error': 'Could not prepare the image'}), 500
    return jsonify({'found': True, 'imageUrl': f'/api/image/{card_type}/{cmc}/{filename}'})
