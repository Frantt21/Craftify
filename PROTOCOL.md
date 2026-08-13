# Protocolo de comunicación de Craftify

Este documento es el **contrato entre el mod (cliente) y el plugin (servidor)**. Define cómo el
mod detecta y envía el estado de Spotify, el formato exacto de los bytes que viajan por la red,
y cómo el plugin debe recibirlos y usarlos.

- **Emisor:** mod Craftify (solo cliente, Fabric).
- **Receptor:** plugin del servidor (en desarrollo). Puede ser un mod Fabric del servidor o un
  plugin Paper/Bukkit — el formato está pensado para que cualquiera de los dos lo decodifique.
- **Dirección:** cliente → servidor. Unidireccional; hoy no hay paquetes de vuelta.

```
┌───────────────────────────────┐   minecraft:custom_payload   ┌───────────────────────────────┐
│  Cliente: mod Craftify        │ ───────────────────────────►  │  Servidor: plugin (futuro)    │
│                               │   canal "craftify:title"     │                               │
│  - Detecta Spotify en el SO   │   payload: JSON UTF-8        │  - Recibe el estado por       │
│  - Lee el título de la canción│                              │    jugador                     │
│  - Envía solo cuando cambia   │                              │  - Lo convierte/expone        │
└───────────────────────────────┘                              └───────────────────────────────┘
```

---

## ⚡ Referencia rápida para el plugin

| Qué | Valor |
|-----|-------|
| Paquete | `minecraft:custom_payload`, fase `play` |
| Canal | `craftify:title` |
| Dirección | Cliente → Servidor |
| Payload | `[VarInt longitud][bytes UTF-8 de un JSON]` |
| JSON | `{"state":"...","track":"...","timestamp":...}` |
| Estados | `playing` · `no_track` · `closed` |
| Frecuencia | **Solo cuando el estado cambia** (nada de heartbeat) |

Reglas de oro:

1. El **último paquete recibido ES el estado actual** del jugador: no esperes actualizaciones
   periódicas.
2. Guarda el estado por UUID y **bórralo al desconectar** el jugador.
3. Usa `timestamp` para descartar paquetes viejos/duplicados.
4. `playing` no significa necesariamente reproduciendo: el mod no distingue pausa
   (el título de la ventana no cambia al pausar).
5. `track` es un único string `"Canción - Artista"`; separar título y artista es trabajo del
   plugin (p. ej. por el último ` - `).

---

## 1. Cómo el mod envía los paquetes

### 1.1 Resumen de la cadena

1. **`SpotifyProcess.readSnapshot(os)`** hace una sonda del sistema operativo del jugador
   (una sola consulta por poll) y devuelve `(running, title)`:
   - Windows: sonda nativa JNA (`Toolhelp32` + `EnumWindows`), ~10–60 ms. Las ventanas se
     identifican **por PID** (no por nombre de módulo) y el título sigue disponible con
     Spotify **minimizado o en la bandeja** (segundo pase sobre ventanas ocultas,
     descartando las auxiliares IME/GDI+).
   - macOS: sonda nativa JNA/CoreGraphics (`CGWindowListCopyWindowInfo`), ~1–10 ms. Solo
     lista ventanas en la sesión actual; el título requiere permiso de Grabación de Pantalla.
   - Linux: `playerctl` (MPRIS) como sonda principal; fallback `pgrep` + `xdotool`.
   - Todas las plataformas tienen fallback CLI si la sonda nativa no está disponible.
2. **`SpotifyTracker`** (hilo `daemon`) consulta ese snapshot mientras el jugador está en un
   mundo y decide si el estado cambió.
3. Si cambió, construye un **`SpotifyTitlePayload`** (JSON) y lo envía con
   `ClientPlayNetworking.send(payload)`.

### 1.2 Cuándo se envía

El mod **no** manda paquetes en bucle. Envía uno **solo cuando el estado cambia**:

| Momento | Qué se envía |
|---------|--------------|
| Al entrar a un mundo | El estado actual (aunque sea `closed`) |
| Cambia la canción | `playing` con el título nuevo |
| Spotify pasa a sin título legible | `no_track` |
| Spotify se cierra | `closed` |
| Se reanuda tras `/craftify send off` | El estado actual (para re-sincronizar) |

