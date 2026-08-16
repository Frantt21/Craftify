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
 * Native (JNA/CoreGraphics) probe for macOS: detects Spotify and reads its window title
 * without spawning external processes.
 *
 * <p>Replaces {@code pgrep} + {@code osascript}, which took ~200–500 ms per query (the
 * AppleScript interpreter startup dominates the cost). A single native call to
 * {@code CGWindowListCopyWindowInfo} returns the window list with its owner and name:
 *
 * <ul>
 *   <li>the window **owner** (e.g. {@code Spotify}) is always available;</li>
 *   <li>the **name** (title) requires the Screen Recording permission on macOS 10.15+;
 *       if it is missing, the title stays empty;</li>
 *   <li>the main window's title is the **account tier** ("Spotify Free"/"Spotify
 *       Premium"), never the song, so it is skipped and the caller falls back to
 *       Spotify's AppleScript dictionary for the real track.</li>
 * </ul>
 *
 * <p>The snapshot reports {@link Status#CLOSED} when Spotify is not running,
 * {@link Status#PLAYING} when a real window title was found, and
 * {@link Status#UNKNOWN} when it is running but only account-tier titles exist — the
 * caller resolves the real state via AppleScript in that case.
 */
public final class MacosSpotify {

    /** {@code kCGWindowListOptionAll}: list all windows, even off-screen ones. */
    private static final int KCG_WINDOW_LIST_OPTION_ALL = 0;
    /** {@code kCGNullWindowID}. */
    private static final int KCG_NULL_WINDOW_ID = 0;

    private static final CoreGraphics CG = CoreGraphics.INSTANCE;
    private static final CoreFoundation CF = CoreFoundation.INSTANCE;

    private MacosSpotify() {
    }

    /**
     * State read with a single native call.
     *
     * @return {@code null} if CoreGraphics returned no data; the caller must fall back to
     *         the CLI
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
                        String candidate = cfStringValue(window.getValue(nameKey));
                        // The main window's title is the account tier ("Spotify Free"/
                        // "Spotify Premium"), never the song. Skip it so the caller falls
                        // back to Spotify's AppleScript dictionary for the real track.
                        if (candidate != null && !SpotifyProcess.isAccountTierTitle(candidate)) {
                            title = candidate;
                        }
                    }
                }
            } finally {
                release(ownerKey);
                release(nameKey);
            }
            if (!running) {
                return new SpotifyProcess.Snapshot(SpotifyProcess.Status.CLOSED, null);
            }
            return title == null
                    ? new SpotifyProcess.Snapshot(SpotifyProcess.Status.UNKNOWN, null)
                    : new SpotifyProcess.Snapshot(SpotifyProcess.Status.PLAYING, title);
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

    /** Minimal CoreGraphics functions for listing windows (not in jna-platform). */
    private interface CoreGraphics extends Library {
        CoreGraphics INSTANCE = Native.load("CoreGraphics", CoreGraphics.class);

        CFArrayRef CGWindowListCopyWindowInfo(int option, int relativeToWindow);
    }
}
