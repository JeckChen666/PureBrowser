#!/usr/bin/env bash
# Developer-only encoder. Serving and Android execution never invoke ffmpeg.
set -euo pipefail
usage() {
  cat <<'EOF'
Usage: generate_hls_fixture.sh --output NEW_DIRECTORY [--long] [--channels 1|2]
Default: 8 seconds, mono AAC. --long: 1801 seconds (>30 min), mono or stereo.
Requires local ffmpeg with libx264 and AAC. Refuses an existing output directory.
EOF
}
output=""
seconds=8
channels=1
while (($#)); do
  case "$1" in
    --output) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; output=$2; shift 2 ;;
    --long) seconds=1801; shift ;;
    --channels) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; channels=$2; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done
[[ -n "$output" && ( "$channels" == 1 || "$channels" == 2 ) ]] || { usage >&2; exit 2; }
command -v ffmpeg >/dev/null || { echo 'ffmpeg is required for generation only' >&2; exit 1; }
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
assets="$script_dir/../../app/src/androidTest/assets/hls"
# mkdir (not mkdir -p) is the no-clobber guard. Relative paths become absolute
# before invoking ffmpeg, so a path beginning with '-' is not an encoder option.
mkdir -- "$output"
output=$(cd -- "$output" && pwd)
complete=0
trap 'if [[ $complete == 0 ]]; then echo "Generation incomplete; inspect/remove output before retrying." >&2; fi' EXIT
ffmpeg -nostdin -hide_banner -loglevel error \
  -f lavfi -i 'testsrc2=size=320x180:rate=24' \
  -f lavfi -i 'sine=frequency=440:sample_rate=48000' \
  -t "$seconds" -map 0:v:0 -map 1:a:0 \
  -c:v libx264 -preset veryfast -profile:v high -level:v 1.2 \
  -pix_fmt yuv420p -crf 23 -g 48 -keyint_min 48 -sc_threshold 0 -bf 2 \
  -flags +cgop -force_key_frames 'expr:gte(t,n_forced*2)' \
  -c:a aac -b:a 96000 -ar 48000 -ac "$channels" \
  -f hls -hls_time 2 -hls_list_size 0 -hls_playlist_type vod \
  -hls_segment_type mpegts -hls_flags independent_segments \
  -start_number 0 -hls_segment_filename "$output/segment-%03d.ts" \
  "$output/video.m3u8"
# One uninterrupted encode/mux timeline, NOT four independent encodes with PTS=0.
cp -- "$assets/master.m3u8" "$assets/LICENSE.txt" "$output/"
{
  printf 'Synthetic testsrc2 + 440Hz sine; duration=%s seconds; AAC channels=%s\n' "$seconds" "$channels"
  printf 'H264 High level 1.2, yuv420p, 320x180, 24fps, closed 48-frame GOP, 2 B-frames; AAC-LC 48kHz 96kbit/s\n'
  printf 'One continuous encode, MPEG-TS HLS, separate 2-second segments; final long segment is 1 second.\n'
  printf 'Generator recipe: tools/fixtures/generate_hls_fixture.sh (v0.1.2)\n'
  ffmpeg -version | sed -n '1p'
} > "$output/PROVENANCE.txt"
if command -v shasum >/dev/null; then
  (cd -- "$output" && shasum -a 256 video.m3u8 master.m3u8 LICENSE.txt segment-*.ts > SHA256SUMS)
elif command -v sha256sum >/dev/null; then
  (cd -- "$output" && sha256sum video.m3u8 master.m3u8 LICENSE.txt segment-*.ts > SHA256SUMS)
fi
complete=1
printf 'Generated %ss synthetic HLS, AAC channels=%s, in %s\n' "$seconds" "$channels" "$output"
