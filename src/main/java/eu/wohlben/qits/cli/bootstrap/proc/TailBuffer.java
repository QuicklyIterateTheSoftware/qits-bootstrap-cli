package eu.wohlben.qits.cli.bootstrap.proc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The last N lines, and nothing older. A cold native build prints tens of thousands of lines; the
 * screen shows a page of them and the log file keeps the rest, so memory here is bounded by
 * construction.
 */
public class TailBuffer {

    private final int max;
    private final Deque<String> lines = new ArrayDeque<>();
    private long dropped;

    public TailBuffer(int max) {
        if (max < 1) {
            throw new IllegalArgumentException("a tail keeps at least one line");
        }
        this.max = max;
    }

    public synchronized void add(String line) {
        lines.addLast(line);
        while (lines.size() > max) {
            lines.removeFirst();
            dropped++;
        }
    }

    /** The last {@code n} lines, oldest first. */
    public synchronized List<String> last(int n) {
        int skip = Math.max(0, lines.size() - n);
        List<String> out = new ArrayList<>(Math.min(n, lines.size()));
        int i = 0;
        for (String line : lines) {
            if (i++ >= skip) {
                out.add(line);
            }
        }
        return out;
    }

    public synchronized List<String> all() {
        return new ArrayList<>(lines);
    }

    public synchronized int size() {
        return lines.size();
    }

    /** How many lines fell off the front. */
    public synchronized long dropped() {
        return dropped;
    }

    public synchronized void clear() {
        lines.clear();
        dropped = 0;
    }
}
