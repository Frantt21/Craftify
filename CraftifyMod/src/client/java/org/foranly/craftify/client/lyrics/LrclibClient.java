package org.foranly.craftify.client.lyrics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches synchronized lyrics from LRCLib ({@code https://lrclib.net}): a free, open
 * lyrics database with a public API that needs no key. The response JSON carries
 * {@code syncedLyrics} (LRC format), {@code plainLyrics} and {@code instrumental}.
 *
 * <p>Rate limit: ~50 requests/min without a token, and a descriptive {@code User-Agent}
 * is expected. Fetching happens only when the song changes, so the limit is never an
 * issue in practice; a 429 is treated as "no lyrics".
 */
public final class LrclibClient {

    /** Fetch result: the synced lines, or a marker for instrumental / no lyrics. */
    public record Result(List<LyricLine> lines, boolean instrumental, boolean found) {

        /** Spotify reported the track as instrumental (no lyrics). */
        public static Result instrumentalResult() {
            return new Result(List.of(), true, true);
        }

        /** No lyrics found (404) or the request failed (429, network, ...). */
        public static Result notFoundResult() {
            return new Result(List.of(), false, false);
        }
    }

    private static final String ENDPOINT = "https://lrclib.net/api/get";
    private static final String USER_AGENT =
            "Craftify/1.0.1 (Minecraft Fabric mod; https://github.com/Frantt21/Craftify)";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

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

    /** Fetches lyrics asynchronously; the result arrives on a daemon thread. */
    public static CompletableFuture<Result> fetchAsync(String artist, String track) {
        return CompletableFuture.supplyAsync(() -> fetch(artist, track), EXECUTOR);
    }

    /** Fetches lyrics synchronously (must not run on the render thread). */
    public static Result fetch(String artist, String track) {
        try {
            String url = ENDPOINT
                    + "?artist_name=" + encode(artist)
                    + "&track_name=" + encode(track);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return Result.notFoundResult();
            }
            if (response.statusCode() != 200) {
                // 429 rate limit or server error: no lyrics this time.
                return Result.notFoundResult();
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (json.has("instrumental") && json.get("instrumental").getAsBoolean()) {
                return Result.instrumentalResult();
            }
            String synced = json.has("syncedLyrics") ? json.get("syncedLyrics").getAsString() : null;
            if (synced != null && !synced.isBlank()) {
                return new Result(LrcParser.parse(synced), false, true);
            }
            return Result.notFoundResult();
        } catch (Exception e) {
            return Result.notFoundResult();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
