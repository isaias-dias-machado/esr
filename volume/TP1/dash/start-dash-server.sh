#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TP1_ROOT="${TP1_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"
# shellcheck disable=SC1091
source "$TP1_ROOT/scripts/tp1-env.sh"

command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 is not installed." >&2; exit 1; }
[[ -s "$SCRIPT_DIR/player.html" ]] || { echo "ERROR: player.html is missing." >&2; exit 1; }
[[ -s "$SCRIPT_DIR/content/video_manifest.mpd" ]] || {
  echo "ERROR: content/video_manifest.mpd is missing." >&2
  echo "Run first: $SCRIPT_DIR/prepare_dash.sh" >&2
  exit 1
}

echo "DASH server with caching disabled"
echo "Directory: $SCRIPT_DIR"
echo "Player URL: http://${STREAMER_IP}:${DASH_HTTP_PORT}/player.html"
echo "Cache-Control: no-store, no-cache"
echo "Stop with Ctrl+C."

exec python3 - "$SCRIPT_DIR" "$DASH_HTTP_PORT" <<'PY'
import os
import sys
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler

root = os.path.abspath(sys.argv[1])
port = int(sys.argv[2])
os.chdir(root)

class NoCacheHandler(SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _drop_conditionals(self):
        # Prevents 304 responses during the experiments: each segment must
        # traverse the experimental link and remain visible in Wireshark.
        for header in ("If-Modified-Since", "If-None-Match"):
            if header in self.headers:
                del self.headers[header]

    def do_GET(self):
        self._drop_conditionals()
        super().do_GET()

    def do_HEAD(self):
        self._drop_conditionals()
        super().do_HEAD()

    def end_headers(self):
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")
        super().end_headers()

ThreadingHTTPServer(("0.0.0.0", port), NoCacheHandler).serve_forever()
PY
