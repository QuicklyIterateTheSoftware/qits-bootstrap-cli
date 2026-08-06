package eu.wohlben.qits.cli.bootstrap.proc;

import java.util.regex.Pattern;

/**
 * Subprocess output is written for a terminal this program owns. Colours, cursor moves and
 * progress rewrites would fight the display's own arithmetic, so they are stripped before a line
 * reaches the body region or the log.
 */
public final class Ansi {

    private static final String ESC = "\u001B";

    private static final Pattern ESCAPES = Pattern.compile(
            ESC + "\\[[0-?]*[ -/]*[@-~]"                    // CSI: colours, cursor moves, erases
                    + "|" + ESC + "\\][^\u0007]*(?:\u0007|" + ESC + "\\\\)"  // OSC: window titles
                    + "|" + ESC + "[@-Z\\\\-_]");           // the two-character escapes

    private Ansi() {
    }

    /**
     * One clean line. A carriage return means the writer redrew the line in place, so only what it
     * ended with is kept.
     */
    public static String clean(String raw) {
        String line = raw;
        int cr = line.lastIndexOf('\r');
        if (cr >= 0) {
            line = line.substring(cr + 1);
        }
        line = ESCAPES.matcher(line).replaceAll("");
        line = line.replace('\t', ' ');
        return line.replaceAll("\\p{Cntrl}", "");
    }
}
