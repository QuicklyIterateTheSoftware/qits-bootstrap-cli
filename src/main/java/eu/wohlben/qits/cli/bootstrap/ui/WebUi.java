package eu.wohlben.qits.cli.bootstrap.ui;

import eu.wohlben.qits.cli.bootstrap.config.BootstrapConfig;
import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseOutcome;
import eu.wohlben.qits.cli.bootstrap.engine.RunResult;

import java.util.List;

/**
 * The browser view of a run. It draws nothing itself: it keeps the run's state where the HTTP
 * layer can serve it, so a page at {@code http://127.0.0.1:8480} shows the same phases, the same
 * wait and the same tail as the terminal does.
 * <p>
 * It sits BESIDE the terminal display rather than instead of it — see {@link CompositeUi}. A boot
 * that nobody watches in a browser costs one bounded ring of lines.
 */
public class WebUi implements Ui {

    /** Long enough for two of the stream's quarter-second passes, short enough not to be felt. */
    private static final int DRAIN_TRIES = 6;
    private static final long DRAIN_MILLIS = 100;

    private final BootState state;

    public WebUi(BootstrapConfig config) {
        this.state = new BootState(config.tailLines());
        this.state.logPath(config.logFile());
        BootState.publish(this.state);
    }

    public BootState state() {
        return state;
    }

    @Override
    public void started(List<Phase> phases) {
        state.started(phases);
    }

    @Override
    public void phaseStarted(int index, Phase phase) {
        state.phaseStarted(index, phase);
    }

    @Override
    public void output(String line) {
        state.output(line);
    }

    @Override
    public void status(String status) {
        state.status(status);
    }

    @Override
    public void phaseFinished(PhaseOutcome outcome) {
        state.phaseFinished(outcome);
    }

    @Override
    public void message(String line) {
        state.output(line);
    }

    @Override
    public void finished(RunResult result) {
        state.finished(result);
    }

    /**
     * Nothing to give back: this display owns no terminal. What it does do is wait a moment while
     * a browser is connected, because the process exits straight after this and the server with
     * it — and the last frames, the ones saying how the run ended, are still on their way out.
     */
    @Override
    public void close() {
        for (int i = 0; i < DRAIN_TRIES && BootState.watched(); i++) {
            try {
                Thread.sleep(DRAIN_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
