package eu.wohlben.qits.cli.bootstrap.engine;

/**
 * What a phase may say while it runs. Everything a phase knows about its own progress goes through
 * here, because everything a phase knows is what the screen shows.
 */
public interface PhaseContext {

    /** One line of the running step's output. Lands in the body region and in the full log. */
    void log(String line);

    /**
     * What this phase is waiting on right now — the poll target, the observed state, the elapsed
     * time and the deadline. Replaces the previous status rather than scrolling.
     */
    void status(String status);

    /** A short note carried on this phase's line in the header once it is done. */
    void note(String note);

    /**
     * Something is wrong, but not wrong enough to stop the boot. The phase ends WARNED and the run
     * exits nonzero — the script's `overall=1`.
     */
    void warn(String message);

    /** Nothing to do. Ends the phase as SKIPPED with the reason on its header line. */
    default void skip(String reason) {
        throw new PhaseSkipped(reason);
    }
}
