package org.foranly.craftify.client.lyrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.foranly.craftify.client.config.CraftifyConfig;
import org.foranly.craftify.client.network.LyricsLinePayload;
import org.foranly.craftify.client.network.SpotifyTitlePayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side synchronized lyrics overlay backed by LRCLib.
 *
 * <p>The mod already knows the current song and its pause state, so:
 * <ul>
 *   <li>the song start is taken as the moment the mod detected the new track (≈ the real
 *       start, because the window title changes at the song boundary, within one poll);</li>
 *   <li>when Spotify is paused the current line freezes instead of advancing.</li>
 * </ul>
 *
 * <p>Lyrics are fetched asynchronously from LRCLib (no key) and cached per song. The
 * overlay is drawn as a Fabric HUD element near the bottom of the screen and toggled with
 * {@code /craftify lyrics}.
 */
public final class LyricsManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("craftify");

    private static final int CACHE_MAX = 24;
    private static final long NEGATIVE_CACHE_MS = 10 * 60 * 1000L;

    /** Max width (px, at scale 1.0) of a rendered line; longer lines wrap. */
    private static final int MAX_LINE_WIDTH = 280;
    /** Max rows drawn in the block (prev + current + next), keeps the overlay compact. */
    private static final int MAX_ROWS = 4;
    /** Minimum/maximum overlay text scale (appearance menu). */
    private static final double MIN_SCALE = 0.5;
    private static final double MAX_SCALE = 3.0;
    /** Minimum/maximum overlay opacity in percent (appearance menu). */
    private static final int MIN_OPACITY = 10;
    private static final int MAX_OPACITY = 100;

    private static final LyricsManager INSTANCE = new LyricsManager();

    private static final class CacheEntry {
        final List<LyricLine> lines;
        final boolean instrumental;
        final String detail;
        final long fetchedAt;

        CacheEntry(List<LyricLine> lines, boolean instrumental, String detail) {
            this.lines = lines;
            this.instrumental = instrumental;
            this.detail = detail;
            this.fetchedAt = System.currentTimeMillis();
        }
    }

    /** LRU cache keyed by the track title. */
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true);

    /** Whether the lyrics overlay is enabled (persisted). */
    private volatile boolean enabled;
    /** Whether the current line is shared with other players (server hologram). */
    private volatile boolean shared;
    /** Anchor of the overlay on the screen (persisted). */
    private volatile LyricsPosition position;
    /** Text scale of the overlay (persisted). */
    private volatile double scale;
    /** Opacity of the overlay in percent (persisted). */
    private volatile int opacity;
    /** Text color of the overlay as RGB (persisted). */
    private volatile int textColor;
    /** Last line sent to the server ({@code null} = nothing sent yet). */
    private volatile String lastSharedLine;
    /** Last line number sent to the server. */
    private volatile int lastSharedNumber = -1;
    private volatile String currentTrack;
    private volatile List<LyricLine> lines;
    private volatile boolean instrumental;
    private volatile long songStartMillis;
    private volatile boolean paused;
    private volatile int frozenIndex = -1;
    /** Wall-clock time when the current pause started (to exclude it from the elapsed). */
    private volatile long pausedAtMillis;

    /** One of: "" (no track), "loading", "loaded", "not_found", "instrumental". */
    private volatile String fetchStatus = "";
    /** Human-readable detail of the last fetch (result or error). */
    private volatile String lastDetail = "";
    /** The last song queried (diagnostics). */
    private volatile String lastQuery = "";
    /** How many times the HUD element has been extracted (diagnostics). */
    private volatile int renderCount;
    /** Whether the overlay has drawn at least one frame (diagnostics). */
    private volatile boolean firstFrameLogged;

    private LyricsManager() {
        // Load the persisted settings; defaults are used the first time.
        CraftifyConfig config = CraftifyConfig.instance();
        this.enabled = config.isLyricsEnabled();
        this.shared = config.isLyricsShared();
        this.position = parsePosition(config.getLyricsPosition());
        this.scale = clamp(config.getLyricsScale(), MIN_SCALE, MAX_SCALE);
        this.opacity = Math.max(MIN_OPACITY, Math.min(MAX_OPACITY, config.getLyricsOpacity()));
        this.textColor = parseColor(config.getLyricsColor(), 0xFFFFFF);
        HudElementRegistry.addFirst(Identifier.fromNamespaceAndPath("craftify", "lyrics"),
                (extractor, deltaTracker) -> render(extractor));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int parseColor(String hex, int fallback) {
        if (hex == null) {
            return fallback;
        }
        try {
            String cleaned = hex.strip();
            if (cleaned.startsWith("#")) {
                cleaned = cleaned.substring(1);
            }
            if (cleaned.length() == 6) {
                return 0xFF000000 | Integer.parseInt(cleaned, 16);
            }
            if (cleaned.length() == 8) {
                return (int) Long.parseLong(cleaned, 16);
            }
        } catch (NumberFormatException e) {
            // Fall through to the default.
        }
        return fallback;
    }

    private static LyricsPosition parsePosition(String name) {
        try {
            return LyricsPosition.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return LyricsPosition.BOTTOM_LEFT;
        }
    }

    public static LyricsManager instance() {
        return INSTANCE;
    }

    // --- State updates (tracker thread) ---

    /**
     * Called by the tracker with the current Spotify state and the wall-clock moment the
     * transition happened (last playing observation for a pause, first playing observation
     * for a resume/new track). Steady-state polls pass the last playing observation.
     */
    public void onState(String state, String track, long transitionAt) {
        switch (state) {
            case SpotifyTitlePayload.STATE_PLAYING -> onPlaying(track, transitionAt);
            case SpotifyTitlePayload.STATE_PAUSED -> onPaused(transitionAt);
            default -> onHidden(); // no_track / closed
        }
        maybeSendSharedLine();
    }

    /**
     * Sends the current line to the server ({@code craftify:lyricsline}) when sharing is
     * enabled and the line changed; sends an empty line to clear the hologram when sharing
     * is off or there is nothing to show. A pause does not change the line, so the server
     * keeps the last one frozen.
     */
    private void maybeSendSharedLine() {
        if (!shared) {
            if (lastSharedLine != null) {
                sendSharedLine("", -1);
                lastSharedLine = null;
                lastSharedNumber = -1;
            }
            return;
        }
        List<LyricLine> lyricLines = lines;
        if (currentTrack == null || lyricLines == null || lyricLines.isEmpty()) {
            if (!"".equals(lastSharedLine)) {
                sendSharedLine("", -1);
                lastSharedLine = "";
                lastSharedNumber = -1;
            }
            return;
        }
        int index = paused ? frozenIndex : computeIndex(System.currentTimeMillis());
        // The line number (1-based) travels with the line so the server can show it in the
        // BELOW_NAME slot instead of a fixed number; -1 when no line is active yet.
        int number = index >= 0 && index < lyricLines.size() ? index + 1 : -1;
        String line = index >= 0 && index < lyricLines.size() ? lyricLines.get(index).text() : "";
        if (!line.equals(lastSharedLine) || number != lastSharedNumber) {
            sendSharedLine(line, number);
            lastSharedLine = line;
            lastSharedNumber = number;
        }
    }

    private void sendSharedLine(String line, int number) {
        try {
            ClientPlayNetworking.send(LyricsLinePayload.of(line, number));
        } catch (IllegalStateException e) {
            // No longer connected to a world; sharing resumes on the next JOIN.
        }
    }

    /** Whether the lyrics overlay is enabled. */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
        CraftifyConfig.instance().setLyricsEnabled(value);
    }

    /** Whether the current lyric line is shared with other players (server hologram). */
    public boolean isShared() {
        return shared;
    }

    public void setShared(boolean value) {
        shared = value;
        CraftifyConfig.instance().setLyricsShared(value);
    }

    /** The anchor of the overlay on the screen. */
    public LyricsPosition position() {
        return position;
    }

    /** Moves the overlay to another anchor (persisted). */
    public void setPosition(LyricsPosition value) {
        position = value;
        CraftifyConfig.instance().setLyricsPosition(value.name());
    }

    /** Current text scale of the overlay. */
    public double scale() {
        return scale;
    }

    /** Sets the text scale (persisted, clamped to the allowed range). */
    public void setScale(double value) {
        scale = clamp(value, MIN_SCALE, MAX_SCALE);
        CraftifyConfig.instance().setLyricsScale(scale);
    }

    /** Current opacity of the overlay in percent. */
    public int opacity() {
        return opacity;
    }

    /** Sets the opacity in percent (persisted, clamped). */
    public void setOpacity(int value) {
        opacity = Math.max(MIN_OPACITY, Math.min(MAX_OPACITY, value));
        CraftifyConfig.instance().setLyricsOpacity(opacity);
    }

    /** Current text color of the overlay as RGB. */
    public int textColor() {
        return textColor;
    }

    /** Sets the text color (persisted). */
    public void setTextColor(int rgb) {
        textColor = rgb;
        CraftifyConfig.instance().setLyricsColor(String.format(java.util.Locale.ROOT, "%06X", rgb & 0xFFFFFF));
    }

    /**
     * Handles the playing state. A different track resets the whole timing (the song start
     * is anchored to the transition moment and every pause estimate is cleared, so a
     * previous pause can never leak into the new song). The same track after a pause means
     * resume: the song start is shifted forward by the exact paused duration, using the
     * refined transition moments instead of the detection times.
     */
    private void onPlaying(String track, long transitionAt) {
        String key = track == null ? "" : track.strip();
        if (key.isEmpty()) {
            onHidden();
            return;
        }
        if (!key.equals(currentTrack)) {
            // New track: clean slate. No pause estimate carries over.
            currentTrack = key;
            songStartMillis = transitionAt;
            paused = false;
            frozenIndex = -1;
            pausedAtMillis = 0;
            load(key);
        } else if (paused) {
            // Resume: the pause lasted (resumeAt - pauseAt), so shift the song start by
            // exactly that, keeping the elapsed time continuous (no skip, no lag).
            songStartMillis += transitionAt - pausedAtMillis;
            paused = false;
            frozenIndex = -1;
            pausedAtMillis = 0;
        }
    }

    /**
     * Freezes the current line at the actual pause moment (the last playing observation,
     * refined by the tracker) instead of the poll that detected the pause — so the frozen
     * line matches where the song stopped.
     */
    private void onPaused(long pauseAt) {
        if (currentTrack == null || paused) {
            return;
        }
        paused = true;
        frozenIndex = pauseAt > 0 ? computeIndex(pauseAt) : computeIndex(System.currentTimeMillis());
        pausedAtMillis = pauseAt > 0 ? pauseAt : System.currentTimeMillis();
    }

    private void onHidden() {
        currentTrack = null;
        lines = null;
        instrumental = false;
        paused = false;
        frozenIndex = -1;
        pausedAtMillis = 0;
        fetchStatus = "";
        lastDetail = "";
        lastQuery = "";
    }

    // --- Lyrics loading and cache ---

    private void load(String key) {
        fetchStatus = "loading";
        lastQuery = key;
        lastDetail = "";
        LOGGER.info("Lyrics: fetching \"{}\" from LRCLib", key);
        synchronized (cache) {
            CacheEntry entry = cache.get(key);
            if (entry != null) {
                boolean staleNegative = !entry.instrumental && entry.lines.isEmpty()
                        && System.currentTimeMillis() - entry.fetchedAt > NEGATIVE_CACHE_MS;
                if (!staleNegative) {
                    apply(entry, key);
                    return;
                }
            }
        }
        CompletableFuture<LrclibClient.Result> future =
                LrclibClient.fetchAsync(artistOf(key), trackOf(key));
        future.whenComplete((result, error) -> {
            if (error != null || result == null) {
                return;
            }
            synchronized (cache) {
                CacheEntry entry = new CacheEntry(result.lines(), result.instrumental(), result.detail());
                cache.put(key, entry);
                while (cache.size() > CACHE_MAX) {
                    java.util.Iterator<Map.Entry<String, CacheEntry>> evict = cache.entrySet().iterator();
                    evict.next(); // oldest entry (access-ordered map)
                    evict.remove();
                }
            }
            if (key.equals(currentTrack)) {
                apply(new CacheEntry(result.lines(), result.instrumental(), result.detail()), key);
            }
        });
    }

    private void apply(CacheEntry entry, String key) {
        lines = entry.lines;
        instrumental = entry.instrumental;
        lastDetail = entry.detail == null ? "" : entry.detail;
        if (entry.instrumental) {
            fetchStatus = "instrumental";
            LOGGER.info("Lyrics: \"{}\" is instrumental ({})", key, lastDetail);
        } else if (entry.lines.isEmpty()) {
            fetchStatus = "not_found";
            LOGGER.warn("Lyrics: no synced lyrics for \"{}\": {}", key, lastDetail);
        } else {
            fetchStatus = "loaded";
            LOGGER.info("Lyrics: loaded {} line(s) for \"{}\" ({})", entry.lines.size(), key, lastDetail);
        }
    }

    // --- Rendering (render thread) ---

    private void render(GuiGraphicsExtractor extractor) {
        renderCount++;
        if (!enabled || currentTrack == null || Minecraft.getInstance().gui.hud.isHidden()) {
            return;
        }
        List<LyricLine> lyricLines = lines;
        if (lyricLines == null) {
            return; // still loading
        }
        Font font = Minecraft.getInstance().font;

        if (instrumental) {
            int alpha = Math.max(0, Math.min(255, (opacity * 255) / 100));
            drawBlock(extractor, font, "♪ Instrumental", List.of(), List.of(),
                    withAlpha(0x000000, Math.min(alpha, (opacity * 150) / 100)),
                    withAlpha(textColor, (alpha * 60) / 100));
            return;
        }
        if (lyricLines.isEmpty()) {
            return; // no synced lyrics for this song
        }

        int index = paused ? frozenIndex : computeIndex(System.currentTimeMillis());
        // Colors from the configured text color + opacity: the current line full, the
        // previous/next dimmed by alpha.
        int alpha = Math.max(0, Math.min(255, (opacity * 255) / 100));
        int currentColor = withAlpha(textColor, alpha);
        int nextColor = withAlpha(blendToGray(textColor, 0xAAAAAA), (alpha * 70) / 100);
        int prevColor = withAlpha(blendToGray(textColor, 0x9A9A9A), (alpha * 60) / 100);
        int headerColor = withAlpha(textColor, (alpha * 60) / 100);
        int backdropColor = withAlpha(0x000000, Math.min(alpha, (opacity * 150) / 100));

        // Build the rows with wrapping: each lyric line is wrapped to MAX_LINE_WIDTH and
        // the whole block is capped at MAX_ROWS so a long line never grows past the HUD.
        List<String> shown = new ArrayList<>(MAX_ROWS);
        List<Integer> colors = new ArrayList<>(MAX_ROWS);
        if (index < 0) {
            // Before the first line: show the upcoming line dimmed (first wrapped chunk).
            addWrapped(font, lyricLines.get(0).text(), nextColor, shown, colors, 1);
        } else {
            if (index > 0) {
                addWrapped(font, lyricLines.get(index - 1).text(), prevColor, shown, colors, 1);
            }
            addWrapped(font, lyricLines.get(Math.min(index, lyricLines.size() - 1)).text(),
                    currentColor, shown, colors, 2);
            if (index + 1 < lyricLines.size() && shown.size() < MAX_ROWS) {
                addWrapped(font, lyricLines.get(index + 1).text(), nextColor, shown, colors, 1);
            }
        }
        drawBlock(extractor, font, "♪ " + currentTrack, shown, colors, backdropColor, headerColor);
        if (!firstFrameLogged) {
            firstFrameLogged = true;
            LOGGER.info("Lyrics: overlay drawing ({} line(s) loaded)", lyricLines.size());
        }
    }

    /** Replaces the alpha of an ARGB color. */
    private static int withAlpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    /** Mixes a color towards a gray target (keeps the hue, lowers the contrast). */
    private static int blendToGray(int argb, int target) {
        int r = ((argb >> 16) & 0xFF);
        int g = ((argb >> 8) & 0xFF);
        int b = (argb & 0xFF);
        int tr = (target >> 16) & 0xFF;
        int tg = (target >> 8) & 0xFF;
        int tb = target & 0xFF;
        return 0xFF000000
                | ((r * 3 + tr) / 4) << 16
                | ((g * 3 + tg) / 4) << 8
                | ((b * 3 + tb) / 4);
    }

    /**
     * Wraps {@code text} to {@link #MAX_LINE_WIDTH} and appends up to {@code maxChunks}
     * chunks to the rows/colors lists (kept bounded by {@link #MAX_ROWS}).
     */
    private static void addWrapped(Font font, String text, int color,
                                   List<String> rows, List<Integer> colors, int maxChunks) {
        if (text == null || text.isEmpty()) {
            return;
        }
        List<String> chunks = wrapToWidth(font, text, MAX_LINE_WIDTH);
        int limit = Math.min(chunks.size(), Math.min(maxChunks, MAX_ROWS - rows.size()));
        for (int i = 0; i < limit; i++) {
            rows.add(chunks.get(i));
            colors.add(color);
        }
    }

    /** Splits a long line into chunks that each fit within {@code maxWidth} (word-aware). */
    private static List<String> wrapToWidth(Font font, String text, int maxWidth) {
        List<String> chunks = new ArrayList<>();
        String remaining = text;
        while (font.width(remaining) > maxWidth && !remaining.isEmpty()) {
            // Find the longest prefix that fits, breaking at the last space when possible.
            int low = 1;
            int high = remaining.length();
            int best = high;
            while (low <= high) {
                int mid = (low + high) / 2;
                if (font.width(remaining.substring(0, mid)) <= maxWidth) {
                    best = mid;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
            int cut = best;
            int lastSpace = remaining.lastIndexOf(' ', best - 1);
            if (lastSpace > 0) {
                cut = lastSpace; // break at the space instead of mid-word
            }
            chunks.add(remaining.substring(0, cut).strip());
            remaining = remaining.substring(cut).strip();
        }
        if (!remaining.isEmpty()) {
            chunks.add(remaining);
        }
        return chunks;
    }

    // --- Diagnostics for /craftify spotify ---

    /** One of: "", "loading", "loaded", "not_found", "instrumental". */
    public String fetchStatus() {
        return fetchStatus;
    }

    /** Current song the lyrics refer to, or {@code null}. */
    public String currentTrack() {
        return currentTrack;
    }

    /** Last song queried (diagnostics). */
    public String lastQuery() {
        return lastQuery;
    }

    /** Human-readable detail of the last fetch: match, error or reason (diagnostics). */
    public String lastDetail() {
        return lastDetail;
    }

    /** Number of synced lines loaded, or -1 if none. */
    public int linesLoaded() {
        List<LyricLine> current = lines;
        return current == null ? -1 : current.size();
    }

    /** Whether the overlay is instrumental. */
    public boolean instrumental() {
        return instrumental;
    }

    /** How many times the HUD element was extracted (diagnostics). */
    public int renderCount() {
        return renderCount;
    }

    /**
     * Draws the subtitle block: header + stacked lines with a shared backdrop, anchored to
     * the configured screen position, flush against the edges (no margin).
     */
    /**
     * Draws the subtitle block scaled by the configured factor: the pose is scaled, so the
     * block is laid out in the base font space and the whole block grows/shrinks with it,
     * still anchored flush against the screen edges (no margin).
     */
    private void drawBlock(GuiGraphicsExtractor extractor, Font font, String header,
                           List<String> lines, List<Integer> colors,
                           int backdropColor, int headerColor) {
        double scale = this.scale;
        int lineHeight = font.lineHeight;
        // Keep the header within the same max width (truncated with an ellipsis).
        String headerText = font.width(header) > MAX_LINE_WIDTH
                ? truncateToWidth(font, header, MAX_LINE_WIDTH) : header;
        int maxWidth = font.width(headerText);
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, font.width(line));
        }

        int blockWidth = maxWidth + 8;
        int blockHeight = lineHeight + 5 + lines.size() * lineHeight + 2;
        LyricsPosition anchor = position;
        int x = switch (anchor) {
            case TOP_LEFT, MIDDLE_LEFT, BOTTOM_LEFT -> 0;
            case TOP_CENTER, MIDDLE_CENTER, BOTTOM_CENTER -> (extractor.guiWidth() - blockWidth) / 2;
            case TOP_RIGHT, MIDDLE_RIGHT, BOTTOM_RIGHT -> extractor.guiWidth() - blockWidth;
        };
        int y = switch (anchor) {
            case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> 0;
            case MIDDLE_LEFT, MIDDLE_CENTER, MIDDLE_RIGHT -> (extractor.guiHeight() - blockHeight) / 2;
            case BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT -> extractor.guiHeight() - blockHeight;
        };

        org.joml.Matrix3x2fStack pose = extractor.pose();
        pose.pushMatrix();
        pose.scale((float) scale);
        try {
            // Layout is in the base font space; the scaled pose maps it back to screen px.
            int sx = (int) Math.round(x / scale);
            int sy = (int) Math.round(y / scale);
            int sw = (int) Math.round(blockWidth / scale);
            int sh = (int) Math.round(blockHeight / scale);
            extractor.fill(sx, sy, sx + sw, sy + sh, backdropColor);
            int headerY = sy + 2;
            extractor.text(font, headerText, sx + 2, headerY, headerColor);
            int lineY = headerY + lineHeight + 3;
            for (int i = 0; i < lines.size(); i++) {
                extractor.text(font, lines.get(i), sx + 2, lineY + i * lineHeight, colors.get(i));
            }
        } finally {
            pose.popMatrix();
        }
    }

    /** Cuts a string so it fits within {@code maxWidth}, appending "...". */
    private static String truncateToWidth(Font font, String text, int maxWidth) {
        String ellipsis = "...";
        int ellipsisWidth = font.width(ellipsis);
        if (text.isEmpty() || font.width(text) <= maxWidth) {
            return text;
        }
        int low = 1;
        int high = text.length();
        int best = 0;
        while (low <= high) {
            int mid = (low + high) / 2;
            if (font.width(text.substring(0, mid)) + ellipsisWidth <= maxWidth) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return text.substring(0, best).strip() + ellipsis;
    }

    // --- Timing ---

    /** Index of the line active at {@code nowMillis}, or -1 before the first line. */
    private int computeIndex(long nowMillis) {
        List<LyricLine> lyricLines = lines;
        if (lyricLines == null || lyricLines.isEmpty()) {
            return -1;
        }
        double elapsed = (nowMillis - songStartMillis) / 1000.0;
        int index = -1;
        for (int i = 0; i < lyricLines.size(); i++) {
            if (lyricLines.get(i).timeSeconds() <= elapsed) {
                index = i;
            } else {
                break;
            }
        }
        return index;
    }

    // --- Title parsing ("Song - Artist" → track / artist) ---

    private static String trackOf(String title) {
        int separator = title.lastIndexOf(" - ");
        return separator < 0 ? title : title.substring(0, separator).strip();
    }

    private static String artistOf(String title) {
        int separator = title.lastIndexOf(" - ");
        return separator < 0 ? "" : title.substring(separator + 3).strip();
    }
}
