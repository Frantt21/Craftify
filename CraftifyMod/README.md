# CraftifyMod

Mod de Minecraft (Fabric, **solo cliente**) que detecta el **título de la canción que está
sonando en Spotify** leyendo el título de la ventana del proceso de Spotify que corre en el
sistema operativo del jugador.

Ese título se envía al servidor por el canal `craftify:title`, donde el
[**CraftifyPlugin**](../CraftifyPlugin/README.md) (Paper) lo recibe y lo expone. El
contrato de comunicación está en [`../PROTOCOL.md`](../PROTOCOL.md).

> ⚠️ **Este es el componente de cliente.** No hace nada visible sin un servidor que reciba
> el canal, pero incluye el comando `/craftify spotify` para verificar la detección en
> singleplayer.

## Detección de Spotify

El mod detecta el proceso de Spotify que está corriendo en el SO del jugador y lee el
título de su ventana, que cambia con cada canción (formato típico: `Canción - Artista`).
En Windows el título se sigue leyendo aunque Spotify esté **minimizado o en la bandeja**
(la ventana oculta conserva el texto; solo se descartan las ventanas auxiliares IME/GDI+).

### Ejecutables soportados (uno por SO)

| SO      | Ejecutable    | Detección del proceso        | Lectura del título |
|---------|---------------|------------------------------|--------------------|
| Windows | `Spotify.exe` | JNA/Win32 (Toolhelp32) con fallback `tasklist` | JNA/Win32 (`EnumWindows` + `GetWindowText`) con fallback PowerShell |
| macOS   | `Spotify`     | JNA/CoreGraphics (dueño de la ventana) con fallback `pgrep` | JNA/CoreGraphics → AppleScript de Spotify (Automatización) → `osascript` |
| Linux   | `spotify`     | MPRIS/`playerctl` (implica corriendo); fallback `pgrep -x spotify` | MPRIS/`playerctl` (metadata) con fallback `xdotool` (ventana) |

### Requisitos por sistema operativo

- **Windows**: usa una sonda nativa (JNA/Win32) sin permisos adicionales; PowerShell solo se
  usa como fallback si JNA no está disponible.
- **macOS**: detectar el proceso no requiere permisos. Para el **título**, el camino más
  fácil es aceptar el aviso único **"controlar Spotify"** (Automatización) cuando aparezca
  al entrar al juego — un clic, sin salir a Ajustes. Alternativas si se prefiere: permiso de
  **Grabación de Pantalla** (Ajustes → Privacidad → Grabación de pantalla) o **Accesibilidad**
  (último recurso). Con cualquiera de los tres el título se lee; sin ninguno, el estado queda
  como `no_track`.
- **Linux**: usa **MPRIS** vía `playerctl` como sonda principal: una sola invocación da el
  proceso y el título, funciona con la ventana minimizada, **sin permisos** y sin depender de
  X11/Wayland. **No hace falta instalar nada con sudo**: el mod incluye el binario oficial de
  playerctl (MIT, solo depende de `libglib2.0-0`) dentro del JAR y lo extrae al directorio
  temporal del usuario la primera vez. Solo si el binario no pudiera ejecutarse, cae a
  `pgrep` + `xdotool` (p. ej. `sudo apt install xdotool`) o al `playerctl` del sistema. La
  API local de Spotify (puerto 4380) ya no existe en los clientes modernos. Spotify debe
  correr en la misma sesión gráfica del usuario.

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
mensaje en rojo, y según la plataforma sugiere qué hacer (aceptar el aviso de
Automatización en macOS, instalar `playerctl`/`xdotool` en Linux, etc.).

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

## Compilación

```bash
cd CraftifyMod
./gradlew build
```

El JAR queda en `CraftifyMod/build/libs/CraftifyMod-1.0.0.jar` y se instala como cualquier
mod de Fabric en la carpeta `mods` del cliente.

El binario de playerctl ya está incluido en las resources; para actualizarlo o re-fetcharlo:

```bash
cd CraftifyMod
bash scripts/fetch-linux-playerctl.sh
```

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
- artifact id: CraftifyMod
