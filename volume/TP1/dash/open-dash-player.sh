#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TP1_ROOT="${TP1_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"
# shellcheck disable=SC1091
source "$TP1_ROOT/scripts/tp1-env.sh"

NODE="${1:-PC3}"
URL="http://${STREAMER_IP}:${DASH_HTTP_PORT}/player.html"

if [[ ! -s "$SCRIPT_DIR/dash.all.min.js" ]] || [[ $(stat -c%s "$SCRIPT_DIR/dash.all.min.js" 2>/dev/null || echo 0) -lt 100000 ]]; then
  echo "ERROR: dash.all.min.js is not available or appears to be incomplete." >&2
  echo "The instructor must run $TP1_ROOT/scripts/fetch-dashjs.sh before distributing the package." >&2
  exit 1
fi

command -v firefox >/dev/null 2>&1 || { echo "ERROR: Firefox is not installed." >&2; exit 1; }

# CORE creates VCMDs with their own environment. In WSLg, the audio socket exists,
# but PULSE_SERVER may not be inherited. Configure it automatically.
if [[ -S /mnt/wslg/PulseServer ]]; then
  export PULSE_SERVER="unix:/mnt/wslg/PulseServer"
fi

export DISPLAY="${DISPLAY:-:0.0}"
export MOZ_ALLOW_RUN_AS_ROOT=1
PROFILE_DIR="/tmp/tp1-firefox-${NODE,,}"

# Each run starts a clean experiment, with no state/cache from a previous run.
pkill -x firefox 2>/dev/null || true
pkill -x firefox-bin 2>/dev/null || true
sleep 0.4
rm -rf "$PROFILE_DIR"
mkdir -p "$PROFILE_DIR"

echo "Opening the DASH player on $NODE"
echo "URL: $URL"
echo "Temporary Firefox profile: $PROFILE_DIR"
if [[ -n "${PULSE_SERVER:-}" ]]; then
  echo "WSLg audio: $PULSE_SERVER"
fi

exec firefox --no-remote --new-instance --profile "$PROFILE_DIR" "$URL"
