package org.foranly.craftify.client.lyrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
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

    private static final int COLOR_CURRENT = 0xFFFFFFFF;
    private static final int COLOR_NEXT = 0xFFAAAAAA;
    private static final int COLOR_PREV = 0xFF9A9A9A;
    private static final int COLOR_HEADER = 0xFF999999;
    private static final int COLOR_INSTRUMENTAL = 0xFFAAAAAA;
    private static final int COLOR_BACKDROP = 0x99000000;

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

    private volatile boolean enabled = true;
    private volatile String currentTrack;
    private volatile List<LyricLine> lines;
    private volatile boolean instrumental;
    private volatile long songStartMillis;
    private volatile boolean paused;
    private volatile int frozenIndex = -1;

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
        HudElementRegistry.addFirst(Identifier.fromNamespaceAndPath("craftify", "lyrics"),
                (extractor, deltaTracker) -> render(extractor));
    }

    public static LyricsManager instance() {
        return INSTANCE;
    }

    // --- State updates (tracker thread) ---

    /**
     * Called by the tracker on every poll with the current Spotify state.
     */
    public void onState(String state, String track) {
        switch (state) {
            case SpotifyTitlePayload.STATE_PLAYING -> onPlaying(track);
            case SpotifyTitlePayload.STATE_PAUSED -> onPaused();
            default -> onHidden(); // no_track / closed
        }
    }

    /** Whether the lyrics overlay is enabled. */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    private void onPlaying(String track) {
        String key = track == null ? "" : track.strip();
        if (key.isEmpty()) {
            onHidden();
            return;
        }
        if (!key.equals(currentTrack)) {
            currentTrack = key;
            songStartMillis = System.currentTimeMillis();
            paused = false;
            frozenIndex = -1;
            load(key);
        } else {
            paused = false; // resumed after a pause
        }
    }

    private void onPaused() {
        if (currentTrack == null || paused) {
            return;
        }
        // Freeze the current line where it is.
        paused = true;
        frozenIndex = computeIndex(System.currentTimeMillis());
    }

    private void onHidden() {
        currentTrack = null;
        lines = null;
        instrumental = false;
        paused = false;
        frozenIndex = -1;
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
        int centerX = extractor.guiWidth() / 2;
        int bottomY = extractor.guiHeight() - 54;

        if (instrumental) {
            drawBlock(extractor, font, centerX, bottomY, "♪ Instrumental",
                    List.of(), List.of());
            return;
        }
        if (lyricLines.isEmpty()) {
            return; // no synced lyrics for this song
        }

        int index = paused ? frozenIndex : computeIndex(System.currentTimeMillis());
        List<String> shown = new ArrayList<>(3);
        List<Integer> colors = new ArrayList<>(3);
        if (index < 0) {
            // Before the first line: show the upcoming line dimmed.
            shown.add(lyricLines.get(0).text());
            colors.add(COLOR_NEXT);
        } else {
            if (index > 0) {
                shown.add(lyricLines.get(index - 1).text());
                colors.add(COLOR_PREV);
            }
            shown.add(lyricLines.get(Math.min(index, lyricLines.size() - 1)).text());
            colors.add(COLOR_CURRENT);
            if (index + 1 < lyricLines.size()) {
                shown.add(lyricLines.get(index + 1).text());
                colors.add(COLOR_NEXT);
            }
        }
        drawBlock(extractor, font, centerX, bottomY, "♪ " + currentTrack, shown, colors);
        if (!firstFrameLogged) {
            firstFrameLogged = true;
            LOGGER.info("Lyrics: overlay drawing ({} line(s) loaded)", lyricLines.size());
        }
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

    /** Draws the subtitle block: header + stacked lines with a shared backdrop. */
    private void drawBlock(GuiGraphicsExtractor extractor, Font font, int centerX, int bottomY,
                           String header, List<String> lines, List<Integer> colors) {
        int lineHeight = font.lineHeight;
        int topY = bottomY - (lines.size() - 1) * lineHeight;

        int maxWidth = font.width(header);
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, font.width(line));
        }
        int x1 = centerX - maxWidth / 2 - 6;
        int x2 = centerX + maxWidth / 2 + 6;
        extractor.fill(x1, topY - lineHeight - 5, x2, bottomY + lineHeight + 2, COLOR_BACKDROP);
        extractor.centeredText(font, header, centerX, topY - lineHeight - 3, COLOR_HEADER);
        for (int i = 0; i < lines.size(); i++) {
            extractor.centeredText(font, lines.get(i), centerX, topY + i * lineHeight, colors.get(i));
        }
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
