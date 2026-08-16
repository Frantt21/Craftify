package org.foranly.craftify.client.spotify;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Detects the Spotify process running on the player's operating system and reads its
 * playback state (playing / paused / closed) and the current song.
 *
 * <p>Each operating system uses a different executable:
 * <ul>
 *   <li>Windows: {@code Spotify.exe}</li>
 *   <li>macOS: {@code Spotify}</li>
 *   <li>Linux: {@code spotify}</li>
 * </ul>
 *
 * <p>On Windows a native probe (JNA/Win32) is used to avoid paying for PowerShell startup
 * on every query; if JNA fails, it falls back to {@code tasklist} + PowerShell.
 */
public final class SpotifyProcess {

    /** Playback state of the Spotify process. */
    public enum Status {
        /** Spotify is not running. */
        CLOSED,
        /** Spotify is running with an active song. */
        PLAYING,
        /** Spotify is running but paused (no active song). */
        PAUSED,
        /** Spotify is running but its state could not be determined. */
        UNKNOWN
    }

    /** Result of a read: the playback state and, if applicable, the current song. */
    public record Snapshot(Status status, String title) {
        /** Whether the Spotify process is running. */
        public boolean running() {
            return status != Status.CLOSED;
        }
    }

    /** Operating systems supported by the mod. */
    public enum Os {
        WINDOWS("Spotify.exe"),
        MACOS("Spotify"),
        LINUX("spotify"),
        UNSUPPORTED(null);

        private final String executable;

        Os(String executable) {
            this.executable = executable;
        }

        /** Spotify executable name on this OS, or {@code null} if unsupported. */
        public String executable() {
            return executable;
        }
    }

    private static final long COMMAND_TIMEOUT_SECONDS = 5;

    private SpotifyProcess() {
    }

    /**
     * Detects the current operating system from the {@code os.name} system property.
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
     * Reads the Spotify state in one go: playback state + current song. A single probe per
     * query (a native call on Windows, light processes on macOS/Linux) so the polling stays
     * cheap.
     */
    public static Snapshot readSnapshot(Os os) {
        return switch (os) {
            case WINDOWS -> readWindowsSnapshot();
            case MACOS -> readMacosSnapshot();
            case LINUX -> readLinuxSnapshot();
            case UNSUPPORTED -> new Snapshot(Status.CLOSED, null);
        };
    }

    /** Whether the Spotify process is running on the given OS. */
    public static boolean isRunning(Os os) {
        return readSnapshot(os).running();
    }

    // --- Windows ---

    private static Snapshot readWindowsSnapshot() {
        try {
            return WindowsSpotify.read();
        } catch (Throwable t) {
            // JNA unavailable or failing: fall back to the CLI (slow, but functional).
            return toWindowsSnapshot(isWindowsRunningCli(), readWindowsTitleCli());
        }
    }

    /**
     * Maps a Windows read to a state. On Windows the window title reverts to the account
     * tier ("Spotify Free"/"Spotify Premium") while paused, so running without a song
     * title means paused.
     */
    private static Snapshot toWindowsSnapshot(boolean running, String title) {
        if (!running) {
            return new Snapshot(Status.CLOSED, null);
        }
        if (title == null || isAccountTierTitle(title)) {
            return new Snapshot(Status.PAUSED, null);
        }
        return new Snapshot(Status.PLAYING, title);
    }

