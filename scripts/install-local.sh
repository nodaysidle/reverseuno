#!/usr/bin/env bash
# Install the local CLI, desktop entry and Right Super Hyprland bind.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN_DIR="$HOME/.local/bin"
CLI="$BIN_DIR/reverseuno"
ELECTRON="$REPO_DIR/node_modules/electron/dist/electron"

chmod +x "$REPO_DIR/bin/reverseuno"
mkdir -p "$BIN_DIR"
ln -sf "$REPO_DIR/bin/reverseuno" "$CLI"
echo "cli: $CLI -> $REPO_DIR/bin/reverseuno"

mkdir -p "$HOME/.local/share/applications"
cat > "$HOME/.local/share/applications/reverseuno.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=reverseuno
Comment=Hold Right Super to dictate
Exec=$ELECTRON $REPO_DIR
Icon=$REPO_DIR/resources/icon.png
Terminal=false
Categories=Utility;AudioVideo;
StartupWMClass=reverseuno
EOF
echo "desktop entry: ~/.local/share/applications/reverseuno.desktop"

python3 "$REPO_DIR/scripts/install-hyprland.py"
python3 "$REPO_DIR/scripts/install-omarchy-menu.py"

echo
echo "Next:"
echo "  1. npm run build"
echo "  2. npm start            # or launch reverseuno from your app menu"
echo "  3. hold Right Super and speak"
