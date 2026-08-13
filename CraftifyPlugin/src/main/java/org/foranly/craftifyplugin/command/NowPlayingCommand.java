package org.foranly.craftifyplugin.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.foranly.craftifyplugin.PlayerSpotifyState;
import org.foranly.craftifyplugin.SpotifyStateManager;

/**
 * {@code /nowplaying} — shows the player's Spotify state (PROTOCOL.md §4.4).
 */
public final class NowPlayingCommand implements CommandExecutor {

    private final SpotifyStateManager stateManager;

    public NowPlayingCommand(SpotifyStateManager stateManager) {
        this.stateManager = stateManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }

        PlayerSpotifyState state = stateManager.get(player.getUniqueId()).orElse(null);
        if (state == null) {
            player.sendMessage(Component.text(
                    "No Spotify data yet. Do you have the Craftify mod installed on the client?",
                    NamedTextColor.GRAY));
            return true;
        }

        switch (state.state()) {
            case PlayerSpotifyState.STATE_PLAYING -> player.sendMessage(
                    Component.text("Listening to: ", NamedTextColor.GREEN)
                            .append(Component.text(state.track(), NamedTextColor.WHITE)));
            case PlayerSpotifyState.STATE_NO_TRACK -> player.sendMessage(
                    Component.text("Spotify open, no active song.", NamedTextColor.YELLOW));
            case PlayerSpotifyState.STATE_CLOSED -> player.sendMessage(
                    Component.text("Spotify closed.", NamedTextColor.RED));
            default -> player.sendMessage(
                    Component.text("Unknown state: " + state.state(), NamedTextColor.GRAY));
        }
        return true;
    }
}
