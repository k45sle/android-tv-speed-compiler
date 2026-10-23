#!/bin/sh
# Builds the README demo (demo.mp4 and demo.gif) from the scripted Robolectric clip in
# ReadmeScreenshotsTest.demoFlow: Home, Apps, Nuvio, Optimize now, each game while it runs, Done, Home. Needs ffmpeg.
# The GIF runs at 8 fps with 64 colours to stay under 3 MB.
# Output goes to $DEMO_OUT (default: a new temp folder), never into the repo, so new takes do not grow git history.
# With DEMO_TAG set, demo.mp4 is attached to that existing GitHub release (gh release upload --clobber).
# Copy demo.gif into docs/media/ by hand only when the clip really changed; the README shows that GIF.
# Run from the repo root with JAVA_HOME and ANDROID_HOME set.
set -eu
out=${DEMO_OUT:-$(mktemp -d)}
mkdir -p "$out"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
DEMO_FLOW_GIF="$work/flow.gif" ./gradlew -q :app:testDebugUnitTest --tests '*ReadmeScreenshotsTest.demoFlow' --rerun -Pmedia -Proborazzi.record
ffmpeg -loglevel error -y -i "$work/flow.gif" -r 30 -pix_fmt yuv420p -c:v libx264 -crf 20 -movflags +faststart "$out/demo.mp4"
ffmpeg -loglevel error -y -i "$work/flow.gif" \
  -vf "fps=8,scale=560:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=64[p];[b][p]paletteuse=dither=none" \
  -loop 0 "$out/demo.gif"
ls -l "$out/demo.mp4" "$out/demo.gif"
if [ -n "${DEMO_TAG:-}" ]; then
  gh release upload "$DEMO_TAG" "$out/demo.mp4" --clobber
else
  echo "To attach the clip to a release: gh release upload <tag> $out/demo.mp4 --clobber"
fi
