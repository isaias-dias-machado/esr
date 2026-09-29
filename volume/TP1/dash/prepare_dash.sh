#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TP1_ROOT="${TP1_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"
MEDIA_DIR="$TP1_ROOT/media"
OUT_DIR="$SCRIPT_DIR/content"
MPD="$OUT_DIR/video_manifest.mpd"

need() {
  command -v "$1" >/dev/null 2>&1 || { echo "ERROR: $1 is not installed." >&2; exit 1; }
}
need ffmpeg
need ffprobe

inputs=(
  "$MEDIA_DIR/video_360p_500k.mp4"
  "$MEDIA_DIR/video_540p_1000k.mp4"
  "$MEDIA_DIR/video_720p_2000k.mp4"
)
for f in "${inputs[@]}"; do
  [[ -r "$f" ]] || { echo "ERROR: missing file: $f" >&2; exit 1; }
done

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

echo "Preparing MPEG-DASH (3 video representations + 1 audio track)..."
# The three representations contain the same synthetic audio track. To avoid
# unnecessary duplication, only the audio track from the first file is
# included in the MPD as a separate Adaptation Set.
ffmpeg -hide_banner -loglevel warning -y \
  -i "${inputs[0]}" -i "${inputs[1]}" -i "${inputs[2]}" \
  -map 0:v:0 -map 1:v:0 -map 2:v:0 -map 0:a:0 \
  -c copy \
  -f dash \
  -seg_duration 2 \
  -use_template 1 \
  -use_timeline 1 \
  -adaptation_sets "id=0,streams=v id=1,streams=a" \
  -init_seg_name 'init_$RepresentationID$.m4s' \
  -media_seg_name 'chunk_$RepresentationID$_$Number%05d$.m4s' \
  "$MPD"

[[ -s "$MPD" ]] || { echo "ERROR: the MPD was not created." >&2; exit 1; }

echo
echo "MPD created at: $MPD"
echo "Representations found in the MPD:"
grep -o '<Representation[^>]*>' "$MPD" | sed 's/^/  /' || true

video_reps=$(grep -c 'mimeType="video/mp4"' "$MPD" || true)
audio_reps=$(grep -c 'mimeType="audio/mp4"' "$MPD" || true)
init_count=$(find "$OUT_DIR" -maxdepth 1 -type f -name 'init_*.m4s' | wc -l)
chunk_count=$(find "$OUT_DIR" -maxdepth 1 -type f -name 'chunk_*.m4s' | wc -l)
segments_per_rep=$(find "$OUT_DIR" -maxdepth 1 -type f -name 'chunk_0_*.m4s' | wc -l)

echo
echo "DASH content summary:"
printf '  Video representations       : %s\n' "$video_reps"
printf '  Audio representations       : %s\n' "$audio_reps"
printf '  Initialization segments     : %s\n' "$init_count"
printf '  Segments per representation : %s\n' "$segments_per_rep"
printf '  Total media segments        : %s\n' "$chunk_count"
printf '  Manifest                    : %s\n' "$MPD"
