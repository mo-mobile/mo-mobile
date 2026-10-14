#!/bin/sh
# Mo Mobile setup for macOS and Linux.
#
#   curl -fsSL https://raw.githubusercontent.com/mo-mobile/mo-mobile/main/setup/setup.sh | sh
#
# Starts the Mo Mobile server in Docker and opens the setup page. Safe to run
# again: it updates the server and keeps the card data.
#
# What it does:
#   1. Checks that Docker is installed and running
#   2. Downloads the server image (ghcr.io/mo-mobile/mo-mobile)
#   3. Starts it on port 8420, restarting automatically, card data in a Docker volume
#   4. Opens the setup page

IMAGE=ghcr.io/mo-mobile/mo-mobile:latest
NAME=mo-mobile
PORT=8420

fail() { printf '\n%s\n\n' "$1" >&2; exit 1; }

echo "Mo Mobile setup"
echo

command -v docker >/dev/null 2>&1 || fail "Docker isn't installed. Mac: get Docker Desktop from https://www.docker.com/products/docker-desktop/. Linux: see the quick start guide."
docker info >/dev/null 2>&1 || fail "Docker isn't running (or needs sudo). Mac: open Docker Desktop and wait until it's running. Linux: log out and back in after installing Docker, or check 'sudo systemctl status docker'."

# This computer's address on the local network, for the phone.
case "$(uname)" in
  Darwin) IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null) ;;
  *)      IP=$(ip route get 1.1.1.1 2>/dev/null | awk '{for (i=1;i<NF;i++) if ($i=="src") print $(i+1)}') ;;
esac
[ -n "$IP" ] || IP=localhost
URL="http://$IP:$PORT"

echo "Downloading the server..."
docker pull "$IMAGE" || fail "Couldn't download $IMAGE. Check your internet connection and try again."

docker rm -f "$NAME" >/dev/null 2>&1
docker run -d --name "$NAME" --restart unless-stopped \
  -p "$PORT:8000" -v mo-mobile-data:/app/store \
  -e "MO_PUBLIC_URL=$URL" "$IMAGE" >/dev/null \
  || fail "The server didn't start. Is something else using port $PORT?"

echo
echo "The server is running at $URL"
echo "Open $URL/setup for the card download progress and a QR code for your phone."
if [ "$(uname)" = Darwin ]; then
  echo "Tip: in Docker Desktop's settings, turn on 'Start Docker Desktop when you sign in'."
  open "$URL/setup"
elif command -v xdg-open >/dev/null 2>&1 && [ -n "$DISPLAY$WAYLAND_DISPLAY" ]; then
  xdg-open "$URL/setup" >/dev/null 2>&1 &
fi
