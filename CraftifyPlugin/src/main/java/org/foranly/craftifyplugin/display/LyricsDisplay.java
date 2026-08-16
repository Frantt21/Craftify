package org.foranly.craftifyplugin.display;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Common contract for the two ways of showing the shared lyric line: the vanilla
 * scoreboard {@code BELOW_NAME} slot ({@link LyricsBelowNameManager}) or the legacy
 * {@code TextDisplay} hologram. The {@code craftify:lyricsline} listener talks to this
 * interface, so the mode is decided by the config, not by the protocol.
 */
public interface LyricsDisplay {

    /** Whether this mode is enabled in the config. */
    boolean isEnabled();

    /**
     * Shows the line under/above the player; an empty line clears it. {@code number} is
     * the 1-based line number the mod sends (useful for the BELOW_NAME slot), or -1 when
     * the line is empty or not provided.
     */
    void update(Player player, String line, int number);

    /** Removes a player's entry (e.g. on disconnect). */
    void remove(UUID player);

    /** Cleans up everything (onDisable / reload). */
    void shutdown();
}
