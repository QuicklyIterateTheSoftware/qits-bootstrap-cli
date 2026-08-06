package eu.wohlben.qits.cli.bootstrap.engine;

/**
 * One step of the boot. The list of them is built at startup from configuration, which is what
 * makes the "3/47" in the header a real count rather than a guess.
 *
 * @param id     stable, machine-readable, unique in a run
 * @param title  what the header says while this phase runs
 * @param action the work
 */
public record Phase(String id, String title, PhaseAction action) {
}
