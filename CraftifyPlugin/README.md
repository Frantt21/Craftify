# CraftifyPlugin

**Paper** server plugin that receives the `craftify:title` channel sent by the
**CraftifyMod** mod (client, in [`../CraftifyMod/`](../CraftifyMod/)) and stores each
player's Spotify state.

> 📄 **Full contract:** [`../PROTOCOL.md`](../PROTOCOL.md) — on-wire format, decoding and
> usage recommendations. This plugin is the server-side implementation.
> 📄 **Overall system documentation:** [`../README.md`](../README.md).

## What it does (skeleton)

- Listens to the `craftify:title` channel (`play` phase, `minecraft:custom_payload`).
- Decodes the payload manually: `[VarInt length][UTF-8 bytes of the JSON]` (PROTOCOL.md §2.1).
- Stores the latest Spotify state per player (PROTOCOL.md §4.1):
  - `playing` → `track` = "Song - Artist"
  - `no_track` → Spotify open without a readable song
  - `closed` → Spotify closed
- Clears the state when the player disconnects.
- `/nowplaying` command to verify your own state.
- **Nametag** (default): shows the title in the player's **floating name**
  (`customName`). Being part of the entity, it follows the player **without lag**
  (unlike the hologram, which teleports every tick). The owner of the name sees it in
  third person (F5) if the client mod includes the "see your own name" mixin.
- **Hologram** (optional): a `TextDisplay` entity that follows the player, no external
  plugin dependencies. A small delay can be noticed when moving. Configurable in
  `config.yml`.

## Configuration (`config.yml`)

```yaml
nametag:
  enabled: true   # enables/disables the nametag (default mode)
  # Format (MiniMessage). Placeholders: {name} = player, {track} = "Song - Artist".
  # <newline> creates a second line.
  format: "<yellow>{name}</yellow><newline><green>♪ </green><white>{track}</white>"

hologram:
  enabled: false  # optional hologram (the previous mode; still available)
  icon: "♪ "       # music icon glyph (default font; try "♫ " if it doesn't render)
  height: 2.15    # height above the player, in blocks
  scale: 0.6      # text size
```

With a `state` other than `playing` (or no title), the nametag is restored to the
player's normal name.

## Requirements

- **Paper** (or compatible) server for Minecraft 26.2.
- Java 21+ (built against Java 25, same as the mod).
- On each player's **client**: the CraftifyMod mod installed (without the mod, the
  channel does not exist).

## Build

```bash
cd CraftifyPlugin
./gradlew build
```

The JAR ends up in `CraftifyPlugin/build/libs/CraftifyPlugin-1.0.0.jar` and installs in
the server's `plugins` folder (Paper).

## Structure

| Class | Responsibility |
|-------|-----------------|
| `CraftifyPlugin` | Main: registers the channel, the listener and the command |
| `SpotifyListener` | `PluginMessageListener`: decodes `craftify:title` (VarInt + UTF-8 + JSON) |
| `PlayerSpotifyState` | Record with `state`, `track`, `timestamp` + JSON parsing |
| `SpotifyStateManager` | Latest state per player UUID (in memory) |
| `PlayerListener` | Clears the state and the display on disconnect |
| `command/NowPlayingCommand` | `/nowplaying`: shows your own state |
| `nametag/NametagManager` | Shows the title in the player's floating name (no lag) |
| `hologram/HologramManager` | Optional `TextDisplay` hologram (icon + title) |

## Quick test

1. Install the mod on the client and the plugin on the server.
2. The player joins the server with Spotify open and playing.
3. The player runs `/nowplaying` to see their state, and the server logs changes at
   `logger.fine`.

## Next step

The skeleton **receives and stores** the state. The conversion logic (chat, scoreboard,
Discord, Twitch, etc.) lives on top of `SpotifyStateManager`.
