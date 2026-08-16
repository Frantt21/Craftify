package org.foranly.craftifyplugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * A player's Spotify state, per {@code PROTOCOL.md §2.2/§2.3}.
 *
 * <p>Fields of the {@code craftify:title} JSON: {@code state}, {@code track}, {@code timestamp}.
 */
public record PlayerSpotifyState(String state, String track, long timestamp) {

    /** Protocol states (PROTOCOL.md §2.3). */
    public static final String STATE_PLAYING = "playing";
    public static final String STATE_PAUSED = "paused";
    public static final String STATE_NO_TRACK = "no_track";
    public static final String STATE_CLOSED = "closed";

    /**
     * Parses the JSON of the {@code craftify:title} payload.
     *
     * @return the state, or {@code null} if the JSON is invalid
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

    /** Whether there is an active song ({@code playing} state). */
    public boolean isPlaying() {
        return STATE_PLAYING.equals(state);
    }
}
