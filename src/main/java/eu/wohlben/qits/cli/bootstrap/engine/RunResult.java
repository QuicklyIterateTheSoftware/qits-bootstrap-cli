package eu.wohlben.qits.cli.bootstrap.engine;

import java.time.Duration;
import java.util.List;

/**
 * The whole run.
 *
 * @param exitCode 0 all good, 1 something warned, 2 a phase failed and stopped the boot
 */
public record RunResult(int exitCode, List<PhaseOutcome> outcomes, Duration took) {

    public PhaseOutcome failure() {
        return outcomes.stream().filter(PhaseOutcome::failed).findFirst().orElse(null);
    }

    public long count(PhaseState state) {
        return outcomes.stream().filter(o -> o.state() == state).count();
    }
}
