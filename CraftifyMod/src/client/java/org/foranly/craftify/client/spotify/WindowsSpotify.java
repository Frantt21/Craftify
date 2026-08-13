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
 * Sonda nativa (JNA/Win32) para Windows: detecta Spotify.exe y lee el título de su ventana
 * sin lanzar procesos externos.
 *
 * <p>Reemplaza a {@code tasklist} + PowerShell, que tardaban ~1,1 s por consulta (el arranque
 * de PowerShell domina el costo) y hacían impracticable un polling rápido. Dos llamadas
 * nativas del orden de milisegundos en total:
 *
 * <ol>
 *   <li>Toolhelp32: PIDs de los procesos {@code Spotify.exe} (funciona aunque Spotify esté
 *       minimizado a la bandeja, sin ventana).</li>
 *   <li>EnumWindows: busca una ventana visible cuyo PID pertenezca a Spotify y lee su título
 *       con {@code GetWindowText}.</li>
 * </ol>
 *
 * <p><b>Importante:</b> la ventana se identifica por PID ({@code GetWindowThreadProcessId})
 * y <b>no</b> por {@code GetWindowModuleFileName}: esa función devuelve 0 para muchas
 * ventanas (incluidas las de Spotify) y hacía que el título nunca se leyera.
 */
public final class WindowsSpotify {

    private static final int MAX_PATH = 260;

    private WindowsSpotify() {
    }

    /**
     * Estado leído con una sola sonda nativa.
     *
     * @throws com.sun.jna.UnsatisfiedLinkError u otro {@link Error} si JNA no está disponible;
     *         el llamador debe volver al CLI en ese caso
     */
    public static SpotifyProcess.Snapshot read() {
        Set<Integer> pids = spotifyPids();
        boolean running = !pids.isEmpty();
        return new SpotifyProcess.Snapshot(running, running ? windowTitle(pids) : null);
    }

    // --- Toolhelp32: PIDs de Spotify.exe ---

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

    // --- User32: título de la ventana (por PID) ---

    private static String windowTitle(Set<Integer> spotifyPids) {
        // 1er pase: ventanas visibles (el título principal suele estar aquí).
        String title = windowTitlePass(spotifyPids, true);
        if (title != null) {
            return title;
        }
        // 2do pase: todas las ventanas de Spotify. Cuando Spotify se oculta a la bandeja la
        // ventana deja de ser visible pero conserva el texto del título (GetWindowText sigue
        // funcionando); solo hay que descartar las ventanas auxiliares (IME, GDI+).
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
                    return false; // detener la enumeración
                }
            }
            return true;
        }, null);
        return found[0];
    }

    /** Ventanas auxiliares de Spotify (IME, GDI+) que no son la ventana principal. */
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

    /** Declaración mínima de Toolhelp32 (jna-platform no la incluye). */
    private interface TlHelp32 extends StdCallLibrary {
        TlHelp32 INSTANCE = Native.load("kernel32", TlHelp32.class);

        int TH32CS_SNAPPROCESS = 0x00000002;

        boolean Process32FirstW(WinNT.HANDLE hSnapshot, PROCESSENTRY32W lppe);

        boolean Process32NextW(WinNT.HANDLE hSnapshot, PROCESSENTRY32W lppe);

        /** Estructura {@code PROCESSENTRY32W}: entrada del snapshot de procesos. */
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
