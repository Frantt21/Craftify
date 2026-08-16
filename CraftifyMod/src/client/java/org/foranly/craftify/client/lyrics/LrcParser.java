package org.foranly.craftify.client.lyrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the LRC format returned by LRCLib ({@code syncedLyrics}).
 *
 * <p>Each line is {@code [mm:ss.xx]text}; a line may carry several timestamps
 * ({@code [00:12.34][00:45.67]text}). Metadata tags ({@code [ti:]}, {@code [ar:]},
 * {@code [offset:]}, ...) are skipped. The {@code offset} tag (milliseconds) shifts all
 * timestamps: a positive offset means the lyrics should appear earlier.
 */
public final class LrcParser {

    private static final Pattern TIME_TAG = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)]");
    private static final Pattern OFFSET_TAG = Pattern.compile("\\[offset:([+-]?\\d+)]", Pattern.CASE_INSENSITIVE);

    private LrcParser() {
    }

    /**
     * Parses an LRC document into lyric lines.
     *
     * @return the lines sorted by time (empty if there are no synced lines)
     */
    public static List<LyricLine> parse(String lrc) {
        double offsetSeconds = 0.0;
        Matcher offsetMatcher = OFFSET_TAG.matcher(lrc);
        if (offsetMatcher.find()) {
            offsetSeconds = Integer.parseInt(offsetMatcher.group(1)) / 1000.0;
        }

        List<LyricLine> lines = new ArrayList<>();
        for (String raw : lrc.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            // Collect every timestamp of the line and the text after the last one.
            Matcher matcher = TIME_TAG.matcher(line);
            List<Double> times = new ArrayList<>();
            int textStart = -1;
            while (matcher.find()) {
                int minutes = Integer.parseInt(matcher.group(1));
                double seconds = Double.parseDouble(matcher.group(2));
                times.add(minutes * 60.0 + seconds - offsetSeconds);
                textStart = matcher.end();
            }
            if (times.isEmpty() || textStart < 0) {
                continue; // metadata tag or non-timed line
            }
            String text = line.substring(textStart).strip();
            if (text.isEmpty()) {
                continue;
            }
            for (double time : times) {
                lines.add(new LyricLine(time, text));
            }
        }
        lines.sort(Comparator.comparingDouble(LyricLine::timeSeconds));
        return lines;
    }
}
