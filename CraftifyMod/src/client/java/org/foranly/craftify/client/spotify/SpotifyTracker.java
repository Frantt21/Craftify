package org.foranly.craftify.client.spotify;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.foranly.craftify.client.network.SpotifyTitlePayload;

/**
 * Monitors the Spotify state while the player is in a world and sends a {@code craftify:title}
 * packet to the server every time that state changes.
 *
 * <p>The payload distinguishes three states:
 * <ul>
 *   <li>{@code playing}: Spotify running with a readable title (active song);</li>
 *   <li>{@code no_track}: Spotify running but without a readable active song;</li>
 *   <li>{@code closed}: Spotify closed.</li>
 * </ul>
 *
 * <p>The polling is adaptive: while Spotify is running it queries every {@value #FAST_POLL_MS}
 * ms (near real-time song changes) and when it is closed it drops to {@value #SLOW_POLL_MS}
 * ms so it does not spawn OS processes in vain.
 *
 * <p>The title read happens on a separate thread (it is OS I/O) and the send goes through
 * the netty channel, which is safe for writes from any thread.
 */
public final class SpotifyTracker {

    /** Interval while Spotify is running. */
    private static final long FAST_POLL_MS = 500;
    /** Interval while Spotify is closed (backoff). */
    private static final long SLOW_POLL_MS = 5000;

    private static ScheduledExecutorService executor;
    private static volatile String lastSignature;
    private static volatile boolean paused;

    private SpotifyTracker() {
    }

    /** Starts tracking. Does nothing if it is already running. */
    public static synchronized void start() {
        if (executor != null) {
            return;
        }
        lastSignature = null;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "craftify-spotify-tracker");
            thread.setDaemon(true);
            return thread;
        });
        executor.schedule(SpotifyTracker::tick, 0, TimeUnit.MILLISECONDS);
    }

    /** Stops tracking and clears the state. */
    public static synchronized void stop() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        executor = null;
        lastSignature = null;
    }

    /** Whether tracking is active. */
    public static boolean isRunning() {
        return executor != null;
    }

    /**
     * Pauses or resumes packet sending. On resume, the next read sends the current Spotify
     * state (as if the player had just joined the world).
     */
    public static synchronized void setPaused(boolean value) {
        paused = value;
        if (!value) {
            // Force the next read to report the current state.
            lastSignature = null;
        }
    }

    /** Whether packet sending is paused. */
    public static boolean isPaused() {
        return paused;
    }

    private static void tick() {
        boolean spotifyRunning = false;
        try {
            SpotifyProcess.Os os = SpotifyProcess.currentOs();
            if (os != SpotifyProcess.Os.UNSUPPORTED) {
                // A single probe per query (OS processes or a native call).
                SpotifyProcess.Snapshot snapshot = SpotifyProcess.readSnapshot(os);
                spotifyRunning = snapshot.running();

                String state;
                String track;
                if (!snapshot.running()) {
                    state = SpotifyTitlePayload.STATE_CLOSED;
                    track = "";
                } else if (snapshot.title() == null) {
                    state = SpotifyTitlePayload.STATE_NO_TRACK;
                    track = "";
                } else {
                    state = SpotifyTitlePayload.STATE_PLAYING;
                    track = snapshot.title();
                }

                // Send only when the state + title combination changes (and not paused).
                String signature = state + '\u0000' + track;
                if (!paused && !signature.equals(lastSignature)) {
                    lastSignature = signature;
                    send(state, track);
                }
            }
        } catch (Exception e) {
            // A failed read or send must not kill the tracking.
        }
        scheduleNext(spotifyRunning);
    }

    private static void scheduleNext(boolean spotifyRunning) {
        synchronized (SpotifyTracker.class) {
            if (executor == null || executor.isShutdown()) {
                return;
            }
            executor.schedule(SpotifyTracker::tick, spotifyRunning ? FAST_POLL_MS : SLOW_POLL_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static void send(String state, String track) {
        SpotifyTitlePayload payload = SpotifyTitlePayload.of(state, track, System.currentTimeMillis());
        try {
            ClientPlayNetworking.send(payload);
        } catch (IllegalStateException e) {
            // No longer connected to a world; it will resume on the next JOIN.
        }
    }
}
