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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hologram with the current lyric line the player chooses to share (PROTOCOL.md §6).
 *
 * <p>The mod only sends {@code craftify:lyricsline} while the player has "share lyrics"
 * enabled, and only when the line changes: an empty line clears the hologram and a pause
 * sends nothing (so the last line stays frozen). Uses a {@link TextDisplay} that follows
 * the player, like the title hologram. Configurable in {@code config.yml} (section
 * {@code lyrics-hologram}).
 */
public final class LyricsHologramManager {

    private final Plugin plugin;
    private final boolean enabled;
    private final double height;
    private final float scale;

    private final Map<UUID, TextDisplay> displays = new ConcurrentHashMap<>();
    private BukkitTask followTask;

    public LyricsHologramManager(Plugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("lyrics-hologram.enabled", true);
        this.height = plugin.getConfig().getDouble("lyrics-hologram.height", 1.85);
        this.scale = (float) plugin.getConfig().getDouble("lyrics-hologram.scale", 0.6);
    }

    /** Whether the lyrics hologram mode is enabled in the config. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Starts the task that keeps the holograms above their players. */
    public void start() {
        if (!enabled) {
            return;
        }
        followTask = Bukkit.getScheduler().runTaskTimer(plugin, this::followPlayers, 1L, 1L);
    }

    /**
     * Updates the player's lyric hologram; an empty line removes it.
     */
    public void update(Player player, String line) {
        if (!enabled) {
            return;
        }
        if (line == null || line.isEmpty()) {
            remove(player.getUniqueId());
            return;
        }
        TextDisplay display = displays.computeIfAbsent(player.getUniqueId(), id -> spawn(player));
        if (display != null) {
            display.text(Component.text(line, NamedTextColor.WHITE));
        }
    }

    /** Removes a player's hologram (e.g. on disconnect). */
    public void remove(UUID player) {
        TextDisplay display = displays.remove(player);
        if (display != null) {
            display.remove();
        }
    }

    /** Cancels the follow task and removes all holograms (onDisable). */
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
            display.setPersistent(false); // do not save to the world (avoids duplicates on restart)
            display.setTransformation(new Transformation(
                    new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
        });
    }

    /** Moves each hologram to its player's current position (also crosses worlds). */
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
