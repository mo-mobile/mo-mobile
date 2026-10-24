"""Sharing: friends print to your printer from their own phone's browser.

Only active on a public server started with MO_SHARE=1 (the droplet). Mo
Mobile on your phone registers here and becomes a "host". Your printer gets a
code, and the QR sticker on it opens /p/<code>: the normal Mo page, where
printing goes into your queue instead of to a Bluetooth bridge. Your phone
picks jobs up from the queue and writes them to the printer.

Host API (header X-Share-Host: <host id>:<secret>):
    POST /api/share/register              -> {host, secret, code}
    POST /api/share/host                  {sharing, push, fcm, owner}
    POST /api/share/host/newcode          -> {code}
    GET  /api/share/host/next?wait=25     -> next job, marked printing (or 204)
    GET  /api/share/host/job/<id>/bytes   -> printer bytes
    POST /api/share/host/job/<id>/done    {ok, error}
    POST /api/share/host/jobs             {path, bytes(base64)}  your own print
    GET  /api/share/host/queue
    POST /api/share/host/job/<id>/cancel
    POST /api/share/host/clear
    GET  /api/share/host/sticker          -> printer bytes for the QR sticker

Friend API (cookie mo_friend, set by /p/<code>):
    GET  /p/<code>
    GET  /api/share/<code>/state
    POST /api/share/<code>/jobs           {path, name}
    POST /api/share/<code>/jobs/<id>/cancel
    POST /api/share/<code>/ask            {name}  Sharing is off: ask the owner
"""

import base64
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import threading
import time
from pathlib import Path
from urllib.parse import unquote

from flask import (Blueprint, Response, abort, current_app, jsonify, make_response,
                   render_template, request)
from PIL import Image, ImageDraw, ImageFont

from ..paths import APP_DATA

bp = Blueprint('share', __name__)

ENABLED = os.environ.get('MO_SHARE', '') == '1'
PUBLIC_URL = os.environ.get('MO_SHARE_URL', '').rstrip('/')
FCM_CREDENTIALS = os.environ.get('MO_FCM_CREDENTIALS', '')

FRIEND_GAP = 10          # seconds between prints, per friend
DECK_MAX = 100           # most prints one friend can have waiting (a deck goes in at once)
HOST_ONLINE = 75         # host counts as online if it polled this recently
PRINTING_STALE = 180     # a "printing" job this old was lost (phone restarted)
ASK_GAP = 120            # at most one "turn on Sharing?" push per this long
DONE_KEEP = 60           # finished jobs stay visible this long
PRINT_PREFIX = '/api/print-data/'

DB_PATH = APP_DATA / 'share.db'
_local = threading.local()


# ------------------------------------------------------------------ storage

def _db():
    conn = getattr(_local, 'conn', None)
    if conn is None:
        DB_PATH.parent.mkdir(parents=True, exist_ok=True)
        conn = sqlite3.connect(str(DB_PATH), timeout=10, isolation_level=None)
        conn.row_factory = sqlite3.Row
        conn.execute('PRAGMA journal_mode=WAL')
        conn.executescript('''
            CREATE TABLE IF NOT EXISTS hosts (
                id TEXT PRIMARY KEY, secret TEXT NOT NULL, code TEXT UNIQUE NOT NULL,
                owner TEXT DEFAULT '', sharing INTEGER DEFAULT 0, push INTEGER DEFAULT 1,
                fcm TEXT DEFAULT '', last_seen REAL DEFAULT 0, last_ask REAL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS jobs (
                id INTEGER PRIMARY KEY AUTOINCREMENT, host TEXT NOT NULL,
                friend TEXT DEFAULT '', name TEXT DEFAULT '', label TEXT DEFAULT '',
                path TEXT DEFAULT '', thumb TEXT DEFAULT '', state TEXT NOT NULL,
                created REAL NOT NULL, updated REAL NOT NULL, error TEXT DEFAULT '',
                bytes BLOB);
            CREATE INDEX IF NOT EXISTS jobs_host ON jobs(host, state, id);
        ''')
        _local.conn = conn
    return conn


