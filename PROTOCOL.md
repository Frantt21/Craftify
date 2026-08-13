# Craftify communication protocol

This document is the **contract between the mod (client) and the plugin (server)**. It
defines how the mod detects and sends the Spotify state, the exact bytes that travel over
the network, and how the plugin must receive and use them.

- **Sender:** the CraftifyMod mod (client-only, Fabric) — see [`CraftifyMod/README.md`](CraftifyMod/README.md).
- **Receiver:** the server plugin — see [`CraftifyPlugin/README.md`](CraftifyPlugin/README.md)
  (Paper; it can also be a Fabric server mod — the format is designed so either of them can
  decode it).
- **Direction:** client → server. One-way; there are no return packets today.
- **Overall system documentation:** [`README.md`](README.md).

```
┌───────────────────────────────┐   minecraft:custom_payload   ┌───────────────────────────────┐
│  Client: CraftifyMod          │ ───────────────────────────►  │  Server: plugin (future)      │
│                               │   channel "craftify:title"   │                               │
│  - Detects Spotify on the OS  │   payload: UTF-8 JSON        │  - Receives the state per     │
│  - Reads the song title       │                              │    player                      │
│  - Sends only on changes      │                              │  - Converts/exposes it        │
└───────────────────────────────┘                              └───────────────────────────────┘
```

---

## ⚡ Quick reference for the plugin

| What | Value |
|-----|-------|
| Packet | `minecraft:custom_payload`, `play` phase |
| Channel | `craftify:title` |
| Direction | Client → Server |
| Payload | `[VarInt length][UTF-8 bytes of a JSON]` |
| JSON | `{"state":"...","track":"...","timestamp":...}` |
| States | `playing` · `no_track` · `closed` |
| Frequency | **Only when the state changes** (no heartbeat) |

Golden rules:

1. The **last received packet IS the player's current state**: don't expect periodic
   updates.
2. Store the state per UUID and **remove it when the player disconnects**.
3. Use `timestamp` to discard old/duplicate packets.
4. `playing` does not necessarily mean "currently playing": the mod does not distinguish
   pause (the window title does not change when paused).
5. `track` is a single string `"Song - Artist"`; splitting title and artist is the
   plugin's job (e.g. by the last ` - `).

---

## 1. How the mod sends the packets

### 1.1 Chain summary

