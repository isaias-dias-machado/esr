#!/usr/bin/env bash
set -u

# ============================================================
# ESR - TP1
# Package and environment verification
# ============================================================

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TP1_ROOT="${TP1_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"

PASS=0
WARN=0
FAIL=0

ok() {
    echo "[ OK ] $*"
    PASS=$((PASS+1))
}

warn() {
    echo "[WARN] $*"
    WARN=$((WARN+1))
}

bad() {
    echo "[FAIL] $*"
    FAIL=$((FAIL+1))
}

echo "============================================================"
echo " ESR - TP1 - Package and environment verification"
echo "============================================================"
echo
echo "TP1_ROOT=$TP1_ROOT"
echo

# ------------------------------------------------------------
# 1. Required package files and directories
# ------------------------------------------------------------

echo "== Package files =="

req_files=(
    media/MEDIA-INFO.txt
    media/video_ref.mp4
    media/video_360p_500k.mp4
    media/video_540p_1000k.mp4
    media/video_720p_2000k.mp4
    media/video_rtp.mp4

    dash/prepare_dash.sh
    dash/open-dash-player.sh
    dash/start-dash-server.sh
    dash/player.html
    dash/dash.all.min.js

    http23/start-http2-server.sh
    http23/start-http3-server.sh
    http23/get-http2.sh
    http23/get-http3.sh
    http23/certs/tp1.crt
    http23/certs/tp1.key

    multicast/enable-multicast.sh
    multicast/README.txt

    rtp/README.txt

    scripts/check-media.sh
    scripts/check-package.sh
    scripts/tp1-env.sh

    tools/curl-http3/bin/curl

    topology/tp1.xml
)

for f in "${req_files[@]}"; do
    if [[ -s "$TP1_ROOT/$f" ]]; then
        ok "$f"
    else
        bad "missing: $f"
    fi
done

# Required package directories
req_dirs=(
    results
)

for d in "${req_dirs[@]}"; do
    if [[ -d "$TP1_ROOT/$d" ]]; then
        ok "$d/"
    else
        bad "missing directory: $d/"
    fi
done

echo

# ------------------------------------------------------------
# 2. Required tools
# ------------------------------------------------------------

echo "== Tools =="

for c in ffmpeg ffprobe ffplay python3 curl nginx openssl caddy tcpdump; do
    if command -v "$c" >/dev/null 2>&1; then
        ok "command $c"
    else
        bad "missing command: $c"
    fi
done

if command -v firefox >/dev/null 2>&1; then
    ok "Firefox"
else
    warn "Firefox not found"
fi

if command -v traceroute >/dev/null 2>&1; then
    ok "traceroute (optional)"
else
    warn "traceroute not found (optional; ping + ip route are sufficient)"
fi

if command -v wireshark >/dev/null 2>&1; then
    ok "Wireshark"
else
    warn "Wireshark not found in PATH (it may be installed on Windows)"
fi

if command -v smcrouted >/dev/null 2>&1 &&
   command -v smcroutectl >/dev/null 2>&1; then
    ok "SMCRoute"
else
    warn "SMCRoute not found (required on the routers for Stage 3)"
fi

echo

# ------------------------------------------------------------
# 3. HTTP/2 - nginx and system curl
# ------------------------------------------------------------

echo "== HTTP/2 =="

if command -v nginx >/dev/null 2>&1; then
    if nginx -V 2>&1 | grep -q -- '--with-http_v2_module'; then
        ok "nginx with HTTP/2 support"
    else
        bad "nginx without --with-http_v2_module"
    fi
fi

if command -v curl >/dev/null 2>&1; then
    if curl -V 2>/dev/null | grep -q 'HTTP2'; then
        ok "system curl with HTTP/2 support"
    else
        bad "system curl without HTTP/2 support"
    fi
fi

echo

# ------------------------------------------------------------
# 4. HTTP/3 - Caddy and private curl
# ------------------------------------------------------------

echo "== HTTP/3 =="

if command -v caddy >/dev/null 2>&1; then
    ok "Caddy available for the HTTP/3 server"
fi

CURL_H3="$TP1_ROOT/tools/curl-http3/bin/curl"

if [[ ! -x "$CURL_H3" ]]; then
    bad "HTTP/3 curl not found or not executable: $CURL_H3"
else
    if "$CURL_H3" -V 2>/dev/null | grep -q 'HTTP3' &&
       "$CURL_H3" --help all 2>/dev/null | grep -q -- '--http3-only'; then
        ok "private curl with HTTP/3 support"
    else
        bad "private curl without functional HTTP/3 support"
    fi
fi

echo

# ------------------------------------------------------------
# 5. Media files
# ------------------------------------------------------------

echo "== Media files =="

if command -v ffprobe >/dev/null 2>&1; then

    check_video() {
        local f="$1"
        local exp="$2"
        local got

        got=$(ffprobe \
            -v error \
            -select_streams v:0 \
            -show_entries stream=width,height \
            -of csv=s=x:p=0 \
            "$TP1_ROOT/$f" 2>/dev/null || true)

        if [[ "$got" == "$exp" ]]; then
            ok "$f = $got"
        else
            bad "$f: expected $exp, got ${got:-?}"
        fi
    }

    check_video media/video_360p_500k.mp4 640x360
    check_video media/video_540p_1000k.mp4 960x540
    check_video media/video_720p_2000k.mp4 1280x720
fi

echo

# ------------------------------------------------------------
# 6. Artifacts that should not exist in the initial package
# ------------------------------------------------------------

echo "== Initial TP state =="

if [[ -d "$TP1_ROOT/dash/content" ]] &&
   find "$TP1_ROOT/dash/content" \
        -maxdepth 1 \
        -type f \
        \( -name '*.m4s' -o -name '*.mpd' \) \
        | grep -q .; then

    warn "dash/content contains previously generated DASH content"
else
    ok "DASH content has not been generated yet"
fi

if find "$TP1_ROOT/rtp" \
        -maxdepth 1 \
        -type f \
        -name '*.sdp' \
        | grep -q .; then

    warn "rtp/ contains previously generated SDP files"
else
    ok "SDP files have not been generated yet"
fi

echo

# ------------------------------------------------------------
# 7. Summary
# ------------------------------------------------------------

echo "============================================================"
echo "Summary: OK=$PASS  WARN=$WARN  FAIL=$FAIL"
echo "============================================================"

if [[ $FAIL -eq 0 ]]; then
    echo "Package and environment verified successfully."
    exit 0
else
    echo "$FAIL problem(s) were found."
    exit 1
fi