def _hash(secret):
    return hashlib.sha256(secret.encode()).hexdigest()


def _new_code():
    return secrets.token_urlsafe(9)


# ----------------------------------------------------------------- the gate

@bp.before_app_request
def public_server_gate():
    """On the public server, only the friend-facing parts of Mo are reachable.

    Card setup (downloads, deletes), the server's own printer and the app
    download page stay private: they are for running your own Mo server.
    """
    if not ENABLED:
        return None
    p = request.path
    if p == '/':
        return render_template('share_home.html')
    blocked = (p.startswith('/setup') or p.startswith('/api/setup')
               or p.startswith('/phone') or p.startswith('/api/print/')
               or p == '/api/decklist/print')
    if blocked:
        abort(404)
    return None


def _need_enabled():
    if not ENABLED:
        abort(404)


# --------------------------------------------------------------- job labels

def _label_for(path):
    """A readable name for a print-data path, and a picture URL for the queue."""
    parts = path[len(PRINT_PREFIX):].split('?')[0].split('/')
    try:
        if parts[0] == 'card' and len(parts) >= 4:
            card_type, cmc, filename = parts[1], parts[2], unquote(parts[3])
            label = filename.replace('_', ' ').strip() or 'Card'
            return label, f'/api/image/{card_type}/{cmc}/{parts[3]}'
        if parts[0] == 'token' and len(parts) >= 2:
            from ..helpers import get_token_data
            tid = parts[1]
            token = next((t for t in get_token_data() if t.get('id') == tid), None)
            name = (token or {}).get('name') or 'Token'
            return f'{name} token', f'/api/token-image/{tid}'
        if parts[0] == 'avatar':
            return 'Avatar', ''
    except Exception:
        pass
    return 'Print', ''


def _render_path(path):
    """Printer bytes for a print-data path, made by this server's own routes."""
    if not path.startswith(PRINT_PREFIX) or '..' in path:
        return None, 'Not a print'
    with current_app.test_client() as client:
        resp = client.get(path)
    if resp.status_code != 200:
        try:
            return None, resp.get_json().get('error') or 'Print failed'
        except Exception:
            return None, f'Print failed ({resp.status_code})'
    return resp.data, None


# ------------------------------------------------------------------- queue

def _expire(host_id):
    now = time.time()
    db = _db()
    db.execute("UPDATE jobs SET state='failed', error='Lost', updated=? "
               "WHERE host=? AND state='printing' AND updated<?",
               (now, host_id, now - PRINTING_STALE))
    db.execute("DELETE FROM jobs WHERE host=? AND state IN ('done','failed','cancelled') AND updated<?",
               (host_id, now - DONE_KEEP))


def _queue(host_id, viewer_friend=None, host_view=False):
    _expire(host_id)
    rows = _db().execute(
        "SELECT id, friend, name, label, thumb, state, error FROM jobs "
        "WHERE host=? AND state IN ('printing','waiting') "
        "ORDER BY CASE state WHEN 'printing' THEN 0 ELSE 1 END, id",
        (host_id,)).fetchall()
    out = []
    for r in rows:
        mine = host_view and not r['friend'] or (viewer_friend and r['friend'] == viewer_friend)
        out.append({
            'id': r['id'], 'name': r['name'], 'label': r['label'],
            'thumb': (PUBLIC_URL + r['thumb']) if (host_view and r['thumb'] and PUBLIC_URL) else r['thumb'],
            'state': r['state'], 'mine': bool(mine),
            'canCancel': r['state'] == 'waiting' and (host_view or bool(mine)),
        })
    return out


def _host_by_code(code):
    return _db().execute('SELECT * FROM hosts WHERE code=?', (code,)).fetchone()


def _host_auth():
    raw = request.headers.get('X-Share-Host', '')
    host_id, _, secret = raw.partition(':')
    row = _db().execute('SELECT * FROM hosts WHERE id=?', (host_id,)).fetchone()
    if row is None or not hmac.compare_digest(row['secret'], _hash(secret)):
        abort(401)
    _db().execute('UPDATE hosts SET last_seen=? WHERE id=?', (time.time(), host_id))
    return row


