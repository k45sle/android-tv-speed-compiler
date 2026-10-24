#!/bin/sh
# Re-saves the README screenshots in docs/media as 256-colour PNGs (about a third of the size, no visible change), so the folder
# stays under the 8 MB budget that tools/demo.sh checks. Run it after recording the stills. Needs ImageMagick. Skips tiny pixel-art files.
set -eu
for f in docs/media/*.png; do
  [ "$(wc -c < "$f")" -gt 20000 ] || continue
  magick "$f" -colors 256 -dither None "PNG8:$f"
done
