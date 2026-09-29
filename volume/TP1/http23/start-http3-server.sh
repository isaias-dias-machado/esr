#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# ESR - TP1
# HTTP/3 Server over QUIC
# ============================================================

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TP1_ROOT="${TP1_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"

# shellcheck disable=SC1091
source "$TP1_ROOT/scripts/tp1-env.sh"

# ------------------------------------------------------------
# Checks
# ------------------------------------------------------------

if ! command -v caddy >/dev/null 2>&1; then
    echo "ERROR: Caddy is not installed." >&2
    echo "Follow the installation procedure described in the lab guide." >&2
    exit 1
fi

CERT="$SCRIPT_DIR/certs/tp1.crt"
KEY="$SCRIPT_DIR/certs/tp1.key"
VIDEO="$TP1_ROOT/media/video_ref.mp4"

if [[ ! -r "$CERT" ]]; then
    echo "ERROR: TLS certificate not found:" >&2
    echo "  $CERT" >&2
    exit 1
fi

if [[ ! -r "$KEY" ]]; then
    echo "ERROR: TLS key not found:" >&2
    echo "  $KEY" >&2
    exit 1
fi

if [[ ! -r "$VIDEO" ]]; then
    echo "ERROR: video_ref.mp4 not found:" >&2
    echo "  $VIDEO" >&2
    exit 1
fi

# ------------------------------------------------------------
# Temporary HTTP/3 server environment
# ------------------------------------------------------------

H3_RUN_DIR="/tmp/tp1-caddy-h3-${USER:-core}"
H3_CADDYFILE="$H3_RUN_DIR/Caddyfile"

rm -rf "$H3_RUN_DIR"

mkdir -p \
    "$H3_RUN_DIR/www" \
    "$H3_RUN_DIR/config" \
    "$H3_RUN_DIR/data" \
    "$H3_RUN_DIR/home"

# Copying the content to /tmp avoids permission issues
# when the lab package is installed under the /home directory.
cp "$VIDEO" "$H3_RUN_DIR/www/video_ref.mp4"
chmod 644 "$H3_RUN_DIR/www/video_ref.mp4"

# ------------------------------------------------------------
# Caddy configuration
#
# This port provides HTTP/3 exclusively.
# ------------------------------------------------------------

cat > "$H3_CADDYFILE" <<EOF
{
	admin off
	auto_https off

	servers {
		protocols h3
	}
}

https://${STREAMER_IP}:${HTTP3_PORT} {
	tls ${CERT} ${KEY}

	root * ${H3_RUN_DIR}/www
	file_server

	header Cache-Control "no-store"
}
EOF

# ------------------------------------------------------------
# Private Caddy environment
# ------------------------------------------------------------

export HOME="$H3_RUN_DIR/home"
export XDG_CONFIG_HOME="$H3_RUN_DIR/config"
export XDG_DATA_HOME="$H3_RUN_DIR/data"

# ------------------------------------------------------------
# Start server
# ------------------------------------------------------------

echo "HTTP/3/QUIC Server"
echo "  https://${STREAMER_IP}:${HTTP3_PORT}/video_ref.mp4"
echo "  Press Ctrl+C to stop"
echo

exec caddy run \
    --config "$H3_CADDYFILE" \
    --adapter caddyfile