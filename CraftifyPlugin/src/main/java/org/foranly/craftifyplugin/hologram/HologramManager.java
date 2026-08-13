package org.foranly.craftifyplugin.hologram;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display.Billboard;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.TextDisplay.TextAlignment;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.foranly.craftifyplugin.PlayerSpotifyState;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holograma sobre el jugador con un icono de música y el título de la canción
 * (PROTOCOL.md §4.4).
 *
 * <p>Usa una entidad {@link TextDisplay} que sigue al jugador (billboard + teleport por
 * tick), sin depender de plugins de hologramas externos. Visible para todos los jugadores
 * cercanos. Configurable en {@code config.yml} (sección {@code hologram}).
 */
public final class HologramManager {

    private final Plugin plugin;
    private final boolean enabled;
    private final String icon;
    private final double height;
    private final float scale;

    private final Map<UUID, TextDisplay> displays = new ConcurrentHashMap<>();
    private BukkitTask followTask;

    public HologramManager(Plugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("hologram.enabled", true);
        this.icon = plugin.getConfig().getString("hologram.icon", "♪ ");
        this.height = plugin.getConfig().getDouble("hologram.height", 2.15);
        this.scale = (float) plugin.getConfig().getDouble("hologram.scale", 0.6);
    }

    /** Arranca la tarea que mantiene los hologramas sobre sus jugadores. */
    public void start() {
        if (!enabled) {
            return;
        }
        followTask = Bukkit.getScheduler().runTaskTimer(plugin, this::followPlayers, 1L, 1L);
    }

    /**
     * Actualiza el holograma del jugador según su nuevo estado: muestra el título si
     * {@code playing}, lo oculta en cualquier otro caso.
     */
    public void update(Player player, PlayerSpotifyState state) {
        if (!enabled) {
            return;
        }
        if (state.isPlaying() && !state.track().isEmpty()) {
            TextDisplay display = displays.computeIfAbsent(player.getUniqueId(), id -> spawn(player));
            if (display != null) {
                display.text(Component.text()
                        .append(Component.text(icon, NamedTextColor.GREEN))
                        .append(Component.text(state.track(), NamedTextColor.WHITE))
                        .build());
            }
        } else {
            remove(player.getUniqueId());
        }
    }

    /** Elimina el holograma de un jugador (p. ej. al desconectar). */
    public void remove(UUID player) {
        TextDisplay display = displays.remove(player);
        if (display != null) {
            display.remove();
        }
    }

    /** Cancela el seguimiento y elimina todos los hologramas (onDisable). */
    public void shutdown() {
        if (followTask != null) {
            followTask.cancel();
            followTask = null;
        }
        displays.values().forEach(TextDisplay::remove);
        displays.clear();
    }

    private TextDisplay spawn(Player player) {
        Location location = player.getLocation().add(0, height, 0);
        return player.getWorld().spawn(location, TextDisplay.class, display -> {
            display.setBillboard(Billboard.CENTER);
            display.setAlignment(TextAlignment.CENTER);
            display.setShadowed(false);
            display.setSeeThrough(true);
            display.setBackgroundColor(Color.fromARGB(120, 0, 0, 0));
            display.setViewRange(2.0f);
            display.setPersistent(false); // no guardar en el mundo (evita duplicados al reiniciar)
            display.setTransformation(new Transformation(
                    new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
        });
    }

    /** Mueve cada holograma a la posición actual de su jugador (también cruza de mundo). */
    private void followPlayers() {
        displays.forEach((uuid, display) -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline() || !display.isValid()) {
                remove(uuid);
                return;
            }
            display.teleport(player.getLocation().add(0, height, 0));
        });
    }
}
