#!/usr/bin/env bash
set -euo pipefail

version_name="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=?[[:space:]]*"([^"]+)".*/\1/p' app/build.gradle)"
[[ "$version_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
printf '%s\n' "$version_name"