def _online(host):
    return time.time() - (host['last_seen'] or 0) < HOST_ONLINE


# -------------------------------------------------------------------- push

_token_cache = {'token': None, 'exp': 0}


def _fcm_access_token():
    if _token_cache['token'] and time.time() < _token_cache['exp'] - 60:
        return _token_cache['token']
    from google.oauth2 import service_account
    from google.auth.transport.requests import Request
    creds = service_account.Credentials.from_service_account_file(
        FCM_CREDENTIALS, scopes=['https://www.googleapis.com/auth/firebase.messaging'])
    creds.refresh(Request())
    _token_cache['token'] = creds.token
    _token_cache['exp'] = creds.expiry.timestamp() if creds.expiry else time.time() + 600
    return creds.token


def _send_push(fcm_token, data):
    if not (FCM_CREDENTIALS and fcm_token):
        return False
    import requests
    project = json.loads(Path(FCM_CREDENTIALS).read_text())['project_id']
    resp = requests.post(
        f'https://fcm.googleapis.com/v1/projects/{project}/messages:send',
        headers={'Authorization': f'Bearer {_fcm_access_token()}'},
        json={'message': {'token': fcm_token, 'data': data,
                          'android': {'priority': 'high', 'ttl': '300s'}}},
        timeout=10)
    return resp.ok


# --------------------------------------------------------------- host API

@bp.route('/api/share/register', methods=['POST'])
def register():
    _need_enabled()
    host_id, secret = secrets.token_hex(8), secrets.token_urlsafe(24)
    body = request.get_json(silent=True) or {}
    _db().execute('INSERT INTO hosts (id, secret, code, owner, fcm, last_seen) VALUES (?,?,?,?,?,?)',
                  (host_id, _hash(secret), _new_code(), str(body.get('owner', ''))[:40],
                   str(body.get('fcm', ''))[:4096], time.time()))
    code = _db().execute('SELECT code FROM hosts WHERE id=?', (host_id,)).fetchone()['code']
    return jsonify({'host': host_id, 'secret': secret, 'code': code,
                    'url': f'{PUBLIC_URL}/p/{code}'})


@bp.route('/api/share/host', methods=['GET', 'POST'])
def host_settings():
    _need_enabled()
    host = _host_auth()
    body = request.get_json(silent=True) or {}
    fields = {'sharing': 'sharing', 'push': 'push'}
    for key, col in fields.items():
        if key in body:
            _db().execute(f'UPDATE hosts SET {col}=? WHERE id=?', (1 if body[key] else 0, host['id']))
    if 'fcm' in body:
        _db().execute('UPDATE hosts SET fcm=? WHERE id=?', (str(body['fcm'])[:4096], host['id']))
    if 'owner' in body:
        _db().execute('UPDATE hosts SET owner=? WHERE id=?', (str(body['owner'])[:40], host['id']))
    host = _db().execute('SELECT * FROM hosts WHERE id=?', (host['id'],)).fetchone()
    return jsonify({'code': host['code'], 'url': f'{PUBLIC_URL}/p/{host["code"]}',
                    'sharing': bool(host['sharing']), 'push': bool(host['push']),
                    'owner': host['owner']})


@bp.route('/api/share/host/newcode', methods=['POST'])
def host_newcode():
    _need_enabled()
    host = _host_auth()
    code = _new_code()
    _db().execute('UPDATE hosts SET code=? WHERE id=?', (code, host['id']))
    return jsonify({'code': code, 'url': f'{PUBLIC_URL}/p/{code}'})


