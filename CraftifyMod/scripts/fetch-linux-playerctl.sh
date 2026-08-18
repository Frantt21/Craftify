#!/usr/bin/env bash
# Downloads the official playerctl binary from the GitHub release and its companion
# library libplayerctl.so.2 (the binary is dynamically linked against it) and places both
# in the mod's resources so they are bundled into the JAR. That way, on Linux the player
# does NOT need to install anything with sudo.
#
# Usage (from the CraftifyMod project root):
#   bash scripts/fetch-linux-playerctl.sh
#
# For other architectures (e.g. aarch64), drop the binary and the library at:
#   src/main/resources/assets/craftify/native/linux/aarch64/
set -euo pipefail

VERSION="2.4.1"
BIN_URL="https://github.com/altdesktop/playerctl/releases/download/v${VERSION}/playerctl-${VERSION}_amd64.deb"
# The official release ships only the playerctl .deb (no lib package), so the companion
# library comes from the Ubuntu archive (jammy build: same 2.4.1 lib, low glib2 symbol
# requirements -> runs on any modern desktop Linux; the mod exposes it via LD_LIBRARY_PATH).
LIB_URL="http://archive.ubuntu.com/ubuntu/pool/universe/p/playerctl/libplayerctl2_${VERSION}-1_amd64.deb"
DEST="src/main/resources/assets/craftify/native/linux/x86_64"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Downloading playerctl ${VERSION} (official GitHub release)..."
curl -fsSL -o "$TMP/playerctl.deb" "$BIN_URL"

echo "Downloading libplayerctl2 ${VERSION} (Ubuntu archive)..."
curl -fsSL -o "$TMP/libplayerctl2.deb" "$LIB_URL"

echo "Extracting the binary and the library from the .debs..."
if command -v 7z >/dev/null 2>&1; then
    (cd "$TMP" && 7z x -y playerctl.deb >/dev/null && tar -xf data.tar.gz)
    (cd "$TMP" && 7z x -y libplayerctl2.deb >/dev/null && tar -xf data.tar.gz)
elif command -v ar >/dev/null 2>&1; then
    (cd "$TMP" && ar x playerctl.deb && tar -xf data.tar.*)
    (cd "$TMP" && ar x libplayerctl2.deb && tar -xf data.tar.*)
else
    echo "ERROR: 7z or ar is required to extract the .debs." >&2
    exit 1
fi

if [ ! -f "$TMP/usr/bin/playerctl" ]; then
    echo "ERROR: usr/bin/playerctl not found inside the package." >&2
    exit 1
fi

mkdir -p "$DEST"
cp "$TMP/usr/bin/playerctl" "$DEST/playerctl"
chmod +x "$DEST/playerctl"

LIB_FILE="$(find "$TMP/usr/lib" -name 'libplayerctl.so.2*' -type f | head -1)"
if [ -n "$LIB_FILE" ]; then
    cp "$LIB_FILE" "$DEST/libplayerctl.so.2"
    chmod +x "$DEST/libplayerctl.so.2"
else
    echo "WARNING: libplayerctl.so.2 not found inside the lib package." >&2
fi

echo "OK: $DEST/playerctl ($(stat -c %s "$DEST/playerctl" 2>/dev/null || echo '?') bytes)"
if [ -f "$DEST/libplayerctl.so.2" ]; then
    echo "OK: $DEST/libplayerctl.so.2 ($(stat -c %s "$DEST/libplayerctl.so.2" 2>/dev/null || echo '?') bytes)"
fi
