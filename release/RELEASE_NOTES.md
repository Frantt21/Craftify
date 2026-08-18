# Craftify v1.0.1

Minecraft mod + server plugin that shows the song the player is listening to on Spotify in-game, without any Spotify API keys.

## What's included

- **CraftifyMod 1.0.1** — detects Spotify natively on Windows (JNA), macOS (MediaRemote via the bundled `nowplaying-cli`, no permission needed, with AppleScript fallback), and Linux (MPRIS/playerctl, with xdotool fallback), reading both the **song title** and the **pause state** (`playing` / `paused` / `no_track` / `closed`), and sends a `craftify:title` plugin message to the server whenever the state changes (polling every 500 ms with native probes, near-zero CPU cost). Includes a mixin so players can see their own nametag in third-person (F5), a **synchronized lyrics overlay** (LRCLib, no key) that advances with the song and freezes on pause, and a **general F10 menu** (real MC screen with vanilla icons): Spotify status check, packet sending toggle, lyrics options submenu (overlay, share, 3x3 position picker — 9 anchors flush to the screen edges) and a lyrics search submenu (LRCLib search box with clickable candidates). Settings are persisted in `config/craftify.json`.

## Lyrics sync improvements (pause/resume)

- **Refined transition detection:** when a pause, resume or song change is detected, the tracker runs a short **confirmation burst** (~100 ms polls) and anchors the transition to the **midpoint** of the window where it happened (last playing ↔ first paused, and last paused ↔ first playing), instead of the detection poll. This removes the systematic lag of the old estimate (up to ~1 s) — after resuming, the line continues within ~0.1–0.25 s of where the song really stopped.
- **Clean estimate on song change:** a new track resets the whole song clock and clears any leftover pause estimate, so a previous pause can never offset the new song's lyrics.
- **CraftifyPlugin 1.0.1** — receives the channel, keeps per-player Spotify state (including `paused`), and renders the track on the player nametag via scoreboard teams (`prefix + name + suffix`, single line, no lag). The shared lyric line is shown by default in the vanilla **`BELOW_NAME` scoreboard slot** (zero lag). The number next to the line is now the **actual lyric line number** the mod sends (`lyrics-display.number: line` — 1, 2, 3...), with `random` or a fixed `0`/`1` still available, and the `TextDisplay` hologram as a legacy mode (`lyrics-display.mode: hologram`). Old configs with the fixed `0` are migrated to `line` automatically. `/nowplaying`, `/craftifyplugin reload` with permissions, auto-generated `config.yml`, and an ANSI-colored startup banner.

## Lyrics overlay appearance (client)

- **Size, opacity and color** for the lyrics overlay, from the F10 menu (Lyrics options -> Appearance): a **Size slider** (50%-300% text scale), an **Opacity slider** (10%-100%) and a **text color** picker (preset palette + custom `#RRGGBB` hex). All applied live and persisted in `config/craftify.json`.
- The **shared lyric line now carries the line number** (`craftify:lyricsline` payload gains `"number"`), so the server's `BELOW_NAME` slot can show the actual line (1, 2, 3...) instead of a fixed number — see PROTOCOL.md §6.

## macOS without permissions

- On **Apple Silicon (arm64)** the mod bundles `nowplaying-cli` v2.1.0 (GPL-3.0, compatible with this project's license) and reads the track via the private **MediaRemote** framework — **no Automation or Screen Recording permission is required** (no "control Spotify" prompt). Works even with the window hidden. The v2.1.0 build needs its two companion helper files next to the binary on macOS 15.4+ (`mediaremote-mini.pl` + `MediaRemoteMini.dylib`); the mod now bundles and extracts all three together.
- **Intel Macs** keep the AppleScript fallback, which needs the one-time Automation prompt. If MediaRemote returns nothing (e.g. nothing playing), AppleScript is tried too.
- While the Automation permission is pending/denied, the mod backs off the AppleScript query (retry every 5 s) and notifies once in chat. A failing/hanging `nowplaying-cli` is backed off too (retry every 30 s) so a broken binary cannot block the tracker thread for the full command timeout on every poll.

## Pause detection

- **Windows:** the window title reverts to the account tier (`"Spotify Free"`/`"Spotify Premium"`) while paused — no extra cost.
- **macOS:** `playbackRate` of the now-playing info via the bundled `nowplaying-cli` (MediaRemote, no permission) with AppleScript fallback (the window title shows the account tier, never the song).
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
