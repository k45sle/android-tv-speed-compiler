#!/bin/sh
# Renders the pixel sources into app resources with ImageMagick (nearest-neighbour, whole-number scales).
# Run from the repo root after editing an XPM: tools/pixel/render.sh
set -eu
res=app/src/main/res
for d in mdpi:4 hdpi:6 xhdpi:8 xxhdpi:12 xxxhdpi:16; do
  # The adaptive icon background (#153C29) shows through the clear cells.
  magick tools/pixel/icon.xpm -transparent '#153C29' -scale "${d#*:}00%" "PNG32:$res/mipmap-${d%%:*}/ic_launcher_foreground.png"
done
magick tools/pixel/banner.xpm -scale 1000% "$res/drawable-xhdpi/banner.png"
magick tools/pixel/icon.xpm -scale 1600% docs/media/icon.png
cp "$res/drawable-xhdpi/banner.png" docs/media/banner.png
python3 -I tools/pixel/bezel.py
magick tools/pixel/bezel.xpm -transparent '#153C29' -scale 400% PNG32:tools/pixel/bezel.png
