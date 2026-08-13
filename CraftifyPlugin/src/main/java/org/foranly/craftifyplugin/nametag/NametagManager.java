package org.foranly.craftifyplugin.nametag;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.foranly.craftifyplugin.PlayerSpotifyState;

/**
 * Muestra el título de la canción en el **nombre flotante** del jugador (el nametag que
 * Minecraft renderiza sobre la cabeza), en vez de una entidad holograma.
 *
 * <p>Ventaja: el nametag es parte de la entidad del jugador, así que sigue sus movimientos
 * sin lag (a diferencia del holograma {@code TextDisplay}, que se teletransporta por tick).
 * Se configura en {@code config.yml} (sección {@code nametag}).
 *
 * <p>Nota: el jugador dueño del nombre solo lo ve en tercera persona (F5) si el mod del
 * cliente trae el mixin correspondiente — por defecto Minecraft no muestra el nombre propio.
 */
public final class NametagManager {

    private static final String DEFAULT_FORMAT = "<yellow>{name}</yellow><newline><green>♪ </green><white>{track}</white>";

    private final boolean enabled;
    private final String format;

    public NametagManager(Plugin plugin) {
        this.enabled = plugin.getConfig().getBoolean("nametag.enabled", true);
        this.format = plugin.getConfig().getString("nametag.format", DEFAULT_FORMAT);
    }

    /**
     * Actualiza el nametag del jugador según su estado de Spotify: con {@code playing} y
     * título, muestra el nombre + el título; en cualquier otro estado restaura el nombre
     * normal del jugador.
     */
    public void update(Player player, PlayerSpotifyState state) {
        if (!enabled) {
            return;
        }
        if (state.isPlaying() && !state.track().isEmpty()) {
            String text = format
                    .replace("{name}", player.getName())
                    .replace("{track}", state.track());
            player.customName(MiniMessage.miniMessage().deserialize(text));
            player.setCustomNameVisible(true);
        } else {
            reset(player);
        }
    }

    /** Restaura el nametag del jugador a su nombre normal (p. ej. al desconectar/deshabilitar). */
    public void reset(Player player) {
        if (!enabled) {
            return;
        }
        player.customName(Component.text(player.getName()));
        player.setCustomNameVisible(true);
    }
}
