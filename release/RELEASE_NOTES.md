# Craftify v1.0.1

Minecraft mod + server plugin that shows the song the player is listening to on Spotify in-game, without any Spotify API keys.

## What's included

- **CraftifyMod 1.0.1** — detects Spotify natively on Windows (JNA), macOS (AppleScript), and Linux (MPRIS/playerctl, with xdotool fallback), reading both the **song title** and the **pause state** (`playing` / `paused` / `no_track` / `closed`), and sends a `craftify:title` plugin message to the server whenever the state changes (polling every 500 ms with native probes, near-zero CPU cost). Includes a mixin so players can see their own nametag in third-person (F5).
- **CraftifyPlugin 1.0.1** — receives the channel, keeps per-player Spotify state (including `paused`), and renders the track on the player nametag via scoreboard teams (`prefix + name + suffix`, single line, no lag). Optional hologram mode, `/nowplaying`, `/craftifyplugin reload` with permissions, auto-generated `config.yml` (with automatic migration from older configs), and an ANSI-colored startup banner.

## Pause detection

- **Windows:** the window title reverts to the account tier (`"Spotify Free"`/`"Spotify Premium"`) while paused — no extra cost.
- **macOS:** Spotify's `player state` via AppleScript (the window title shows the account tier, never the song).
- **Linux:** MPRIS `PlaybackStatus` via `playerctl` (same single invocation as the metadata).

## Files

- `CraftifyMod-1.0.1.jar` — client mod (Fabric; place in `mods/`, requires Fabric API).
- `CraftifyPlugin-1.0.1.jar` — server plugin (Paper 26.2; place in `plugins/`).

## Compatibility

- Minecraft 26.2 / Fabric + Fabric API for the mod.
- Paper 26.2 for the plugin.
- Windows 10/11, macOS, Linux (xdotool or playerctl).

## Documentation

- `README.md` — project overview and how it works.
- `PROTOCOL.md` — full communication contract between mod and plugin.
- `CraftifyMod/README.md` and `CraftifyPlugin/README.md` — per-component docs.
