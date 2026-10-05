#!/usr/bin/env bash
# Developer-only encoder for the v0.1.9 separate-audio HLS fixtures. Serving and Android
# execution never invoke ffmpeg; regeneration is manual and re-checks SHA256SUMS by hand.
set -euo pipefail
usage() {
  cat <<'EOF'
Usage: generate_hls_separate_fixture.sh --output NEW_DIRECTORY [--seconds N]
Default: 8 seconds. Requires local ffmpeg with libx264 and AAC. Refuses an existing directory.
Produces video-only TS segments, audio-only TS segments and hand-shaped playlists; see
PROVENANCE.txt inside the output.
EOF
}
output=""
seconds=8
while (($#)); do
  case "$1" in
    --output) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; output=$2; shift 2 ;;
    --seconds) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; seconds=$2; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done
[[ -n "$output" ]] || { usage >&2; exit 2; }
command -v ffmpeg >/dev/null || { echo 'ffmpeg is required for generation only' >&2; exit 1; }
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
assets="$script_dir/../../app/src/androidTest/assets/hls-separate"
mkdir -- "$output"
output=$(cd -- "$output" && pwd)
complete=0
trap 'if [[ $complete == 0 ]]; then echo "Generation incomplete; inspect/remove output before retry." >&2; fi' EXIT
ffmpeg -nostdin -hide_banner -loglevel error -y \
  -f lavfi -i 'testsrc2=size=320x180:rate=24' -t "$seconds" \
  -map 0:v:0 -c:v libx264 -preset veryfast -profile:v high -level:v 1.2 \
    -pix_fmt yuv420p -crf 23 -g 48 -keyint_min 48 -sc_threshold 0 -bf 2 \
    -flags +cgop -force_key_frames 'expr:gte(t,n_forced*2)' \
  -an -f hls -hls_time 2 -hls_list_size 0 -hls_playlist_type vod \
    -hls_segment_type mpegts -hls_flags independent_segments \
    -start_number 0 -hls_segment_filename "$output/video-%03d.ts" "$output/video.m3u8"
ffmpeg -nostdin -hide_banner -loglevel error -y \
  -f lavfi -i 'sine=frequency=440:sample_rate=48000' -t "$seconds" \
  -vn -c:a aac -b:a 96000 -ar 48000 -ac 1 \
  -f hls -hls_time 2 -hls_list_size 0 -hls_playlist_type vod \
    -hls_segment_type mpegts -hls_flags independent_segments \
    -start_number 0 -hls_segment_filename "$output/audio-%03d.ts" "$output/audio.m3u8"
cp -- "$assets/master.m3u8" "$assets/LICENSE.txt" "$output/"
{
  printf 'Synthetic separate-audio HLS; seconds=%s\n' "$seconds"
  ffmpeg -version | sed -n 1p
} > "$output/PROVENANCE.txt"
complete=1
printf 'Generated separate-audio fixtures in %s\n' "$output"
