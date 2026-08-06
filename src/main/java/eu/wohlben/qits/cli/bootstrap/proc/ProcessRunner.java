package eu.wohlben.qits.cli.bootstrap.proc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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

        ProcessBuilder builder = new ProcessBuilder(cmd.command());
        builder.redirectErrorStream(true);
        if (cmd.workDir() != null) {
            builder.directory(cmd.workDir().toFile());
        }
        Map<String, String> env = builder.environment();
        env.putAll(cmd.environment());

        List<String> captured = new ArrayList<>();
        TailBuffer tail = new TailBuffer(TAIL_LINES);
        boolean[] truncated = {false};

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            String message = "cannot start " + cmd.command().getFirst() + ": " + e.getMessage();
            emit(sink, tail, message);
            return new ProcessResult(127, List.of(message), tail.all(), false, false);
        }

        writeStdin(process, cmd);

        // The output is read on its own thread so that the timeout below is a real deadline: a
        // command that prints nothing and never exits would otherwise be waited on inside
        // readLine, where no clock is looking.
        Thread reader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String raw;
                while ((raw = lines.readLine()) != null) {
                    String line = cmd.maskText(Ansi.clean(raw));
                    if (captured.size() < cmd.captureLimit()) {
                        captured.add(line);
                    } else {
                        truncated[0] = true;
                    }
                    emit(sink, tail, line);
                }
            } catch (IOException e) {
                emit(sink, tail, "output stream ended: " + e.getMessage());
            }
        }, "output-reader");
        reader.setDaemon(true);
        reader.start();

        boolean timedOut = false;
        int exit;
        try {
            if (process.waitFor(cmd.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                exit = process.exitValue();
            } else {
                timedOut = true;
                process.destroyForcibly().waitFor();
                exit = -1;
                emit(sink, tail, "!! killed after " + cmd.timeout());
            }
            // join, not a race: the reader's last lines are the ones an error message quotes.
            reader.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            exit = -1;
        }
        return new ProcessResult(exit, List.copyOf(captured), tail.all(), timedOut, truncated[0]);
    }

    /**
     * stdin is written on its own thread: a Dockerfile fed on stdin can be larger than the pipe
     * buffer, and a single-threaded write would deadlock against a command already printing.
     */
    private void writeStdin(Process process, Cmd cmd) {
        String text = cmd.stdinText();
        if (text == null) {
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // The command may have exited already; nothing to do about it.
            }
            return;
        }
        Thread writer = new Thread(() -> {
            try (OutputStream out = process.getOutputStream()) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // A command that stops reading before we stop writing is its own business.
            }
        }, "stdin-writer");
        writer.setDaemon(true);
        writer.start();
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
