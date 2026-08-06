package eu.wohlben.qits.cli.bootstrap.ui;

import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseOutcome;
import eu.wohlben.qits.cli.bootstrap.engine.RunResult;

import java.util.List;

/**
 * Where a run shows itself. Two implementations: a live two-region terminal display, and plain
 * sequential lines for anything that is not a terminal.
 */
public interface Ui extends AutoCloseable {

    /** The plan, before anything runs. This is where the "of 47" comes from. */
    void started(List<Phase> phases);

    void phaseStarted(int index, Phase phase);

    /** One line of the running phase's output. */
    void output(String line);

    /** The running phase's current wait, replacing the previous one. */
    void status(String status);

    void phaseFinished(PhaseOutcome outcome);

    /** The last frame: the summary, and the failing tail if there is one. */
    void finished(RunResult result);

    /** Free text outside any phase — the closing summary of what was built. */
    void message(String line);

    /**
     * Whether this display redraws. A live one takes the screen back when it closes, so the caller
     * prints the closing report afterwards; a plain one has already printed everything it saw.
     */
    default boolean live() {
        return false;
    }

    @Override
    void close();
}
