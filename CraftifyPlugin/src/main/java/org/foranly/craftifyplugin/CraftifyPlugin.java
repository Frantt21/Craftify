package org.foranly.craftifyplugin;

import org.bukkit.plugin.java.JavaPlugin;
import org.foranly.craftifyplugin.command.NowPlayingCommand;

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

    @Override
    public void onEnable() {
        stateManager = new SpotifyStateManager();

        // Recibir C→S: el payload llega como minecraft:custom_payload (PROTOCOL.md §3.2).
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL,
                new SpotifyListener(stateManager, getLogger()));

        // Limpiar el estado al desconectar (PROTOCOL.md §4.1).
        getServer().getPluginManager().registerEvents(new PlayerListener(stateManager), this);

        // Verificación: /nowplaying
        getCommand("nowplaying").setExecutor(new NowPlayingCommand(stateManager));

        getLogger().info("CraftifyPlugin habilitado — escuchando el canal " + CHANNEL);
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        stateManager = null;
    }

    /** Acceso al estado por si otro componente del plugin lo necesita. */
    public SpotifyStateManager getStateManager() {
        return stateManager;
    }
}
