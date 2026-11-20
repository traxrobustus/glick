#!/usr/bin/env bash
# Rewrites the dependency list of the .deb that jpackage produced.
#
# jpackage copies the exact package names of the build machine, and some of
# those exist only on Ubuntu (libjpeg-turbo8) or only on recent releases
# (the *t64 names). This replaces them with the short list glick really needs,
# written so that both Debian and Ubuntu, old and new, can satisfy it.
#
#   packaging/linux/portable-deb.sh <input.deb> <output.deb>
set -euo pipefail

input=${1:?input .deb}
output=${2:?output .deb}
depends='libc6, libstdc++6, zlib1g, libasound2t64 | libasound2, libx11-6, libxext6, libxi6, libxrender1, libxtst6, libfreetype6, libfontconfig1, libgl1, xdg-utils'

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
dpkg-deb -R "$input" "$work/pkg"
sed -i "s/^Depends:.*/Depends: $depends/" "$work/pkg/DEBIAN/control"
dpkg-deb --root-owner-group -b "$work/pkg" "$output" > /dev/null
echo "wrote $output"
