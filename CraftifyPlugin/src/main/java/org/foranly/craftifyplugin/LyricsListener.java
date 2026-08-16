package org.foranly.craftifyplugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.foranly.craftifyplugin.hologram.LyricsHologramManager;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Receives the {@code craftify:lyricsline} channel (PROTOCOL.md §6): the current lyric
 * line the player shares with others (empty line = clear the hologram).
 *
 * <p>The listener runs on the netty thread, so the hologram update (entity operations) is
 * scheduled on the main server thread.
 */
public final class LyricsListener implements PluginMessageListener {

    private final Plugin plugin;
    private final LyricsHologramManager lyricsHolograms;
    private final Logger logger;

    public LyricsListener(Plugin plugin, LyricsHologramManager lyricsHolograms, Logger logger) {
        this.plugin = plugin;
        this.lyricsHolograms = lyricsHolograms;
        this.logger = logger;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CraftifyPlugin.LYRICS_CHANNEL.equals(channel)) {
            return;
        }

        String line;
        try {
            int[] offset = {0};
            int length = readVarInt(message, offset);
            if (length < 0 || offset[0] + length > message.length) {
                throw new IllegalArgumentException("length " + length + " out of range");
            }
            String json = new String(message, offset[0], length, StandardCharsets.UTF_8);
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            line = object.has("line") ? object.get("line").getAsString() : "";
        } catch (RuntimeException e) {
            logger.warning("Invalid craftify:lyricsline payload from " + player.getName() + ": " + e.getMessage());
            return;
        }

        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online == null || !online.isOnline()) {
                return;
            }
            lyricsHolograms.update(online, line);
        });
    }

    /**
     * Minecraft VarInt: 7 bits per group, the high bit (0x80) means there are more bytes
     * (PROTOCOL.md §3.3).
     */
    private static int readVarInt(byte[] buf, int[] offset) {
        int value = 0;
        int shift = 0;
        while (offset[0] < buf.length) {
            byte b = buf[offset[0]++];
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            if (shift >= 28) {
                throw new IllegalArgumentException("VarInt too long");
            }
            shift += 7;
        }
        throw new IllegalArgumentException("Unterminated VarInt");
    }
}
