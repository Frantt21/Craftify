package org.foranly.craftifyplugin;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.foranly.craftifyplugin.hologram.HologramManager;
import org.foranly.craftifyplugin.nametag.NametagManager;

import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Recibe el canal {@code craftify:title} (PROTOCOL.md §3.2).
 *
 * <p>Payload on-wire: {@code [VarInt longitud][bytes UTF-8 del JSON]}
 * (PROTOCOL.md §2.1). Se decodifica a mano para no depender del mod.
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
                throw new IllegalArgumentException("longitud " + length + " fuera de rango");
            }
            json = new String(message, offset[0], length, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            logger.warning("Payload craftify:title inválido de " + player.getName() + ": " + e.getMessage());
            return;
        }

        PlayerSpotifyState state = PlayerSpotifyState.fromJson(json);
        if (state == null) {
            logger.warning("JSON craftify:title inválido de " + player.getName() + ": " + json);
            return;
        }

        stateManager.update(player.getUniqueId(), state);
        nametags.update(player, state);
        holograms.update(player, state);
        logger.fine(player.getName() + " → " + state);
    }

    /**
     * VarInt de Minecraft: 7 bits por grupo, el bit alto (0x80) indica que hay más bytes
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
                throw new IllegalArgumentException("VarInt demasiado largo");
            }
            shift += 7;
        }
        throw new IllegalArgumentException("VarInt sin terminar");
    }
}
