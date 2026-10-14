#!/bin/sh
# Keeps all card data in one volume (/app/store) and builds it on first start.
set -e

STORE="${MO_STORE:-/app/store}"
mkdir -p "$STORE/data" "$STORE/images" "$STORE/images_dithered" "$STORE/cards_json"
cp -n /app/cards_json.seed/* "$STORE/cards_json/" 2>/dev/null || true

link() {  # link /app/<path> to the store
    if [ ! -L "/app/$1" ]; then rm -rf "/app/$1"; ln -s "$STORE/$2" "/app/$1"; fi
}
link images images
link images_dithered images_dithered
link cards_json cards_json
link app/data data

# Card data: normally you pick what to download on the /setup page. Two
# exceptions run on their own: a download that was interrupted (restart =
# resume), and MO_AUTO_BUILD for setups without a browser
# (MO_AUTO_BUILD=all, or a list like token,creature).
DATA="$STORE/data"
TYPES=$(python - "$DATA" <<'PY'
import json, sys
try:
    s = json.load(open(sys.argv[1] + '/build_status.json'))
    if s.get('state') in ('running', 'failed') and s.get('types'):
        print(','.join(s['types']))
except Exception:
    pass
PY
)
AUTO="${MO_AUTO_BUILD:-}"
if [ -z "$TYPES" ] && [ -n "$AUTO" ] && [ "$AUTO" != "0" ] && [ ! -f "$DATA/.build_complete" ]; then
    [ "$AUTO" = "1" ] && AUTO=all
    TYPES="$AUTO"
fi
if [ -n "$TYPES" ]; then
    echo "Downloading card data ($TYPES) in the background; progress at /setup."
    (cd /app && python scripts/build_data.py --types "$TYPES" --prune >> "$DATA/build.log" 2>&1) &
fi

exec gunicorn --bind 0.0.0.0:8000 --workers 2 --threads 4 app.app:app
