# Mo Mobile server: the Mo web app for phone printing.
#
# Runs the web UI only. Printing happens on the phone through the Android app,
# which fetches finished printer bytes from /api/print-data/... and writes them
# over Bluetooth. The container needs no Bluetooth.
#
# No card images are included. On first start the container downloads and
# dithers them itself into the /app/store volume (about half an hour; progress at
# /setup). The Android app is bundled so phones can install it from /phone.

FROM python:3.12-slim

RUN pip install --no-cache-dir gunicorn==23.0.0

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app/ ./app/
COPY scripts/ ./scripts/
COPY cards_json/ ./cards_json.seed/
COPY docker/entrypoint.sh /entrypoint.sh

# The app from the latest GitHub release (same file, same signature).
ARG APK_URL=https://github.com/mo-mobile/mo-mobile/releases/latest/download/MoMobile.apk
ADD ${APK_URL} /app/apk/MoMobile.apk

ENV MO_STORE=/app/store PYTHONUNBUFFERED=1
VOLUME /app/store
EXPOSE 8000
ENTRYPOINT ["/entrypoint.sh"]
