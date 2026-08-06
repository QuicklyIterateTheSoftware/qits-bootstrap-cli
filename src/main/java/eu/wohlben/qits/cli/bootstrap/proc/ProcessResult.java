package eu.wohlben.qits.cli.bootstrap.proc;

import java.util.List;

/**
 * What a command did.
 *
 * @param exitCode   127 is this program's "could not start it"; -1 means it was killed on timeout
 * @param captured   the first {@code captureLimit} lines, for parsing
 * @param tail       the last lines, for saying what went wrong
 * @param timedOut   whether the timeout killed it
 * @param truncated  whether output ran past the capture limit
 */
public record ProcessResult(int exitCode, List<String> captured, List<String> tail, boolean timedOut,
                            boolean truncated) {

    public boolean ok() {
        return exitCode == 0;
    }

    /** The captured output as one string. */
    public String out() {
        return String.join("\n", captured);
    }

    /** The captured output with no surrounding whitespace — the shape a `git rev-parse` wants. */
    public String trimmed() {
        return out().trim();
    }

    public String tailText(int lines) {
        List<String> t = tail;
        int from = Math.max(0, t.size() - lines);
        return String.join("\n", t.subList(from, t.size()));
    }
}
