package org.foranly.craftify.client.spotify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Detecta el proceso de Spotify que corre en el sistema operativo del jugador y
 * lee el título de su ventana, que cambia con cada canción.
 *
 * <p>Cada sistema operativo usa un ejecutable distinto:
 * <ul>
 *   <li>Windows: {@code Spotify.exe}</li>
 *   <li>macOS: {@code Spotify}</li>
 *   <li>Linux: {@code spotify}</li>
 * </ul>
 *
 * <p>En Windows se usa una sonda nativa (JNA/Win32) para no pagar el arranque de PowerShell
 * en cada consulta; si JNA falla, se vuelve a {@code tasklist} + PowerShell.
 */
public final class SpotifyProcess {

    /** Resultado de una lectura: si Spotify está corriendo y, en ese caso, el título. */
    public record Snapshot(boolean running, String title) {
    }

    /** Sistemas operativos soportados por el mod. */
    public enum Os {
        WINDOWS("Spotify.exe"),
        MACOS("Spotify"),
        LINUX("spotify"),
        UNSUPPORTED(null);

        private final String executable;

        Os(String executable) {
            this.executable = executable;
        }

        /** Nombre del ejecutable de Spotify en este sistema operativo, o {@code null} si no está soportado. */
        public String executable() {
            return executable;
        }
    }

    private static final long COMMAND_TIMEOUT_SECONDS = 5;

    private SpotifyProcess() {
    }

    /**
     * Detecta el sistema operativo actual a partir de la propiedad del sistema {@code os.name}.
     */
    public static Os currentOs() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return Os.WINDOWS;
        }
        if (osName.contains("mac")) {
            return Os.MACOS;
        }
        if (osName.contains("linux")) {
            return Os.LINUX;
        }
        return Os.UNSUPPORTED;
    }

    /**
     * Lee el estado de Spotify de una sola vez: si está corriendo y, en ese caso, el título.
     * Una sola sonda por consulta (en Windows una llamada nativa, en macOS/Linux procesos
     * ligeros) para que el polling sea barato.
     */
    public static Snapshot readSnapshot(Os os) {
        return switch (os) {
            case WINDOWS -> readWindowsSnapshot();
            case MACOS -> readMacosSnapshot();
            case LINUX -> readLinuxSnapshot();
            case UNSUPPORTED -> new Snapshot(false, null);
        };
    }

    /** Indica si el proceso de Spotify está corriendo en el sistema operativo dado. */
    public static boolean isRunning(Os os) {
        return readSnapshot(os).running();
    }

    /**
     * Lee el título de Spotify, con formato "Canción - Artista".
     *
     * @return el título actual, o {@code null} si no se pudo obtener
     */
    public static String readTitle(Os os) {
        return readSnapshot(os).title();
    }

    // --- Windows ---

    private static Snapshot readWindowsSnapshot() {
        try {
            return WindowsSpotify.read();
        } catch (Throwable t) {
            // JNA no disponible o falla: volver al CLI (lento, pero funcional).
            return new Snapshot(isWindowsRunningCli(), readWindowsTitleCli());
        }
    }

    private static boolean isWindowsRunningCli() {
        return run("tasklist", "/FI", "IMAGENAME eq Spotify.exe", "/FO", "CSV", "/NH")
                .contains("Spotify.exe");
    }

    private static String readWindowsTitleCli() {
        return firstNonBlankLine(run("powershell", "-NoProfile", "-NonInteractive", "-Command",
                "(Get-Process -Name Spotify -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1).MainWindowTitle"));
    }

    // --- macOS ---

    private static Snapshot readMacosSnapshot() {
        Snapshot jna;
        try {
            jna = MacosSpotify.read();
        } catch (Throwable t) {
            jna = null;
        }
        if (jna == null) {
            // JNA no disponible: CLI (pgrep + osascript).
            return new Snapshot(isMacosRunningCli(), readMacosTitleCli());
        }
        if (jna.running() && jna.title() == null) {
            // Corriendo pero sin título nativo (falta Grabación de Pantalla): probar osascript.
            return new Snapshot(true, readMacosTitleCli());
        }
        return jna;
    }

    private static boolean isMacosRunningCli() {
        return !run("pgrep", "-x", "Spotify").isBlank();
    }

    private static String readMacosTitleCli() {
        return firstNonBlankLine(run("osascript", "-e",
                "tell application \"System Events\" to tell process \"Spotify\" to get name of front window"));
    }

    // --- Linux ---

    private static Snapshot readLinuxSnapshot() {
        // 1) MPRIS vía playerctl: una sola invocación da running + título, sin depender de X11.
        String mpris = firstNonBlankLine(run("playerctl", "--player=spotify", "metadata", "--format",
                "{{ artist }} - {{ title }}"));
        if (mpris != null) {
            return new Snapshot(true, mpris);
        }
        // 2) Fallback: pgrep (¿corre?) + título de la ventana con xdotool.
        boolean running = !run("pgrep", "-x", "spotify").isBlank();
        String title = running ? readLinuxWindowTitle() : null;
        return new Snapshot(running, title);
    }

    /** Título de la ventana de Spotify en Linux vía {@code xdotool}. */
    private static String readLinuxWindowTitle() {
        return firstNonBlankLine(run("xdotool", "search", "--class", "spotify", "getwindowname", "%@"));
    }

    // --- Utilidades ---

    private static String firstNonBlankLine(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        for (String line : output.split("\\R")) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return null;
    }

    private static String run(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            return output.strip();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
