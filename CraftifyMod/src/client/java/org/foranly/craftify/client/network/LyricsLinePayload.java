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
 * {@code craftify:lyricsline} payload: the current lyric line the player wants to share
 * with other players (the server renders it as a hologram, PROTOCOL.md §6).
 *
 * <p>Sent only while the player has "share lyrics" enabled (F10 / {@code /craftify
 * lyrics share}), and only when the line changes. An empty {@code line} clears the
 * hologram; a pause simply stops sending, so the server keeps the last line.
 *
 * <pre>{@code
 * {"line":"First lyric line","number":1}
 * }</pre>
 *
 * <p>{@code number} is the 1-based index of the current line inside the song's lyrics
 * (the server can show it in the BELOW_NAME slot instead of a fixed number); it is
 * {@code -1} when the line is empty. Older plugin versions ignore unknown fields.
 */
public record LyricsLinePayload(String json) implements CustomPacketPayload {

    public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("craftify", "lyricsline");
    public static final Type<LyricsLinePayload> TYPE = new Type<>(CHANNEL);

    public static final StreamCodec<ByteBuf, LyricsLinePayload> CODEC =
            ByteBufCodecs.STRING_UTF8.map(LyricsLinePayload::new, LyricsLinePayload::json);

    /** Registers the payload as serverbound (client → server). Call once. */
    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
    }

    /**
     * Builds a payload from a lyric line and its 1-based line number
     * (empty line + {@code -1} clears the display).
     */
    public static LyricsLinePayload of(String line, int number) {
        JsonObject object = new JsonObject();
        object.addProperty("line", line == null ? "" : line);
        object.addProperty("number", number);
        return new LyricsLinePayload(new Gson().toJson(object));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
