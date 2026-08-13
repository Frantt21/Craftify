package org.foranly.craftifyplugin;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.foranly.craftifyplugin.hologram.HologramManager;

/** Limpia el estado y el holograma al desconectar el jugador (PROTOCOL.md §4.1). */
public final class PlayerListener implements Listener {

    private final SpotifyStateManager stateManager;
    private final HologramManager holograms;

    public PlayerListener(SpotifyStateManager stateManager, HologramManager holograms) {
        this.stateManager = stateManager;
        this.holograms = holograms;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stateManager.remove(event.getPlayer().getUniqueId());
        holograms.remove(event.getPlayer().getUniqueId());
    }
}
