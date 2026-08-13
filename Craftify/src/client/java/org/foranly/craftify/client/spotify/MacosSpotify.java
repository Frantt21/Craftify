package org.foranly.craftify.client.spotify;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.mac.CoreFoundation;
import com.sun.jna.platform.mac.CoreFoundation.CFArrayRef;
import com.sun.jna.platform.mac.CoreFoundation.CFDictionaryRef;
import com.sun.jna.platform.mac.CoreFoundation.CFIndex;
import com.sun.jna.platform.mac.CoreFoundation.CFStringRef;

/**
 * Sonda nativa (JNA/CoreGraphics) para macOS: detecta Spotify y lee el título de su ventana
 * sin lanzar procesos externos.
 *
 * <p>Reemplaza a {@code pgrep} + {@code osascript}, que tardaban ~200–500 ms por consulta
 * (el arranque del intérprete de AppleScript domina el costo). Una sola llamada nativa a
 * {@code CGWindowListCopyWindowInfo} devuelve la lista de ventanas con su dueño y su nombre:
 *
 * <ul>
 *   <li>el **dueño** de la ventana (p. ej. {@code Spotify}) siempre está disponible;</li>
 *   <li>el **nombre** (título) requiere permiso de Grabación de Pantalla en macOS 10.15+;
 *       si falta, el título queda vacío y el llamador puede caer a {@code osascript}.</li>
 * </ul>
 */
public final class MacosSpotify {

    /** {@code kCGWindowListOptionAll}: listar todas las ventanas, incluso las que no están en pantalla. */
    private static final int KCG_WINDOW_LIST_OPTION_ALL = 0;
    /** {@code kCGNullWindowID}. */
    private static final int KCG_NULL_WINDOW_ID = 0;

    private static final CoreGraphics CG = CoreGraphics.INSTANCE;
    private static final CoreFoundation CF = CoreFoundation.INSTANCE;

    private MacosSpotify() {
    }

    /**
     * Estado leído con una sola llamada nativa.
     *
     * @return {@code null} si CoreGraphics no devolvió datos; el llamador debe volver al CLI
     */
    public static SpotifyProcess.Snapshot read() {
        CFArrayRef windows = CG.CGWindowListCopyWindowInfo(KCG_WINDOW_LIST_OPTION_ALL, KCG_NULL_WINDOW_ID);
        if (windows == null) {
            return null;
        }
        try {
            boolean running = false;
            String title = null;

            CFStringRef ownerKey = cfString("kCGWindowOwnerName");
            CFStringRef nameKey = cfString("kCGWindowName");
            try {
                int count = windows.getCount();
                for (int i = 0; i < count; i++) {
                    Pointer value = windows.getValueAtIndex(i);
                    if (value == null) {
                        continue;
                    }
                    CFDictionaryRef window = new CFDictionaryRef(value);
                    String owner = cfStringValue(window.getValue(ownerKey));
                    if (owner == null || !"Spotify".equalsIgnoreCase(owner)) {
                        continue;
                    }
                    running = true;
                    if (title == null) {
                        title = cfStringValue(window.getValue(nameKey));
                    }
                }
            } finally {
                release(ownerKey);
                release(nameKey);
            }
            return new SpotifyProcess.Snapshot(running, title);
        } finally {
            release(windows);
        }
    }

    private static CFStringRef cfString(String value) {
        return CF.CFStringCreateWithCharacters(null, value.toCharArray(), new CFIndex(value.length()));
    }

    private static String cfStringValue(Pointer pointer) {
        if (pointer == null) {
            return null;
        }
        String value = new CFStringRef(pointer).stringValue();
        return value == null || value.isEmpty() ? null : value;
    }

    private static void release(CoreFoundation.CFTypeRef ref) {
        if (ref != null) {
            CF.CFRelease(ref);
        }
    }

    /** Funciones mínimas de CoreGraphics para listar ventanas (no vienen en jna-platform). */
    private interface CoreGraphics extends Library {
        CoreGraphics INSTANCE = Native.load("CoreGraphics", CoreGraphics.class);

        CFArrayRef CGWindowListCopyWindowInfo(int option, int relativeToWindow);
    }
}
