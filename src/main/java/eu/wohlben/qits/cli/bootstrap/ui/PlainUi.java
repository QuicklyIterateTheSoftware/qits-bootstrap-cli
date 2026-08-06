package eu.wohlben.qits.cli.bootstrap.ui;

import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseOutcome;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseState;
import eu.wohlben.qits.cli.bootstrap.engine.RunResult;

import java.io.PrintStream;
import java.time.Duration;
import java.util.List;

/**
 * The fallback for anything that is not a terminal: a pipe, a CI job, a dumb TERM. Same
 * information, in the order it happened, one line at a time — no cursor moves, no redraw, nothing
 * a log file cannot hold.
 */
public class PlainUi implements Ui {

    private final PrintStream out;
    private final long statusRepeatMillis;
    private int total;
    private int index;
    private String lastStatus = "";
    private long lastStatusAt;
    private boolean statusPrinted;
    private final LongClock clock;

    /** A clock so a test does not have to wait for the status throttle. */
    @FunctionalInterface
    public interface LongClock {
        long millis();
    }

    public PlainUi(PrintStream out) {
        this(out, 15_000, System::currentTimeMillis);
    }

    public PlainUi(PrintStream out, long statusRepeatMillis, LongClock clock) {
        this.out = out;
        this.statusRepeatMillis = statusRepeatMillis;
        this.clock = clock;
    }

    @Override
    public void started(List<Phase> phases) {
        total = phases.size();
        out.println("qits bootstrap: " + total + " phases");
    }

    @Override
    public void phaseStarted(int index, Phase phase) {
        this.index = index + 1;
        lastStatus = "";
        lastStatusAt = 0;
        statusPrinted = false;
        out.println();
        out.println("==> " + this.index + "/" + total + " " + phase.title());
    }

    @Override
    public void output(String line) {
        out.println("    " + line);
    }

    /**
     * A wait polls every ten seconds and its status carries the elapsed time, so printing every
     * one of them would bury an hour-long deployment in its own clock. What gets a line is a
     * change in WHAT is being waited on, or the repeat interval passing.
     */
    @Override
    public void status(String status) {
        if (status == null || status.isBlank()) {
            return;
        }
        long now = clock.millis();
        String subject = subject(status);
        boolean changed = !subject.equals(lastStatus);
        boolean stale = !statusPrinted || now - lastStatusAt >= statusRepeatMillis;
        if (changed || stale) {
            out.println("  · " + status);
            lastStatus = subject;
            lastStatusAt = now;
            statusPrinted = true;
        }
    }

    /** The part before the clock: what is being polled and what it last answered. */
    private static String subject(String status) {
        int clockAt = status.indexOf(" · ");
        return clockAt < 0 ? status : status.substring(0, clockAt);
    }

    @Override
    public void phaseFinished(PhaseOutcome outcome) {
        String note = outcome.note() == null || outcome.note().isBlank() ? ""
                : " — " + outcome.note();
        out.println(marker(outcome.state()) + " " + index + "/" + total + " "
                + outcome.phase().title() + " (" + Format.duration(outcome.took()) + ")" + note);
        if (outcome.state() == PhaseState.FAILED) {
            out.println();
            out.println("FAILED: " + outcome.phase().title());
            if (outcome.error() != null) {
                out.println("  " + outcome.error());
            }
        }
    }

    @Override
    public void message(String line) {
        out.println(line);
    }

    @Override
    public void finished(RunResult result) {
        out.println();
        out.println(summary(result));
    }

    /** The one-line verdict, shared with the live display. */
    static String summary(RunResult result) {
        Duration took = result.took();
        return switch (result.exitCode()) {
            case 0 -> "done: " + result.count(PhaseState.DONE) + " phases, "
                    + result.count(PhaseState.SKIPPED) + " skipped, in " + Format.duration(took);
            case 1 -> "finished with warnings: " + result.count(PhaseState.WARNED)
                    + " phase(s) need a look, in " + Format.duration(took);
            default -> "stopped after " + Format.duration(took) + ": "
                    + (result.failure() == null ? "unknown failure" : result.failure().phase().title());
        };
    }

    static String marker(PhaseState state) {
        return switch (state) {
            case DONE -> "  ok";
            case SKIPPED -> "skip";
            case WARNED -> "warn";
            case FAILED -> "FAIL";
            default -> "  ..";
        };
    }

    @Override
    public void close() {
        out.flush();
    }
}
