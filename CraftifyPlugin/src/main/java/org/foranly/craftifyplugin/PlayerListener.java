package org.foranly.craftifyplugin;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.foranly.craftifyplugin.hologram.HologramManager;
import org.foranly.craftifyplugin.hologram.LyricsHologramManager;
import org.foranly.craftifyplugin.nametag.NametagManager;

import java.util.UUID;

/** Clears the state and the display when the player disconnects (PROTOCOL.md §4.1). */
public final class PlayerListener implements Listener {

    private final SpotifyStateManager stateManager;
    private final HologramManager holograms;
    private final NametagManager nametags;
    private final LyricsHologramManager lyricsHolograms;

    public PlayerListener(SpotifyStateManager stateManager, HologramManager holograms,
                          NametagManager nametags, LyricsHologramManager lyricsHolograms) {
        this.stateManager = stateManager;
        this.holograms = holograms;
        this.nametags = nametags;
        this.lyricsHolograms = lyricsHolograms;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        stateManager.remove(uuid);
        nametags.remove(uuid);
        holograms.remove(uuid);
        lyricsHolograms.remove(uuid);
    }
}
