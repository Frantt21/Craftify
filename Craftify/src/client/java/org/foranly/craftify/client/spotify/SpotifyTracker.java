package org.foranly.craftify.client.spotify;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.foranly.craftify.client.network.SpotifyTitlePayload;

/**
 * Monitorea el estado de Spotify mientras el jugador está en un mundo y envía un paquete
 * {@code craftify:title} al servidor cada vez que ese estado cambia.
 *
 * <p>El payload distingue tres estados:
 * <ul>
 *   <li>{@code playing}: Spotify corriendo con un título legible (canción activa);</li>
 *   <li>{@code no_track}: Spotify corriendo pero sin canción activa legible;</li>
 *   <li>{@code closed}: Spotify cerrado.</li>
 * </ul>
 *
 * <p>El polling es adaptativo: mientras Spotify está corriendo se consulta cada
 * {@value #FAST_POLL_MS} ms (cambios de canción casi en tiempo real) y cuando está cerrado
 * se baja a {@value #SLOW_POLL_MS} ms para no lanzar procesos del SO en vano.
 *
 * <p>La lectura del título se hace en un hilo aparte (es I/O del sistema operativo) y el
 * envío se hace por el canal de netty, que es seguro para escrituras desde cualquier hilo.
 */
public final class SpotifyTracker {

    /** Intervalo con Spotify corriendo. */
    private static final long FAST_POLL_MS = 500;
    /** Intervalo con Spotify cerrado (backoff). */
    private static final long SLOW_POLL_MS = 5000;

    private static ScheduledExecutorService executor;
    private static volatile String lastSignature;
    private static volatile boolean paused;

    private SpotifyTracker() {
    }

    /** Arranca el seguimiento. No hace nada si ya está corriendo. */
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

    /** Detiene el seguimiento y limpia el estado. */
    public static synchronized void stop() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        executor = null;
        lastSignature = null;
    }

    /** Indica si el seguimiento está activo. */
    public static boolean isRunning() {
        return executor != null;
    }

    /**
     * Pausa o reanuda el envío de paquetes. Al reanudar, la siguiente lectura envía el
     * estado actual de Spotify (como si se acabara de entrar al mundo).
     */
    public static synchronized void setPaused(boolean value) {
        paused = value;
        if (!value) {
            // Forzar que la próxima lectura notifique el estado actual.
            lastSignature = null;
        }
    }

    /** Indica si el envío de paquetes está pausado. */
    public static boolean isPaused() {
        return paused;
    }

    private static void tick() {
        boolean spotifyRunning = false;
        try {
            SpotifyProcess.Os os = SpotifyProcess.currentOs();
            if (os != SpotifyProcess.Os.UNSUPPORTED) {
                // Una sola sonda por consulta (procesos del SO o llamada nativa).
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

                // Enviar solo cuando cambia la combinación estado + título (y no esté pausado).
                String signature = state + '\u0000' + track;
                if (!paused && !signature.equals(lastSignature)) {
                    lastSignature = signature;
                    send(state, track);
                }
            }
        } catch (Exception e) {
            // Una lectura fallida o un envío fallido no deben tumbar el seguimiento.
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
            // Ya no estamos conectados a un mundo; se retomará en el próximo JOIN.
        }
    }
}