@bp.route('/api/share/host/next')
def host_next():
    """Long poll: the next waiting job, marked printing. 204 when none came."""
    _need_enabled()
    host = _host_auth()
    try:
        wait = max(0, min(25, int(request.args.get('wait', 25))))
    except ValueError:
        wait = 25
    deadline = time.time() + wait
    db = _db()
    while True:
        _expire(host['id'])
        busy = db.execute("SELECT 1 FROM jobs WHERE host=? AND state='printing'", (host['id'],)).fetchone()
        if not busy:
            row = db.execute("SELECT id, name, label FROM jobs WHERE host=? AND state='waiting' "
                             "ORDER BY id LIMIT 1", (host['id'],)).fetchone()
            if row:
                now = time.time()
                cur = db.execute("UPDATE jobs SET state='printing', updated=? WHERE id=? AND state='waiting'",
                                 (now, row['id']))
                if cur.rowcount:
                    return jsonify({'id': row['id'], 'name': row['name'], 'label': row['label'],
                                    'waiting': _waiting_count(host['id'])})
        if time.time() >= deadline:
            db.execute('UPDATE hosts SET last_seen=? WHERE id=?', (time.time(), host['id']))
            return Response(status=204)
        time.sleep(0.5)


def _waiting_count(host_id):
    return _db().execute("SELECT COUNT(*) FROM jobs WHERE host=? AND state IN ('waiting','printing')",
                         (host_id,)).fetchone()[0]


@bp.route('/api/share/host/job/<int:job_id>/bytes')
def host_job_bytes(job_id):
    _need_enabled()
    host = _host_auth()
    row = _db().execute('SELECT bytes FROM jobs WHERE id=? AND host=?', (job_id, host['id'])).fetchone()
    if row is None or row['bytes'] is None:
        abort(404)
    return Response(bytes(row['bytes']), mimetype='application/octet-stream')


@bp.route('/api/share/host/job/<int:job_id>/done', methods=['POST'])
def host_job_done(job_id):
    _need_enabled()
    host = _host_auth()
    body = request.get_json(silent=True) or {}
    ok = bool(body.get('ok'))
    if ok:
        _db().execute("UPDATE jobs SET state='done', bytes=NULL, updated=? WHERE id=? AND host=?",
                      (time.time(), job_id, host['id']))
    else:
        # The printer was off or out of reach: put the job back at the front.
        _db().execute("UPDATE jobs SET state='waiting', error=?, updated=? WHERE id=? AND host=?",
                      (str(body.get('error', ''))[:200], time.time(), job_id, host['id']))
    return jsonify({'ok': True})


@bp.route('/api/share/host/jobs', methods=['POST'])
def host_add_job():
    """The owner's own print, made on their phone, into the same line."""
    _need_enabled()
    host = _host_auth()
    body = request.get_json(silent=True) or {}
    try:
        data = base64.b64decode(body.get('bytes', ''), validate=True)
    except Exception:
        return jsonify({'error': 'Bad print data'}), 400
    if not data or len(data) > 4_000_000:
        return jsonify({'error': 'Bad print data'}), 400
    path = str(body.get('path', ''))[:500]
    label, thumb = _label_for(path) if path.startswith(PRINT_PREFIX) else ('Print', '')
    now = time.time()
    cur = _db().execute(
        "INSERT INTO jobs (host, friend, name, label, path, thumb, state, created, updated, bytes) "
        "VALUES (?,?,?,?,?,?, 'waiting', ?, ?, ?)",
        (host['id'], '', host['owner'] or 'You', label, path, thumb, now, now, data))
    return jsonify({'id': cur.lastrowid, 'waiting': _waiting_count(host['id'])})


@bp.route('/api/share/host/queue')
def host_queue():
    _need_enabled()
    host = _host_auth()
    return jsonify({'queue': _queue(host['id'], host_view=True), 'sharing': bool(host['sharing'])})


@bp.route('/api/share/host/job/<int:job_id>/cancel', methods=['POST'])
def host_cancel(job_id):
    _need_enabled()
    host = _host_auth()
    _db().execute("UPDATE jobs SET state='cancelled', bytes=NULL, updated=? "
                  "WHERE id=? AND host=? AND state='waiting'", (time.time(), job_id, host['id']))
    return jsonify({'queue': _queue(host['id'], host_view=True)})


