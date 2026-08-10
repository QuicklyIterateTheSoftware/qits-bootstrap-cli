package eu.wohlben.qits.cli.bootstrap.ui;

import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseOutcome;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseState;
import eu.wohlben.qits.cli.bootstrap.engine.RunResult;
import eu.wohlben.qits.cli.bootstrap.proc.TailBuffer;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Display;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The live display: a header that says where the boot is, and under it the output of the step that
 * is running right now.
 *
 * <pre>
 *   qits bootstrap · 12m30s elapsed · log qits-bootstrap-cli.log
 *     … 6 earlier phases done
 *     ok  7/47 publish qits-eventstream 1.0.0 (41s)
 *     ok  8/47 build the seed image qits/ci:latest (11m02s)
 *   ▸  9/45 build the seed image qits/idp:latest       ⠹ 2m10s
 *      waiting for the native build — 2m10s elapsed
 *   ────────────────────────────────────┬─ platform events ──
 *   [INFO] Building qits-platform-idp   │ 14:22:01 SCMRelease qits-stt 1.4.0
 *   ... the last lines of the command   │ 14:22:07 BuildSuccessful qits-stt
 * </pre>
 *
 * The lower region is two columns: on the left what the running step is printing, on the right what
 * the PLATFORM is announcing while it does it. The split is fixed for a given terminal width so the
 * divider does not move under the reader, and on a narrow terminal there is no right column at all —
 * cutting build output to half of eighty columns shows nothing worth reading.
 * <p>
 * The whole frame is repainted on a 250 ms timer, so a long silence still shows a moving clock —
 * which is the difference between "it is working" and "it is stuck".
 */
public class TuiUi implements Ui {

    private static final String[] SPINNER_UNICODE = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final String[] SPINNER_ASCII = {"|", "/", "-", "\\"};
    private static final int MAX_DONE_LINES = 6;

    /** Below this the screen is one column and the event pane is dropped. */
    static final int MIN_SPLIT_WIDTH = 100;

    /** What the event column is kept between: wide enough for a name and a version, never greedy. */
    static final int MIN_EVENT_COLUMN = 36;
    static final int MAX_EVENT_COLUMN = 60;

    /** Between the columns. Three cells, so the divider line's ┬ sits under the │. */
    static final String GUTTER = " │ ";

    /** Event lines kept for the right column. A screen holds tens; this holds a scroll of them. */
    private static final int EVENT_LINES = 500;

