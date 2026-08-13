package org.foranly.craftifyplugin;

import org.bukkit.Bukkit;
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

    // ANSI colors for the console banner (printed with Bukkit.getConsoleSender(), so they
    // are NOT converted/stripped; require a console that supports ANSI).
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_PURPLE = "\u001B[35m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_GRAY = "\u001B[37m";

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
     * Prints a colored startup banner directly to the console with ANSI escape codes
     * (via {@link Bukkit#getConsoleSender()}, so the codes are not stripped). Consoles
     * that support ANSI will show the colors; the in-game colors (the nametag) come from
     * the MiniMessage format in {@code config.yml}.
     */
    private void logStartup(boolean migrated, long started) {
        long ms = System.currentTimeMillis() - started;
        banner(ANSI_PURPLE + SEPARATOR + ANSI_RESET);
        banner(ANSI_CYAN + "CraftifyPlugin " + ANSI_RESET + getPluginMeta().getVersion());
        banner(ANSI_GRAY + "Channel: " + ANSI_RESET + CHANNEL + ANSI_GRAY + " (client → server, see PROTOCOL.md)" + ANSI_RESET);
        if (migrated) {
            banner(ANSI_YELLOW + "Old config.yml detected → overwritten with current defaults." + ANSI_RESET);
        }
        banner(ANSI_GRAY + "Nametag: " + ANSI_RESET + status(nametags != null && nametags.isEnabled()));
        banner(ANSI_GRAY + "Hologram: " + ANSI_RESET + status(holograms != null && holograms.isEnabled()));
        banner(ANSI_GRAY + "Commands: " + ANSI_RESET + "/nowplaying, /craftifyplugin reload");
        banner(ANSI_PURPLE + SEPARATOR + ANSI_RESET);
        banner(ANSI_GREEN + "CraftifyPlugin enabled in " + ms + " ms" + ANSI_RESET);
    }

    private void banner(String message) {
        Bukkit.getConsoleSender().sendMessage(message);
    }

    private String status(boolean enabled) {
        return enabled ? ANSI_GREEN + "ENABLED" + ANSI_RESET : ANSI_RED + "DISABLED" + ANSI_RESET;
    }
}
