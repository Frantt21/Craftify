package org.foranly.craftify.client.spotify;

import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;

import java.util.Arrays;
import java.util.List;

/**
 * Sonda nativa (JNA/Win32) para Windows: detecta Spotify.exe y lee el título de su ventana
 * sin lanzar procesos externos.
 *
 * <p>Reemplaza a {@code tasklist} + PowerShell, que tardaban ~1,1 s por consulta (el arranque
 * de PowerShell domina el costo) y hacían impracticable un polling rápido. Dos llamadas
 * nativas del orden de milisegundos en total:
 *
 * <ol>
 *   <li>Toolhelp32: busca un proceso cuyo ejecutable sea {@code Spotify.exe} (funciona aunque
 *       Spotify esté minimizado a la bandeja, sin ventana).</li>
 *   <li>EnumWindows: encuentra la ventana visible de ese proceso y lee su título con
 *       {@code GetWindowText}.</li>
 * </ol>
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
        boolean running = isAnySpotifyProcessRunning();
        return new SpotifyProcess.Snapshot(running, running ? windowTitle() : null);
    }

    // --- Toolhelp32: ¿está corriendo Spotify.exe? ---

    private static boolean isAnySpotifyProcessRunning() {
        WinNT.HANDLE snapshot = Kernel32.INSTANCE.CreateToolhelp32Snapshot(
                new WinDef.DWORD(TlHelp32.TH32CS_SNAPPROCESS), new WinDef.DWORD(0));
        if (snapshot == null || WinNT.INVALID_HANDLE_VALUE.equals(snapshot)) {
            return false;
        }
        try {
            TlHelp32.PROCESSENTRY32W entry = new TlHelp32.PROCESSENTRY32W();
            entry.dwSize = entry.size();
            if (!TlHelp32.INSTANCE.Process32FirstW(snapshot, entry)) {
                return false;
            }
            do {
                String exe = new String(entry.szExeFile, 0, indexOfNul(entry.szExeFile));
                if ("Spotify.exe".equalsIgnoreCase(exe)) {
                    return true;
                }
            } while (TlHelp32.INSTANCE.Process32NextW(snapshot, entry));
            return false;
        } finally {
            Kernel32.INSTANCE.CloseHandle(snapshot);
        }
    }

    // --- User32: título de la ventana ---

    private static String windowTitle() {
        final String[] found = new String[1];
        User32.INSTANCE.EnumWindows((hWnd, userData) -> {
            if (!User32.INSTANCE.IsWindowVisible(hWnd)) {
                return true;
            }
            char[] module = new char[MAX_PATH];
            int moduleLength = User32.INSTANCE.GetWindowModuleFileName(hWnd, module, module.length);
            if (moduleLength > 0 && isSpotifyExecutable(module, moduleLength)) {
                char[] buffer = new char[2048];
                int length = User32.INSTANCE.GetWindowText(hWnd, buffer, buffer.length);
                if (length > 0) {
                    found[0] = new String(buffer, 0, length);
                    return false; // detener la enumeración
                }
            }
            return true;
        }, null);
        return found[0];
    }

    private static boolean isSpotifyExecutable(char[] module, int length) {
        String path = new String(module, 0, length);
        String exe = path.substring(Math.max(0, path.lastIndexOf('\\') + 1));
        return "Spotify.exe".equalsIgnoreCase(exe);
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
