package org.foranly.craftify.client.lyrics;

/**
 * A single synchronized lyric line.
 *
 * @param timeSeconds the timestamp of the line, in seconds since the start of the song
 * @param text        the line text
 */
public record LyricLine(double timeSeconds, String text) {
}
