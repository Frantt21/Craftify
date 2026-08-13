#!/usr/bin/env bash
# Descarga el binario oficial de playerctl (MIT, solo depende de libglib2.0-0) desde el
# release de GitHub y lo coloca en las resources del mod para que se incluya en el JAR.
# Así, en Linux el jugador NO necesita instalar nada con sudo.
#
# Uso (desde la raíz del proyecto Craftify):
#   bash scripts/fetch-linux-playerctl.sh
#
# Para otras arquitecturas (p. ej. aarch64), deja el binario en:
#   src/main/resources/assets/craftify/native/linux/aarch64/playerctl
set -euo pipefail

VERSION="2.4.1"
URL="https://github.com/altdesktop/playerctl/releases/download/v${VERSION}/playerctl-${VERSION}_amd64.deb"
DEST="src/main/resources/assets/craftify/native/linux/x86_64"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Descargando playerctl ${VERSION} (release oficial de GitHub)..."
curl -fsSL -o "$TMP/playerctl.deb" "$URL"

echo "Extrayendo el binario del .deb..."
if command -v 7z >/dev/null 2>&1; then
    (cd "$TMP" && 7z x -y playerctl.deb >/dev/null && tar -xf data.tar.gz)
elif command -v ar >/dev/null 2>&1; then
    (cd "$TMP" && ar x playerctl.deb && tar -xf data.tar.*)
else
    echo "ERROR: se necesita 7z o ar para extraer el .deb." >&2
    exit 1
fi

if [ ! -f "$TMP/usr/bin/playerctl" ]; then
    echo "ERROR: no se encontró usr/bin/playerctl dentro del paquete." >&2
    exit 1
fi

mkdir -p "$DEST"
cp "$TMP/usr/bin/playerctl" "$DEST/playerctl"
chmod +x "$DEST/playerctl"

echo "OK: $DEST/playerctl ($(stat -c %s "$DEST/playerctl" 2>/dev/null || echo '?') bytes)"
