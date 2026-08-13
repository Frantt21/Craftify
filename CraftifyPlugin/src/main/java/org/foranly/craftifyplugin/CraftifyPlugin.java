package org.foranly.craftifyplugin;

import org.bukkit.plugin.java.JavaPlugin;
import org.foranly.craftifyplugin.command.CraftifyCommand;
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

    private static final String SEPARATOR = "==================================================";

    private SpotifyStateManager stateManager;
    private HologramManager holograms;
    private NametagManager nametags;

    @Override
    public void onEnable() {
        long started = System.currentTimeMillis();

        // Create config.yml on first run (Paper standard) and load it.
        saveDefaultConfig();
        reloadConfig();
        boolean migrated = migrateConfig();

        stateManager = new SpotifyStateManager();
        nametags = new NametagManager(this);
        holograms = new HologramManager(this);
        holograms.start();

        // Receive C→S: the payload arrives as minecraft:custom_payload (PROTOCOL.md §3.2).
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL,
                new SpotifyListener(this, stateManager, holograms, nametags, getLogger()));

        // Clear the state and the display on disconnect (PROTOCOL.md §4.1).
        getServer().getPluginManager().registerEvents(new PlayerListener(stateManager, holograms, nametags), this);

        // Verification: /nowplaying
        getCommand("nowplaying").setExecutor(new NowPlayingCommand(stateManager));

        // Administration: /craftifyplugin reload
        getCommand("craftifyplugin").setExecutor(new CraftifyCommand(this));

        logStartup(migrated, started);
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        // Unregister our nametag teams and remove the holograms before shutting down.
        if (nametags != null) {
            nametags.shutdown();
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

    /**
     * Reloads {@code config.yml} and re-applies the current Spotify states to all online
     * players (used by {@code /craftifyplugin reload}).
     */
    public void reloadPlugin() {
        reloadConfig();

        // Recreate the display managers so the new config applies (cleaning old ones first).
        if (nametags != null) {
            nametags.shutdown();
        }
        if (holograms != null) {
            holograms.shutdown();
        }
        nametags = new NametagManager(this);
        holograms = new HologramManager(this);
        holograms.start();

        // Re-apply the stored state of every online player.
        getServer().getOnlinePlayers().forEach(player ->
                stateManager.get(player.getUniqueId()).ifPresent(state -> {
                    nametags.update(player, state);
                    holograms.update(player, state);
                }));

        getLogger().info("CraftifyPlugin reloaded (config.yml re-read, nametags re-applied).");
    }

    /**
     * Migrates old configs: the pre-nametag versions had no {@code nametag} section and
     * left {@code hologram.enabled: true} (so existing servers would keep the hologram on
     * after updating), and the first nametag version used a {@code nametag.format} key that
     * has since been replaced by {@code prefix}/{@code suffix}. When the {@code nametag.prefix}
     * key is missing, the default config is forced (nametag on, hologram off).
     *
     * @return {@code true} if the config was overwritten
     */
    private boolean migrateConfig() {
        if (getConfig().contains("nametag.prefix")) {
            return false;
        }
        if (getResource("config.yml") != null) {
            saveResource("config.yml", true);
            reloadConfig();
            return true;
        }
        getLogger().warning("config.yml not found inside the jar; using in-memory defaults.");
        return false;
    }

    /**
     * Prints a startup banner with the channel and the activation states. Plain text
     * on purpose: legacy {@code \u00a7} color codes render literally on consoles that
     * don't convert them to ANSI. In-game colors (the nametag) come from MiniMessage.
     */
    private void logStartup(boolean migrated, long started) {
        long ms = System.currentTimeMillis() - started;
        getLogger().info(SEPARATOR);
        getLogger().info("CraftifyPlugin " + getPluginMeta().getVersion());
        getLogger().info("Channel: " + CHANNEL + " (client → server, see PROTOCOL.md)");
        if (migrated) {
            getLogger().info("Old config.yml detected → overwritten with current defaults.");
        }
        getLogger().info("Nametag: " + status(nametags != null && nametags.isEnabled()));
        getLogger().info("Hologram: " + status(holograms != null && holograms.isEnabled()));
        getLogger().info("Commands: /nowplaying, /craftifyplugin reload");
        getLogger().info(SEPARATOR);
        getLogger().info("CraftifyPlugin enabled in " + ms + " ms");
    }

    private String status(boolean enabled) {
        return enabled ? "ENABLED" : "DISABLED";
    }
}
