package org.foranly.craftifyplugin.display;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shows the shared lyric line in the vanilla {@code BELOW_NAME} scoreboard slot: the line
 * is the objective's display name and each sharing player gets a numeric score (vanilla
 * requires a number there, so it is set to the configured value — 0, 1 or random).
 *
 * <p>The line is part of the player's name tag (no separate entity), so it follows the
 * player perfectly with zero lag. Unlike the hologram, the {@code BELOW_NAME} text is the
 * same for every observer, so when several players share at once only the latest line is
 * shown (under each sharing player's name). Configurable in {@code config.yml} (section
 * {@code lyrics-display}). Implements {@link LyricsDisplay}.
 */
public final class LyricsBelowNameManager implements LyricsDisplay {

    /** Vanilla limits objective names to 16 chars. */
    private static final String OBJECTIVE_NAME = "craftify_lyr";
    private static final String SLOT_DISPLAY_NAME = "Craftify";

    private final boolean enabled;
    /**
     * Score mode: {@code -2} = use the line number the mod sends, {@code -1} = random per
     * player, otherwise the fixed configured value.
     */
    private final int score;

    private final Map<UUID, String> entries = new ConcurrentHashMap<>();
    private Objective objective;

    public LyricsBelowNameManager(Plugin plugin) {
        this.enabled = plugin.getConfig().getBoolean("lyrics-display.enabled", true);
        String scoreConfig = plugin.getConfig().getString("lyrics-display.number", "line");
        if ("random".equalsIgnoreCase(scoreConfig)) {
            this.score = -1;
        } else if ("line".equalsIgnoreCase(scoreConfig)) {
            this.score = -2;
        } else {
            this.score = parseInt(scoreConfig, 0);
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void update(Player player, String line, int number) {
        if (!enabled) {
            return;
        }
        Objective objective = getObjective();
        if (objective == null) {
            return;
        }
        if (line == null || line.isEmpty()) {
            clearScore(player.getUniqueId());
            entries.remove(player.getUniqueId());
            return;
        }
        // Vanilla renders the objective's display name + the numeric score below the name.
        objective.displayName(Component.text(line, NamedTextColor.WHITE));
        // "line" mode: the number changes with the lyric line (1, 2, 3...) the mod sends.
        int value;
        if (score == -2) {
            value = number > 0 ? number : 1;
        } else if (score == -1) {
            value = ThreadLocalRandom.current().nextInt(0, 100);
        } else {
            value = score;
        }
        objective.getScore(player.getName()).setScore(value);
        entries.put(player.getUniqueId(), line);
    }

    @Override
    public void remove(UUID player) {
        clearScore(player);
        entries.remove(player);
    }

    @Override
    public void shutdown() {
        entries.clear();
        if (objective != null) {
            objective.unregister();
            objective = null;
        }
    }

    /** Creates the objective lazily on the main scoreboard. */
    private Objective getObjective() {
        if (objective != null && objective.getScoreboard() != null) {
            return objective;
        }
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        objective = scoreboard.getObjective(OBJECTIVE_NAME);
        if (objective == null) {
            try {
                objective = scoreboard.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY,
                        Component.text(SLOT_DISPLAY_NAME, NamedTextColor.WHITE));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        objective.setDisplaySlot(DisplaySlot.BELOW_NAME);
        return objective;
    }

    private void clearScore(UUID player) {
        Player online = Bukkit.getPlayer(player);
        if (online == null || objective == null) {
            return;
        }
        org.bukkit.scoreboard.Score score = objective.getScore(online.getName());
        if (score.getScore() != 0 || entries.containsKey(player)) {
            score.resetScore();
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
