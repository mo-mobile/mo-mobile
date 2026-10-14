#!/usr/bin/env python3
"""
Build all the card data Mo needs, in one go.

Run from the repo root:

    python scripts/build_data.py

It downloads MTGJSON card data and every card and token image from Scryfall,
dithering each image for a 384-dot thermal printer as it arrives, so only the
small printer-ready copies are ever saved (about 1 GB in total). Then it adds
the Momir/Jhoira/Stonehewer avatars and builds the search index. About 35,000
images; on a decent connection the whole thing takes around half an hour.

Safe to re-run: the download steps skip images that already exist, so an
interrupted run picks up where it stopped.

Options:
  --types token,creature,...   only these categories (default: all). Choose from
                               token, creature, instant, sorcery, artifact,
                               enchantment, land, planeswalker, battle.
                               Running again with more adds them.
  --prune   afterwards, delete the 160 MB card file and other build-only files
            (a later build downloads them again).
  --refresh-index   rebuild the card search index even if it's recent.

Progress goes to app/data/build_status.json (read by the server's /setup page),
and app/data/.build_complete is written when everything has finished.
"""

import json
import os
import shutil
import subprocess
import sys
import time
import urllib.parse
from pathlib import Path

import requests

CODE = Path(__file__).resolve().parent.parent
# Data can live outside the code folder (the Android app keeps it in its own storage).
ROOT = Path(os.environ.get('MO_DATA_ROOT') or CODE)
BUILD = CODE / 'scripts' / 'build'
PY = sys.executable
DATA = ROOT / 'app' / 'data'
STATUS = DATA / 'build_status.json'
COMPLETE = DATA / '.build_complete'
PAUSE = DATA / 'pause'
PAUSED_EXIT = 3  # the download scripts exit with this when paused


class Paused(Exception):
    pass
TOTAL_STEPS = 6
INDEX_MAX_AGE = 30 * 24 * 3600  # rebuild the search index monthly

ATOMIC_URL = 'https://mtgjson.com/api/v5/AtomicCards.json'
SCRYFALL_HEADERS = {'User-Agent': 'MoMobile/1.0', 'Accept': '*/*'}

CARD_TYPES = ['creature', 'instant', 'sorcery', 'artifact',
              'enchantment', 'land', 'planeswalker', 'battle']

AVATARS = {
    'momir': 'Momir Vig, Simic Visionary Avatar',
    'jhoira': 'Jhoira of the Ghitu Avatar',
    'stonehewer': 'Stonehewer Giant Avatar',
}


def write_status(**fields):
    DATA.mkdir(parents=True, exist_ok=True)
    fields['updated'] = int(time.time())
    tmp = STATUS.with_suffix('.tmp')
    tmp.write_text(json.dumps(fields))
    tmp.replace(STATUS)


def step(title, types=None):
    if PAUSE.exists():
        raise Paused()
    number = int(title.split('/')[0])
    write_status(state='running', step=number, total=TOTAL_STEPS,
                 title=title.split('  ', 1)[1], types=types or [])
    print(f'\n=== {title} ===', flush=True)


# Android can't start a second Python process, so there the steps run inside
# this one (MO_INPROCESS=1). Everywhere else each step gets its own process.
IN_PROCESS = os.environ.get('MO_INPROCESS') == '1'


def run(script, *args):
    if not IN_PROCESS:
        result = subprocess.run([PY, str(script), *map(str, args)], cwd=ROOT)
        if result.returncode == PAUSED_EXIT:
            raise Paused()
        if result.returncode:
            raise subprocess.CalledProcessError(result.returncode, script)
        return
    saved_argv, saved_path = sys.argv, list(sys.path)
    sys.argv = [str(script), *map(str, args)]
    sys.path.insert(0, str(BUILD))
    try:
        # Plain exec rather than runpy.run_path: on Android, Chaquopy's importer
        # claims these paths and runpy would treat the script as a package.
        source = Path(script).read_text(encoding='utf-8')
        exec(compile(source, str(script), 'exec'),
             {'__name__': '__main__', '__file__': str(script), '__builtins__': __builtins__})
    except SystemExit as e:
        if e.code == PAUSED_EXIT:
            raise Paused()
        if e.code not in (None, 0):
            raise subprocess.CalledProcessError(e.code, sys.argv)
    finally:
        sys.argv, sys.path = saved_argv, saved_path


def download(url, dest, headers=None):
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + '.part')
    with requests.get(url, headers=headers, stream=True, timeout=60) as r:
        r.raise_for_status()
        with open(tmp, 'wb') as f:
            for chunk in r.iter_content(1 << 20):
                f.write(chunk)
    tmp.replace(dest)


ALL_TYPES = ['token'] + CARD_TYPES
DOWNLOADED = DATA / 'downloaded.json'


def downloaded_types():
    """Categories finished by earlier builds."""
    try:
        return set(json.loads(DOWNLOADED.read_text()))
    except (OSError, ValueError):
        return set()


