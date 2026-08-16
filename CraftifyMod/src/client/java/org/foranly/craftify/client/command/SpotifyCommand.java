package org.foranly.craftify.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.foranly.craftify.client.gui.CraftifyMenuScreen;
import org.foranly.craftify.client.gui.LyricsOptionsScreen;
import org.foranly.craftify.client.lyrics.LrclibClient;
import org.foranly.craftify.client.lyrics.LrclibClient.SearchCandidate;
import org.foranly.craftify.client.lyrics.LrclibClient.SearchOutcome;
import org.foranly.craftify.client.lyrics.LyricsManager;
import org.foranly.craftify.client.lyrics.LyricsPosition;
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
                .then(ClientCommands.literal("menu")
                        .executes(ctx -> {
                            Minecraft.getInstance().gui.setScreen(new CraftifyMenuScreen());
                            return 1;
                        }))
                .then(ClientCommands.literal("spotify")
                        .executes(SpotifyCommand::executeSpotify))
                .then(ClientCommands.literal("send")
                        .then(ClientCommands.literal("on")
                                .executes(ctx -> setSending(ctx, false)))
                        .then(ClientCommands.literal("off")
                                .executes(ctx -> setSending(ctx, true)))
                        .then(ClientCommands.literal("toggle")
                                .executes(ctx -> setSending(ctx, !SpotifyTracker.isPaused()))))
                .then(ClientCommands.literal("lyrics")
                        .executes(ctx -> openLyricsScreen(ctx))
                        .then(ClientCommands.literal("on")
                                .executes(ctx -> setLyrics(ctx, true)))
                        .then(ClientCommands.literal("off")
                                .executes(ctx -> setLyrics(ctx, false)))
                        .then(ClientCommands.literal("toggle")
                                .executes(ctx -> setLyrics(ctx, !LyricsManager.instance().isEnabled())))
                        .then(ClientCommands.literal("share")
                                .then(ClientCommands.literal("on")
                                        .executes(ctx -> setShared(ctx, true)))
                                .then(ClientCommands.literal("off")
                                        .executes(ctx -> setShared(ctx, false)))
                                .then(ClientCommands.literal("toggle")
                                        .executes(ctx -> setShared(ctx, !LyricsManager.instance().isShared()))))
                        .then(ClientCommands.literal("search")
                                .executes(ctx -> searchLyrics(ctx, null))
                                .then(ClientCommands.argument("query", StringArgumentType.greedyString())
                                        .executes(ctx -> searchLyrics(ctx,
                                                StringArgumentType.getString(ctx, "query")))))
                        .then(ClientCommands.literal("position")
                                .then(ClientCommands.argument("pos", StringArgumentType.word())
                                        .executes(ctx -> setPosition(ctx,
                                                StringArgumentType.getString(ctx, "pos")))))));
    }

    private static int setPosition(CommandContext<FabricClientCommandSource> context, String id) {
        FabricClientCommandSource source = context.getSource();
        try {
            LyricsPosition position = LyricsPosition.fromId(id);
            LyricsManager.instance().setPosition(position);
            source.sendFeedback(Component.literal("[Craftify] Lyrics overlay position: ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(position.displayName()).withStyle(ChatFormatting.WHITE)));
        } catch (IllegalArgumentException e) {
            source.sendError(Component.literal("[Craftify] Unknown position \"" + id + "\". "
                    + "Valid: topleft, topcenter, topright, middleleft, middlecenter, "
                    + "middleright, bottomleft, bottomcenter, bottomright")
                    .withStyle(ChatFormatting.RED));
        }
        return 1;
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

    /** Shows the lyrics overlay state (enabled, share, position, track, fetch result). */
    private static void sendLyricsStatus(Consumer<Component> feedback) {
        LyricsManager lyrics = LyricsManager.instance();
        feedback.accept(Component.literal("[Craftify] Lyrics: ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(lyrics.isEnabled() ? "enabled (LRCLib)" : "disabled (/craftify lyrics on)")
                        .withStyle(lyrics.isEnabled() ? ChatFormatting.GREEN : ChatFormatting.GRAY)));
        feedback.accept(Component.literal("[Craftify] Lyrics share: ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(lyrics.isShared()
                                ? "shared with others (hologram)"
                                : "only you (F10 or /craftify lyrics)")
                        .withStyle(lyrics.isShared() ? ChatFormatting.GREEN : ChatFormatting.GRAY)));
        feedback.accept(Component.literal("[Craftify] Lyrics position: ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(lyrics.position().displayName())
                        .withStyle(ChatFormatting.WHITE)));

        String track = lyrics.currentTrack();
        String status = lyrics.fetchStatus();
        if (track == null) {
            feedback.accept(Component.literal("[Craftify] Lyrics track: ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("waiting for a playing song").withStyle(ChatFormatting.GRAY)));
            return;
        }
        feedback.accept(Component.literal("[Craftify] Lyrics track: ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(track).withStyle(ChatFormatting.WHITE)));

        String fetchText = switch (status) {
            case "loading" -> "loading lyrics...";
            case "loaded" -> lyrics.linesLoaded() + " synced line(s) loaded";
            case "instrumental" -> "instrumental (no lyrics)";
            case "not_found" -> "no synced lyrics found (LRCLib)";
            default -> "no lyrics loaded";
        };
        feedback.accept(Component.literal("[Craftify] Lyrics state: ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(fetchText)
                        .withStyle("loaded".equals(status) ? ChatFormatting.GREEN : ChatFormatting.YELLOW)));

        if (!lyrics.lastDetail().isEmpty()) {
            feedback.accept(Component.literal("[Craftify] Lyrics detail: ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(lyrics.lastDetail()).withStyle(ChatFormatting.GRAY)));
        }

        // If the overlay never renders, the problem is on the HUD side, not the data.
        feedback.accept(Component.literal("[Craftify] Lyrics overlay: ")
                .withStyle(ChatFormatting.GOLD)
                .append(lyrics.renderCount() > 0
                        ? Component.literal("rendering (" + lyrics.renderCount() + " frames)").withStyle(ChatFormatting.GREEN)
                        : Component.literal("not rendering (HUD element never called)").withStyle(ChatFormatting.RED)));
    }

    /**
     * {@code /craftify lyrics search [query]} — searches LRCLib and lists the candidates
     * so a miss can be debugged (what the API returns vs what the auto-match expected).
     * Without arguments it uses the currently detected song.
     */
    private static int searchLyrics(CommandContext<FabricClientCommandSource> context, String query) {
        FabricClientCommandSource source = context.getSource();
        String finalQuery = query;
        if (finalQuery == null || finalQuery.isBlank()) {
            String track = LyricsManager.instance().currentTrack();
            if (track == null) {
                source.sendError(Component.literal("[Craftify] No song detected and no query given. "
                        + "Usage: /craftify lyrics search <song artist>").withStyle(ChatFormatting.RED));
                return 0;
            }
            finalQuery = track.replace(" - ", " ");
        }

        String usedQuery = finalQuery;
        source.sendFeedback(Component.literal("[Craftify] Searching LRCLib for: ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(usedQuery).withStyle(ChatFormatting.WHITE)));

        LrclibClient.searchAsync(usedQuery).whenComplete((outcome, error) ->
                Minecraft.getInstance().execute(() -> showSearchResults(source, usedQuery, outcome, error)));
        return 1;
    }

    private static void showSearchResults(FabricClientCommandSource source, String query,
                                          SearchOutcome outcome, Throwable error) {
        if (error != null) {
            source.sendError(Component.literal("[Craftify] Search failed: " + error.getMessage())
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (outcome.error() != null) {
            source.sendError(Component.literal("[Craftify] Search error: " + outcome.error())
                    .withStyle(ChatFormatting.RED));
            return;
        }
        List<SearchCandidate> candidates = outcome.candidates();
        if (candidates.isEmpty()) {
            source.sendError(Component.literal("[Craftify] No results for: " + query).withStyle(ChatFormatting.RED));
            return;
        }
        source.sendFeedback(Component.literal("[Craftify] " + candidates.size() + " result(s):")
                .withStyle(ChatFormatting.GOLD));
        int shown = Math.min(candidates.size(), 8);
        for (int i = 0; i < shown; i++) {
            SearchCandidate candidate = candidates.get(i);
            String tags = candidate.instrumental()
                    ? "[instrumental]"
                    : candidate.hasSyncedLyrics()
                            ? "[synced " + formatDuration(candidate.durationSeconds()) + "]"
                            : candidate.hasPlainLyrics()
                                    ? "[plain only]"
                                    : "[]";
            source.sendFeedback(Component.literal("[Craftify]   " + (i + 1) + ". "
                    + candidate.trackName() + " - " + candidate.artistName() + " " + tags)
                    .withStyle(candidate.hasSyncedLyrics() ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        }
        if (candidates.size() > shown) {
            source.sendFeedback(Component.literal("[Craftify]   ... and " + (candidates.size() - shown) + " more")
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    private static String formatDuration(int seconds) {
        return (seconds / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60);
    }

    private static int openLyricsScreen(CommandContext<FabricClientCommandSource> context) {
        Minecraft.getInstance().gui.setScreen(new LyricsOptionsScreen());
        return 1;
    }

    private static int setShared(CommandContext<FabricClientCommandSource> context, boolean shared) {
        LyricsManager.instance().setShared(shared);
        context.getSource().sendFeedback(Component.literal("[Craftify] Sharing lyrics with others "
                + (shared ? "enabled (server hologram)." : "disabled (only you)."))
                .withStyle(shared ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        return 1;
    }

    private static int setLyrics(CommandContext<FabricClientCommandSource> context, boolean enabled) {
        LyricsManager.instance().setEnabled(enabled);
        FabricClientCommandSource source = context.getSource();
        source.sendFeedback(Component.literal("[Craftify] Lyrics overlay "
                + (enabled ? "enabled (LRCLib)." : "disabled."))
                .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        return 1;
    }

    private static int executeSpotify(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        printSpotifyStatus(source::sendFeedback, source::sendError);
        return 1;
    }

    /**
     * Prints the full Spotify status (OS, process, state, title, lyrics and sending state)
     * through the given feedback/error consumers, so both the command and the F10 menu can
     * reuse it.
     */
    public static void printSpotifyStatus(Consumer<Component> feedback, Consumer<Component> error) {
        SpotifyProcess.Os os = SpotifyProcess.currentOs();

        feedback.accept(Component.literal("[Craftify] Operating system: ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(os.name()).withStyle(ChatFormatting.YELLOW)));

        if (os == SpotifyProcess.Os.UNSUPPORTED) {
            error.accept(Component.literal("[Craftify] This operating system is not supported.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        SpotifyProcess.Snapshot snapshot = SpotifyProcess.readSnapshot(os);
        feedback.accept(Component.literal("[Craftify] Spotify process (" + os.executable() + "): ")
                .withStyle(ChatFormatting.GOLD)
                .append(snapshot.running()
                        ? Component.literal("running").withStyle(ChatFormatting.GREEN)
                        : Component.literal("not found").withStyle(ChatFormatting.RED)));

        switch (snapshot.status()) {
            case PLAYING -> {
                feedback.accept(Component.literal("[Craftify] State: ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("playing").withStyle(ChatFormatting.GREEN)));
                feedback.accept(Component.literal("[Craftify] Current title: ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(snapshot.title()).withStyle(ChatFormatting.WHITE)));
            }
            case PAUSED -> feedback.accept(Component.literal("[Craftify] State: ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("paused").withStyle(ChatFormatting.YELLOW)));
            case UNKNOWN -> {
                feedback.accept(Component.literal("[Craftify] State: ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("no active song (no_track)").withStyle(ChatFormatting.YELLOW)));
                sendNoTrackHint(feedback, os);
            }
            default -> feedback.accept(Component.literal("[Craftify] State: ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("Spotify closed (closed)").withStyle(ChatFormatting.RED)));
        }

        sendLyricsStatus(feedback);

        feedback.accept(Component.literal("[Craftify] Packet sending: ")
                .withStyle(ChatFormatting.GOLD)
                .append(SpotifyTracker.isRunning()
                        ? (SpotifyTracker.isPaused()
                                ? Component.literal("paused (use /craftify send on)").withStyle(ChatFormatting.YELLOW)
                                : Component.literal("active (real-time song change detection)").withStyle(ChatFormatting.GREEN))
                        : Component.literal("inactive (only sends while in a world)").withStyle(ChatFormatting.GRAY)));
    }

    /** Suggests what to do when the state could not be determined, per operating system. */
    private static void sendNoTrackHint(Consumer<Component> feedback, SpotifyProcess.Os os) {
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
            feedback.accept(hint);
        }
    }
}