No existe heartbeat ni confirmación (ACK): si el servidor pierde un paquete, se entera en el
siguiente cambio de estado.

### 1.3 Latencia y polling

- **~500 ms** de intervalo mientras Spotify está corriendo → cambios de canción casi en
  tiempo real (ciclo efectivo ~0,5–0,6 s).
- **~5 s** de backoff cuando Spotify está cerrado (no gastar recursos en vano).
- El envío se puede pausar/reanudar en el cliente con `/craftify send on|off|toggle`.

### 1.4 Clases relevantes del mod

| Clase | Responsabilidad |
|-------|-----------------|
| `SpotifyTracker` | Polling adaptativo, detección de cambios, pausa/reanudación |
| `SpotifyProcess` | Snapshot `(running, title)` por SO + fallbacks CLI |
| `WindowsSpotify` / `MacosSpotify` | Sondas nativas JNA |
| `SpotifyTitlePayload` | Definición del canal, codec y serialización JSON |

---

## 2. Formato on-wire (el contrato)

| Campo | Valor |
|-------|-------|
| Paquete de Minecraft | `minecraft:custom_payload` |
| Fase | `play` |
| Canal | `craftify:title` |
| Dirección | Cliente → Servidor (serverbound) |
| Contenido | Un único String UTF-8 con JSON |

### 2.1 Bytes del payload

```
[VarInt longitud] [bytes UTF-8 del JSON]
```

1. Un `VarInt` (codificación estándar de Minecraft, 7 bits por grupo, máx. 5 bytes) con la
   longitud **en bytes** del String.
2. Los bytes UTF-8 del JSON que se describe abajo.

Un título de canción típico ocupa menos de 200 bytes, muy por debajo del límite de los
payloads pequeños (~32 KB).

### 2.2 Contenido del JSON

```json
{
  "state": "playing",
  "track": "Mi Canción Favorita - Mi Artista",
  "timestamp": 1760000000000
}
```

| Campo | Tipo | Descripción |
|-------|------|-------------|
| `state` | string | Uno de los tres estados (tabla abajo). |
| `track` | string | Título de la canción (`"Canción - Artista"`); vacío si no hay canción activa. |
| `timestamp` | number (long) | Epoch millis del momento de la captura. |

### 2.3 Estados

| `state` | Significado | `track` |
|---------|-------------|---------|
| `playing` | Spotify está corriendo y hay un título legible (canción activa). La canción puede estar en pausa: el título no distingue pausa/reproducción. | El título |
| `no_track` | Spotify está corriendo pero no se pudo leer ninguna canción (permisos del SO sin otorgar, o en macOS/Linux cuando la ventana no es legible — en Windows sí se lee aunque esté en la bandeja). | `""` |
| `closed` | Spotify no está corriendo. | `""` |

---

## 3. Cómo debe recibirlos el plugin

El plugin puede ser de dos tipos; en ambos casos los bytes que llegan son los del §2.1.

### 3.1 Plugin Fabric (mod del servidor)

Registra el mismo tipo de payload (serverbound) y un receiver global:

```java
// En el onInitialize() del plugin del servidor
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

// Payload idéntico al del cliente
public record SpotifyTitlePayload(String json) implements CustomPacketPayload {
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

// Registro + handler
PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);

ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) -> {
    ServerPlayer player = context.player();
    // payload.json() es el JSON crudo; parsearlo (Gson, Jackson, etc.)
    // y guardarlo como el estado actual del jugador.
    handleTitle(player.getUUID(), payload.json());
});
```

### 3.2 Plugin Paper/Bukkit

Intercepta el paquete `minecraft:custom_payload` en la capa de red y lee los bytes crudos:

```java
public class SpotifyListener implements PluginMessageListener {

    private static final String CHANNEL = "craftify:title";

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        // El payload es: [VarInt longitud][UTF-8 JSON]
        int[] offset = {0};
        int length = readVarInt(message, offset);
        String json = new String(message, offset[0], length, StandardCharsets.UTF_8);
        handleTitle(player.getUniqueId(), json);
    }

    // VarInt de Minecraft: 7 bits por grupo; el bit alto (0x80) indica que hay más bytes.
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
        throw new IllegalArgumentException("VarInt sin terminar");
    }
}
```

