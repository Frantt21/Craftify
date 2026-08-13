# CraftifyPlugin

**Paper** server plugin that receives the `craftify:title` channel sent by the
**CraftifyMod** mod (client, in [`../CraftifyMod/`](../CraftifyMod/)) and stores each
player's Spotify state.

> **Full contract:** [`../PROTOCOL.md`](../PROTOCOL.md) — on-wire format, decoding and
> usage recommendations. This plugin is the server-side implementation.
> **Overall system documentation:** [`../README.md`](../README.md).

## What it does (skeleton)

- Listens to the `craftify:title` channel (`play` phase, `minecraft:custom_payload`).
- Decodes the payload manually: `[VarInt length][UTF-8 bytes of the JSON]` (PROTOCOL.md §2.1).
- Stores the latest Spotify state per player (PROTOCOL.md §4.1):
  - `playing` → `track` = "Song - Artist"
  - `no_track` → Spotify open without a readable song
  - `closed` → Spotify closed
- Clears the state when the player disconnects.
- `/nowplaying` command to verify your own state.
- `/craftifyplugin reload` command (permission `craftifyplugin.reload`, default: ops only)
  to reload `config.yml` and re-apply the current states without restarting the server.
- **Nametag** (default): shows the title next to the player's **floating name** using
  scoreboard teams (prefix/suffix). Being part of the entity, it follows the player
  **without lag** (unlike the hologram, which teleports every tick). Note: vanilla
  renders player name tags on a single line and ignores the custom name for players
  (`getDisplayName()` = team prefix + name + suffix), so the song is appended as a team
  suffix. The owner of the name sees it in third person (F5) if the client mod includes
  the "see your own name" mixin.
- **Hologram** (optional): a `TextDisplay` entity that follows the player, no external
  plugin dependencies. A small delay can be noticed when moving. Configurable in
  `config.yml`.

## Configuration (`config.yml`)

The plugin **generates `config.yml` automatically** in `plugins/CraftifyPlugin/` on the
first start (and overwrites it if an old pre-nametag version is detected). On startup it
prints a banner with the channel, the version and the activation state of each display
mode (plain text on purpose: legacy `§` codes render literally on consoles that don't
convert them to ANSI; the in-game colors come from the MiniMessage format).

```yaml
nametag:
  enabled: true   # enables/disables the nametag (default mode)
  # Text shown BEFORE the player name (MiniMessage). Placeholders:
  #   {name}  → player name
  #   {track} → "Song - Artist"
  prefix: ""
  # Text shown AFTER the player name (MiniMessage).
  suffix: " <green>♪ </green><white>{track}</white>"

hologram:
  enabled: false  # optional hologram (the previous mode; still available)
  icon: "♪ "       # music icon glyph (default font; try "♫ " if it doesn't render)
  height: 2.15    # height above the player, in blocks
  scale: 0.6      # text size
```

The name tag renders as `prefix + name + suffix` on a single line (vanilla does not
render multi-line player name tags). With a `state` other than `playing` (or no title)
the prefix/suffix are cleared and the nametag goes back to normal.

## Commands and permissions

| Command | Permission | Default | Description |
|---------|------------|---------|-------------|
| `/nowplaying` | `craftifyplugin.nowplaying` | everyone | Shows your own Spotify state |
| `/craftifyplugin reload` | `craftifyplugin.reload` | ops only | Reloads `config.yml` and re-applies the states of all online players |

The server console can always run both commands.

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
| `command/CraftifyCommand` | `/craftifyplugin reload`: reloads config + re-applies states (`craftifyplugin.reload`) |
| `nametag/NametagManager` | Shows the title in the player's floating name via scoreboard teams (prefix/suffix, no lag) |
| `hologram/HologramManager` | Optional `TextDisplay` hologram (icon + title) |

## Quick test

1. Install the mod on the client and the plugin on the server.
2. The player joins the server with Spotify open and playing.
3. The player runs `/nowplaying` to see their state, and the server logs changes at
   `logger.fine`.

## Next step

The skeleton **receives and stores** the state. The conversion logic (chat, scoreboard,
Discord, Twitch, etc.) lives on top of `SpotifyStateManager`.
