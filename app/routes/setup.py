"""First-run setup: build progress, and getting the phone connected.

    GET /setup                  build progress + QR code for the phone
    GET /phone                  what the phone opens: download the app, connect
    GET /setup/qr.svg           QR code pointing at /phone
    GET /setup/MoMobile.apk      the Android app bundled in the image
    GET /api/setup/status       build progress as JSON
    GET /api/setup/options      the card categories, their sizes, what's downloaded
    POST /api/setup/download    {"types": [...]} start downloading those categories

While the card data is still being built, / redirects to /setup.
"""

import json
import os
import re
import subprocess
import sys
import threading
import time
from io import BytesIO
from pathlib import Path
from urllib.parse import quote

from flask import (Blueprint, Response, jsonify, redirect, render_template,
                   request, send_file)

from ..paths import APP_DATA, DATA_ROOT

bp = Blueprint('setup', __name__)

ROOT = DATA_ROOT
DATA = APP_DATA
STATUS = DATA / 'build_status.json'
COMPLETE = DATA / '.build_complete'
INDEX = DATA / 'card_text_index.json'
BUILD_LOG = DATA / 'build.log'
APK = Path(os.environ.get('MO_APK', '/app/apk/MoMobile.apk'))
APK_FALLBACK = 'https://github.com/mo-mobile/mo-mobile/releases/latest/download/MoMobile.apk'
APP_PACKAGE = 'app.momobile'

_LOCAL_HOSTS = {'localhost', '127.0.0.1', '::1', '0.0.0.0'}
DOWNLOADED = DATA / 'downloaded.json'

# What can be downloaded. Counts and sizes are approximate (October 2026) and
# only shown to help people choose.
CATEGORIES = [
    {'id': 'token', 'name': 'Tokens', 'cards': 2853, 'mb': 75,
     'for': 'Token Print'},
    {'id': 'creature', 'name': 'Creatures', 'cards': 18011, 'mb': 520,
     'for': 'Momir, card and deck printing'},
    {'id': 'instant', 'name': 'Instants', 'cards': 3706, 'mb': 105,
     'for': 'Jhoira, card and deck printing'},
    {'id': 'sorcery', 'name': 'Sorceries', 'cards': 3421, 'mb': 100,
     'for': 'Jhoira, card and deck printing'},
    {'id': 'artifact', 'name': 'Artifacts', 'cards': 2343, 'mb': 70,
     'for': 'Stonehewer (equipment), card and deck printing'},
    {'id': 'enchantment', 'name': 'Enchantments', 'cards': 3395, 'mb': 100,
     'for': 'Card and deck printing'},
    {'id': 'land', 'name': 'Lands', 'cards': 1146, 'mb': 35,
     'for': 'Card and deck printing'},
    {'id': 'planeswalker', 'name': 'Planeswalkers', 'cards': 311, 'mb': 10,
     'for': 'Card and deck printing'},
    {'id': 'battle', 'name': 'Battles', 'cards': 36, 'mb': 2,
     'for': 'Card and deck printing'},
]
CATEGORY_IDS = [c['id'] for c in CATEGORIES]

_build_lock = threading.Lock()
_build_thread = None
_build_process = None


def downloaded_types():
    try:
        return set(json.loads(DOWNLOADED.read_text()))
    except (OSError, ValueError):
        # Data built before categories existed: everything was downloaded.
        return set(CATEGORY_IDS) if data_ready() else set()


def build_running():
    if _build_thread is not None and _build_thread.is_alive():
        return True
    if _build_process is not None and _build_process.poll() is None:
        return True
    # A build started by another worker or by the container entrypoint:
    # trust the status file while the log keeps moving.
    try:
        status = json.loads(STATUS.read_text())
        return status.get('state') == 'running' and time.time() - BUILD_LOG.stat().st_mtime < 180
    except (OSError, ValueError):
        return False


