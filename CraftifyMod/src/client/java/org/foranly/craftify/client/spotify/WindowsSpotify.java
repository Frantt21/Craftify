package org.foranly.craftify.client.spotify;

import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Native (JNA/Win32) probe for Windows: detects Spotify.exe and reads its window title
 * without spawning external processes.
 *
 * <p>Replaces {@code tasklist} + PowerShell, which took ~1.1 s per query (PowerShell
 * startup dominates the cost) and made fast polling impractical. Two native calls on the
 * order of milliseconds in total:
 *
 * <ol>
 *   <li>Toolhelp32: PIDs of the {@code Spotify.exe} processes (works even when Spotify is
 *       minimized to the tray, with no window).</li>
 *   <li>EnumWindows: looks for a visible window whose PID belongs to Spotify and reads its
 *       title with {@code GetWindowText}.</li>
 * </ol>
 *
 * <p><b>Important:</b> the window is identified by PID ({@code GetWindowThreadProcessId})
 * and <b>not</b> by {@code GetWindowModuleFileName}: that function returns 0 for many
 * windows (including Spotify's) and made the title never get read.
 */
public final class WindowsSpotify {

    private static final int MAX_PATH = 260;

    private WindowsSpotify() {
    }

    /**
     * State read with a single native probe.
     *
     * <p>On Windows the window title reverts to the account tier ("Spotify Free"/"Spotify
     * Premium") while paused, so running without a song title means {@link Status#PAUSED}.
     *
     * @throws com.sun.jna.UnsatisfiedLinkError or another {@link Error} if JNA is not
     *         available; the caller must fall back to the CLI in that case
     */
    public static SpotifyProcess.Snapshot read() {
        Set<Integer> pids = spotifyPids();
        if (pids.isEmpty()) {
            return new SpotifyProcess.Snapshot(SpotifyProcess.Status.CLOSED, null);
        }
        String title = windowTitle(pids);
        if (title == null || SpotifyProcess.isAccountTierTitle(title)) {
            return new SpotifyProcess.Snapshot(SpotifyProcess.Status.PAUSED, null);
        }
        return new SpotifyProcess.Snapshot(SpotifyProcess.Status.PLAYING, title);
    }

    // --- Toolhelp32: Spotify.exe PIDs ---

    private static Set<Integer> spotifyPids() {
        Set<Integer> pids = new HashSet<>();
        WinNT.HANDLE snapshot = Kernel32.INSTANCE.CreateToolhelp32Snapshot(
                new WinDef.DWORD(TlHelp32.TH32CS_SNAPPROCESS), new WinDef.DWORD(0));
        if (snapshot == null || WinNT.INVALID_HANDLE_VALUE.equals(snapshot)) {
            return pids;
        }
        try {
            TlHelp32.PROCESSENTRY32W entry = new TlHelp32.PROCESSENTRY32W();
            entry.dwSize = entry.size();
            if (!TlHelp32.INSTANCE.Process32FirstW(snapshot, entry)) {
                return pids;
            }
            do {
                String exe = new String(entry.szExeFile, 0, indexOfNul(entry.szExeFile));
                if ("Spotify.exe".equalsIgnoreCase(exe)) {
                    pids.add(entry.th32ProcessID);
                }
            } while (TlHelp32.INSTANCE.Process32NextW(snapshot, entry));
            return pids;
        } finally {
            Kernel32.INSTANCE.CloseHandle(snapshot);
        }
    }

    // --- User32: window title (by PID) ---

    private static String windowTitle(Set<Integer> spotifyPids) {
        // 1st pass: visible windows (the main title is usually here).
        String title = windowTitlePass(spotifyPids, true);
        if (title != null) {
            return title;
        }
        // 2nd pass: all Spotify windows. When Spotify hides to the tray the window stops
        // being visible but keeps its title text (GetWindowText still works); only the
        // auxiliary windows (IME, GDI+) need to be skipped.
        return windowTitlePass(spotifyPids, false);
    }

    private static String windowTitlePass(Set<Integer> spotifyPids, boolean visibleOnly) {
        final String[] found = new String[1];
        User32.INSTANCE.EnumWindows((hWnd, userData) -> {
            IntByReference pid = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hWnd, pid);
            if (!spotifyPids.contains(pid.getValue())) {
                return true;
            }
            if (visibleOnly && !User32.INSTANCE.IsWindowVisible(hWnd)) {
                return true;
            }
            char[] buffer = new char[2048];
            int length = User32.INSTANCE.GetWindowText(hWnd, buffer, buffer.length);
            if (length > 0) {
                String candidate = new String(buffer, 0, length);
                if (!isAuxiliaryWindow(candidate)) {
                    found[0] = candidate;
                    return false; // stop the enumeration
                }
            }
            return true;
        }, null);
        return found[0];
    }

    /** Spotify auxiliary windows (IME, GDI+) that are not the main window. */
    private static boolean isAuxiliaryWindow(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        return lower.contains("default ime")
                || lower.contains("msctfime")
                || lower.startsWith("gdi+");
    }

    private static int indexOfNul(char[] chars) {
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == '\0') {
                return i;
            }
        }
        return chars.length;
    }

    /** Minimal Toolhelp32 declaration (not included in jna-platform). */
    private interface TlHelp32 extends StdCallLibrary {
        TlHelp32 INSTANCE = Native.load("kernel32", TlHelp32.class);

        int TH32CS_SNAPPROCESS = 0x00000002;

        boolean Process32FirstW(WinNT.HANDLE hSnapshot, PROCESSENTRY32W lppe);

        boolean Process32NextW(WinNT.HANDLE hSnapshot, PROCESSENTRY32W lppe);

        /** {@code PROCESSENTRY32W} structure: process snapshot entry. */
        class PROCESSENTRY32W extends Structure {
            public int dwSize;
            public int cntUsage;
            public int th32ProcessID;
            public BaseTSD.ULONG_PTR th32DefaultHeapID;
            public int th32ModuleID;
            public int cntThreads;
            public int th32ParentProcessID;
            public int pcPriClassBase;
            public int dwFlags;
            public char[] szExeFile = new char[MAX_PATH];

            @Override
            protected List<String> getFieldOrder() {
                return Arrays.asList("dwSize", "cntUsage", "th32ProcessID", "th32DefaultHeapID",
                        "th32ModuleID", "cntThreads", "th32ParentProcessID", "pcPriClassBase",
                        "dwFlags", "szExeFile");
            }
        }
    }
}
