package org.foranly.craftify.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.foranly.craftify.client.spotify.SpotifyProcess;
import org.foranly.craftify.client.spotify.SpotifyTracker;

/**
 * {@code /craftify spotify} command: verifies that the mod correctly detects the Spotify
 * process and reads its playback state and current song on the current operating system.
 */
public final class SpotifyCommand {

    private SpotifyCommand() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal("craftify")
                .then(ClientCommands.literal("spotify")
                        .executes(SpotifyCommand::executeSpotify))
                .then(ClientCommands.literal("send")
                        .then(ClientCommands.literal("on")
                                .executes(ctx -> setSending(ctx, false)))
                        .then(ClientCommands.literal("off")
                                .executes(ctx -> setSending(ctx, true)))
                        .then(ClientCommands.literal("toggle")
                                .executes(ctx -> setSending(ctx, !SpotifyTracker.isPaused())))));
    }

    private static int setSending(CommandContext<FabricClientCommandSource> context, boolean paused) {
        SpotifyTracker.setPaused(paused);
        FabricClientCommandSource source = context.getSource();
        if (paused) {
            source.sendFeedback(Component.literal("[Craftify] Packet sending paused.")
                    .withStyle(ChatFormatting.YELLOW));
            source.sendFeedback(Component.literal("[Craftify] Use /craftify send on to resume.")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            source.sendFeedback(Component.literal("[Craftify] Packet sending resumed.")
                    .withStyle(ChatFormatting.GREEN));
            source.sendFeedback(Component.literal("[Craftify] The current Spotify state will be sent on the next read.")
                    .withStyle(ChatFormatting.GRAY));
        }
        return 1;
    }

    private static int executeSpotify(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        SpotifyProcess.Os os = SpotifyProcess.currentOs();

        source.sendFeedback(Component.literal("[Craftify] Operating system: ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(os.name()).withStyle(ChatFormatting.YELLOW)));

        if (os == SpotifyProcess.Os.UNSUPPORTED) {
            source.sendError(Component.literal("[Craftify] This operating system is not supported.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        SpotifyProcess.Snapshot snapshot = SpotifyProcess.readSnapshot(os);
        source.sendFeedback(Component.literal("[Craftify] Spotify process (" + os.executable() + "): ")
                .withStyle(ChatFormatting.GOLD)
                .append(snapshot.running()
                        ? Component.literal("running").withStyle(ChatFormatting.GREEN)
                        : Component.literal("not found").withStyle(ChatFormatting.RED)));

        switch (snapshot.status()) {
            case PLAYING -> {
                source.sendFeedback(Component.literal("[Craftify] State: ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("playing").withStyle(ChatFormatting.GREEN)));
                source.sendFeedback(Component.literal("[Craftify] Current title: ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(snapshot.title()).withStyle(ChatFormatting.WHITE)));
            }
            case PAUSED -> source.sendFeedback(Component.literal("[Craftify] State: ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("paused").withStyle(ChatFormatting.YELLOW)));
            case UNKNOWN -> {
                source.sendFeedback(Component.literal("[Craftify] State: ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("no active song (no_track)").withStyle(ChatFormatting.YELLOW)));
                sendNoTrackHint(source, os);
            }
            default -> source.sendFeedback(Component.literal("[Craftify] State: ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("Spotify closed (closed)").withStyle(ChatFormatting.RED)));
        }

        source.sendFeedback(Component.literal("[Craftify] Packet sending: ")
                .withStyle(ChatFormatting.GOLD)
                .append(SpotifyTracker.isRunning()
                        ? (SpotifyTracker.isPaused()
                                ? Component.literal("paused (use /craftify send on)").withStyle(ChatFormatting.YELLOW)
                                : Component.literal("active (real-time song change detection)").withStyle(ChatFormatting.GREEN))
                        : Component.literal("inactive (only sends while in a world)").withStyle(ChatFormatting.GRAY)));
        return 1;
    }

    /** Suggests what to do when the state could not be determined, per operating system. */
    private static void sendNoTrackHint(FabricClientCommandSource source, SpotifyProcess.Os os) {
        Component hint = switch (os) {
            case MACOS -> Component.literal("[Craftify] To read the track: accept the \"control Spotify\" "
                    + "prompt when it appears (once).")
                    .withStyle(ChatFormatting.GRAY);
            case LINUX -> Component.literal("[Craftify] No title: the mod bundles playerctl; as a backup "
                    + "install xdotool (e.g. sudo apt install xdotool) or the system playerctl.")
                    .withStyle(ChatFormatting.GRAY);
            default -> null;
        };
        if (hint != null) {
            source.sendFeedback(hint);
        }
    }
}
