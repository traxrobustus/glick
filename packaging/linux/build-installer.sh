#!/usr/bin/env bash
# Builds glick-<version>-linux-x64.run: one self-extracting file that installs
# glick for the current user, without root and on any distribution.
#
#   packaging/linux/build-installer.sh <app image dir> <version> <output file>
#
# <app image dir> is what `./gradlew :app:createDistributable` produces:
# app/build/compose/binaries/main/app/glick
set -euo pipefail

image=${1:?app image directory}
version=${2:?version}
output=${3:?output file}
here=$(cd "$(dirname "$0")" && pwd)

[ -x "$image/bin/glick" ] || { echo "no glick app image in $image" >&2; exit 1; }

stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
cp -a "$image" "$stage/glick"
cp "$here/../icons/glick.png" "$stage/glick/glick.png"

sed "s/@VERSION@/$version/g" "$here/installer-header.sh" > "$output"
tar -C "$stage" -czf - glick >> "$output"
chmod +x "$output"
echo "wrote $output"