Regístrate como listener en `onEnable()`:

```java
getServer().getMessenger().registerIncomingPluginChannel(this, "craftify:title", new SpotifyListener());
```

### 3.3 Decodificación manual (cualquier stack)

Los bytes crudos del payload se decodifican así, sin depender del mod:

1. Leer un `VarInt` → longitud `L` en bytes del String.
2. Leer los siguientes `L` bytes como texto UTF-8.
3. Parsear ese texto como JSON.
4. Leer `state`, `track` y `timestamp`.

Decodificador VarInt (7 bits por grupo, como usa Minecraft):

```
leer bytes hasta que el bit alto (0x80) esté apagado:
    valor |= (byte & 0x7F) << (7 * posición)
```

---

## 4. Cómo usarlos (recomendaciones para el plugin)

### 4.1 Mantener el último estado por jugador

Guarda el estado en memoria indexado por UUID y actualízalo en cada paquete:

```java
Map<UUID, SpotifyState> byPlayer = new ConcurrentHashMap<>();
// SpotifyState { String state; String track; long timestamp; }
```

- El plugin **no debe** asumir que recibe actualizaciones periódicas: el mod solo envía en
  cambios. El último paquete recibido ES el estado actual.
- Al **desconectar** un jugador, elimina su entrada
  (`PlayerQuitEvent` en Paper, `ServerPlayConnectionEvents.DISCONNECT` en Fabric).

### 4.2 Ordenar y descartar mensajes viejos con `timestamp`

El `timestamp` es epoch millis de la captura en el cliente. Úsalo para:

- ignorar paquetes que llegan con `timestamp` anterior al último procesado (evita reordenados
  o duplicados);
- saber cuándo fue la última vez que cambió la canción.

### 4.3 Interpretación recomendada

| Estado recibido | Qué significa para el plugin |
|-----------------|------------------------------|
| `playing` con `track` | El jugador está escuchando esa canción (puede estar en pausa). |
| `no_track` | Spotify abierto pero sin canción legible: mostrar "escuchando Spotify" sin título. |
| `closed` | Spotify cerrado: limpiar cualquier "now playing" del jugador. |

### 4.4 Ejemplo de feature

"Now playing" en el chat/scoreboard: al recibir `playing`, formatear
`<jugador> está escuchando <track>`; al recibir `closed`, quitar la línea.

---

## 5. Limitaciones y notas

- **Sin handshake ni ACK:** el canal se usa tal cual; el servidor no confirma nada.
- **Latencia:** la detección de un cambio tarda ≤ ~0,5–0,6 s (intervalo de poll de 500 ms +
  costo de sonda).
- **`playing` ≠ reproduciendo:** el título de la ventana no cambia al pausar; el mod no
  distingue pausa/reproducción. Un futuro paquete con estado de reproducción (p. ej. vía MPRIS)
  es una extensión natural del protocolo.
- **Permisos del SO (cliente):** en macOS detectar el proceso no requiere permisos; para el
  título, el camino de menor fricción es el diccionario AppleScript de la propia app de
  Spotify (un único prompt de Automatización), con Grabación de Pantalla y Accesibilidad
  como alternativas. En Linux se prefiere `playerctl` (MPRIS, sin permisos; solo hay que
  instalarlo), con `xdotool` como respaldo. La API local de Spotify (puerto 4380) ya no
  existe en los clientes modernos.
- **Ventana oculta por plataforma:** en Windows el título se sigue leyendo con Spotify
  minimizado o en la bandeja (por eso el plugin seguirá recibiendo `playing` aunque el
  jugador lo oculte). En macOS y Linux, si la ventana deja de ser legible, el mod pasa a
  `no_track` — el plugin no debería tratar eso como "Spotify cerrado".
- **Tamaño:** el payload es pequeño; los límites de los payloads de Fabric (`register` normal)
  no son un problema para títulos de canción.
- **Fabric sin el tipo registrado:** el servidor Fabric descarta los payloads cuyo tipo no
  tiene handler. Paper, en cambio, intercepta los bytes crudos en la capa de red y los ve
  siempre, sin importar registros.
- **Extensión futura:** añadir nuevos canales (p. ej. `craftify:playback` con estado
  pausa/reproducción) no rompe este contrato: cada canal es independiente.
