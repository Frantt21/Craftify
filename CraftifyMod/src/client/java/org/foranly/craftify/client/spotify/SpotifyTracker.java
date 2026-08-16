package org.foranly.craftify.client.spotify;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.foranly.craftify.client.lyrics.LyricsManager;
import org.foranly.craftify.client.network.SpotifyTitlePayload;

/**
 * Monitors the Spotify state while the player is in a world and sends a {@code craftify:title}
 * packet to the server every time that state changes.
 *
 * <p>The payload distinguishes four states:
 * <ul>
 *   <li>{@code playing}: Spotify running with a readable title (active song);</li>
 *   <li>{@code paused}: Spotify running but paused (no active song);</li>
 *   <li>{@code no_track}: Spotify running but its state could not be determined;</li>
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
    /** Interval of the transition confirmation burst (only while confirming). */
    private static final long BURST_POLL_MS = 100;
    /** How many burst polls are run to pin down a transition moment. */
    private static final int BURST_TICKS = 4;

    private static ScheduledExecutorService executor;
    private static volatile String lastSignature;
    private static volatile boolean paused;

    // --- Transition tracking (single tracker thread, no extra synchronization) ---
    /** Status applied to the lyrics manager (for pause/resume/change detection). */
    private static volatile String lastStatus = "";
    /** Title applied to the lyrics manager. */
    private static volatile String lastTitle = "";
    /** Wall-clock of the last poll that observed Spotify actually playing. */
    private static volatile long lastPlayingAt;
    /** Wall-clock of the last poll that observed the paused state (resume anchor). */
    private static volatile long lastPausedAt;
    /** Status of the transition being confirmed, or {@code null}. */
    private static volatile String pendingStatus;
    /** Title of the transition being confirmed. */
    private static volatile String pendingTitle;
    /** When the pending status was first observed during the burst. */
    private static volatile long pendingAt;
    /** Burst polls left before applying the pending transition. */
    private static volatile int burstLeft;

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

                String state = toState(snapshot);
                String track = state.equals(SpotifyTitlePayload.STATE_PLAYING) ? snapshot.title() : "";

                if (state.equals(SpotifyTitlePayload.STATE_PLAYING)) {
                    // The song is actually playing: this is the anchor for the next pause.
                    lastPlayingAt = System.currentTimeMillis();
                } else if (state.equals(SpotifyTitlePayload.STATE_PAUSED)) {
                    // Last observed paused moment: the anchor for the next resume.
                    lastPausedAt = System.currentTimeMillis();
                }

                if (burstLeft > 0) {
                    // Confirmation burst: keep sampling at BURST_POLL_MS until it settles
                    // (a very short pause/resume inside the burst updates the pending state).
                    burstLeft--;
                    if (burstLeft > 0) {
                        scheduleNext(true);
                        return;
                    }
                    applyTransition();
                } else if (state.equals(lastStatus) && track.equals(lastTitle)) {
                    // Steady state: keep the lyrics manager in sync (no transition to apply).
                    LyricsManager.instance().onState(state, track, lastPlayingAt);
                } else {
                    // A change was detected: enter the confirmation burst so the transition
                    // is applied only after a few quick polls confirm it.
                    pendingStatus = state;
                    pendingTitle = track;
                    pendingAt = System.currentTimeMillis();
                    burstLeft = BURST_TICKS;
                    scheduleNext(true);
                    return;
                }
            }
        } catch (Exception e) {
            // A failed read or send must not kill the tracking.
        }
        scheduleNext(spotifyRunning);
    }

    /** Maps a snapshot status to the payload state constant. */
    private static String toState(SpotifyProcess.Snapshot snapshot) {
        return switch (snapshot.status()) {
            case CLOSED -> SpotifyTitlePayload.STATE_CLOSED;
            case PLAYING -> SpotifyTitlePayload.STATE_PLAYING;
            case PAUSED -> SpotifyTitlePayload.STATE_PAUSED;
            default -> SpotifyTitlePayload.STATE_NO_TRACK;
        };
    }

    /**
     * Applies the confirmed transition: feeds the lyrics manager with the refined moment
     * and sends the packet when the signature changed.
     *
     * <p>Both anchors are midpoints of the window where the transition happened, which
     * halves the poll-quantization error:
     * <ul>
     *   <li>pause → midpoint between the last playing observation and the first paused one;</li>
     *   <li>resume → midpoint between the last paused observation and the first playing one;</li>
     *   <li>new track → the first playing observation (song boundary).</li>
     * </ul>
     */
    private static void applyTransition() {
        String state = pendingStatus;
        String track = pendingTitle;
        if (state == null) {
            return;
        }
        long transitionAt = pendingAt;
        if (state.equals(SpotifyTitlePayload.STATE_PAUSED)) {
            transitionAt = (lastPlayingAt + transitionAt) / 2;
        } else if (state.equals(SpotifyTitlePayload.STATE_PLAYING) && lastStatus.equals(SpotifyTitlePayload.STATE_PAUSED)) {
            // Resume: the transition happened between the last paused and the first playing
            // observation — take the midpoint so the resume anchor is as accurate as possible.
            transitionAt = (lastPausedAt + transitionAt) / 2;
        }
        pendingStatus = null;
        pendingTitle = null;
        pendingAt = 0;
        burstLeft = 0;

        lastStatus = state;
        lastTitle = track;
        if (!state.equals(SpotifyTitlePayload.STATE_PLAYING)) {
            lastPlayingAt = 0;
        }

        LyricsManager.instance().onState(state, track, transitionAt);

        // Send only when the state + title combination changes (and not paused).
        String signature = state + '\u0000' + track;
        if (!paused && !signature.equals(lastSignature)) {
            lastSignature = signature;
            send(state, track);
        }
    }

    private static void scheduleNext(boolean spotifyRunning) {
        synchronized (SpotifyTracker.class) {
            if (executor == null || executor.isShutdown()) {
                return;
            }
            long delay = spotifyRunning ? FAST_POLL_MS : SLOW_POLL_MS;
            if (burstLeft > 0) {
                delay = BURST_POLL_MS;
            }
            executor.schedule(SpotifyTracker::tick, delay, TimeUnit.MILLISECONDS);
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