def start_build(types):
    """Download the given categories in the background. False if one is running."""
    global _build_thread, _build_process
    with _build_lock:
        if build_running():
            return False
        DATA.mkdir(parents=True, exist_ok=True)
        if os.environ.get('MO_INPROCESS') == '1':
            # Android: no subprocesses, so build in a thread in this process.
            def work():
                import scripts.build  # noqa: F401  (makes Chaquopy unpack the scripts)
                import scripts.build_data as build_data
                try:
                    build_data.main(types=types, prune=True)
                except Exception as e:
                    build_data.write_status(state='failed', error=str(e), types=types)
            _build_thread = threading.Thread(target=work, daemon=True)
            _build_thread.start()
        else:
            from ..paths import CODE_ROOT
            log = open(BUILD_LOG, 'a')
            _build_process = subprocess.Popen(
                [sys.executable, str(CODE_ROOT / 'scripts' / 'build_data.py'),
                 '--types', ','.join(types), '--prune'],
                cwd=str(CODE_ROOT), stdout=log, stderr=subprocess.STDOUT)
        return True


def data_ready():
    """Card data is usable: the build finished, or data was built elsewhere."""
    if COMPLETE.exists():
        return True
    return INDEX.exists() and not STATUS.exists()


def _status():
    try:
        status = json.loads(STATUS.read_text())
    except (OSError, ValueError):
        status = {'state': 'done' if data_ready() else 'waiting'}
    status['ready'] = data_ready()

    # The image download is the long part; pull "x of y" from the log.
    try:
        with open(BUILD_LOG, 'rb') as f:
            f.seek(0, os.SEEK_END)
            f.seek(max(0, f.tell() - 4000))
            tail = f.read().decode('utf-8', 'replace')
        matches = re.findall(r'(\d+)-(\d+) of (\d+)', tail)
        if matches:
            _, done, total = matches[-1]
            status['detail'] = {'done': int(done), 'total': int(total)}
    except OSError:
        pass
    return status


def server_url():
    """The address the phone should use for this server."""
    configured = os.environ.get('MO_PUBLIC_URL', '').strip().rstrip('/')
    host = (request.host or '').rsplit(':', 1)[0].strip('[]')
    if host and host not in _LOCAL_HOSTS:
        return request.host_url.rstrip('/')
    return configured or request.host_url.rstrip('/')


@bp.before_app_request
def send_to_setup_until_ready():
    if request.path == '/' and not data_ready():
        return redirect('/setup')
    return None


@bp.route('/setup')
def setup_page():
    url = server_url()
    return render_template('setup.html', server=url, phone_url=f'{url}/phone',
                           is_local=url.split('//', 1)[-1].split(':')[0] in _LOCAL_HOSTS,
                           on_phone=os.environ.get('MO_LOCAL') == '1')


@bp.route('/api/setup/status')
def setup_status():
    status = _status()
    status['running'] = build_running()
    status['downloaded'] = sorted(downloaded_types(), key=CATEGORY_IDS.index)
    return jsonify(status)


@bp.route('/api/setup/options')
def setup_options():
    have = downloaded_types()
    return jsonify([dict(c, downloaded=c['id'] in have) for c in CATEGORIES])


@bp.route('/api/setup/download', methods=['POST'])
def setup_download():
    types = (request.get_json(silent=True) or {}).get('types') or []
    types = [t for t in CATEGORY_IDS if t in types]
    if not types:
        return jsonify({'error': 'Pick at least one category.'}), 400
    if not start_build(types):
        return jsonify({'error': 'A download is already running.'}), 409
    return jsonify({'started': types})


@bp.route('/setup/qr.svg')
def setup_qr():
    import segno
    out = BytesIO()
    segno.make(f'{server_url()}/phone', error='m').save(out, kind='svg', scale=8, border=2)
    return Response(out.getvalue(), mimetype='image/svg+xml')


@bp.route('/setup/MoMobile.apk')
def setup_apk():
    if APK.exists():
        return send_file(str(APK), mimetype='application/vnd.android.package-archive',
                         as_attachment=True, download_name='MoMobile.apk')
    return redirect(APK_FALLBACK)


@bp.route('/phone')
def phone_page():
    url = server_url()
    apk_url = f'{url}/setup/MoMobile.apk'
    # Chrome intent link: opens the app with this server, or downloads the
    # app if it isn't installed yet.
    connect = (f'intent://connect?server={quote(url, safe="")}'
               f'#Intent;scheme=momobile;package={APP_PACKAGE};'
               f'S.browser_fallback_url={quote(apk_url, safe="")};end')
    return render_template('phone.html', server=url, apk_url=apk_url,
                           connect_url=connect, ready=data_ready())
