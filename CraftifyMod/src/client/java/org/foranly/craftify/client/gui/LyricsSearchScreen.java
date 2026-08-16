package org.foranly.craftify.client.gui;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.foranly.craftify.client.lyrics.LrclibClient;
import org.foranly.craftify.client.lyrics.LrclibClient.SearchCandidate;
import org.foranly.craftify.client.lyrics.LrclibClient.SearchOutcome;
import org.foranly.craftify.client.lyrics.LyricsManager;

/**
 * Lyrics search submenu (F10 -> "Lyrics search" or {@code /craftify lyrics search}): a
 * search box against LRCLib and the candidates as clickable buttons. Clicking a candidate
 * prints its details to the chat (same info the {@code /craftify lyrics search} command
 * shows), for debugging why the auto-match may miss.
 */
public final class LyricsSearchScreen extends Screen {

    private static final int MAX_RESULTS = 8;

    private EditBox searchBox;
    /** Query preserved across rebuilds (async results arrive after init()). */
    private String lastQuery = "";
    private List<SearchCandidate> candidates = List.of();
    private String error;
    private boolean searching;

    public LyricsSearchScreen() {
        super(Component.literal("Craftify - Lyrics search"));
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 - 60;

        // Prefill with the currently detected song when there is no query yet.
        if (lastQuery.isEmpty()) {
            String track = LyricsManager.instance().currentTrack();
            if (track != null && !track.isBlank()) {
                lastQuery = track;
            }
        }

        this.searchBox = new EditBox(Minecraft.getInstance().font, centerX - 100, y, 154, 20,
                Component.literal("Song or artist..."));
        this.searchBox.setMaxLength(120);
        this.searchBox.setValue(lastQuery);
        this.searchBox.setResponder(value -> lastQuery = value);
        this.searchBox.setCanLoseFocus(false);
        this.addRenderableWidget(this.searchBox);

        Button searchButton = Button.builder(Component.literal(searching ? "..." : "Search"),
                        button -> runSearch())
                .bounds(centerX + 58, y, 42, 20)
                .tooltip(Tooltip.create(Component.literal("Search LRCLib (async)")))
                .build();
        this.addRenderableWidget(searchButton);

        int resultY = y + 30;
        int rows = 0;
        if (error != null) {
            // Real failures (network, rate limit) are red; "no results" is informational.
            boolean failure = error.contains("failed") || error.contains("429")
                    || error.contains("empty query");
            ChatFormatting color = failure ? ChatFormatting.RED : ChatFormatting.GRAY;
            this.addRenderableWidget(Button.builder(Component.literal(shorten(error))
                            .withStyle(color), button -> {
                    })
                    .bounds(centerX - 100, resultY, 200, 20)
                    .build());
            rows = 1;
        } else {
            int shown = Math.min(candidates.size(), MAX_RESULTS);
            for (int i = 0; i < shown; i++) {
                SearchCandidate candidate = candidates.get(i);
                Button resultButton = Button.builder(
                                Component.literal((i + 1) + ". " + shorten(candidateLabel(candidate))),
                                button -> printCandidateDetail(candidate))
                        .bounds(centerX - 100, resultY + i * 22, 200, 20)
                        .tooltip(Tooltip.create(Component.literal(
                                candidate.trackName() + " - " + candidate.artistName())))
                        .build();
                this.addRenderableWidget(resultButton);
            }
            rows = shown;
            if (candidates.size() > shown) {
                this.addRenderableWidget(Button.builder(Component.literal("... and "
                                + (candidates.size() - shown) + " more"), button -> {
                        })
                        .bounds(centerX - 100, resultY + shown * 22, 200, 20)
                        .build());
            }
        }

        Button backButton = Button.builder(Component.literal("Back"),
                        button -> Minecraft.getInstance().gui.setScreen(new CraftifyMenuScreen()))
                .bounds(centerX - 100, resultY + rows * 22 + 8, 200, 20)
                .build();
        this.addRenderableWidget(backButton);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER && this.searchBox != null
                && this.searchBox.isFocused()) {
            runSearch();
            return true;
        }
        return super.keyPressed(event);
    }

    private void runSearch() {
        String query = lastQuery == null ? "" : lastQuery.trim();
        if (query.isEmpty()) {
            this.error = "Type a song or artist to search";
            this.rebuildWidgets();
            return;
        }
        this.searching = true;
        this.candidates = List.of();
        this.error = null;
        this.rebuildWidgets();

        // Same cleanup as the command: the detected title is "Song - Artist", and LRCLib
        // matches better without the separator.
        String searchQuery = query.replace(" - ", " ");
        LrclibClient.searchAsync(searchQuery).whenComplete((outcome, throwable) ->
                Minecraft.getInstance().execute(() -> {
                    this.searching = false;
                    if (throwable != null) {
                        this.error = "search failed: " + throwable.getMessage();
                    } else {
                        applyOutcome(outcome);
                    }
                    this.rebuildWidgets();
                }));
    }

    private void applyOutcome(SearchOutcome outcome) {
        if (outcome.error() != null) {
            this.error = outcome.error();
            this.candidates = List.of();
            return;
        }
        this.candidates = outcome.candidates() == null ? List.of() : outcome.candidates();
        if (this.candidates.isEmpty()) {
            this.error = "No results for \"" + lastQuery + "\"";
        }
    }

    /** Prints the candidate details to the chat, like the search command does. */
    private void printCandidateDetail(SearchCandidate candidate) {
        if (Minecraft.getInstance().player == null) {
            return;
        }
        String tags = candidate.instrumental()
                ? "[instrumental]"
                : candidate.hasSyncedLyrics()
                        ? "[synced " + formatDuration(candidate.durationSeconds()) + "]"
                        : candidate.hasPlainLyrics() ? "[plain only]" : "[]";
        Minecraft.getInstance().player.sendSystemMessage(
                Component.literal("[Craftify] " + candidate.trackName() + " - " + candidate.artistName() + " " + tags)
                        .withStyle(candidate.hasSyncedLyrics() ? ChatFormatting.GREEN : ChatFormatting.GRAY));
    }

    private static String candidateLabel(SearchCandidate candidate) {
        String tags = candidate.instrumental()
                ? " [instrumental]"
                : candidate.hasSyncedLyrics()
                        ? " [synced " + formatDuration(candidate.durationSeconds()) + "]"
                        : candidate.hasPlainLyrics() ? " [plain only]" : " []";
        return candidate.trackName() + " - " + candidate.artistName() + tags;
    }

    private static String formatDuration(int seconds) {
        return (seconds / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60);
    }

    private static String shorten(String text) {
        return text.length() <= 40 ? text : text.substring(0, 37) + "...";
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(null);
    }
}
