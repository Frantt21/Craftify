# Craftify

Mod de Minecraft (Fabric, solo cliente) que detecta el **título de la canción que está
sonando en Spotify** leyendo el título de la ventana del proceso de Spotify que corre en el
sistema operativo del jugador.

Ese título se envía al servidor por paquetes, donde un **plugin (en desarrollo)** será el
encargado de entender y convertir los paquetes (por ejemplo, para mostrarlo en Discord,
Twitch, etc.).

> ⚠️ **Estado actual:** el mod detecta el proceso de Spotify, lee el título y **envía el
> paquete `craftify:title` al servidor cada vez que el título cambia** (mientras el jugador
> está en un mundo). Ya existe un **esqueleto de plugin Paper** ([`CraftifyPlugin/`](CraftifyPlugin/))
> que recibe y guarda el estado por jugador; falta la **lógica de conversión** (exponerlo en
> chat, Discord, Twitch, etc.). El protocolo on-wire está definido en [`PROTOCOL.md`](PROTOCOL.md).

## Arquitectura

```
┌─────────────────────────────┐   paquetes    ┌─────────────────────────────┐
│  Cliente: mod Craftify      │ ────────────► │  Servidor: plugin (futuro)  │
│                             │               │                             │
│  Lee el título de la        │               │  Entiende y convierte los   │
│  ventana de Spotify (el SO  │               │  paquetes (Discord, Twitch, │
│  del jugador)               │               │  lo que sea)                │
└─────────────────────────────┘               └─────────────────────────────┘
```

- El **mod** corre del lado del cliente y es el único componente con acceso al sistema
  operativo del jugador.
- El **plugin** correrá en el servidor y consumirá los paquetes que envíe el mod.
- El mod **no interpreta la música**: solo captura el título de la ventana de Spotify y lo
  envía. Toda la lógica de conversión vive en el plugin.

## Detección de Spotify

El mod detecta el proceso de Spotify que está corriendo en el SO del jugador y lee el título
de su ventana, que cambia con cada canción (formato típico: `Canción - Artista`). En Windows
el título se sigue leyendo aunque Spotify esté **minimizado o en la bandeja** (la ventana
oculta conserva el texto; solo se descartan las ventanas auxiliares IME/GDI+).

### Ejecutables soportados (uno por SO)

| SO      | Ejecutable    | Detección del proceso        | Lectura del título |
|---------|---------------|------------------------------|--------------------|
| Windows | `Spotify.exe` | JNA/Win32 (Toolhelp32) con fallback `tasklist` | JNA/Win32 (`EnumWindows` + `GetWindowText`) con fallback PowerShell |
| macOS   | `Spotify`     | JNA/CoreGraphics (dueño de la ventana) con fallback `pgrep` | JNA/CoreGraphics (`CGWindowListCopyWindowInfo`) con fallback `osascript` |
| Linux   | `spotify`     | MPRIS/`playerctl` (implica corriendo); fallback `pgrep -x spotify` | MPRIS/`playerctl` (metadata) con fallback `xdotool` (ventana) |

### Requisitos por sistema operativo

- **Windows**: usa una sonda nativa (JNA/Win32) sin permisos adicionales; PowerShell solo se
  usa como fallback si JNA no está disponible.
- **macOS**: la sonda nativa (CoreGraphics) detecta el proceso sin permisos, pero leer el
  **título** de la ventana de otra app requiere permiso de **Grabación de Pantalla**
  (Ajustes del Sistema → Privacidad y seguridad → Grabación de pantalla). Si no se otorga,
  el mod cae a `osascript` (que a su vez requiere **Accesibilidad**). Con cualquiera de los
  dos permisos el título se lee; sin ambos, el estado queda como `no_track`.
- **Linux**: usa **MPRIS** vía `playerctl` (p. ej. `sudo apt install playerctl`,
  `sudo pacman -S playerctl`) como sonda principal: una sola invocación da el proceso y el
  título, funciona con la ventana minimizada y no depende de X11/Wayland. Si `playerctl` no
  está instalado o no responde, cae a `pgrep` + `xdotool` (p. ej. `sudo apt install xdotool`).
  Spotify debe correr en la misma sesión gráfica del usuario.

## Comandos

Dentro del juego (mundo singleplayer o conectado a un servidor):

```
/craftify spotify
/craftify send on|off|toggle
```

### `/craftify spotify` — estado y verificación

Muestra:

1. El sistema operativo detectado.
2. Si el proceso de Spotify está corriendo (y con qué ejecutable).
3. El estado actual (reproduciendo / sin canción activa / cerrado) y el título.
4. Si el envío de paquetes está activo, pausado o inactivo.

Salida de ejemplo:

```
[Craftify] Sistema operativo: WINDOWS
[Craftify] Proceso de Spotify (Spotify.exe): corriendo
[Craftify] Estado: reproduciendo (playing)
[Craftify] Título actual: Mi Canción Favorita - Mi Artista
[Craftify] Envío de paquetes: activo (detección en tiempo real de cambios de canción)
```

Si el proceso no está corriendo o el título no se pudo leer, el comando lo indica con un
mensaje en rojo.

### `/craftify send` — pausar/reanudar el envío de paquetes

- `/craftify send off` pausa el envío (el seguimiento sigue leyendo, pero no envía paquetes).
- `/craftify send on` lo reanuda y **envía el estado actual de Spotify en la próxima lectura**.
- `/craftify send toggle` alterna entre ambos.

El estado de pausa persiste entre mundos (solo se pierde al cerrar el juego). El comando
`/craftify spotify` refleja si el envío está pausado.

## Envío de paquetes

