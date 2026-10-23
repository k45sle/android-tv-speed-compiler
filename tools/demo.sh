#!/bin/sh
# Builds the README demo (docs/media/demo.mp4 and demo.gif) from the scripted Robolectric clip in
# ReadmeScreenshotsTest.demoFlow: Home, Apps, Nuvio, Optimize now, each game while it runs, Done, Home. Needs ffmpeg.
# The GIF runs at 8 fps with 64 colours to stay under 3 MB.
# Run from the repo root with JAVA_HOME and ANDROID_HOME set.
set -eu
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
DEMO_FLOW_GIF="$out/flow.gif" ./gradlew -q :app:testDebugUnitTest --tests '*ReadmeScreenshotsTest.demoFlow' --rerun -Proborazzi.record
ffmpeg -loglevel error -y -i "$out/flow.gif" -r 30 -pix_fmt yuv420p -c:v libx264 -crf 20 -movflags +faststart docs/media/demo.mp4
ffmpeg -loglevel error -y -i "$out/flow.gif" \
  -vf "fps=8,scale=560:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=64[p];[b][p]paletteuse=dither=none" \
  -loop 0 docs/media/demo.gif
ls -l docs/media/demo.mp4 docs/media/demo.gif
