package org.foranly.craftifyplugin;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Último estado de Spotify por jugador (PROTOCOL.md §4.1).
 *
 * <p>El mod solo envía cuando el estado cambia, así que el último paquete recibido ES el
 * estado actual del jugador.
 */
public final class SpotifyStateManager {

    private final Map<UUID, PlayerSpotifyState> byPlayer = new ConcurrentHashMap<>();

    /** Guarda (o reemplaza) el estado de un jugador. */
    public void update(UUID player, PlayerSpotifyState state) {
        byPlayer.put(player, state);
    }

    /** Devuelve el último estado conocido del jugador, si existe. */
    public Optional<PlayerSpotifyState> get(UUID player) {
        return Optional.ofNullable(byPlayer.get(player));
    }

    /** Elimina el estado de un jugador (p. ej. al desconectar). */
    public void remove(UUID player) {
        byPlayer.remove(player);
    }

    /** Cantidad de jugadores con estado conocido. */
    public int size() {
        return byPlayer.size();
    }
}
