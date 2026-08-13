package org.foranly.craftifyplugin;

import org.bukkit.plugin.java.JavaPlugin;
import org.foranly.craftifyplugin.command.NowPlayingCommand;
import org.foranly.craftifyplugin.hologram.HologramManager;

/**
 * Plugin Paper que recibe el canal {@code craftify:title} enviado por el mod Craftify
 * (cliente) y guarda el estado de Spotify de cada jugador.
 *
 * <p>Contrato completo: ver {@code PROTOCOL.md} en la raíz del repositorio.
 */
public final class CraftifyPlugin extends JavaPlugin {

    /** Canal del protocolo (PROTOCOL.md §2). */
    public static final String CHANNEL = "craftify:title";

    private SpotifyStateManager stateManager;
    private HologramManager holograms;

    @Override
    public void onEnable() {
        stateManager = new SpotifyStateManager();
        holograms = new HologramManager(this);
        holograms.start();

        // Recibir C→S: el payload llega como minecraft:custom_payload (PROTOCOL.md §3.2).
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL,
                new SpotifyListener(stateManager, holograms, getLogger()));

        // Limpiar el estado y el holograma al desconectar (PROTOCOL.md §4.1).
        getServer().getPluginManager().registerEvents(new PlayerListener(stateManager, holograms), this);

        // Verificación: /nowplaying
        getCommand("nowplaying").setExecutor(new NowPlayingCommand(stateManager));

        getLogger().info("CraftifyPlugin habilitado — escuchando el canal " + CHANNEL);
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        if (holograms != null) {
            holograms.shutdown();
        }
        stateManager = null;
    }

    /** Acceso al estado por si otro componente del plugin lo necesita. */
    public SpotifyStateManager getStateManager() {
        return stateManager;
    }
}