    /** Whether the title is just the Spotify account tier shown in the window bar. */
    static boolean isAccountTierTitle(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        return lower.equals("spotify")
                || lower.equals("spotify free")
                || lower.equals("spotify premium");
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
            // JNA unavailable: pgrep (is it running?) + AppleScript for the state.
            return readMacosAppleScript(isMacosRunningCli());
        }
        if (!jna.running()) {
            return jna;
        }
        if (jna.status() == Status.UNKNOWN) {
            // No real track in the window title: on macOS it shows the account tier
            // ("Spotify Free"/"Spotify Premium"), never the song, and the Screen Recording
            // permission may hide titles too. Query Spotify directly via its AppleScript
            // dictionary (a single Automation prompt, works even with the window hidden).
            return readMacosAppleScript(true);
        }
        return jna;
    }

    private static boolean isMacosRunningCli() {
        return !run("pgrep", "-x", "Spotify").isBlank();
    }

    /**
     * Resolves the macOS state via Spotify's own AppleScript dictionary (no Accessibility
     * needed). A single call returns the playback state plus the current track:
     * {@code playing|Song - Artist}, {@code paused|Song - Artist} or {@code stopped|...}.
     */
    private static Snapshot readMacosAppleScript(boolean running) {
        if (!running) {
            return new Snapshot(Status.CLOSED, null);
        }
        String out = firstNonBlankLine(runChecked("osascript",
                "-e", "tell application \"Spotify\"",
                "-e", "set st to player state as text",
                "-e", "try",
                "-e", "set tr to (name of current track) & \" - \" & (artist of current track)",
                "-e", "on error",
                "-e", "set tr to \"\"",
                "-e", "end try",
                "-e", "return st & \"|\" & tr",
                "-e", "end tell"));
        if (out == null) {
            // Automation permission denied or osascript unavailable.
            return new Snapshot(Status.UNKNOWN, null);
        }
        int separator = out.indexOf('|');
        String state = (separator >= 0 ? out.substring(0, separator) : out).trim().toLowerCase(Locale.ROOT);
        String track = separator >= 0 ? out.substring(separator + 1).strip() : "";
        return switch (state) {
            case "playing" -> track.isEmpty() ? new Snapshot(Status.UNKNOWN, null)
                                               : new Snapshot(Status.PLAYING, track);
            case "paused", "stopped" -> new Snapshot(Status.PAUSED, null);
            default -> new Snapshot(Status.UNKNOWN, null);
        };
    }

    // --- Linux ---

    private static Snapshot readLinuxSnapshot() {
        // 1) MPRIS via playerctl: a single invocation gives running + playback status +
        // title, no X11 needed. Uses the binary bundled in the mod (no sudo); if it could
        // not be extracted, the system one.
        String playerctl = playerctlBinary();
        String mpris = firstNonBlankLine(run(playerctl, "--player=spotify", "metadata", "--format",
                "{{ status }}|{{ artist }} - {{ title }}"));
        if (mpris != null) {
            return parsePlayerctl(mpris);
        }
        // 2) Fallback: pgrep (is it running?) + window title via xdotool.
        boolean running = !run("pgrep", "-x", "spotify").isBlank();
        if (!running) {
            return new Snapshot(Status.CLOSED, null);
        }
        String title = readLinuxWindowTitle();
        // xdotool has no pause info; a readable title means playing, otherwise unknown.
        return title == null ? new Snapshot(Status.UNKNOWN, null)
                             : new Snapshot(Status.PLAYING, title);
    }

    /** Maps a playerctl output ({@code status|track}) to a state. */
    private static Snapshot parsePlayerctl(String out) {
        int separator = out.indexOf('|');
        String status = (separator >= 0 ? out.substring(0, separator) : out).trim().toLowerCase(Locale.ROOT);
        String track = separator >= 0 ? out.substring(separator + 1).strip() : "";
        return switch (status) {
            case "playing" -> track.isEmpty() ? new Snapshot(Status.UNKNOWN, null)
                                               : new Snapshot(Status.PLAYING, track);
            case "paused", "stopped" -> new Snapshot(Status.PAUSED, null);
            default -> new Snapshot(Status.UNKNOWN, null);
        };
    }

    /**
     * Resolves the playerctl binary on Linux: first the one bundled in the mod's JAR
     * (extracted to the user's temp directory on first use, no root needed) and, if it is
     * not available, the system-installed one ({@code playerctl} on the PATH).
     */
    private static String playerctlBinary() {
        String arch = linuxArch();
        String resource = "/assets/craftify/native/linux/" + arch + "/playerctl";
        try (InputStream in = SpotifyProcess.class.getResourceAsStream(resource)) {
            if (in == null) {
                return "playerctl";
            }
            File dir = new File(System.getProperty("java.io.tmpdir"), "craftify-playerctl-" + arch + "-v2.4.1");
            File binary = new File(dir, "playerctl");
            if (!binary.isFile()) {
                if (!dir.mkdirs() && !dir.isDirectory()) {
                    return "playerctl";
                }
                try (OutputStream out = new FileOutputStream(binary)) {
                    in.transferTo(out);
                }
                binary.setExecutable(true, true);
            }
            return binary.getAbsolutePath();
        } catch (IOException e) {
            return "playerctl";
        }
    }

    /** Architecture of the bundled playerctl binary (x86_64 by default). */
    private static String linuxArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "aarch64";
        }
        return "x86_64";
    }

    /** Spotify window title on Linux via {@code xdotool}. */
    private static String readLinuxWindowTitle() {
        return firstNonBlankLine(run("xdotool", "search", "--class", "spotify", "getwindowname", "%@"));
    }

    // --- Utilities ---

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

    /**
     * Runs a command and returns its output only when it exits successfully ({@code null}
     * otherwise). Used for commands whose failure must not be mistaken for data, e.g.
     * {@code osascript} when the Automation permission was denied.
     */
    private static String runChecked(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 ? output.strip() : null;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