1. **`SpotifyProcess.readSnapshot(os)`** probes the player's operating system (a single
   query per poll) and returns `(running, title)`:
   - Windows: native JNA probe (`Toolhelp32` + `EnumWindows`), ~10–60 ms. Windows are
     identified **by PID** (not by module name) and the title stays available with Spotify
     **minimized or in the tray** (second pass over hidden windows, skipping auxiliary
     IME/GDI+ windows).
   - macOS: native JNA/CoreGraphics probe (`CGWindowListCopyWindowInfo`), ~1–10 ms. It only
     lists windows in the current session; the title requires the Screen Recording
     permission.
   - Linux: `playerctl` (MPRIS) as the main probe — **the mod bundles the official
     playerctl binary inside the JAR** (extracted to the user's temp directory, no sudo)
     with a fallback to the system `playerctl` and then `pgrep` + `xdotool`.
   - All platforms have a CLI fallback if the native probe is not available.
2. **`SpotifyTracker`** (a `daemon` thread) queries that snapshot while the player is in a
   world and decides whether the state changed.
3. If it changed, it builds a **`SpotifyTitlePayload`** (JSON) and sends it with
   `ClientPlayNetworking.send(payload)`.

### 1.2 When it sends

The mod does **not** send packets in a loop. It sends one **only when the state changes**:

| Moment | What is sent |
|---------|--------------|
| Joining a world | The current state (even if `closed`) |
| The song changes | `playing` with the new title |
| Spotify goes to no readable title | `no_track` |
| Spotify closes | `closed` |
| Resuming after `/craftify send off` | The current state (to re-sync) |

There is no heartbeat or acknowledgement (ACK): if the server drops a packet, it learns
about it on the next state change.

### 1.3 Latency and polling

- **~500 ms** interval while Spotify is running → near real-time song changes (effective
  cycle ~0.5–0.6 s).
- **~5 s** backoff when Spotify is closed (don't waste resources).
- Sending can be paused/resumed on the client with `/craftify send on|off|toggle`.

### 1.4 Relevant mod classes

| Class | Responsibility |
|-------|-----------------|
| `SpotifyTracker` | Adaptive polling, change detection, pause/resume |
| `SpotifyProcess` | `(running, title)` snapshot per OS + CLI fallbacks |
| `WindowsSpotify` / `MacosSpotify` | Native JNA probes |
| `SpotifyTitlePayload` | Channel definition, codec and JSON serialization |

---

## 2. On-wire format (the contract)

| Field | Value |
|-------|-------|
| Minecraft packet | `minecraft:custom_payload` |
| Phase | `play` |
| Channel | `craftify:title` |
| Direction | Client → Server (serverbound) |
| Content | A single UTF-8 String containing JSON |

### 2.1 Payload bytes

```
[VarInt length] [UTF-8 bytes of the JSON]
```

1. A `VarInt` (standard Minecraft encoding, 7 bits per group, max 5 bytes) with the
   length **in bytes** of the String.
2. The UTF-8 bytes of the JSON described below.

A typical song title takes less than 200 bytes, well below the small-payload limit
(~32 KB).

### 2.2 JSON content

```json
{
  "state": "playing",
  "track": "My Favorite Song - My Artist",
  "timestamp": 1760000000000
}
```

| Field | Type | Description |
|-------|------|-------------|
| `state` | string | One of the three states (table below). |
| `track` | string | Song title (`"Song - Artist"`); empty when there is no active song. |
| `timestamp` | number (long) | Epoch millis of the capture moment. |

### 2.3 States

| `state` | Meaning | `track` |
|---------|-------------|---------|
| `playing` | Spotify is running and there is a readable title (active song). The song may be paused: the title does not distinguish pause/play. | The title |
| `no_track` | Spotify is running but no song could be read (OS permissions not granted, or on macOS/Linux when the window is not readable — on Windows it is read even when in the tray). | `""` |
| `closed` | Spotify is not running. | `""` |

---

## 3. How the plugin must receive them

The plugin can be of two types; in both cases the bytes that arrive are those of §2.1.

### 3.1 Fabric plugin (server mod)

Registers the same payload type (serverbound) and a global receiver:

```java
// In the server plugin's onInitialize()
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("craftify", "title");

public static final Type<SpotifyTitlePayload> TYPE = new Type<>(CHANNEL);

public static final StreamCodec<ByteBuf, SpotifyTitlePayload> CODEC =
        ByteBufCodecs.STRING_UTF8.map(SpotifyTitlePayload::new, SpotifyTitlePayload::json);

// Payload identical to the client's
public record SpotifyTitlePayload(String json) implements CustomPacketPayload {
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

// Registration + handler
PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);

ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) -> {
    ServerPlayer player = context.player();
    // payload.json() is the raw JSON; parse it (Gson, Jackson, etc.)
    // and store it as the player's current state.
    handleTitle(player.getUUID(), payload.json());
});
```

### 3.2 Paper/Bukkit plugin

Intercepts the `minecraft:custom_payload` packet at the network layer and reads the raw
bytes:

```java
public class SpotifyListener implements PluginMessageListener {

    private static final String CHANNEL = "craftify:title";

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        // The payload is: [VarInt length][UTF-8 JSON]
        int[] offset = {0};
        int length = readVarInt(message, offset);
        String json = new String(message, offset[0], length, StandardCharsets.UTF_8);
        handleTitle(player.getUniqueId(), json);
    }

    // Minecraft VarInt: 7 bits per group; the high bit (0x80) means there are more bytes.
    private static int readVarInt(byte[] buf, int[] offset) {
        int value = 0;
        int shift = 0;
        while (offset[0] < buf.length) {
            byte b = buf[offset[0]++];
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IllegalArgumentException("Unterminated VarInt");
    }
}
```

Register the listener in `onEnable()`:

```java
getServer().getMessenger().registerIncomingPluginChannel(this, "craftify:title", new SpotifyListener());
```

### 3.3 Manual decoding (any stack)

The raw payload bytes are decoded like this, without depending on the mod:

1. Read a `VarInt` → length `L` in bytes of the String.
2. Read the next `L` bytes as UTF-8 text.
3. Parse that text as JSON.
4. Read `state`, `track` and `timestamp`.

VarInt decoder (7 bits per group, like Minecraft uses):

```
read bytes until the high bit (0x80) is off:
    value |= (byte & 0x7F) << (7 * position)
```

---

## 4. How to use them (recommendations for the plugin)

### 4.1 Keep the latest state per player

Store the state in memory indexed by UUID and update it on every packet:

```java
Map<UUID, SpotifyState> byPlayer = new ConcurrentHashMap<>();
// SpotifyState { String state; String track; long timestamp; }
```

- The plugin **must not** assume periodic updates: the mod only sends on changes. The last
  received packet IS the current state.
- When a player **disconnects**, remove their entry
  (`PlayerQuitEvent` on Paper, `ServerPlayConnectionEvents.DISCONNECT` on Fabric).

### 4.2 Order and discard old messages with `timestamp`

The `timestamp` is the client's capture epoch millis. Use it to:

- ignore packets whose `timestamp` is older than the last processed one (avoids
  reordering or duplicates);
- know when the song last changed.

### 4.3 Recommended interpretation

| State received | What it means for the plugin |
|-----------------|------------------------------|
| `playing` with `track` | The player is listening to that song (may be paused). |
| `no_track` | Spotify open but no readable song: show "listening to Spotify" without a title. |
| `closed` | Spotify closed: clear any "now playing" for the player. |

### 4.4 Example feature

"Now playing" in chat/scoreboard: on `playing`, format
`<player> is listening to <track>`; on `closed`, remove the line.

---

## 5. Limitations and notes

- **No handshake or ACK:** the channel is used as-is; the server acknowledges nothing.
- **Latency:** a change takes ≤ ~0.5–0.6 s to be detected (500 ms poll interval + probe
  cost).
- **`playing` ≠ playing:** the window title does not change on pause; the mod does not
  distinguish pause/play. A future packet with playback state (e.g. via MPRIS) is a natural
  extension of the protocol.
- **OS permissions (client):** on macOS detecting the process needs no permissions; for the
  title, the lowest-friction path is Spotify's own AppleScript dictionary (a single
  Automation prompt), with Screen Recording and Accessibility as alternatives. On Linux,
  `playerctl` (MPRIS, no permissions) is used **bundled in the mod's JAR** (extracted to
  the user's temp dir, no sudo), with `xdotool` as a fallback. Spotify's local API
  (port 4380) no longer exists on modern clients.
- **Hidden window by platform:** on Windows the title keeps being read with Spotify
  minimized or in the tray (so the plugin will keep receiving `playing` even if the player
  hides it). On macOS and Linux, if the window stops being readable the mod switches to
  `no_track` — the plugin should not treat that as "Spotify closed".
- **Size:** the payload is small; Fabric's payload limits (regular `register`) are not a
  problem for song titles.
- **Fabric without the type registered:** a Fabric server drops payloads whose type has no
  handler. Paper, on the other hand, intercepts raw bytes at the network layer and always
  sees them, regardless of registrations.
- **Future extension:** adding new channels (e.g. `craftify:playback` with pause/play
  state) does not break this contract: each channel is independent.
