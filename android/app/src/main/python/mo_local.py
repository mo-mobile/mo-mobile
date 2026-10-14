"""Runs the Mo server inside the Android app ("Everything on this phone").

The app calls start(data_dir) once. It points Mo at the app's own storage and
starts the web server on 127.0.0.1. The WebView shows the server's /setup page,
where you pick which cards to download (the build runs in a thread, see
app/routes/setup.py), and Mo once there are cards.
"""

import os
import shutil
import sys
import threading
from pathlib import Path

PORT = 8421
_started = False


def _seed(data_dir):
    """Copy the set files that ship with Mo into the data folder."""
    src = Path(__file__).parent / 'mo_seed' / 'cards_json'
    dst = Path(data_dir) / 'cards_json'
    dst.mkdir(parents=True, exist_ok=True)
    for f in src.glob('*.json'):
        if not (dst / f.name).exists():
            shutil.copyfile(f, dst / f.name)


def _resume_interrupted():
    """Carry on with a download the app was closed in the middle of."""
    import json
    from app.routes.setup import STATUS, start_build
    try:
        status = json.loads(STATUS.read_text())
    except (OSError, ValueError):
        return
    if status.get('state') == 'running' and status.get('types'):
        start_build(status['types'])


def start(data_dir):
    """Start the server; returns its address."""
    global _started
    os.environ['MO_DATA_ROOT'] = str(data_dir)
    os.environ['MO_INPROCESS'] = '1'   # no subprocesses on Android
    os.environ['MO_LOCAL'] = '1'       # setup page: everything runs on this phone
    url = f'http://127.0.0.1:{PORT}'
    if _started:
        return url
    _seed(data_dir)

    from werkzeug.serving import make_server
    from app.app import app
    server = make_server('127.0.0.1', PORT, app, threaded=True)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    _started = True
    _resume_interrupted()
    return url
