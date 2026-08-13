package org.foranly.craftifyplugin;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.foranly.craftifyplugin.hologram.HologramManager;
import org.foranly.craftifyplugin.nametag.NametagManager;

import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Receives the {@code craftify:title} channel (PROTOCOL.md §3.2).
 *
 * <p>On-wire payload: {@code [VarInt length][UTF-8 bytes of the JSON]}
 * (PROTOCOL.md §2.1). It is decoded manually so it does not depend on the mod.
 */
public final class SpotifyListener implements PluginMessageListener {

    private final SpotifyStateManager stateManager;
    private final HologramManager holograms;
    private final NametagManager nametags;
    private final Logger logger;

    public SpotifyListener(SpotifyStateManager stateManager, HologramManager holograms,
                           NametagManager nametags, Logger logger) {
        this.stateManager = stateManager;
        this.holograms = holograms;
        this.nametags = nametags;
        this.logger = logger;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CraftifyPlugin.CHANNEL.equals(channel)) {
            return;
        }

        String json;
        try {
            int[] offset = {0};
            int length = readVarInt(message, offset);
            if (length < 0 || offset[0] + length > message.length) {
                throw new IllegalArgumentException("length " + length + " out of range");
            }
            json = new String(message, offset[0], length, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            logger.warning("Invalid craftify:title payload from " + player.getName() + ": " + e.getMessage());
            return;
        }

        PlayerSpotifyState state = PlayerSpotifyState.fromJson(json);
        if (state == null) {
            logger.warning("Invalid craftify:title JSON from " + player.getName() + ": " + json);
            return;
        }

        stateManager.update(player.getUniqueId(), state);
        nametags.update(player, state);
        holograms.update(player, state);
        logger.fine(player.getName() + " → " + state);
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
