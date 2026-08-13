# CraftifyPlugin

Plugin **Paper** del servidor que recibe el canal `craftify:title` enviado por el mod
**Craftify** (cliente, en `../Craftify`) y guarda el estado de Spotify de cada jugador.

> 📄 **Contrato completo:** [`../PROTOCOL.md`](../PROTOCOL.md) — formato on-wire, decodificación
> y recomendaciones de uso. Este plugin es la implementación del lado servidor.

## Qué hace (esqueleto)

- Escucha el canal `craftify:title` (fase `play`, `minecraft:custom_payload`).
- Decodifica el payload a mano: `[VarInt longitud][bytes UTF-8 del JSON]` (PROTOCOL.md §2.1).
- Guarda el último estado de Spotify por jugador (PROTOCOL.md §4.1):
  - `playing` → `track` = "Canción - Artista"
  - `no_track` → Spotify abierto sin canción legible
  - `closed` → Spotify cerrado
- Limpia el estado al desconectar el jugador.
- Comando `/nowplaying` para verificar el estado propio.

## Requisitos

- Servidor **Paper** (o compatible) para Minecraft 26.2.
- Java 21+ (compilado apuntando a Java 25, igual que el mod).
- En el **cliente** de cada jugador: el mod Craftify instalado (sin el mod, el canal no existe).

## Compilación

```bash
cd CraftifyPlugin
./gradlew build
```

El JAR queda en `CraftifyPlugin/build/libs/CraftifyPlugin-1.0.0.jar` y se instala en la
carpeta `plugins` del servidor.

## Estructura

| Clase | Responsabilidad |
|-------|-----------------|
| `CraftifyPlugin` | Main: registra el canal, el listener y el comando |
| `SpotifyListener` | `PluginMessageListener`: decodifica `craftify:title` (VarInt + UTF-8 + JSON) |
| `PlayerSpotifyState` | Registro con `state`, `track`, `timestamp` + parseo del JSON |
| `SpotifyStateManager` | Último estado por UUID del jugador (en memoria) |
| `PlayerListener` | Limpia el estado al desconectar |
| `command/NowPlayingCommand` | `/nowplaying`: muestra el estado propio |

## Prueba rápida

1. Instala el mod en el cliente y el plugin en el servidor.
2. El jugador entra al servidor con Spotify abierto y reproduciendo.
3. En el servidor: `nowplaying <jugador>`-style aún no existe; el jugador ejecuta
   `/nowplaying` para ver su estado, y el servidor registra los cambios en `logger.fine`.

## Siguiente paso

El esqueleto **recibe y guarda** el estado. La lógica de conversión (exponerlo en chat,
scoreboard, Discord, Twitch, etc.) vive a partir de `SpotifyStateManager`.
