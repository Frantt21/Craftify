package org.foranly.craftifyplugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Estado de Spotify de un jugador, según {@code PROTOCOL.md §2.2/§2.3}.
 *
 * <p>Campos del JSON {@code craftify:title}: {@code state}, {@code track}, {@code timestamp}.
 */
public record PlayerSpotifyState(String state, String track, long timestamp) {

    /** Estados del protocolo (PROTOCOL.md §2.3). */
    public static final String STATE_PLAYING = "playing";
    public static final String STATE_NO_TRACK = "no_track";
    public static final String STATE_CLOSED = "closed";

    /**
     * Parsea el JSON del payload {@code craftify:title}.
     *
     * @return el estado, o {@code null} si el JSON es inválido
     */
    public static PlayerSpotifyState fromJson(String json) {
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            String state = object.has("state") ? object.get("state").getAsString() : STATE_CLOSED;
            String track = object.has("track") ? object.get("track").getAsString() : "";
            long timestamp = object.has("timestamp")
                    ? object.get("timestamp").getAsLong()
                    : System.currentTimeMillis();
            return new PlayerSpotifyState(state, track, timestamp);
        } catch (IllegalStateException | JsonSyntaxException e) {
            return null;
        }
    }

    /** Indica si hay una canción activa (estado {@code playing}). */
    public boolean isPlaying() {
        return STATE_PLAYING.equals(state);
    }
}
