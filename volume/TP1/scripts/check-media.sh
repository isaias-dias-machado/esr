#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TP1_ROOT="${TP1_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"
printf '%-28s %-10s %-8s %-9s %-11s %-8s %-8s\n' 'file' 'resolution' 'fps' 'duration' 'video kb/s' 'audio' 'Hz'
for f in \
  video_ref.mp4 \
  video_360p_500k.mp4 \
  video_540p_1000k.mp4 \
  video_720p_2000k.mp4 \
  video_rtp.mp4; do
  P="$TP1_ROOT/media/$f"
  res=$(ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=s=x:p=0 "$P")
  fps=$(ffprobe -v error -select_streams v:0 -show_entries stream=avg_frame_rate -of default=nk=1:nw=1 "$P")
  dur=$(ffprobe -v error -show_entries format=duration -of default=nk=1:nw=1 "$P")
  vbr=$(ffprobe -v error -select_streams v:0 -show_entries stream=bit_rate -of default=nk=1:nw=1 "$P")
  acodec=$(ffprobe -v error -select_streams a:0 -show_entries stream=codec_name -of default=nk=1:nw=1 "$P")
  ar=$(ffprobe -v error -select_streams a:0 -show_entries stream=sample_rate -of default=nk=1:nw=1 "$P")
  printf '%-28s %-10s %-8s %7.1fs %9.0f %-8s %-8s\n' "$f" "$res" "$fps" "$dur" "$(awk -v b="$vbr" 'BEGIN{print b/1000}')" "$acodec" "$ar"
done
