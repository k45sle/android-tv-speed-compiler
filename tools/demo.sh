#!/bin/sh
# Builds the README clips from the scripted Robolectric tests in ReadmeScreenshotsTest (hero, arcade, handsoff, setup). Needs ffmpeg,
# ImageMagick and, for the setup clip's phone page, Google Chrome (CHROME overrides its path).
# One Gradle call records every clip. For each clip it writes docs/media/<name>.gif (10 fps, 640 px wide with the pixel TV bezel from
# tools/pixel/bezel.png; setup.gif is wider, with a phone beside the TV that shows the phone page during the pair step) and docs/media/<name>.png
# (the first frame, the poster for prefers-reduced-motion), and <name>.mp4 (the bare clip) into $DEMO_OUT (default: a new temp folder).
# The phone page is also written to docs/media/phone-pair.png. MP4s never go into the repo, so new takes do not grow git history.
# With DEMO_TAG set, every MP4 is attached to that existing GitHub release (gh release upload --clobber). Last, a size check: no GIF
# over 2 MB and docs/media (without MP4s) under 8 MB.
# Run from the repo root with JAVA_HOME and ANDROID_HOME set. GRADLE_ARGS adds Gradle flags (for example --offline);
# DEMO_CLIPS=arcade redoes just one clip.
set -eu
clips=${DEMO_CLIPS:-"hero arcade handsoff setup"}
media=docs/media
chrome=${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}
# Seconds into setup.gif while the pair page shows its QR code (the phone page sits beside the TV then).
phone_from=4.3
phone_to=7.1
out=${DEMO_OUT:-$(mktemp -d)}
mkdir -p "$out"
work=${DEMO_WORK:-$(mktemp -d)} # DEMO_WORK=<dir> keeps the recordings there and reuses them, to retune the encoding
mkdir -p "$work"
[ -n "${DEMO_WORK:-}" ] || trap 'rm -rf "$work"' EXIT

tests=""
for c in $clips; do tests="$tests --tests *ReadmeScreenshotsTest.$c"; done
case " $clips " in *" setup "*) tests="$tests --tests *ReadmeScreenshotsTest.phonePage" ;; esac
record=""
for c in $clips; do [ -f "$work/$c.gif" ] || record=1; done
# shellcheck disable=SC2086
[ -z "$record" ] || DEMO_CLIPS_DIR="$work" ./gradlew ${GRADLE_ARGS:-} -q :app:testDebugUnitTest $tests --rerun -Pmedia -Proborazzi.record

case " $clips " in *" setup "*)
  # An iframe gives the page a true 390 px phone layout; headless Chrome's own window is wider. 2x pixels keep the text sharp.
  printf '<!doctype html><body style="margin:0"><iframe src="file://%s/phone.html" width=390 height=480 style="border:0;display:block"></iframe>' "$work" > "$work/frame.html"
  "$chrome" --headless --hide-scrollbars --force-device-scale-factor=2 --window-size=800,600 --screenshot="$work/phone-full.png" "file://$work/frame.html" > /dev/null 2>&1
  magick "$work/phone-full.png" -crop 780x960+0+0 +repage "$media/phone-pair.png"
  # A simple phone frame: grey body, dark outline.
  magick "$media/phone-pair.png" -resize 290x -bordercolor '#4D5560' -border 8 -bordercolor '#0B2017' -border 2 "$work/phone.png"
  # The same frame with a dark screen, shown until the pair step.
  magick "$media/phone-pair.png" -resize 290x -fill '#07140E' -colorize 100 -bordercolor '#4D5560' -border 8 -bordercolor '#0B2017' -border 2 "$work/phone-off.png"
  ;;
esac

# The clip scaled into the bezel's 576x324 screen (at 32,32) on a dark panel, the bezel on top. $1 is the picture width.
tv() { printf '[0:v]fps=10,scale=576:324:flags=lanczos[s];color=c=0x0B2017:s=%sx412:r=10[bg];[bg][s]overlay=32:32:shortest=1[t];[t][1:v]overlay=0:0:shortest=1' "$1"; }

for c in $clips; do
  ffmpeg -loglevel error -y -i "$work/$c.gif" -r 30 -pix_fmt yuv420p -vf "scale=trunc(iw/2)*2:trunc(ih/2)*2" \
    -c:v libx264 -crf 20 -movflags +faststart "$out/$c.mp4"
  if [ "$c" = setup ]; then
    fx="$(tv 958)[tv];[tv][2:v]overlay=648:18:shortest=1[off];[off][3:v]overlay=648:18:enable='between(t,$phone_from,$phone_to)':shortest=1"
    extra="-loop 1 -i $work/phone-off.png -loop 1 -i $work/phone.png"
  else
    fx=$(tv 640)
    extra=""
  fi
  # shellcheck disable=SC2086
  ffmpeg -loglevel error -y -i "$work/$c.gif" -loop 1 -i tools/pixel/bezel.png $extra \
    -filter_complex "$fx,split[a][b];[a]palettegen=max_colors=64[p];[b][p]paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle" -loop 0 "$media/$c.gif"
  # shellcheck disable=SC2086
  ffmpeg -loglevel error -y -i "$work/$c.gif" -loop 1 -i tools/pixel/bezel.png $extra -filter_complex "$fx" -frames:v 1 "$media/$c.png"
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
