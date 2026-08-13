package org.foranly.craftifyplugin.nametag;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.foranly.craftifyplugin.PlayerSpotifyState;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Shows the song title in the player's floating nametag using **scoreboard teams**
 * (prefix/suffix), instead of a hologram entity.
 *
 * <p>Why teams and not {@code customName}: in this Minecraft version,
 * {@code Entity.getDisplayName()} renders {@code teamPrefix + name + teamSuffix} and
 * ignores the custom name for players (that's why every nametag plugin uses teams).
 * Name tags also render on a single line here (the label is drawn with
 * {@code Font.prepareText} without splitting on newlines).
 *
 * <p>The team only changes the nametag above the head — the tab list is untouched.
 * Configured in {@code config.yml} (section {@code nametag}: {@code prefix} / {@code suffix}).
 *
 * <p>Note: the owner of the name only sees it in third person (F5) if the client mod
 * includes the corresponding mixin — by default Minecraft does not show your own name.
 */
public final class NametagManager {

    private static final String DEFAULT_PREFIX = "";
    private static final String DEFAULT_SUFFIX = " <green>♪ </green><white>{track}</white>";
    /** Short prefix for our team names (vanilla limit is 16 chars). */
    private static final String TEAM_NAME_PREFIX = "cft-";

    private final boolean enabled;
    private final String prefixFormat;
    private final String suffixFormat;
    private final Logger logger;

    /** Team name and scoreboard entry per player, for cleanup on quit/reload. */
    private final Map<UUID, String> teamNames = new ConcurrentHashMap<>();
    private int teamCounter;

    public NametagManager(Plugin plugin) {
        this.enabled = plugin.getConfig().getBoolean("nametag.enabled", true);
        this.prefixFormat = plugin.getConfig().getString("nametag.prefix", DEFAULT_PREFIX);
        this.suffixFormat = plugin.getConfig().getString("nametag.suffix", DEFAULT_SUFFIX);
        this.logger = plugin.getLogger();
    }

    /** Whether the nametag mode is enabled in the config. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Updates the player's nametag according to their Spotify state: with {@code playing}
     * and a title, sets the team prefix/suffix (name + song); in any other state clears them.
     */
    public void update(Player player, PlayerSpotifyState state) {
        if (!enabled) {
            return;
        }
        if (state.isPlaying() && !state.track().isEmpty()) {
            Team team = teamFor(player);
            if (team == null) {
                return;
            }
            team.prefix(render(prefixFormat, player, state));
            team.suffix(render(suffixFormat, player, state));
            logger.fine("Nametag applied for " + player.getName() + ": " + state.track());
        } else {
            clear(player);
        }
    }

    /** Restores the player's nametag to their normal name. */
    public void reset(Player player) {
        clear(player);
    }

    /** Unregisters the player's team (e.g. on disconnect). */
    public void remove(UUID player) {
        String teamName = teamNames.remove(player);
        if (teamName == null) {
            return;
        }
        Team team = mainScoreboard().getTeam(teamName);
        if (team != null) {
            team.unregister();
        }
        logger.fine("Nametag team removed for " + player);
    }

    /** Unregisters every team this manager created (on disable/reload). */
    public void shutdown() {
        Scoreboard board = mainScoreboard();
        teamNames.values().forEach(name -> {
            Team team = board.getTeam(name);
            if (team != null) {
                team.unregister();
            }
        });
        teamNames.clear();
    }

    private void clear(Player player) {
        String teamName = teamNames.get(player.getUniqueId());
        if (teamName != null) {
            Team team = mainScoreboard().getTeam(teamName);
            if (team != null) {
                team.prefix(Component.empty());
                team.suffix(Component.empty());
            }
        }
        logger.fine("Nametag reset for " + player.getName());
    }

    /** Gets (or creates) the player's team, with the player's name as an entry. */
    private Team teamFor(Player player) {
        Scoreboard board = mainScoreboard();
        String teamName = teamNames.computeIfAbsent(player.getUniqueId(), uuid -> {
            String name;
            do {
                name = TEAM_NAME_PREFIX + (teamCounter++);
            } while (board.getTeam(name) != null);
            return name;
        });
        Team team = board.getTeam(teamName);
        if (team == null) {
            team = board.registerNewTeam(teamName);
        }
        team.addEntry(player.getName());
        return team;
    }

    private Component render(String format, Player player, PlayerSpotifyState state) {
        MiniMessage miniMessage = MiniMessage.miniMessage();
        String text = format
                .replace("{track}", miniMessage.escapeTags(state.track()))
                .replace("{name}", miniMessage.escapeTags(player.getName()));
        return miniMessage.deserialize(text);
    }

    private Scoreboard mainScoreboard() {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        if (board == null) {
            throw new IllegalStateException("No main scoreboard available");
        }
        return board;
    }
}
