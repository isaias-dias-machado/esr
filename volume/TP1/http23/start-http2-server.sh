#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# ESR - TP1
# HTTP/2 Server over TLS/TCP
# ============================================================

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TP1_ROOT="${TP1_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"

# shellcheck disable=SC1091
source "$TP1_ROOT/scripts/tp1-env.sh"

# ------------------------------------------------------------
# Checks
# ------------------------------------------------------------

if ! command -v nginx >/dev/null 2>&1; then
    echo "ERROR: nginx is not installed." >&2
    exit 1
fi

if ! nginx -V 2>&1 | grep -q -- '--with-http_v2_module'; then
    echo "ERROR: nginx does not support HTTP/2." >&2
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
# Temporary HTTP/2 server environment
# ------------------------------------------------------------

H2_RUN_DIR="/tmp/tp1-nginx-h2-${USER:-core}"
H2_NGINX_CONF="$H2_RUN_DIR/nginx.conf"

rm -rf "$H2_RUN_DIR"
mkdir -p "$H2_RUN_DIR/logs" "$H2_RUN_DIR/www"

# Copying the content to /tmp avoids permission issues
# when the lab package is installed under the /home directory.
cp "$VIDEO" "$H2_RUN_DIR/www/video_ref.mp4"
chmod 644 "$H2_RUN_DIR/www/video_ref.mp4"

# ------------------------------------------------------------
# nginx configuration
# ------------------------------------------------------------

cat > "$H2_NGINX_CONF" <<EOF
worker_processes 1;

error_log logs/error.log info;
pid logs/nginx.pid;

events {
    worker_connections 256;
}

http {
    access_log logs/access.log;
    sendfile on;

    server {
        listen ${HTTP2_PORT} ssl http2;

        ssl_certificate ${CERT};
        ssl_certificate_key ${KEY};
        ssl_protocols TLSv1.2 TLSv1.3;

        location / {
            root ${H2_RUN_DIR}/www;
            add_header Cache-Control "no-store" always;
        }
    }
}
EOF

# ------------------------------------------------------------
# Start server
# ------------------------------------------------------------

echo "HTTP/2/TLS Server"
echo "  https://${STREAMER_IP}:${HTTP2_PORT}/video_ref.mp4"
echo "  Press Ctrl+C to stop"
echo

exec nginx \
    -p "$H2_RUN_DIR/" \
    -c nginx.conf \
    -g 'daemon off;'