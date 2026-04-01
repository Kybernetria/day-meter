#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$ROOT_DIR/app/src/main/res"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

square_master="$TMP_DIR/ic_launcher_master.png"
round_master="$TMP_DIR/ic_launcher_round_master.png"

render_icon() {
  local base_shape="$1"
  local output="$2"

  convert -size 1024x1024 canvas:none \
    -fill '#0F1720' -stroke 'none' -draw "$base_shape" \
    -stroke '#273746' -strokewidth 24 -fill none -draw "$base_shape" \
    -stroke '#314554' -strokewidth 64 -fill none -draw 'arc 208,172 816,780 205,335' \
    -stroke '#45E2D3' -strokewidth 64 -fill none -draw 'arc 208,172 816,780 205,292' \
    -stroke '#F7B45A' -strokewidth 64 -fill none -draw 'arc 208,172 816,780 292,320' \
    -fill '#F9D36A' -stroke 'none' -draw 'circle 688,332 688,268' \
    -stroke '#3B5161' -strokewidth 22 -draw 'line 230,604 794,604' \
    -fill '#15212C' -stroke '#324656' -strokewidth 18 -draw 'roundrectangle 224,688 800,798 54,54' \
    -fill '#45E2D3' -stroke 'none' -draw 'roundrectangle 252,716 612,770 27,27' \
    -fill '#F7B45A' -stroke 'none' -draw 'circle 642,743 642,716' \
    -fill '#F9D36A' -draw 'circle 278,604 278,584' \
    -fill '#F9D36A' -draw 'circle 746,604 746,584' \
    "$output"
}

render_icon 'roundrectangle 120,120 904,904 220,220' "$square_master"
render_icon 'circle 512,512 512,108' "$round_master"

for density in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  name="${density%%:*}"
  size="${density##*:}"
  convert "$square_master" -resize "${size}x${size}" "$OUT_DIR/mipmap-${name}/ic_launcher.png"
  convert "$round_master" -resize "${size}x${size}" "$OUT_DIR/mipmap-${name}/ic_launcher_round.png"
done

echo "Launcher icons regenerated."
