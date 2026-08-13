package org.foranly.craftifyplugin;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.foranly.craftifyplugin.command.NowPlayingCommand;
import org.foranly.craftifyplugin.hologram.HologramManager;
import org.foranly.craftifyplugin.nametag.NametagManager;

/**
 * Paper plugin that receives the {@code craftify:title} channel sent by the Craftify mod
 * (client) and stores each player's Spotify state.
 *
 * <p>Full contract: see {@code PROTOCOL.md} at the repository root.
 */
public final class CraftifyPlugin extends JavaPlugin {

    /** Protocol channel (PROTOCOL.md §2). */
    public static final String CHANNEL = "craftify:title";

    private SpotifyStateManager stateManager;
    private HologramManager holograms;
    private NametagManager nametags;

    @Override
    public void onEnable() {
        stateManager = new SpotifyStateManager();
        nametags = new NametagManager(this);
        holograms = new HologramManager(this);
        holograms.start();

        // Receive C→S: the payload arrives as minecraft:custom_payload (PROTOCOL.md §3.2).
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL,
                new SpotifyListener(stateManager, holograms, nametags, getLogger()));

        // Clear the state and the display on disconnect (PROTOCOL.md §4.1).
        getServer().getPluginManager().registerEvents(new PlayerListener(stateManager, holograms, nametags), this);

        // Verification: /nowplaying
        getCommand("nowplaying").setExecutor(new NowPlayingCommand(stateManager));

        getLogger().info("CraftifyPlugin enabled — listening on channel " + CHANNEL);
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        // Restore any modified nametags before shutting down.
        if (nametags != null) {
            getServer().getOnlinePlayers().forEach(nametags::reset);
        }
        if (holograms != null) {
            holograms.shutdown();
        }
        stateManager = null;
    }

    /** State access, in case another plugin component needs it. */
    public SpotifyStateManager getStateManager() {
        return stateManager;
    }
}
