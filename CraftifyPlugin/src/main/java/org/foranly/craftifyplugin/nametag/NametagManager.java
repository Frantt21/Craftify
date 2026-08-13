package org.foranly.craftifyplugin.nametag;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.foranly.craftifyplugin.PlayerSpotifyState;

/**
 * Shows the song title in the player's **floating name** (the nametag Minecraft renders
 * above the head), instead of a hologram entity.
 *
 * <p>Advantage: the nametag is part of the player's entity, so it follows their movements
 * without lag (unlike the {@code TextDisplay} hologram, which teleports every tick). It is
 * configured in {@code config.yml} (section {@code nametag}).
 *
 * <p>Note: the owner of the name only sees it in third person (F5) if the client mod
 * includes the corresponding mixin — by default Minecraft does not show your own name.
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
     * Updates the player's nametag according to their Spotify state: with {@code playing}
     * and a title, shows name + title; in any other state restores the player's normal name.
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

    /** Restores the player's nametag to their normal name (e.g. on disconnect/disable). */
    public void reset(Player player) {
        if (!enabled) {
            return;
        }
        player.customName(Component.text(player.getName()));
        player.setCustomNameVisible(true);
    }
}
