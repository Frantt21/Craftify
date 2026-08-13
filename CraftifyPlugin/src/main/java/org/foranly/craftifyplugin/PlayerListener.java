package org.foranly.craftifyplugin;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** Limpia el estado de Spotify al desconectar el jugador (PROTOCOL.md §4.1). */
public final class PlayerListener implements Listener {

    private final SpotifyStateManager stateManager;

    public PlayerListener(SpotifyStateManager stateManager) {
        this.stateManager = stateManager;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stateManager.remove(event.getPlayer().getUniqueId());
    }
}
