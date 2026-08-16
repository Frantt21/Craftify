package org.foranly.craftify.client.lyrics;

/**
 * Anchor of the lyrics overlay on the screen: 9 positions (top/middle/bottom x
 * left/center/right). The overlay is drawn flush against the chosen edge or corner,
 * with no margin.
 */
public enum LyricsPosition {

    TOP_LEFT("Top Left", "TL"),
    TOP_CENTER("Top Center", "TC"),
    TOP_RIGHT("Top Right", "TR"),
    MIDDLE_LEFT("Middle Left", "ML"),
    MIDDLE_CENTER("Middle Center", "MC"),
    MIDDLE_RIGHT("Middle Right", "MR"),
    BOTTOM_LEFT("Bottom Left", "BL"),
    BOTTOM_CENTER("Bottom Center", "BC"),
    BOTTOM_RIGHT("Bottom Right", "BR");

    private final String displayName;
    private final String shortName;

    LyricsPosition(String displayName, String shortName) {
        this.displayName = displayName;
        this.shortName = shortName;
    }

    /** Human-readable name, e.g. "Bottom Left". */
    public String displayName() {
        return displayName;
    }

    /** Two-letter label used in the position picker grid. */
    public String shortName() {
        return shortName;
    }

    /** Parses an id like {@code "bottom-left"}, {@code "bottomleft"} or {@code "BL"}. */
    public static LyricsPosition fromId(String id) {
        String normalized = id == null ? "" : id.replaceAll("[\\s_-]", "").toLowerCase(java.util.Locale.ROOT);
        for (LyricsPosition position : values()) {
            if (position.name().toLowerCase(java.util.Locale.ROOT).equals(normalized)
                    || position.shortName().toLowerCase(java.util.Locale.ROOT).equals(normalized)) {
                return position;
            }
        }
        throw new IllegalArgumentException("Unknown position: " + id);
    }
}
