#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# ESR - TP1
# HTTP/3 Client
# ============================================================

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TP1_ROOT="${TP1_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"

# shellcheck disable=SC1091
source "$TP1_ROOT/scripts/tp1-env.sh"

# ------------------------------------------------------------
# curl provided with the lab package, compiled with HTTP/3 support
# ------------------------------------------------------------

CURL_H3="$TP1_ROOT/tools/curl-http3/bin/curl"

if [[ ! -x "$CURL_H3" ]]; then
    echo "ERROR: curl with HTTP/3 support was not found." >&2
    echo "Expected at:" >&2
    echo "  $CURL_H3" >&2
    exit 1
fi

# Confirm that the provided curl supports HTTP/3
if ! "$CURL_H3" -V 2>/dev/null | grep -q 'HTTP3'; then
    echo "ERROR: the curl provided with the lab package does not have functional HTTP/3 support." >&2
    exit 1
fi

# ------------------------------------------------------------
# Transfer
# ------------------------------------------------------------

URL="https://${STREAMER_IP}:${HTTP3_PORT}/video_ref.mp4"

# Create a unique temporary file for this execution.
# Avoid conflicts with files left by previous executions,
# particularly between WSL and the CORE nodes.
OUT="$(mktemp /tmp/esr-tp1-http3-XXXXXX.mp4)"
trap 'rm -f "$OUT"' EXIT

echo "HTTP/3 -> $URL"

"$CURL_H3" \
    --http3-only \
    --insecure \
    --fail \
    --silent \
    --show-error \
    -H 'Cache-Control: no-cache' \
    --output "$OUT" \
    --write-out $'http_version=%{http_version}\nsize_download=%{size_download}\ntime_connect=%{time_connect}s\ntime_appconnect=%{time_appconnect}s\ntime_starttransfer=%{time_starttransfer}s\ntime_total=%{time_total}s\nspeed_download=%{speed_download}B/s\n' \
    "$URL"

# Confirm that content was received
if [[ ! -s "$OUT" ]]; then
    echo "ERROR: HTTP/3 transfer returned no content." >&2
    exit 1
fi