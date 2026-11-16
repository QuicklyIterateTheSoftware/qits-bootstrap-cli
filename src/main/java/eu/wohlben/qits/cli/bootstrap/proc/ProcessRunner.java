package eu.wohlben.qits.cli.bootstrap.proc;

import eu.wohlben.qits.containers.driver.docker.ContainerProcess;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shells out, with the output merged and streamed line by line rather than collected and dumped at
 * the end. That streaming is the point of this program: a cold native build is fifty silent minutes
 * unless someone is reading it out loud.
 * <p>
 * Memory is bounded whatever the command prints: the first {@code captureLimit} lines are kept for
 * parsing, the last few hundred for the error message, and every line goes to the log and the
 * consumer as it arrives.
 */
public class ProcessRunner {

    private static final int TAIL_LINES = 300;

    private final RunLog log;

    public ProcessRunner(RunLog log) {
        this.log = log;
    }

    public ProcessResult run(Cmd cmd, Consumer<String> sink) {
        if (log != null) {
            log.line("$ " + cmd.display());
        }
        if (sink != null) {
            sink.accept("$ " + cmd.display());
        }

        List<String> captured = new ArrayList<>();
        TailBuffer tail = new TailBuffer(TAIL_LINES);
        boolean[] truncated = {false};

        // Spawning, the reader thread, stdin, the deadline and the kill-and-reap on interrupt are
        // the library's (qits-2212): the one process host the platform's docker callers share, so
        // a fix there (qits-894's reap) lands here too. Masking, cleaning, capture and the tail
        // stay here, because only this side knows which values are secrets.
        ContainerProcess.Result result = ContainerProcess.stream(
                cmd.workDir(), cmd.command(), cmd.environment(), cmd.stdinText(), cmd.timeout(),
                raw -> {
                    String line = cmd.maskText(Ansi.clean(raw));
                    synchronized (captured) {
                        if (captured.size() < cmd.captureLimit()) {
                            captured.add(line);
                        } else {
                            truncated[0] = true;
                        }
                    }
                    emit(sink, tail, line);
                });

        if (result.timedOut()) {
            emit(sink, tail, "!! killed after " + cmd.timeout());
            return new ProcessResult(-1, snapshot(captured), tail.all(), true, truncated[0]);
        }
        // Exit 127 on a start failure: the library has already delivered "cannot start <argv0>:
        // <reason>" as a line, so it sits in the capture and the tail like any other.
        return new ProcessResult(result.exitCode(), snapshot(captured), tail.all(), false, truncated[0]);
    }

    private static List<String> snapshot(List<String> captured) {
        synchronized (captured) {
            return List.copyOf(captured);
        }
    }

    private void emit(Consumer<String> sink, TailBuffer tail, String line) {
        tail.add(line);
        if (log != null) {
            log.line(line);
        }
        if (sink != null) {
            sink.accept(line);
        }
    }
}
