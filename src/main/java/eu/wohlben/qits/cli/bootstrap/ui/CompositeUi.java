package eu.wohlben.qits.cli.bootstrap.ui;

import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseOutcome;
import eu.wohlben.qits.cli.bootstrap.engine.RunResult;

import java.util.List;
import java.util.function.Consumer;

/**
 * One run, shown in more than one place: the terminal and the browser get every event.
 * <p>
 * The terminal display is the primary one — it decides what {@link #live()} answers, and a failure
 * in it is a real failure. The extra ones are watchers: if one throws, the boot carries on without
 * it, because no boot should end because a browser view broke.
 */
public class CompositeUi implements Ui {

    private final Ui primary;
    private final List<Ui> extras;

    public CompositeUi(Ui primary, Ui... extras) {
        this.primary = primary;
        this.extras = List.of(extras);
    }

    private void each(Consumer<Ui> call) {
        call.accept(primary);
        for (Ui extra : extras) {
            try {
                call.accept(extra);
            } catch (RuntimeException e) {
                // A watcher that fails is not a reason to stop the boot.
            }
        }
    }

    @Override
    public void started(List<Phase> phases) {
        each(ui -> ui.started(phases));
    }

    @Override
    public void phaseStarted(int index, Phase phase) {
        each(ui -> ui.phaseStarted(index, phase));
    }

    @Override
    public void output(String line) {
        each(ui -> ui.output(line));
    }

    @Override
    public void status(String status) {
        each(ui -> ui.status(status));
    }

    @Override
    public void phaseFinished(PhaseOutcome outcome) {
        each(ui -> ui.phaseFinished(outcome));
    }

    @Override
    public void message(String line) {
        each(ui -> ui.message(line));
    }

    @Override
    public void event(String line) {
        each(ui -> ui.event(line));
    }

    @Override
    public void finished(RunResult result) {
        each(ui -> ui.finished(result));
    }

    /** The terminal's answer: it is the one that takes the screen and has to give it back. */
    @Override
    public boolean live() {
        return primary.live();
    }

    @Override
    public void close() {
        for (Ui extra : extras) {
            try {
                extra.close();
            } catch (RuntimeException e) {
                // Closing a watcher must not stop the terminal being handed back.
            }
        }
        primary.close();
    }
}
