package eu.wohlben.qits.cli.bootstrap.ui;

import java.time.Duration;

/** Small shapes the header repeats a lot. */
public final class Format {

    private Format() {
    }

    /** "9s", "2m10s", "1h04m". Short enough to sit at the end of a header line. */
    public static String duration(Duration d) {
        long seconds = Math.max(0, d.toSeconds());
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m" + String.format("%02ds", seconds % 60);
        }
        return (seconds / 3600) + "h" + String.format("%02dm", (seconds % 3600) / 60);
    }

    /** Cuts a line to the width of the terminal so nothing wraps into the next region. */
    public static String fit(String line, int width) {
        String text = line == null ? "" : line;
        if (width <= 1) {
            return "";
        }
        if (text.length() <= width) {
            return text;
        }
        return text.substring(0, width - 1) + "…";
    }
}
