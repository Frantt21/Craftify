#!/usr/bin/env bash
# Downloads the official playerctl binary (MIT, only depends on libglib2.0-0) from the
# GitHub release and places it in the mod's resources so it is bundled into the JAR.
# That way, on Linux the player does NOT need to install anything with sudo.
#
# Usage (from the CraftifyMod project root):
#   bash scripts/fetch-linux-playerctl.sh
#
# For other architectures (e.g. aarch64), drop the binary at:
#   src/main/resources/assets/craftify/native/linux/aarch64/playerctl
set -euo pipefail

VERSION="2.4.1"
URL="https://github.com/altdesktop/playerctl/releases/download/v${VERSION}/playerctl-${VERSION}_amd64.deb"
DEST="src/main/resources/assets/craftify/native/linux/x86_64"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Downloading playerctl ${VERSION} (official GitHub release)..."
curl -fsSL -o "$TMP/playerctl.deb" "$URL"

echo "Extracting the binary from the .deb..."
if command -v 7z >/dev/null 2>&1; then
    (cd "$TMP" && 7z x -y playerctl.deb >/dev/null && tar -xf data.tar.gz)
elif command -v ar >/dev/null 2>&1; then
    (cd "$TMP" && ar x playerctl.deb && tar -xf data.tar.*)
else
    echo "ERROR: 7z or ar is required to extract the .deb." >&2
    exit 1
fi

if [ ! -f "$TMP/usr/bin/playerctl" ]; then
    echo "ERROR: usr/bin/playerctl not found inside the package." >&2
    exit 1
fi

mkdir -p "$DEST"
cp "$TMP/usr/bin/playerctl" "$DEST/playerctl"
chmod +x "$DEST/playerctl"

echo "OK: $DEST/playerctl ($(stat -c %s "$DEST/playerctl" 2>/dev/null || echo '?') bytes)"
