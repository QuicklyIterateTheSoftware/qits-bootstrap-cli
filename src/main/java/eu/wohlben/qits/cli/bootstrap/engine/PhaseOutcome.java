package eu.wohlben.qits.cli.bootstrap.engine;

import java.time.Duration;

/**
 * What became of one phase.
 *
 * @param index zero-based position in the plan
 * @param phase the phase itself
 * @param state how it ended
 * @param took  wall time
 * @param note  the skip reason, the warning, or whatever the phase wanted on its header line
 * @param error the failure, if it failed
 */
public record PhaseOutcome(int index, Phase phase, PhaseState state, Duration took, String note,
                           Throwable error) {

    public boolean failed() {
        return state == PhaseState.FAILED;
    }
}
