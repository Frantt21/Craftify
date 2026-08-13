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
 * window title, which changes with each song.
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

    /** Result of a read: whether Spotify is running and, if so, the title. */
    public record Snapshot(boolean running, String title) {
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
     * Reads the Spotify state in one go: whether it is running and, if so, the title.
     * A single probe per query (a native call on Windows, light processes on macOS/Linux)
     * so the polling stays cheap.
     */
    public static Snapshot readSnapshot(Os os) {
        return switch (os) {
            case WINDOWS -> readWindowsSnapshot();
            case MACOS -> readMacosSnapshot();
            case LINUX -> readLinuxSnapshot();
            case UNSUPPORTED -> new Snapshot(false, null);
        };
    }

    /** Whether the Spotify process is running on the given OS. */
    public static boolean isRunning(Os os) {
        return readSnapshot(os).running();
    }

    /**
     * Reads the Spotify title, formatted as "Song - Artist".
     *
     * @return the current title, or {@code null} if it could not be obtained
     */
    public static String readTitle(Os os) {
        return readSnapshot(os).title();
    }

    // --- Windows ---

    private static Snapshot readWindowsSnapshot() {
        try {
            return WindowsSpotify.read();
        } catch (Throwable t) {
            // JNA unavailable or failing: fall back to the CLI (slow, but functional).
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
            // JNA unavailable: CLI (pgrep + osascript).
            return new Snapshot(isMacosRunningCli(), readMacosTitleCli());
        }
        if (jna.running() && jna.title() == null) {
            // No native title (missing Screen Recording). From least to most friction:
            // 1) Spotify's own AppleScript dictionary (a single Automation prompt, works
            //    even with the window hidden); 2) System Events (Accessibility).
            String title = readMacosTitleSpotifyDirect();
            if (title == null) {
                title = readMacosTitleCli();
            }
            return new Snapshot(true, title);
        }
        return jna;
    }

    private static boolean isMacosRunningCli() {
        return !run("pgrep", "-x", "Spotify").isBlank();
    }

    /** Title via Spotify's own AppleScript dictionary (no Accessibility needed). */
    private static String readMacosTitleSpotifyDirect() {
        return firstNonBlankLine(run("osascript", "-e",
                "tell application \"Spotify\" to get name of current track & \" - \" & artist of current track"));
    }

    /** Title via System Events (requires Accessibility). Last resort on macOS. */
    private static String readMacosTitleCli() {
        return firstNonBlankLine(run("osascript", "-e",
                "tell application \"System Events\" to tell process \"Spotify\" to get name of front window"));
    }

    // --- Linux ---

    private static Snapshot readLinuxSnapshot() {
        // 1) MPRIS via playerctl: a single invocation gives running + title, no X11 needed.
        // Uses the binary bundled in the mod (no sudo); if it could not be extracted, the
        // system one.
        String playerctl = playerctlBinary();
        String mpris = firstNonBlankLine(run(playerctl, "--player=spotify", "metadata", "--format",
                "{{ artist }} - {{ title }}"));
        if (mpris != null) {
            return new Snapshot(true, mpris);
        }
        // 2) Fallback: pgrep (is it running?) + window title via xdotool.
        boolean running = !run("pgrep", "-x", "spotify").isBlank();
        String title = running ? readLinuxWindowTitle() : null;
        return new Snapshot(running, title);
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
}
