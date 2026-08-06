package eu.wohlben.qits.cli.bootstrap.engine;

/** Thrown by {@link PhaseContext#skip(String)}: nothing to do, and the reason. */
public class PhaseSkipped extends RuntimeException {
    public PhaseSkipped(String reason) {
        super(reason);
    }
}
