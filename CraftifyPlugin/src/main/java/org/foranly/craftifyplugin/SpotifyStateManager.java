package org.foranly.craftifyplugin;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Latest Spotify state per player (PROTOCOL.md §4.1).
 *
 * <p>The mod only sends when the state changes, so the last received packet IS the player's
 * current state.
 */
public final class SpotifyStateManager {

    private final Map<UUID, PlayerSpotifyState> byPlayer = new ConcurrentHashMap<>();

    /** Stores (or replaces) a player's state. */
    public void update(UUID player, PlayerSpotifyState state) {
        byPlayer.put(player, state);
    }

    /** Returns the player's latest known state, if any. */
    public Optional<PlayerSpotifyState> get(UUID player) {
        return Optional.ofNullable(byPlayer.get(player));
    }

    /** Removes a player's state (e.g. on disconnect). */
    public void remove(UUID player) {
        byPlayer.remove(player);
    }

    /** Number of players with a known state. */
    public int size() {
        return byPlayer.size();
    }
}
