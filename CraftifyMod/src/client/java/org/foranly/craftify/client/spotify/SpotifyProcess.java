package org.foranly.craftify.client.spotify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

    /**
     * How long to stop querying AppleScript on macOS after a failure. While the one-time
     * Automation ("control Spotify") permission is pending or denied, every osascript call
     * can block on or re-trigger the system dialog, so the mod backs off and only retries
     * once the window has passed.
     */
    private static final long MACOS_APPLESCRIPT_BACKOFF_MS = 5000;

    /** When the last macOS AppleScript attempt was blocked, or {@code 0}. */
    private static volatile long macosAppleScriptBlockedAt;

    /**
     * How long to stop re-running the bundled {@code nowplaying-cli} binary after it failed
     * (timed out, printed nothing, or could not be extracted). On macOS 15.4+ a build
     * missing its companion helper files hangs on the MediaRemote daemon instead of
     * exiting, so every poll would otherwise block the tracker thread for the full command
     * timeout.
     */
    private static final long MACOS_NOWPLAYING_BACKOFF_MS = 30000;

    /** When the last {@code nowplaying-cli} attempt failed, or {@code 0}. */
    private static volatile long macosNowPlayingBlockedAt;

    /** Whether the bundled {@code nowplaying-cli} read is currently in its failure backoff. */
    private static boolean macosNowPlayingBlocked() {
        return macosNowPlayingBlockedAt != 0
                && System.currentTimeMillis() - macosNowPlayingBlockedAt < MACOS_NOWPLAYING_BACKOFF_MS;
    }

    private SpotifyProcess() {
    }

    /**
     * Whether reading the track on macOS is currently blocked by the Automation permission
     * (the one-time "control Spotify" prompt is pending, or it was denied). While blocked,
     * {@link #readMacosSnapshot()} skips osascript to avoid re-triggering the system dialog
     * every poll; it retries automatically after the backoff window, so accepting the
     * prompt picks the track up within a few seconds.
     */
    public static boolean macosAppleScriptBlocked() {
        return macosAppleScriptBlockedAt != 0
                && System.currentTimeMillis() - macosAppleScriptBlockedAt < MACOS_APPLESCRIPT_BACKOFF_MS;
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
            // permission may hide titles too. Prefer the bundled nowplaying-cli binary
            // (private MediaRemote framework, reports the track with NO permission prompt)
            // and only fall back to Spotify's AppleScript dictionary (Automation prompt)
            // when it is unavailable (Intel Macs) or could not read anything.
            Snapshot nowPlaying = readMacosNowPlaying();
            if (nowPlaying != null && nowPlaying.status() != Status.UNKNOWN) {
                return nowPlaying;
            }
            return readMacosAppleScript(true);
        }
        return jna;
    }

    /**
     * Path to the bundled {@code nowplaying-cli} binary (Apple Silicon only: the official
     * release ships an arm64 build). Extracted to the user's temp directory on first use,
     * together with the two companion files the v2.1.0 build requires to read the track
     * without any permission prompt ({@code mediaremote-mini.pl} + {@code MediaRemoteMini.dylib}).
     */
    private static String macosNowPlayingBinary() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!(arch.contains("aarch64") || arch.contains("arm64"))) {
            return null;
        }
        // -r3: re-extracts even if a previous build left the binary (or its companion
        // helper files) missing/stale in the temp directory.
        File dir = new File(System.getProperty("java.io.tmpdir"), "craftify-nowplaying-v2.1.0-r3");
        File binary = new File(dir, "nowplaying-cli");
        File helperPl = new File(dir, "share/nowplaying-cli/scripts/mediaremote-mini.pl");
        File helperDylib = new File(dir, "lib/nowplaying-cli/MediaRemoteMini.dylib");
        if (!(binary.isFile() && helperPl.isFile() && helperDylib.isFile())) {
            if (!dir.mkdirs() && !dir.isDirectory()) {
                return null;
            }
            // The binary searches its own directory for the helper files in two layouts:
            //   <dir>/scripts/mediaremote-mini.pl + <dir>/build/mediaremote-mini/MediaRemoteMini.dylib
            //   <dir>/share/nowplaying-cli/scripts/mediaremote-mini.pl + <dir>/lib/nowplaying-cli/MediaRemoteMini.dylib
            // The second (Homebrew install) layout is the one bundled here.
            if (extract("/assets/craftify/native/macos/arm64/nowplaying-cli", binary)
                    && extract("/assets/craftify/native/macos/arm64/share/nowplaying-cli/scripts/mediaremote-mini.pl",
                            helperPl)
                    && extract("/assets/craftify/native/macos/arm64/lib/nowplaying-cli/MediaRemoteMini.dylib",
                            helperDylib)) {
                binary.setExecutable(true, true);
                // Apple Silicon requires every binary to carry at least an ad-hoc code
                // signature; the downloaded release may arrive with a broken/absent one and
                // then be killed in silence by the kernel. Re-sign defensively (no
                // permissions needed for ad-hoc signing of your own files).
                run("codesign", "--force", "--sign", "-", binary.getAbsolutePath());
                run("codesign", "--force", "--sign", "-", helperDylib.getAbsolutePath());
            } else {
                return null;
            }
        }
        return binary.getAbsolutePath();
    }

    /**
     * Copies a bundled resource to the given file, creating parent directories as needed.
     *
     * @return {@code true} on success (including when the file already exists).
     */
    private static boolean extract(String resource, File target) {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return false;
        }
        try (InputStream in = SpotifyProcess.class.getResourceAsStream(resource);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                return false;
            }
            in.transferTo(out);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Diagnostic for {@code /craftify spotify}: explains why the bundled nowplaying-cli is
     * (or is not) available and shows its raw output, so the macOS read chain can be
     * inspected on the player's machine.
     */
    public static String macosNowPlayingDiagnostic() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!(arch.contains("aarch64") || arch.contains("arm64"))) {
            return "no bundled binary for this arch (os.arch=" + System.getProperty("os.arch", "?") + ") -> AppleScript fallback";
        }
        try (InputStream in = SpotifyProcess.class.getResourceAsStream(
                "/assets/craftify/native/macos/arm64/nowplaying-cli")) {
            if (in == null) {
                return "bundled binary missing from the JAR (assets/craftify/native/macos/arm64/nowplaying-cli)";
            }
        } catch (IOException e) {
            return "could not check the bundled binary: " + e;
        }
        String binary = macosNowPlayingBinary();
        if (binary == null) {
            return "bundled binary or its companion helper files could not be extracted to the temp directory";
        }
        ProcessResult result = runWithExit(binary, "get", "--json", "title", "artist", "playbackRate");
        if (result.output() == null || result.output().isBlank()) {
            String reason = switch (result.exitCode()) {
                case Integer.MIN_VALUE -> "timed out after " + COMMAND_TIMEOUT_SECONDS + " s";
                case 0 -> "exited with code 0 but printed nothing";
                default -> "exited with code " + result.exitCode()
                        + (result.exitCode() == 137 ? " (SIGKILL - killed by the code-signature check)" : "");
            };
            return "nowplaying-cli ran but returned nothing, " + reason + " (binary at " + binary + ")";
        }
        return "nowplaying-cli output: " + result.output();
    }

    /**
     * Reads the macOS track via {@code nowplaying-cli get --json title artist playbackRate}
     * (single invocation, JSON out). A rate of {@code 0} means paused; a title means
     * playing; otherwise {@code null} so the caller can fall back to AppleScript.
     */
    private static Snapshot readMacosNowPlaying() {
        if (macosNowPlayingBlocked()) {
            return null;
        }
        String binary = macosNowPlayingBinary();
        if (binary == null) {
            return null;
        }
        String out = run(binary, "get", "--json", "title", "artist", "playbackRate");
        if (out == null || out.isBlank() || out.equals("{}")) {
            macosNowPlayingBlockedAt = System.currentTimeMillis();
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(out).getAsJsonObject();
            String title = json.has("title") && !json.get("title").isJsonNull()
                    ? json.get("title").getAsString().strip() : "";
            String artist = json.has("artist") && !json.get("artist").isJsonNull()
                    ? json.get("artist").getAsString().strip() : "";
            double playbackRate = json.has("playbackRate") && !json.get("playbackRate").isJsonNull()
                    ? json.get("playbackRate").getAsDouble() : 1.0;
            if (playbackRate == 0.0) {
                return new Snapshot(Status.PAUSED, null);
            }
            if (title.isBlank()) {
                return new Snapshot(Status.UNKNOWN, null);
            }
            return new Snapshot(Status.PLAYING,
                    artist.isBlank() ? title : title + " - " + artist);
        } catch (Exception e) {
            return null;
        }
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
        if (macosAppleScriptBlocked()) {
            // Permission pending or denied: skip the query so the system dialog is not
            // re-triggered/blocked every poll. The state stays unknown until the backoff
            // window passes and the (now allowed) call succeeds.
            return new Snapshot(Status.UNKNOWN, null);
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
            // The query failed: the Automation permission is not granted yet (the prompt is
            // blocking, or it was denied) or osascript is unavailable. Back off and treat
            // the state as unknown until the permission is granted.
            macosAppleScriptBlockedAt = System.currentTimeMillis();
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
                "{{ status }}|{{ title }} - {{ artist }}"));
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
        return runWithExit(command).output();
    }

    /**
     * Result of running a command: exit code plus merged output. A negative code means the
     * command was killed ({@link Integer#MIN_VALUE} when our timeout hit; otherwise Unix
     * reports signal deaths as {@code 128 + signal}, e.g. {@code 137} = SIGKILL).
     */
    private record ProcessResult(int exitCode, String output) {
    }

    private static ProcessResult runWithExit(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            // Wait with the timeout BEFORE reading (all our commands produce tiny output,
            // so reading after the process exits is safe and the timeout actually fires
            // when a command hangs without writing anything).
            boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor();
                return new ProcessResult(Integer.MIN_VALUE,
                        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip());
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return new ProcessResult(process.exitValue(), output);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProcessResult(Integer.MIN_VALUE, "");
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
