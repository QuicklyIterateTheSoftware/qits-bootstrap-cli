package eu.wohlben.qits.cli.bootstrap.proc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The full log. The screen keeps a page of output; this keeps all of it, which is what makes the
 * rolling tail safe to be a tail.
 */
public class RunLog implements AutoCloseable {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Path path;
    private final Writer writer;

    public RunLog(Path path) {
        this.path = path;
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open the log file " + path, e);
        }
    }

    public Path path() {
        return path;
    }

    public synchronized void line(String line) {
        write(LocalDateTime.now().format(STAMP) + " " + line);
    }

    /** A section marker, so the log reads as the phases the screen showed. */
    public synchronized void section(String title) {
        write("");
        write("=== " + LocalDateTime.now().format(STAMP) + " " + title + " ===");
    }

    private void write(String text) {
        try {
            writer.write(text);
            writer.write(System.lineSeparator());
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            writer.close();
        } catch (IOException e) {
            // Closing the log is not worth failing a boot over.
        }
    }
}
