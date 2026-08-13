# CraftifyMod

Minecraft mod (Fabric, **client-only**) that detects the **song title currently playing on
Spotify** by reading the window title of the Spotify process running on the player's
operating system.

The title is sent to the server over the `craftify:title` channel, where the
[**CraftifyPlugin**](../CraftifyPlugin/README.md) (Paper) receives and displays it. The
communication contract is in [`../PROTOCOL.md`](../PROTOCOL.md).

> **Note: this is the client component.** It does nothing visible without a server receiving
> the channel, but it includes the `/craftify spotify` command to verify detection in
> singleplayer.

## Spotify detection

The mod detects the Spotify process running on the player's OS and reads its window title,
which changes with each song (typically `Song - Artist`). On Windows the title keeps being
read even when Spotify is **minimized or in the tray** (the hidden window keeps its text;
only auxiliary IME/GDI+ windows are skipped).

### Supported executables (one per OS)

| OS      | Executable    | Process detection        | Title reading |
|---------|---------------|------------------------------|--------------------|
| Windows | `Spotify.exe` | JNA/Win32 (Toolhelp32) with `tasklist` fallback | JNA/Win32 (`EnumWindows` + `GetWindowText`) with PowerShell fallback |
| macOS   | `Spotify`     | JNA/CoreGraphics (window owner) with `pgrep` fallback | JNA/CoreGraphics → Spotify AppleScript (Automation) → `osascript` |
| Linux   | `spotify`     | MPRIS/`playerctl` (implies running); `pgrep -x spotify` fallback | MPRIS/`playerctl` (metadata) with `xdotool` fallback (window) |

### Requirements per OS

- **Windows**: uses a native probe (JNA/Win32) without extra permissions; PowerShell is
  only used as a fallback if JNA is unavailable.
- **macOS**: detecting the process needs no permissions. For the **title**, the easiest
  path is accepting the one-time **"control Spotify"** (Automation) prompt when it appears
  when you join the game — one click, no need to open System Settings. Alternatives if you
  prefer: the **Screen Recording** permission (System Settings → Privacy & Security →
  Screen Recording) or **Accessibility** (last resort). With any of the three the title is
  read; without any, the state stays `no_track`.
- **Linux**: uses **MPRIS** via `playerctl` as the main probe: a single invocation gives
  process + title, works with the window minimized, **no permissions** and no X11/Wayland
  dependency. **You don't need to install anything with sudo**: the mod bundles the
  official playerctl binary (MIT, only depends on `libglib2.0-0`) inside the JAR and
  extracts it to the user's temp directory on first use. Only if that binary can't run does
  it fall back to `pgrep` + `xdotool` (e.g. `sudo apt install xdotool`) or the system
  `playerctl`. Spotify's local API (port 4380) no longer exists on modern clients. Spotify
  must run in the same graphical session as the user.

## Commands

In-game (singleplayer world or connected to a server):

```
/craftify spotify
/craftify send on|off|toggle
```

### `/craftify spotify` — status and verification

Shows:

1. The detected operating system.
2. Whether the Spotify process is running (and with which executable).
3. The current state (playing / no active song / closed) and the title.
4. Whether packet sending is active, paused or inactive.

Example output:

```
[Craftify] Operating system: WINDOWS
[Craftify] Spotify process (Spotify.exe): running
[Craftify] State: playing
[Craftify] Current title: My Favorite Song - My Artist
[Craftify] Packet sending: active (real-time song change detection)
```

If the process is not running or the title could not be read, the command says so in red
and, depending on the platform, suggests what to do (accept the Automation prompt on
macOS, install `playerctl`/`xdotool` on Linux, etc.).

### `/craftify send` — pause/resume packet sending

- `/craftify send off` pauses sending (tracking keeps reading, but no packets are sent).
- `/craftify send on` resumes it and **sends the current Spotify state on the next read**.
- `/craftify send toggle` switches between both.

The pause state persists between worlds (only lost when closing the game). The
`/craftify spotify` command reflects whether sending is paused.

## See your own name in third person (F5)

By default Minecraft does **not show the player's own nametag** in first or third person:
the client hides it because the local player matches the camera
(`entity == minecraft.getCameraEntity()` in `LivingEntityRenderer#shouldShowName`).

The mod enables it: with the camera in third person (F5), you will see your **own name**
floating above your head — and, if the server runs the plugin in nametag mode, also the
song you're listening to (the plugin uses `customName`, which the mixin displays).

- Implementation: `LivingEntityRendererMixin` (registered in
  `craftify.client.mixins.json`), injected into `shouldShowName`.
- It only activates when looking at yourself in third person: it does not affect first
  person or spectating other players, and it respects the hidden HUD (F1) state.

## Packet sending

While the player is in a world (singleplayer or connected to a server), a separate thread
reads the Spotify state with **adaptive polling** and sends the `craftify:title` packet to
the server **only when the state changes** (not in a loop):

- **every ~0.5 s** while Spotify is running → near real-time song changes;
- **every ~5 s** (backoff) when Spotify is closed, to avoid wasting resources;
- joining a world sends the initial state;
- every song change sends `state: "playing"` with the new title;
- if Spotify is running but no song is readable, it sends `state: "no_track"`;
- when Spotify closes, it sends `state: "closed"`.

Sending can be **paused and resumed** with [`/craftify send`](#craftify-send--pauseresume-packet-sending).

Tracking starts with `ClientPlayConnectionEvents.JOIN` and stops on
`DISCONNECT`. OS reads happen on a `daemon` thread so rendering is never blocked.

### Detection cost

The cost of each query was measured (Windows 11, Java 26):

| Probe | Cost per query |
|-------|--------------------|
| `tasklist` (process) | ~110 ms |
| PowerShell (`MainWindowTitle`) | ~1,100 ms |
| Old Windows poll (tasklist + PowerShell) | ~1,060 ms |
| **Current Windows poll (JNA/Win32)** | **~10–60 ms** (first native load ~500 ms) |
| `osascript` (title, macOS) | ~200–500 ms |
| **Current macOS poll (JNA/CoreGraphics)** | **~1–10 ms** (only the title needs Screen Recording) |
| Linux poll (`playerctl` or `pgrep` + `xdotool`) | ~10–80 ms (light OS processes) |

The bottleneck was **PowerShell startup** (~1.1 s per invocation) on Windows and
**AppleScript startup** (~200–500 ms) on macOS. That's why the mod uses **native JNA
probes** on both: `Toolhelp32` + `EnumWindows` on Windows, `CGWindowListCopyWindowInfo` on
macOS — on the order of milliseconds. If JNA is unavailable, it falls back to the CLI
(slow but functional). On Linux the OS processes are already light, so the probe uses
`playerctl` (MPRIS) as the main method and `xdotool` as a backup.

With the 500 ms interval, each effective cycle lasts ~0.5–0.6 s on any platform, with
negligible CPU cost.

## Build

```bash
cd CraftifyMod
./gradlew build
```

The JAR ends up in `CraftifyMod/build/libs/CraftifyMod-1.0.0.jar` and installs like any
Fabric mod in the client's `mods` folder.

The playerctl binary is already bundled in the resources; to update or re-fetch it:

```bash
cd CraftifyMod
bash scripts/fetch-linux-playerctl.sh
```

## Project configuration

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
- client entrypoint: org.foranly.craftify.client.CraftifyClient

- group id: org.foranly
- artifact id: CraftifyMod
