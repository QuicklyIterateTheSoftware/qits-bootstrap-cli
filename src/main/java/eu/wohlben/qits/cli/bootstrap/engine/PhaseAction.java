package eu.wohlben.qits.cli.bootstrap.engine;

/** The work of a phase. Anything thrown fails the phase and stops the run. */
@FunctionalInterface
public interface PhaseAction {
    void run(PhaseContext ctx) throws Exception;
}
