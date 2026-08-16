package org.foranly.craftify.client.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Fetches synchronized lyrics from LRCLib ({@code https://lrclib.net}): a free, open
 * lyrics database with a public API that needs no key.
 *
 * <p>Uses the **search** endpoint ({@code /api/search?q=}) instead of the strict
 * {@code /api/get}: the response is a list of candidates, and the best one is picked by
 * fuzzy (Levenshtein) matching against the cleaned title/artist. Spotify titles often
 * carry suffixes (" - Remastered 2009", "(Remix)", "feat. X") that would make the strict
 * endpoint 404, so the title and artist are cleaned first (same approach as the Forawn
 * app, which uses this API successfully).
 *
 * <p>Rate limit: ~50 requests/min without a token, and a descriptive {@code User-Agent}
 * is expected. Fetching happens only when the song changes, so the limit is rarely an
 * issue; a 429 is reported as an error detail instead of a silent miss.
 */
public final class LrclibClient {

    /** Fetch result: the synced lines, or a marker for instrumental / no lyrics. */
    public record Result(List<LyricLine> lines, boolean instrumental, boolean found, String detail) {

        /** The track is instrumental (no lyrics). */
        public static Result instrumentalResult() {
            return new Result(List.of(), true, true, "instrumental");
        }

        /** No usable lyrics, with a human-readable reason for diagnostics. */
        public static Result notFoundResult(String detail) {
            return new Result(List.of(), false, false, detail);
        }
    }

    /** Result of a search: the candidates, or the error that prevented the search. */
    public record SearchOutcome(List<SearchCandidate> candidates, String error) {
        static SearchOutcome ok(List<SearchCandidate> candidates) {
            return new SearchOutcome(candidates, null);
        }
    }

    /** A search hit, as returned by the LRCLib search endpoint. */
    public record SearchCandidate(String trackName, String artistName, boolean instrumental,
                                  String syncedLyrics, String plainLyrics, int durationSeconds) {

        /** Whether the candidate carries synced (timed) lyrics. */
        public boolean hasSyncedLyrics() {
            return syncedLyrics != null && !syncedLyrics.isBlank();
        }

        /** Whether the candidate carries plain (untimed) lyrics. */
        public boolean hasPlainLyrics() {
            return plainLyrics != null && !plainLyrics.isBlank();
        }
    }

    private record SearchResponse(int status, String error, List<SearchCandidate> candidates) {
        static SearchResponse failed(int status, String error) {
            return new SearchResponse(status, error, List.of());
        }
    }

    private static final String SEARCH_ENDPOINT = "https://lrclib.net/api/search";
    private static final String USER_AGENT =
            "Craftify/1.0.1 (Minecraft Fabric mod; https://github.com/Frantt21/Craftify)";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** Levenshtein similarity threshold for accepting a candidate (Forawn uses 0.5). */
    private static final double SIMILARITY_THRESHOLD = 0.5;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "craftify-lrclib");
        thread.setDaemon(true);
        return thread;
    });

    private LrclibClient() {
    }

    // --- Public API ---

    /** Fetches lyrics asynchronously; the result arrives on a daemon thread. */
    public static CompletableFuture<Result> fetchAsync(String artist, String track) {
        return CompletableFuture.supplyAsync(() -> fetch(artist, track), EXECUTOR);
    }

    /** Searches LRCLib asynchronously (for {@code /craftify lyrics search}). */
    public static CompletableFuture<SearchOutcome> searchAsync(String query) {
        return CompletableFuture.supplyAsync(() -> {
            // Must go through searchRawQ (URL-encoded): the raw query passed straight to
            // URI.create would throw IllegalArgumentException on spaces/special chars.
            SearchResponse response = searchRawQ(query);
            if (response.status() != 200) {
                return new SearchOutcome(List.of(), response.error());
            }
            return SearchOutcome.ok(response.candidates());
        }, EXECUTOR);
    }

    /**
     * Fetches lyrics synchronously (must not run on the render thread).
     *
     * <p>The detected title is "Song - Artist" on most platforms, but some report
     * "Artist - Song", so the search is order-independent: it queries LRCLib by field
     * ({@code track_name} + {@code artist_name}) in both alignments, falls back to the
     * free-text search in both orders, and the candidate match itself is alignment-agnostic.
     */
    public static Result fetch(String artist, String track) {
        String cleanTrack = cleanTitle(track);
        String cleanArtist = cleanArtist(artist);

        List<SearchCandidate> candidates = new ArrayList<>();
        String error = null;

        // 1) Field search, both alignments.
        for (String[] pair : List.of(new String[]{cleanTrack, cleanArtist}, new String[]{cleanArtist, cleanTrack})) {
            SearchResponse response = searchRawFields(pair[0], pair[1]);
            if (response.status() == 200) {
                candidates.addAll(response.candidates());
            } else if (error == null) {
                error = response.error();
            }
        }
        // 2) If the field search found nothing, free-text search, both orders.
        if (candidates.isEmpty()) {
            for (String query : List.of(cleanTrack + " " + cleanArtist, cleanArtist + " " + cleanTrack)) {
                SearchResponse response = searchRawQ(query);
                if (response.status() == 200) {
                    candidates.addAll(response.candidates());
                } else if (error == null) {
                    error = response.error();
                }
            }
        }
        if (candidates.isEmpty()) {
            return Result.notFoundResult(error != null ? error : "search returned 0 results");
        }
        return bestMatch(candidates, cleanTrack, cleanArtist);
    }

    /** Searches LRCLib synchronously (must not run on the render thread). */
    public static List<SearchCandidate> search(String query) {
        return searchRawQ(query).candidates();
    }

    // --- HTTP + parsing ---

    private static SearchResponse searchRawFields(String trackName, String artistName) {
        return searchRaw(SEARCH_ENDPOINT + "?track_name=" + encode(trackName) + "&artist_name=" + encode(artistName));
    }

    private static SearchResponse searchRawQ(String query) {
        return searchRaw(SEARCH_ENDPOINT + "?q=" + encode(query));
    }

    private static SearchResponse searchRaw(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                return SearchResponse.failed(429, "HTTP 429: rate limited by LRCLib (50 req/min) — wait about a minute");
            }
            if (response.statusCode() != 200) {
                return SearchResponse.failed(response.statusCode(), "HTTP " + response.statusCode());
            }
            List<SearchCandidate> candidates = new ArrayList<>();
            JsonArray array = JsonParser.parseString(response.body()).getAsJsonArray();
            for (JsonElement element : array) {
                JsonObject item = element.getAsJsonObject();
                candidates.add(new SearchCandidate(
                        jsonString(item, "trackName"),
                        jsonString(item, "artistName"),
                        jsonBoolean(item, "instrumental"),
                        jsonNullableString(item, "syncedLyrics"),
                        jsonNullableString(item, "plainLyrics"),
                        jsonDuration(item)));
            }
            return new SearchResponse(200, null, candidates);
        } catch (Exception e) {
            return SearchResponse.failed(-1,
                    "network error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Picks the first candidate whose cleaned track and artist are similar enough to the
     * searched ones and that carries synced lyrics.
     */
    private static Result bestMatch(List<SearchCandidate> candidates, String searchTrack, String searchArtist) {
        if (candidates.isEmpty()) {
            return Result.notFoundResult("search returned 0 results for \"" + searchTrack + " " + searchArtist + "\"");
        }
        boolean instrumentalMatch = false;
        String plainOnlyMatch = null;
        for (SearchCandidate candidate : candidates) {
            // Order-agnostic: the detected title may be "Song - Artist" or "Artist - Song".
            if (!matches(candidate, searchTrack, searchArtist)) {
                continue;
            }
            if (candidate.instrumental()) {
                instrumentalMatch = true;
                continue;
            }
            if (candidate.hasSyncedLyrics()) {
                List<LyricLine> lines = LrcParser.parse(candidate.syncedLyrics());
                return new Result(lines, false, true,
                        "matched \"" + candidate.trackName() + " - " + candidate.artistName()
                                + "\" (" + lines.size() + " line(s))");
            }
            if (plainOnlyMatch == null) {
                plainOnlyMatch = candidate.trackName() + " - " + candidate.artistName();
            }
        }
        if (instrumentalMatch) {
            return Result.instrumentalResult();
        }
        if (plainOnlyMatch != null) {
            return Result.notFoundResult("match \"" + plainOnlyMatch + "\" has only plain lyrics (no sync)");
        }
        return Result.notFoundResult("no candidate matched \"" + searchTrack + " - " + searchArtist
                + "\" among " + candidates.size() + " result(s)");
    }

    /** Whether a candidate matches the searched track/artist in either alignment. */
    private static boolean matches(SearchCandidate candidate, String searchTrack, String searchArtist) {
        return (similar(candidate.trackName(), searchTrack) && similar(candidate.artistName(), searchArtist))
                || (similar(candidate.trackName(), searchArtist) && similar(candidate.artistName(), searchTrack));
    }

    // --- Cleaning (same as the Forawn app) ---

    private static final Pattern[] TITLE_CLEANUPS = {
            Pattern.compile("\\s*-\\s*Remaster(ed)?\\s*\\d*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s*\\(Remaster(ed)?\\s*\\d*\\)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s*\\[Remaster(ed)?\\s*\\d*]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s*\\(.*?(?:Remix|Version|Edit|Mix).*?\\)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s*\\[.*?(?:Remix|Version|Edit|Mix).*?]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s+(?:ft\\.?|feat\\.?|featuring|con|with)\\s+.*", Pattern.CASE_INSENSITIVE),
    };

    private static String cleanTitle(String title) {
        String clean = title == null ? "" : title;
        for (Pattern pattern : TITLE_CLEANUPS) {
            clean = pattern.matcher(clean).replaceAll("");
        }
        return clean.strip();
    }

    private static String cleanArtist(String artist) {
        String clean = artist == null ? "" : artist;
        clean = Pattern.compile("\\s*-\\s*Topic\\s*$", Pattern.CASE_INSENSITIVE).matcher(clean).replaceAll("");
        java.util.regex.Matcher first = Pattern.compile("^([^,&]+)").matcher(clean);
        if (first.find()) {
            clean = first.group(1);
        }
        return clean.strip();
    }

    // --- Matching ---

    /**
     * Whether {@code result} is equal, contains the searched term (multi-artist strings
     * like {@code "A; B"} vs {@code "A"}), or similar enough (> {@value #SIMILARITY_THRESHOLD}).
     */
    private static boolean similar(String result, String search) {
        String a = result.toLowerCase(Locale.ROOT);
        String b = search.toLowerCase(Locale.ROOT);
        if (a.equals(b)) {
            return true;
        }
        // Containment with a minimum length guards against tiny false positives.
        if (Math.min(a.length(), b.length()) >= 4 && (a.contains(b) || b.contains(a))) {
            return true;
        }
        return levenshteinSimilarity(a, b) > SIMILARITY_THRESHOLD;
    }

    /** Levenshtein similarity in [0, 1]: 1 - distance / maxLength. */
    private static double levenshteinSimilarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        int len1 = a.length();
        int len2 = b.length();
        int[][] matrix = new int[len1 + 1][len2 + 1];
        for (int i = 0; i <= len1; i++) {
            matrix[i][0] = i;
        }
        for (int j = 0; j <= len2; j++) {
            matrix[0][j] = j;
        }
        for (int i = 1; i <= len1; i++) {
            for (int j = 1; j <= len2; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                matrix[i][j] = Math.min(Math.min(matrix[i - 1][j] + 1, matrix[i][j - 1] + 1),
                        matrix[i - 1][j - 1] + cost);
            }
        }
        return 1.0 - (double) matrix[len1][len2] / Math.max(len1, len2);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    // --- Null-safe JSON reads (LRCLib may return null fields on some entries) ---

    private static String jsonString(JsonObject item, String field) {
        JsonElement element = item.get(field);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static String jsonNullableString(JsonObject item, String field) {
        JsonElement element = item.get(field);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static boolean jsonBoolean(JsonObject item, String field) {
        JsonElement element = item.get(field);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }

    private static int jsonDuration(JsonObject item) {
        JsonElement element = item.get("duration");
        return element != null && element.isJsonPrimitive() ? (int) element.getAsDouble() : 0;
    }
}