    private final Terminal terminal;
    private final Display display;
    private final TailBuffer tail;
    private final TailBuffer eventTail = new TailBuffer(EVENT_LINES);
    private final boolean unicode;
    private final String logPath;
    private final ScheduledExecutorService ticker =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tui-redraw");
                t.setDaemon(true);
                return t;
            });

    private List<Phase> phases = List.of();
    private final List<PhaseOutcome> finished = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    private int currentIndex = -1;
    private Phase current;
    private long currentStartNanos;
    private long runStartNanos;
    private String status = "";
    private RunResult result;
    private int rows;
    private int cols;
    private long frame;
    private boolean closed;

    public TuiUi(Terminal terminal, int tailLines, String logPath) {
        this.terminal = terminal;
        this.tail = new TailBuffer(Math.max(50, tailLines));
        this.logPath = logPath;
        this.display = new Display(terminal, true);
        this.unicode = StandardCharsets.UTF_8.equals(terminal.encoding());
        this.runStartNanos = System.nanoTime();
        ticker.scheduleAtFixedRate(this::tick, 250, 250, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean live() {
        return true;
    }

    private void tick() {
        try {
            render();
        } catch (RuntimeException e) {
            // A redraw that throws must never take the boot down with it.
        }
    }

    @Override
    public synchronized void started(List<Phase> phases) {
        this.phases = List.copyOf(phases);
        this.runStartNanos = System.nanoTime();
        render();
    }

    @Override
    public synchronized void phaseStarted(int index, Phase phase) {
        this.currentIndex = index;
        this.current = phase;
        this.currentStartNanos = System.nanoTime();
        this.status = "";
        tail.clear();
        render();
    }

    @Override
    public synchronized void output(String line) {
        tail.add(line);
    }

    @Override
    public synchronized void status(String status) {
        this.status = status;
    }

    @Override
    public synchronized void phaseFinished(PhaseOutcome outcome) {
        finished.add(outcome);
        if (outcome.state() == PhaseState.FAILED) {
            // The tail stays: the failing output is the most useful thing on the screen.
            status = "";
        }
        current = null;
        render();
    }

    @Override
    public synchronized void message(String line) {
        messages.add(line);
        tail.add(line);
    }

    /**
     * The right column's own tail. It is NOT cleared when a phase starts, the way the left one is:
     * the platform's account of itself runs across phases, and that continuity is the point of
     * showing it beside them.
     */
    @Override
    public synchronized void event(String line) {
        eventTail.add(line);
    }

    @Override
    public synchronized void finished(RunResult result) {
        this.result = result;
        render();
    }

    private synchronized void render() {
        if (closed) {
            return;
        }
        frame++;
        int width = Math.max(40, terminal.getWidth() > 0 ? terminal.getWidth() : 100);
        int height = Math.max(12, terminal.getHeight() > 0 ? terminal.getHeight() : 30);
        if (width != cols || height != rows) {
            cols = width;
            rows = height;
            display.clear();
            display.resize(rows, cols);
        }

        List<AttributedString> lines = new ArrayList<>();
        lines.add(styled(title(width), AttributedStyle.BOLD.foreground(AttributedStyle.CYAN), width));

        int hidden = Math.max(0, finished.size() - MAX_DONE_LINES);
        if (hidden > 0) {
            lines.add(styled("  … " + hidden + " earlier phase" + (hidden == 1 ? "" : "s")
                    + " done", faint(), width));
        }
        for (PhaseOutcome outcome : finished.subList(hidden, finished.size())) {
            lines.add(styled("  " + doneLine(outcome), styleOf(outcome.state()), width));
        }

        if (current != null) {
            String spinner = spinner();
            String elapsed = Format.duration(Duration.ofNanos(System.nanoTime() - currentStartNanos));
            lines.add(styled(pointer() + " " + position(currentIndex) + " " + current.title()
                            + "   " + spinner + " " + elapsed,
                    AttributedStyle.BOLD, width));
            if (!status.isBlank()) {
                lines.add(styled("     " + status, AttributedStyle.DEFAULT
                        .foreground(AttributedStyle.YELLOW), width));
            }
        }

        int pending = phases.size() - finished.size() - (current != null ? 1 : 0);
        if (pending > 0) {
            String next = "";
            int nextIndex = finished.size() + (current != null ? 1 : 0);
            if (nextIndex < phases.size()) {
                next = " — next: " + phases.get(nextIndex).title();
            }
            lines.add(styled("  " + pending + " phase" + (pending == 1 ? "" : "s") + " pending"
                    + next, faint(), width));
        }
        if (result != null) {
            lines.add(styled("  " + PlainUi.summary(result),
                    result.exitCode() == 0
                            ? AttributedStyle.BOLD.foreground(AttributedStyle.GREEN)
                            : AttributedStyle.BOLD.foreground(AttributedStyle.RED), width));
        }
        int events = eventColumn(width);
        lines.add(styled(divider(width, events), faint(), width));

        int body = Math.max(3, rows - lines.size());
        List<String> tailLines = tail.last(body);
        List<String> eventLines = events > 0 ? eventTail.last(body) : List.of();
        for (int i = 0; i < body; i++) {
            String left = i < tailLines.size() ? tailLines.get(i) : "";
            String right = i < eventLines.size() ? eventLines.get(i) : "";
            if (left.isEmpty() && right.isEmpty()) {
                lines.add(new AttributedString(""));
            } else {
                lines.add(styled(row(left, right, width, events), AttributedStyle.DEFAULT, width));
            }
        }

        display.update(lines, 0);
    }

    /**
     * How many columns the event pane gets, or 0 for "this terminal is too narrow to split".
     * <p>
     * A third of the screen, bounded at both ends: less than {@link #MIN_EVENT_COLUMN} shows a
     * timestamp and half a name, and more than {@link #MAX_EVENT_COLUMN} takes room from build
     * output for a column whose lines are short by construction. It depends on the width alone, so
     * the divider only ever moves when the terminal is resized.
     */
    static int eventColumn(int width) {
        if (width < MIN_SPLIT_WIDTH) {
            return 0;
        }
        return Math.clamp(width / 3, MIN_EVENT_COLUMN, MAX_EVENT_COLUMN);
    }

    /**
     * One row of the lower region: the step's line, padded to the split, then the event line.
     * <p>
     * Each side is cut to its OWN width before they are joined. JLine's frame arithmetic breaks on a
     * line that wraps, and cutting the pair afterwards would cut the event column away rather than
     * the overlong build line that caused it. The padding is what makes the divider a straight
     * vertical: without it the gutter would sit wherever the left line happened to end.
     */
    static String row(String left, String right, int width, int eventColumn) {
        if (eventColumn <= 0) {
            return Format.fit(left, width);
        }
        int leftWidth = width - eventColumn - GUTTER.length();
        String cut = Format.fit(left, leftWidth);
        return cut + " ".repeat(leftWidth - cut.length()) + GUTTER + Format.fit(right, eventColumn);
    }

    /** The rule under the header, carrying the right column's only label. */
    static String divider(int width, int eventColumn) {
        if (eventColumn <= 0) {
            return "─".repeat(width);
        }
        String label = " platform events ";
        return "─".repeat(width - eventColumn - GUTTER.length()) + "─┬─"
                + label + "─".repeat(eventColumn - label.length());
    }

    private String title(int width) {
        String elapsed = Format.duration(Duration.ofNanos(System.nanoTime() - runStartNanos));
        return Format.fit("qits bootstrap · " + elapsed + " elapsed · log " + logPath, width);
    }

    private String doneLine(PhaseOutcome outcome) {
        String note = outcome.note() == null || outcome.note().isBlank() ? ""
                : "  — " + outcome.note();
        return mark(outcome.state()) + " " + position(outcome.index()) + " "
                + outcome.phase().title() + " (" + Format.duration(outcome.took()) + ")" + note;
    }

    private String position(int index) {
        return (index + 1) + "/" + phases.size();
    }

    private String mark(PhaseState state) {
        return switch (state) {
            case DONE -> unicode ? "✓" : "ok";
            case SKIPPED -> unicode ? "·" : "--";
            case WARNED -> "!";
            case FAILED -> unicode ? "✗" : "XX";
            default -> " ";
        };
    }

    private String pointer() {
        return unicode ? "▸" : ">";
    }

    private String spinner() {
        String[] frames = unicode ? SPINNER_UNICODE : SPINNER_ASCII;
        return frames[(int) (frame % frames.length)];
    }

    private static AttributedStyle faint() {
        return AttributedStyle.DEFAULT.faint();
    }

    private static AttributedStyle styleOf(PhaseState state) {
        return switch (state) {
            case DONE -> AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN);
            case SKIPPED -> faint();
            case WARNED -> AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
            case FAILED -> AttributedStyle.BOLD.foreground(AttributedStyle.RED);
            default -> AttributedStyle.DEFAULT;
        };
    }

    private static AttributedString styled(String text, AttributedStyle style, int width) {
        return new AttributedStringBuilder().style(style).append(Format.fit(text, width))
                .toAttributedString();
    }

    /**
     * Gives the screen back. The last frame is repainted as ordinary scrollback, so what the run
     * did survives the program that drew it.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        ticker.shutdownNow();
        display.update(List.of(), 0);
        var writer = terminal.writer();
        for (PhaseOutcome outcome : finished) {
            writer.println(doneLine(outcome));
        }
        for (String message : messages) {
            writer.println(message);
        }
        if (result != null) {
            writer.println();
            writer.println(PlainUi.summary(result));
            PhaseOutcome failure = result.failure();
            if (failure != null) {
                writer.println();
                writer.println("FAILED: " + failure.phase().title());
                if (failure.error() != null) {
                    writer.println("  " + failure.error());
                }
                writer.println();
                writer.println("the last lines of that step (full log: " + logPath + "):");
                for (String line : tail.last(30)) {
                    writer.println("  " + line);
                }
            }
        }
        writer.flush();
        try {
            terminal.close();
        } catch (Exception ignored) {
            // Nothing useful to do while handing the terminal back.
        }
    }
}
