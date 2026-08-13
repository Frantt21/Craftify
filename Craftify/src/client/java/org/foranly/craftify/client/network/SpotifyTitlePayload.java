package org.foranly.craftify.client.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Payload {@code craftify:title}: estado del Spotify del jugador que el mod envía al servidor
 * cuando cambia.
 *
 * <p>El contenido del payload es un único String UTF-8 con un JSON, para que el plugin del
 * servidor (futuro) pueda decodificarlo sin necesidad del mod:
 *
 * <pre>{@code
 * {"state":"playing","track":"Canción - Artista","timestamp":1760000000000}
 * }</pre>
 *
 * <ul>
 *   <li>{@code state}: uno de {@value #STATE_PLAYING}, {@value #STATE_NO_TRACK} o
 *       {@value #STATE_CLOSED}.</li>
 *   <li>{@code track}: título leído de Spotify; vacío si no hay canción activa.</li>
 *   <li>{@code timestamp}: epoch millis del momento de la captura.</li>
 * </ul>
 */
public record SpotifyTitlePayload(String json) implements CustomPacketPayload {

    /** Spotify está corriendo y hay un título legible (canción activa). */
    public static final String STATE_PLAYING = "playing";
    /** Spotify está corriendo pero no hay canción activa (o la ventana no es legible). */
    public static final String STATE_NO_TRACK = "no_track";
    /** Spotify no está corriendo. */
    public static final String STATE_CLOSED = "closed";

    public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("craftify", "title");
    public static final Type<SpotifyTitlePayload> TYPE = new Type<>(CHANNEL);

    public static final StreamCodec<ByteBuf, SpotifyTitlePayload> CODEC =
            ByteBufCodecs.STRING_UTF8.map(SpotifyTitlePayload::new, SpotifyTitlePayload::json);

    /** Registra el payload como serverbound (cliente → servidor). Llamar una sola vez. */
    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
    }

    /** Construye un payload a partir del estado, el título y el momento de la captura. */
    public static SpotifyTitlePayload of(String state, String track, long timestamp) {
        JsonObject object = new JsonObject();
        object.addProperty("state", state);
        object.addProperty("track", track);
        object.addProperty("timestamp", timestamp);
        return new SpotifyTitlePayload(new Gson().toJson(object));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
