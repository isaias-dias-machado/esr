#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# ESR - TP1
# HTTP/2 Client
# ============================================================

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TP1_ROOT="${TP1_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"

# shellcheck disable=SC1091
source "$TP1_ROOT/scripts/tp1-env.sh"

# ------------------------------------------------------------
# Checks
# ------------------------------------------------------------

if ! command -v curl >/dev/null 2>&1; then
    echo "ERROR: curl is not installed." >&2
    exit 1
fi

if ! curl -V 2>/dev/null | grep -q 'HTTP2'; then
    echo "ERROR: this curl does not support HTTP/2." >&2
    exit 1
fi

# ------------------------------------------------------------
# Transfer
# ------------------------------------------------------------

URL="https://${STREAMER_IP}:${HTTP2_PORT}/video_ref.mp4"

# Create a unique temporary file for this execution.
# Avoid conflicts with files left by previous executions,
# particularly between WSL and the CORE nodes.
OUT="$(mktemp /tmp/esr-tp1-http2-XXXXXX.mp4)"
trap 'rm -f "$OUT"' EXIT

echo "HTTP/2 -> $URL"

curl \
    --http2 \
    --insecure \
    --no-sessionid \
    --fail \
    --silent \
    --show-error \
    -H 'Cache-Control: no-cache' \
    --output "$OUT" \
    --write-out $'http_version=%{http_version}\nsize_download=%{size_download}\ntime_connect=%{time_connect}s\ntime_appconnect=%{time_appconnect}s\ntime_starttransfer=%{time_starttransfer}s\ntime_total=%{time_total}s\nspeed_download=%{speed_download}B/s\n' \
    "$URL"

# Confirm that content was received
if [[ ! -s "$OUT" ]]; then
    echo "ERROR: HTTP/2 transfer returned no content." >&2
    exit 1
fi