@bp.route('/api/share/host/clear', methods=['POST'])
def host_clear():
    _need_enabled()
    host = _host_auth()
    _db().execute("UPDATE jobs SET state='cancelled', bytes=NULL, updated=? "
                  "WHERE host=? AND state='waiting'", (time.time(), host['id']))
    return jsonify({'queue': _queue(host['id'], host_view=True)})


@bp.route('/api/share/host/sticker')
def host_sticker():
    """Printer bytes for a sticker: the printer's QR code with a line of text."""
    _need_enabled()
    host = _host_auth()
    import segno
    from ..printer import (PRINTER_DENSITY, PRINTER_FEED, PRINTER_WIDTH_DOTS,
                           _cmd_density, _cmd_feed, _cmd_image_bands, _image_to_raster_data)

    url = f'{PUBLIC_URL}/p/{host["code"]}'
    qr = segno.make(url, error='m')
    modules = [list(row) for row in qr.matrix]
    scale = max(1, (PRINTER_WIDTH_DOTS - 60) // len(modules))
    qr_img = Image.new('1', (len(modules) * scale, len(modules) * scale), 1)
    qr_draw = ImageDraw.Draw(qr_img)
    for y, row in enumerate(modules):
        for x, dark in enumerate(row):
            if dark:
                qr_draw.rectangle([x * scale, y * scale, (x + 1) * scale - 1, (y + 1) * scale - 1], fill=0)

    def font(size):
        try:
            return ImageFont.load_default(size=size)
        except TypeError:
            return ImageFont.load_default()

    owner = host['owner'] or 'My'
    title = f"{owner}'s printer" if host['owner'] else 'Mo printer'
    lines = [(title, font(30)), ('Scan to print', font(24))]
    probe = ImageDraw.Draw(Image.new('1', (1, 1), 1))
    text_h = sum(probe.textbbox((0, 0), t, font=f)[3] + 10 for t, f in lines)
    height = 20 + text_h + 10 + qr_img.height + 30
    img = Image.new('1', (PRINTER_WIDTH_DOTS, height), 1)
    draw = ImageDraw.Draw(img)
    y = 20
    for text, f in lines:
        w = probe.textlength(text, font=f)
        draw.text(((PRINTER_WIDTH_DOTS - w) / 2, y), text, font=f, fill=0)
        y += probe.textbbox((0, 0), text, font=f)[3] + 10
    img.paste(qr_img, ((PRINTER_WIDTH_DOTS - qr_img.width) // 2, y + 10))

    raster, h = _image_to_raster_data(img)
    payload = bytearray(_cmd_density(PRINTER_DENSITY))
    for packet in _cmd_image_bands(raster, h):
        payload += packet
    payload += _cmd_feed(PRINTER_FEED)
    return Response(bytes(payload), mimetype='application/octet-stream')


# ------------------------------------------------------------- friend API

def _friend_id():
    fid = request.cookies.get('mo_friend', '')
    return fid if 8 <= len(fid) <= 64 else ''


@bp.route('/p/<code>')
def friend_page(code):
    _need_enabled()
    host = _host_by_code(code)
    if host is None:
        return render_template('share_missing.html'), 404
    resp = make_response(render_template('index.html', share={
        'code': code, 'owner': host['owner'] or ''}))
    if not _friend_id():
        resp.set_cookie('mo_friend', secrets.token_hex(12), max_age=3600 * 24 * 365,
                        secure=request.is_secure or bool(PUBLIC_URL), httponly=True, samesite='Lax')
    resp.headers['Cache-Control'] = 'no-store'
    return resp


@bp.route('/api/share/<code>/state')
def friend_state(code):
    _need_enabled()
    host = _host_by_code(code)
    if host is None:
        abort(404)
    return jsonify({
        'owner': host['owner'] or '',
        'sharing': bool(host['sharing']) and _online(host),
        'sharingOn': bool(host['sharing']),
        'online': _online(host),
        'queue': _queue(host['id'], viewer_friend=_friend_id() or None),
    })


@bp.route('/api/share/<code>/jobs', methods=['POST'])
def friend_add_job(code):
    _need_enabled()
    host = _host_by_code(code)
    if host is None:
        abort(404)
    friend = _friend_id()
    if not friend:
        return jsonify({'error': 'Open the page from the QR code again.'}), 400
    if not host['sharing']:
        return jsonify({'error': 'Sharing is off.', 'off': True}), 409
    body = request.get_json(silent=True) or {}
    name = ' '.join(str(body.get('name', '')).split())[:24] or 'Friend'
    path = str(body.get('path', ''))[:500]

    waiting = _db().execute("SELECT COUNT(*) FROM jobs WHERE host=? AND friend=? AND state='waiting'",
                            (host['id'], friend)).fetchone()[0]
    if waiting >= DECK_MAX:
        return jsonify({'error': 'You have a lot waiting already. Try again once some have printed.'}), 429
    # One print per FRIEND_GAP, except the cards of a deck going in together.
    if not body.get('deck'):
        last = _db().execute('SELECT MAX(created) FROM jobs WHERE host=? AND friend=?',
                             (host['id'], friend)).fetchone()[0] or 0
        wait = FRIEND_GAP - (time.time() - last)
        if wait > 0:
            return jsonify({'error': f'Wait {int(wait) + 1} seconds.', 'wait': int(wait) + 1}), 429

    data, error = _render_path(path)
    if error:
        return jsonify({'error': error}), 400
    label, thumb = _label_for(path)
    now = time.time()
    cur = _db().execute(
        "INSERT INTO jobs (host, friend, name, label, path, thumb, state, created, updated, bytes) "
        "VALUES (?,?,?,?,?,?, 'waiting', ?, ?, ?)",
        (host['id'], friend, name, label, path, thumb, now, now, data))
    # The owner's phone hasn't checked in lately (asleep): wake it up.
    if time.time() - (host['last_seen'] or 0) > 40 and host['fcm']:
        try:
            _send_push(host['fcm'], {'type': 'job'})
        except Exception as e:
            current_app.logger.warning('wake push failed: %s', e)
    queue = _queue(host['id'], viewer_friend=friend)
    place = next((i + 1 for i, j in enumerate(queue) if j['id'] == cur.lastrowid), len(queue))
    return jsonify({'id': cur.lastrowid, 'place': place, 'queue': queue})


@bp.route('/api/share/<code>/jobs/<int:job_id>/cancel', methods=['POST'])
def friend_cancel(code, job_id):
    _need_enabled()
    host = _host_by_code(code)
    friend = _friend_id()
    if host is None or not friend:
        abort(404)
    _db().execute("UPDATE jobs SET state='cancelled', bytes=NULL, updated=? "
                  "WHERE id=? AND host=? AND friend=? AND state='waiting'",
                  (time.time(), job_id, host['id'], friend))
    return jsonify({'queue': _queue(host['id'], viewer_friend=friend)})


@bp.route('/api/share/<code>/ask', methods=['POST'])
def friend_ask(code):
    """Sharing is off: send the owner a "turn on Sharing?" notification."""
    _need_enabled()
    host = _host_by_code(code)
    if host is None or not _friend_id():
        abort(404)
    if host['sharing'] and _online(host):
        return jsonify({'sent': False, 'on': True})
    if not host['push'] or not host['fcm']:
        return jsonify({'sent': False, 'push': False})
    now = time.time()
    if now - (host['last_ask'] or 0) < ASK_GAP:
        return jsonify({'sent': True})  # already asked recently
    body = request.get_json(silent=True) or {}
    name = ' '.join(str(body.get('name', '')).split())[:24] or 'Someone'
    try:
        sent = _send_push(host['fcm'], {'type': 'share_request', 'name': name})
    except Exception as e:
        current_app.logger.warning('push failed: %s', e)
        sent = False
    if sent:
        _db().execute('UPDATE hosts SET last_ask=? WHERE id=?', (now, host['id']))
    return jsonify({'sent': sent})
