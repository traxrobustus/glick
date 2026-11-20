#!/usr/bin/env bash
# glick @VERSION@ installer for Linux (x86-64). Installs for the current user only.
#
#   ./glick-@VERSION@-linux-x64.run              install or upgrade
#   ./glick-@VERSION@-linux-x64.run --uninstall  remove (saved patterns are kept)
#
# Set PREFIX to install somewhere other than ~/.local.
set -euo pipefail

prefix=${PREFIX:-$HOME/.local}
app_dir=$prefix/opt/glick
launcher=$prefix/bin/glick
desktop_file=$prefix/share/applications/glick.desktop
icon_file=$prefix/share/icons/hicolor/512x512/apps/glick.png

if [ "${1:-}" = "--uninstall" ]; then
    rm -rf "$app_dir" "$launcher" "$desktop_file" "$icon_file"
    echo "glick removed. Patterns and the activity log stay in ${XDG_DATA_HOME:-$HOME/.local/share}/glick."
    exit 0
fi

[ "$(uname -m)" = "x86_64" ] || { echo "This installer is for x86-64 Linux; this machine is $(uname -m)." >&2; exit 1; }

echo "Installing glick @VERSION@ into $app_dir"
mkdir -p "$prefix/opt" "$prefix/bin" "$(dirname "$desktop_file")" "$(dirname "$icon_file")"
rm -rf "$app_dir"
payload_line=$(awk '/^__PAYLOAD__$/ { print NR + 1; exit }' "$0")
tail -n +"$payload_line" "$0" | tar -xz -C "$prefix/opt"

ln -sf "$app_dir/bin/glick" "$launcher"
cp "$app_dir/glick.png" "$icon_file"
cat > "$desktop_file" <<DESKTOP
[Desktop Entry]
Type=Application
Name=glick
Comment=Metronome with polyrhythms, subdivisions and practice timers
Exec=$app_dir/bin/glick
Icon=$icon_file
Categories=AudioVideo;Audio;Music;
Terminal=false
DESKTOP

echo "Done. Start it from your applications menu, or run: $launcher"
case ":$PATH:" in *":$prefix/bin:"*) ;; *) echo "Note: $prefix/bin is not on your PATH." ;; esac
exit 0
__PAYLOAD__
