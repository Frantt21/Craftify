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
 * {@code craftify:title} payload: the player's Spotify state that the mod sends to the
 * server when it changes.
 *
 * <p>The payload content is a single UTF-8 String containing JSON, so the (future) server
 * plugin can decode it without depending on the mod:
 *
 * <pre>{@code
 * {"state":"playing","track":"Song - Artist","timestamp":1760000000000}
 * }</pre>
 *
 * <ul>
 *   <li>{@code state}: one of {@value #STATE_PLAYING}, {@value #STATE_NO_TRACK} or
 *       {@value #STATE_CLOSED}.</li>
 *   <li>{@code track}: title read from Spotify; empty when there is no active song.</li>
 *   <li>{@code timestamp}: epoch millis of the capture moment.</li>
 * </ul>
 */
public record SpotifyTitlePayload(String json) implements CustomPacketPayload {

    /** Spotify is running and there is a readable title (active song). */
    public static final String STATE_PLAYING = "playing";
    /** Spotify is running but there is no active song (or the window is not readable). */
    public static final String STATE_NO_TRACK = "no_track";
    /** Spotify is not running. */
    public static final String STATE_CLOSED = "closed";

    public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("craftify", "title");
    public static final Type<SpotifyTitlePayload> TYPE = new Type<>(CHANNEL);

    public static final StreamCodec<ByteBuf, SpotifyTitlePayload> CODEC =
            ByteBufCodecs.STRING_UTF8.map(SpotifyTitlePayload::new, SpotifyTitlePayload::json);

    /** Registers the payload as serverbound (client → server). Call once. */
    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
    }

    /** Builds a payload from the state, the title and the capture moment. */
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
