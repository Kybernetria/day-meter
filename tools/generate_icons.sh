#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$ROOT_DIR/app/src/main/res"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

square_master="$TMP_DIR/ic_launcher_master.png"
round_master="$TMP_DIR/ic_launcher_round_master.png"

convert -size 1024x1024 canvas:none \
  -fill '#101B25' -stroke 'none' -draw 'roundrectangle 120,120 904,904 220,220' \
  -stroke '#213748' -strokewidth 24 -fill none -draw 'roundrectangle 120,120 904,904 220,220' \
  -stroke '#223948' -strokewidth 86 -fill none -draw 'arc 250,230 774,754 205,335' \
  -stroke '#46E3D3' -strokewidth 86 -fill none -draw 'arc 250,230 774,754 205,288' \
  -stroke '#F6B356' -strokewidth 86 -fill none -draw 'arc 250,230 774,754 288,335' \
  -fill '#FFD37A' -stroke 'none' -draw 'circle 708,362 708,298' \
  -fill '#162633' -draw 'roundrectangle 292,660 732,742 42,42' \
  -fill '#46E3D3' -draw 'roundrectangle 316,684 608,718 20,20' \
  -fill '#F6B356' -draw 'circle 640,701 640,675' \
  "$square_master"

convert -size 1024x1024 canvas:none \
  -fill '#101B25' -stroke 'none' -draw 'circle 512,512 512,108' \
  -stroke '#213748' -strokewidth 24 -fill none -draw 'circle 512,512 512,108' \
  -stroke '#223948' -strokewidth 86 -fill none -draw 'arc 250,230 774,754 205,335' \
  -stroke '#46E3D3' -strokewidth 86 -fill none -draw 'arc 250,230 774,754 205,288' \
  -stroke '#F6B356' -strokewidth 86 -fill none -draw 'arc 250,230 774,754 288,335' \
  -fill '#FFD37A' -stroke 'none' -draw 'circle 708,362 708,298' \
  -fill '#162633' -draw 'roundrectangle 292,660 732,742 42,42' \
  -fill '#46E3D3' -draw 'roundrectangle 316,684 608,718 20,20' \
  -fill '#F6B356' -draw 'circle 640,701 640,675' \
  "$round_master"

for density in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  name="${density%%:*}"
  size="${density##*:}"
  convert "$square_master" -resize "${size}x${size}" "$OUT_DIR/mipmap-${name}/ic_launcher.png"
  convert "$round_master" -resize "${size}x${size}" "$OUT_DIR/mipmap-${name}/ic_launcher_round.png"
done

echo "Launcher icons regenerated."
