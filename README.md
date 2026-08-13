# Craftify

Sistema de dos componentes que muestra en el servidor de Minecraft **qué canción está
sonando en Spotify** de cada jugador:

| Componente | Carpeta | Qué hace |
|------------|---------|----------|
| **CraftifyMod** | [`CraftifyMod/`](CraftifyMod/) | Mod de Minecraft (Fabric, solo cliente): detecta Spotify en el SO del jugador, lee el título de la canción desde la ventana del proceso y lo envía al servidor. |
| **CraftifyPlugin** | [`CraftifyPlugin/`](CraftifyPlugin/) | Plugin Paper del servidor: recibe el título, lo guarda por jugador y lo expone (holograma, comando). |

El **contrato de comunicación** entre ambos — el formato exacto de los paquetes y cómo debe
recibirlos el plugin — está definido en [`PROTOCOL.md`](PROTOCOL.md).

## Cómo funciona

```
┌──────────────────────────────┐   craftify:title (custom_payload)   ┌──────────────────────────────┐
│  Cliente: CraftifyMod        │ ─────────────────────────────────►  │  Servidor: CraftifyPlugin     │
│                              │                                     │                              │
│  Detecta Spotify en el SO    │   {"state":"playing",               │  Guarda el estado por        │
│  Lee el título de la ventana │    "track":"Canción - Artista",     │  jugador (UUID)              │
│  Envía SOLO cuando cambia    │    "timestamp": ...}                │  Nametag + /nowplaying       │
│  Ve su nombre en 3ª persona  │                                     │  (holograma opcional)        │
└──────────────────────────────┘                                     └──────────────────────────────┘
```

1. **El mod** corre en el cliente y es el único componente con acceso al sistema operativo
   del jugador: detecta el proceso de Spotify (Windows/macOS/Linux) y lee el título de su
   ventana, que cambia con cada canción (formato típico: `Canción - Artista`).
2. **Solo cuando el estado cambia** (canción nueva, Spotify cerrado, etc.) envía un paquete
   `minecraft:custom_payload` por el canal `craftify:title` con un JSON de tres campos:
   `state`, `track` y `timestamp`.
3. **El plugin** corre en el servidor, decodifica el payload y guarda el último estado por
   jugador. Hoy ya lo expone en el **nombre flotante** del jugador (nametag: nombre +
   canción, sin lag) y con el comando `/nowplaying`. Un **holograma** (`TextDisplay`)
   sigue disponible como modo opcional.
4. **El mod** además permite ver tu propio nombre en **tercera persona (F5)** — vanilla lo
   oculta — así el jugador ve su nametag con la canción.

El mod **no interpreta la música** y el plugin **no toca el sistema operativo**: cada
componente hace una sola cosa, y se comunican únicamente por el protocolo definido en
[`PROTOCOL.md`](PROTOCOL.md).

## Documentación por componente

- **📄 [`CraftifyMod/README.md`](CraftifyMod/README.md)** — el mod: detección por sistema
  operativo, requisitos, comandos (`/craftify spotify`, `/craftify send`), polling y costo
  de la detección, compilación.
- **📄 [`CraftifyPlugin/README.md`](CraftifyPlugin/README.md)** — el plugin: qué hace hoy
  (recepción del canal, estado por jugador, holograma, `/nowplaying`), configuración y
  compilación.
- **📄 [`PROTOCOL.md`](PROTOCOL.md)** — el contrato entre ambos: formato exacto de los
  bytes on-wire, los 3 estados posibles y cómo debe recibirlos/decodificarlos el plugin
  (con ejemplos para Paper y Fabric).

## Estado actual

- ✅ El mod detecta Spotify, envía `craftify:title` en cada cambio de estado y muestra el
  propio nombre en tercera persona (F5).
- ✅ El plugin Paper recibe el canal, guarda el estado por jugador y lo muestra en el
  nametag del jugador (holograma opcional).
- ⏳ Lógica de conversión en el plugin (chat, scoreboard, Discord, Twitch, etc.).

## Compilación rápida

```bash
# Mod (cliente)
cd CraftifyMod
./gradlew build        # → CraftifyMod/build/libs/CraftifyMod-1.0.0.jar

# Plugin (servidor)
cd CraftifyPlugin
./gradlew build        # → CraftifyPlugin/build/libs/CraftifyPlugin-1.0.0.jar
```

El JAR del mod se instala en la carpeta `mods` del cliente; el del plugin, en `plugins/`
del servidor Paper. Ambos proyectos usan el mismo wrapper de Gradle y el JDK 26 local
(compilando apuntando a Java 25).

## Roadmap

1. ✅ Detección del proceso de Spotify y lectura del título (`/craftify spotify`).
2. ✅ Envío de paquetes `craftify:title` al servidor cuando el título cambie.
3. ✅ (esqueleto) Plugin Paper que recibe el canal y guarda el estado por jugador + holograma.
4. ⏳ Lógica de conversión en el plugin (chat, scoreboard, Discord, Twitch, etc.).
