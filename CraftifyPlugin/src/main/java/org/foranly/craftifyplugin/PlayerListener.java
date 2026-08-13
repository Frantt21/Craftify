package org.foranly.craftifyplugin;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.foranly.craftifyplugin.hologram.HologramManager;
import org.foranly.craftifyplugin.nametag.NametagManager;

/** Clears the state and the display when the player disconnects (PROTOCOL.md §4.1). */
public final class PlayerListener implements Listener {

    private final SpotifyStateManager stateManager;
    private final HologramManager holograms;
    private final NametagManager nametags;

    public PlayerListener(SpotifyStateManager stateManager, HologramManager holograms,
                          NametagManager nametags) {
        this.stateManager = stateManager;
        this.holograms = holograms;
        this.nametags = nametags;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stateManager.remove(event.getPlayer().getUniqueId());
        nametags.reset(event.getPlayer());
        holograms.remove(event.getPlayer().getUniqueId());
    }
}
