#!/bin/sh
# Builds the README clips from the scripted Robolectric tests in ReadmeScreenshotsTest (hero, arcade, handsoff). Needs ffmpeg.
# One Gradle call records every clip. For each clip it writes docs/media/<name>.gif (640 px wide, 10 fps) and docs/media/<name>.png
# (the first frame, the poster for prefers-reduced-motion), and <name>.mp4 into $DEMO_OUT (default: a new temp folder).
# MP4s never go into the repo, so new takes do not grow git history. With DEMO_TAG set, every MP4 is attached to that existing
# GitHub release (gh release upload --clobber). Last, a size check: no GIF over 2 MB and docs/media (without MP4s) under 8 MB.
# Run from the repo root with JAVA_HOME and ANDROID_HOME set. GRADLE_ARGS adds Gradle flags (for example --offline).
set -eu
clips=${DEMO_CLIPS:-"hero arcade handsoff"} # DEMO_CLIPS=arcade redoes just one clip
media=docs/media
out=${DEMO_OUT:-$(mktemp -d)}
mkdir -p "$out"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

tests=""
for c in $clips; do tests="$tests --tests *ReadmeScreenshotsTest.$c"; done
# shellcheck disable=SC2086
DEMO_CLIPS_DIR="$work" ./gradlew ${GRADLE_ARGS:-} -q :app:testDebugUnitTest $tests --rerun -Pmedia -Proborazzi.record

for c in $clips; do
  ffmpeg -loglevel error -y -i "$work/$c.gif" -r 30 -pix_fmt yuv420p -vf "scale=trunc(iw/2)*2:trunc(ih/2)*2" \
    -c:v libx264 -crf 20 -movflags +faststart "$out/$c.mp4"
  ffmpeg -loglevel error -y -i "$work/$c.gif" \
    -vf "fps=10,scale=640:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=96:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5" \
    -loop 0 "$media/$c.gif"
  ffmpeg -loglevel error -y -i "$work/$c.gif" -frames:v 1 -vf "scale=640:-1:flags=lanczos" "$media/$c.png"
done
ls -l "$media"/*.gif "$media"/*.png "$out"/*.mp4

if [ -n "${DEMO_TAG:-}" ]; then
  for c in $clips; do gh release upload "$DEMO_TAG" "$out/$c.mp4" --clobber; done
else
  echo "To attach the clips to a release: gh release upload <tag> $out/*.mp4 --clobber"
fi

fail=0
for f in "$media"/*.gif; do
  size=$(wc -c < "$f")
  if [ "$size" -gt 2097152 ]; then echo "ERROR: $f is $size bytes, over the 2 MB (2097152) GIF limit" >&2; fail=1; fi
done
total=$(find "$media" -type f ! -name '*.mp4' -exec cat {} + | wc -c)
if [ "$total" -gt 8388608 ]; then echo "ERROR: $media (without MP4s) is $total bytes, over the 8 MB (8388608) limit" >&2; fail=1; fi
[ "$fail" -eq 0 ] || exit 1
echo "Size check passed: docs/media without MP4s is $total bytes"