Mientras el jugador está en un mundo (singleplayer o conectado a un servidor), un hilo aparte
lee el estado de Spotify con un **polling adaptativo** y envía el paquete `craftify:title` al
servidor **solo cuando el estado cambia** (no en bucle):

- **cada ~0,5 segundos** mientras Spotify está corriendo → cambios de canción detectados
  casi en tiempo real;
- **cada ~5 segundos** (backoff) cuando Spotify está cerrado, para no gastar recursos en
  vano;
- al entrar al mundo se envía el estado inicial;
- cada cambio de canción envía `state: "playing"` con el nuevo título;
- si Spotify está corriendo pero no hay canción legible se envía `state: "no_track"`;
- cuando Spotify se cierra se envía `state: "closed"`.

El envío se puede **pausar y reanudar** con [`/craftify send`](#craftify-send--pausarreanudar-el-envío-de-paquetes).

El seguimiento arranca con `ClientPlayConnectionEvents.JOIN` y se detiene con
`DISCONNECT`. La lectura del SO se hace en un hilo `daemon` para no bloquear el render.

### Costo de la detección

El costo de cada consulta fue medido (Windows 11, Java 26):

| Sonda | Costo por consulta |
|-------|--------------------|
| `tasklist` (proceso) | ~110 ms |
| PowerShell (`MainWindowTitle`) | ~1.100 ms |
| Poll anterior en Windows (tasklist + PowerShell) | ~1.060 ms |
| **Poll actual en Windows (JNA/Win32)** | **~10–60 ms** (la primera carga del nativo ~500 ms) |
| `osascript` (título, macOS) | ~200–500 ms |
| **Poll actual en macOS (JNA/CoreGraphics)** | **~1–10 ms** (solo el título requiere permiso de Grabación de Pantalla) |
| Poll en Linux (`playerctl` o `pgrep` + `xdotool`) | ~10–80 ms (procesos ligeros del SO) |

El cuello de botella era el **arranque de PowerShell** (~1,1 s por invocación) en Windows y
el **arranque de AppleScript** (~200–500 ms) en macOS. Por eso el mod usa **sondas nativas
con JNA** en ambos: `Toolhelp32` + `EnumWindows` en Windows, `CGWindowListCopyWindowInfo` en
macOS — del orden de milisegundos. Si JNA no está disponible, vuelve al fallback CLI (lento
pero funcional). En Linux los procesos del SO ya son ligeros, así que la sonda usa `playerctl`
(MPRIS) como método principal y `xdotool` como respaldo.

Con el intervalo de 500 ms, cada ciclo efectivo dura ~0,5–0,6 s en cualquier plataforma, con
un costo de CPU despreciable.

## Protocolo de paquetes

> 📄 **El detalle completo está en [`PROTOCOL.md`](PROTOCOL.md)**: cómo el mod envía los
> paquetes, el formato on-wire exacto y cómo el plugin debe recibirlos y usarlos (Fabric y
> Paper).

| Packet ID        | Dirección | Contenido |
|------------------|-----------|-----------|
| `craftify:title` | C → S     | Estado y título actual de Spotify (cambia con cada canción) |

### Transporte

- Fase: `play`
- Paquete de Minecraft: `minecraft:custom_payload`
- Canal: `craftify:title`

### Payload on-wire

Los bytes del payload son un **único String UTF-8 con un JSON** (así el plugin puede
decodificarlo sin necesidad del mod):

```
[VarInt longitud] [UTF-8: {"state":"...","track":"...","timestamp":...}]
```

1. Un `VarInt` con la longitud en bytes del String (p. ej. `0x37` = 55).
2. Los bytes UTF-8 del JSON:

```json
{
  "state": "playing",
  "track": "Mi Canción Favorita - Mi Artista",
  "timestamp": 1760000000000
}
```

- `state`: el estado del Spotify del jugador:
  - `"playing"` — Spotify corriendo y hay un título legible (canción activa). `track` trae
    el título.
  - `"no_track"` — Spotify corriendo pero sin canción activa legible (ventana oculta,
    permisos de Accesibilidad sin otorgar en macOS, etc.). `track` va vacío.
  - `"closed"` — Spotify no está corriendo. `track` va vacío.
- `track`: el título leído de Spotify; `""` cuando no hay canción activa.
- `timestamp`: epoch millis del momento de la captura, para que el plugin pueda ordenar o
  descartar mensajes viejos.

El plugin será quien decida qué hacer con el dato (validarlo, formatearlo, exponerlo, etc.).

## Roadmap

1. ✅ Detección del proceso de Spotify y lectura del título (`/craftify spotify`).
2. ✅ Envío de paquetes `craftify:title` al servidor cuando el título cambie.
3. ✅ (esqueleto) Plugin Paper ([`CraftifyPlugin/`](CraftifyPlugin/)) que recibe el canal
   `craftify:title` y guarda el estado por jugador.
4. ⏳ Lógica de conversión en el plugin (chat, scoreboard, Discord, Twitch, etc.).

## Compilación

```bash
cd Craftify
./gradlew build
```

El JAR queda en `Craftify/build/libs/` y se instala como cualquier mod de Fabric en la
carpeta `mods` del cliente.

## Configuración del proyecto

- group: mod
- template: fabric
- language: java
- mc version: 26.2
- loom version: 1.17-SNAPSHOT
- loader version: 0.19.3
- fabric api version: 0.157.0+26.2
- environment: client

- mod id: craftify
- mod name: craftify
- mod version: 1.0.0
- main class: org.foranly.craftify.Craftify
- entrypoint de cliente: org.foranly.craftify.client.CraftifyClient

- group id: org.foranly
- artifact id: Craftify
