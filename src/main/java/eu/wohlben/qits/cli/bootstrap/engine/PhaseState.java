package eu.wohlben.qits.cli.bootstrap.engine;

/** How a phase ended. */
public enum PhaseState {
    /** Not reached yet. */
    PENDING,
    RUNNING,
    DONE,
    /** Ran, decided there was nothing to do, and said why. */
    SKIPPED,
    /** Finished, but something is wrong enough to make the run's exit code nonzero. */
    WARNED,
    /** Stopped the run. */
    FAILED
}
