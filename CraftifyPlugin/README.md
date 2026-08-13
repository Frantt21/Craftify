# CraftifyPlugin

Plugin **Paper** del servidor que recibe el canal `craftify:title` enviado por el mod
**CraftifyMod** (cliente, en [`../CraftifyMod/`](../CraftifyMod/)) y guarda el estado de
Spotify de cada jugador.

> 📄 **Contrato completo:** [`../PROTOCOL.md`](../PROTOCOL.md) — formato on-wire, decodificación
> y recomendaciones de uso. Este plugin es la implementación del lado servidor.
> 📄 **Documentación general del sistema:** [`../README.md`](../README.md).

## Qué hace (esqueleto)

- Escucha el canal `craftify:title` (fase `play`, `minecraft:custom_payload`).
- Decodifica el payload a mano: `[VarInt longitud][bytes UTF-8 del JSON]` (PROTOCOL.md §2.1).
- Guarda el último estado de Spotify por jugador (PROTOCOL.md §4.1):
  - `playing` → `track` = "Canción - Artista"
  - `no_track` → Spotify abierto sin canción legible
  - `closed` → Spotify cerrado
- Limpia el estado al desconectar el jugador.
- Comando `/nowplaying` para verificar el estado propio.
- **Nametag** (por defecto): muestra el título en el **nombre flotante** del jugador
  (`customName`). Al ser parte de la entidad, sigue al jugador **sin lag** (a diferencia
  del holograma, que se teletransporta por tick). El jugador dueño del nombre lo ve en
  tercera persona (F5) si el mod del cliente incluye el mixin de "ver tu propio nombre".
- **Holograma** (opcional): entidad `TextDisplay` que sigue al jugador, sin dependencias
  externas. Puede notarse un pequeño retraso al moverse. Configurable en `config.yml`.

## Configuración (`config.yml`)

```yaml
nametag:
  enabled: true   # activa/desactiva el nametag (modo por defecto)
  # Formato (MiniMessage). Placeholders: {name} = jugador, {track} = "Canción - Artista".
  # <newline> crea una segunda línea.
  format: "<yellow>{name}</yellow><newline><green>♪ </green><white>{track}</white>"

hologram:
  enabled: false  # holograma opcional (era el modo anterior; sigue disponible)
  icon: "♪ "       # glifo del icono (fuente por defecto; probar "♫ " si no se ve)
  height: 2.15    # altura sobre el jugador en bloques
  scale: 0.6      # tamaño del texto
```

Con `state` distinto de `playing` (o sin título) el nametag se restaura al nombre normal
del jugador.

## Requisitos

- Servidor **Paper** (o compatible) para Minecraft 26.2.
- Java 21+ (compilado apuntando a Java 25, igual que el mod).
- En el **cliente** de cada jugador: el mod CraftifyMod instalado (sin el mod, el canal no existe).

## Compilación

```bash
cd CraftifyPlugin
./gradlew build
```

El JAR queda en `CraftifyPlugin/build/libs/CraftifyPlugin-1.0.0.jar` y se instala en la
carpeta `plugins` del servidor Paper.

## Estructura

| Clase | Responsabilidad |
|-------|-----------------|
| `CraftifyPlugin` | Main: registra el canal, el listener y el comando |
| `SpotifyListener` | `PluginMessageListener`: decodifica `craftify:title` (VarInt + UTF-8 + JSON) |
| `PlayerSpotifyState` | Registro con `state`, `track`, `timestamp` + parseo del JSON |
| `SpotifyStateManager` | Último estado por UUID del jugador (en memoria) |
| `PlayerListener` | Limpia el estado y la visualización al desconectar |
| `command/NowPlayingCommand` | `/nowplaying`: muestra el estado propio |
| `nametag/NametagManager` | Muestra el título en el nombre flotante del jugador (sin lag) |
| `hologram/HologramManager` | Holograma `TextDisplay` opcional (icono + título) |

## Prueba rápida

1. Instala el mod en el cliente y el plugin en el servidor.
2. El jugador entra al servidor con Spotify abierto y reproduciendo.
3. En el servidor: `nowplaying <jugador>`-style aún no existe; el jugador ejecuta
   `/nowplaying` para ver su estado, y el servidor registra los cambios en `logger.fine`.

## Siguiente paso

El esqueleto **recibe y guarda** el estado. La lógica de conversión (exponerlo en chat,
scoreboard, Discord, Twitch, etc.) vive a partir de `SpotifyStateManager`.