class _Tee:
    """Copy output to build.log too; the setup page reads progress from it."""
    def __init__(self, stream, path):
        self.stream, self.log = stream, open(path, 'a', encoding='utf-8', buffering=1)

    def write(self, text):
        self.log.write(text)
        return self.stream.write(text)

    def flush(self):
        self.log.flush()
        self.stream.flush()


def parse_types(argv):
    """--types token,creature,... (default: everything)."""
    for i, arg in enumerate(argv):
        if arg == '--types' and i + 1 < len(argv):
            value = argv[i + 1]
            break
        if arg.startswith('--types='):
            value = arg.split('=', 1)[1]
            break
    else:
        return list(ALL_TYPES)
    if value == 'all':
        return list(ALL_TYPES)
    types = [t.strip() for t in value.split(',') if t.strip()]
    unknown = [t for t in types if t not in ALL_TYPES]
    if unknown:
        sys.exit(f'Unknown type(s): {", ".join(unknown)}. Choose from: {", ".join(ALL_TYPES)}')
    return types


def main(types=None, prune=None):
    types = parse_types(sys.argv) if types is None else list(types)
    prune = ('--prune' in sys.argv) if prune is None else prune
    try:
        build(types, prune)
    except Paused:
        PAUSE.unlink(missing_ok=True)
        write_status(state='paused', types=types)
        print('\nPaused. Start it again to carry on where it stopped.', flush=True)


def build(types, prune):
    os.chdir(ROOT)  # the step scripts use paths relative to the data folder
    for d in ('images/avatars', 'app/data', 'cards_json'):
        (ROOT / d).mkdir(parents=True, exist_ok=True)
    if IN_PROCESS and not isinstance(sys.stdout, _Tee):
        sys.stdout = _Tee(sys.stdout, DATA / 'build.log')

    PAUSE.unlink(missing_ok=True)
    card_types = [t for t in CARD_TYPES if t in types]
    print(f'Building: {", ".join(types)}', flush=True)
    write_status(state='running', step=0, total=TOTAL_STEPS, title='Starting', types=types)

    # The search index covers every card no matter which kinds are downloaded,
    # so it's only rebuilt when missing or a month old (new sets). Reading the
    # 160 MB card file is slow on a phone.
    index = DATA / 'card_text_index.json'
    refresh_index = (not index.exists()
                     or time.time() - index.stat().st_mtime > INDEX_MAX_AGE
                     or '--refresh-index' in sys.argv)
    need_card_file = bool(card_types) or refresh_index

    atomic = ROOT / 'cards_json' / 'AtomicCards.json'
    step('1/6  Card data from MTGJSON', types)
    if not need_card_file:
        print('Not needed for these cards, skipping.')
    elif atomic.exists():
        print('AtomicCards.json already present, skipping.')
    else:
        download(ATOMIC_URL, atomic)
        print(f'Saved {atomic.relative_to(ROOT)}')
    if need_card_file:
        run(BUILD / 'fetch_set_list.py')

    # Images are dithered as they download, so only the small printer-ready
    # BMPs are ever written to disk.
    step('2/6  Token images', types)
    if 'token' in types:
        run(BUILD / 'download_tokens.py', '--dither')

    step('3/6  Card lists', types)
    for t in card_types:
        run(BUILD / 'extract_cards.py', '--type', t)

    step('4/6  Card images', types)
    for t in card_types:
        run(BUILD / 'download_images.py', '--type', t, '--dither')

    step('5/6  Avatars', types)
    for key, name in AVATARS.items():
        dest = ROOT / 'images' / 'avatars' / f'{key}_avatar.jpg'
        if dest.exists():
            continue
        url = ('https://api.scryfall.com/cards/named?format=image&version=large&fuzzy='
               + urllib.parse.quote(name))
        download(url, dest, SCRYFALL_HEADERS)
        time.sleep(0.2)
    run(BUILD / 'dither_avatars.py')

    step('6/6  Search index', types)
    if refresh_index:
        run(BUILD / 'build_card_text_index.py')
    else:
        print('Search index is up to date, skipping.')

    if prune:
        # The big card file and card lists are only needed while building
        # (a later build downloads them again), and full-size originals from
        # older builds aren't used at all.
        atomic.unlink(missing_ok=True)
        for f in (ROOT / 'cards_json').glob('*_for_download.json'):
            f.unlink()
        for t in ALL_TYPES:
            shutil.rmtree(ROOT / 'images' / t, ignore_errors=True)

    done = sorted(downloaded_types() | set(types), key=ALL_TYPES.index)
    DOWNLOADED.write_text(json.dumps(done))
    COMPLETE.write_text(time.strftime('%Y-%m-%d %H:%M:%S'))
    write_status(state='done', step=TOTAL_STEPS, total=TOTAL_STEPS, title='Done', types=types)
    print('\nDone.')


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as e:
        write_status(state='failed', error=str(e))
        sys.exit(f'\nA step failed ({e}). Fix the error above and run this again; '
                 'finished downloads are kept.')
