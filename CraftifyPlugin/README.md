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
prints a **colored banner** directly to the console (ANSI escape codes via
`Bukkit.getConsoleSender()`): channel, version and the activation state of each display
mode. The in-game colors come from the MiniMessage format below.

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

lyrics-display:
  mode: below-name  # how the shared lyric line is shown (see below)
  number: line      # number the BELOW_NAME slot requires: "line" (the actual lyric
                    # line number the mod sends: 1, 2, 3...), "random", or a fixed 0/1

lyrics-hologram:    # legacy mode, only used when lyrics-display.mode: hologram
  enabled: true
  height: 1.85      # height above the player, in blocks
  scale: 0.6        # text size
```

The name tag renders as `prefix + name + suffix` on a single line (vanilla does not
render multi-line player name tags). With a `state` other than `playing` (or no title)
the prefix/suffix are cleared and the nametag goes back to normal.

### Lyrics display (shared lines)

The current lyric line of players who enabled **"Share lyrics with others"** in the mod
(F10 menu) is shown via `craftify:lyricsline` — the mod sends it only on line changes
(empty line clears it, a pause keeps the last line frozen), so it is opt-in per player
and never rendered for players who don't share. Two modes, selected with
`lyrics-display.mode`:

- **`below-name` (default)** — the vanilla scoreboard `BELOW_NAME` slot: the line is the
  objective's display name and vanilla requires a number after it, set with
  `lyrics-display.number` (`0`, `1`, or `random`). It is part of the player's name tag
  (no separate entity), so it follows the player with **zero lag**. Note: the `BELOW_NAME`
  text is the same for every observer, so when several players share at once only the
  latest line is shown (under each sharing player's name).
- **`hologram`** — a `TextDisplay` entity that follows the player (the previous mode;
  works, but being a separate entity that teleports every tick, a small delay can be
  noticed when moving).

### Supported colors and formats (MiniMessage)

The `prefix` and `suffix` values use Adventure MiniMessage. Named colors:

`<black>` `<dark_blue>` `<dark_green>` `<dark_aqua>` `<dark_red>` `<dark_purple>` `<gold>` `<gray>` `<dark_gray>` `<blue>` `<green>` `<aqua>` `<red>` `<light_purple>` `<yellow>` `<white>`

Also supported:

- `<color:#RRGGBB>` — any hex color, e.g. `<color:#ffaa00>`.
- `<bold>`, `<italic>`, `<underlined>`, `<strikethrough>`, `<obfuscated>`, `<reset>` —
  formatting.
- `<newline>` / `<br>` — line break. Note: vanilla renders player name tags on a single
  line, so a newline inside the nametag will not create a visible second line.

Formatting tags must be closed: `<green>text</green>`, `<bold>text</bold>`.

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

The JAR ends up in `CraftifyPlugin/build/libs/CraftifyPlugin-1.0.1.jar` and installs in
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
| `LyricsListener` | `PluginMessageListener`: decodes `craftify:lyricsline` (VarInt + UTF-8 + JSON, line + number) |
| `display/LyricsDisplay` | Interface: how the shared lyric line is shown (below-name or hologram) |
| `display/LyricsBelowNameManager` | `BELOW_NAME` scoreboard objective with the shared line + number (`line`/`random`/fixed) |
| `hologram/LyricsHologramManager` | `TextDisplay` hologram with the shared lyric line (legacy mode) |

## Quick test

1. Install the mod on the client and the plugin on the server.
2. The player joins the server with Spotify open and playing.
3. The player runs `/nowplaying` to see their state, and the server logs changes at
   `logger.fine`.

## Next step

The skeleton **receives and stores** the state. The conversion logic (chat, scoreboard,
Discord, Twitch, etc.) lives on top of `SpotifyStateManager`.
