package eu.wohlben.qits.cli.bootstrap.phases;

import eu.wohlben.qits.cli.bootstrap.platform.Docker;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessResult;
import eu.wohlben.qits.cli.bootstrap.proc.ScriptedRunner;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What preflight does about the daemon's swarm, state by state. One state is repaired and every
 * other one stops the run, because initialising over a swarm that is somebody else's tears a
 * machine out of a cluster it belongs to.
 */
class SwarmPreflightTest {

    /** The answers in order, so a call that should not happen runs out of script and shows up. */
    private static ScriptedRunner answering(ProcessResult... answers) {
        Deque<ProcessResult> queue = new ArrayDeque<>(List.of(answers));
        return new ScriptedRunner(command -> queue.isEmpty()
                ? ScriptedRunner.failed("unexpected: " + String.join(" ", command))
                : queue.removeFirst());
    }

    @Test
    void aDaemonInNobodysSwarmIsMadeOne() {
        ScriptedRunner runner = answering(
                ScriptedRunner.ok("inactive false"),
                ScriptedRunner.ok("Swarm initialized: current node is now a manager."),
                ScriptedRunner.ok("active true"));

        assertThat(SeedPhases.ensureSwarm(new Docker(runner), null))
                .isEqualTo("active (initialised by this run)");
        assertThat(runner.lines()).contains("docker swarm init");
    }

    @Test
    void anActiveManagerIsLeftAlone() {
        ScriptedRunner runner = answering(ScriptedRunner.ok("active true"));

        assertThat(SeedPhases.ensureSwarm(new Docker(runner), null)).isEqualTo("active");
        assertThat(runner.lines()).noneMatch(line -> line.contains("swarm init"));
    }

    /** Active, but taking its orders from a manager elsewhere: it creates no overlay of its own. */
    @Test
    void aWorkerStopsTheRunUntouched() {
        ScriptedRunner runner = answering(ScriptedRunner.ok("active false"));

        assertThatThrownBy(() -> SeedPhases.ensureSwarm(new Docker(runner), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active but not a manager")
                .hasMessageContaining("WORKER");
        assertThat(runner.lines()).noneMatch(line -> line.contains("swarm init"));
    }

    @Test
    void aJoinInFlightStopsTheRunUntouched() {
        ScriptedRunner runner = answering(ScriptedRunner.ok("pending true"));

        assertThatThrownBy(() -> SeedPhases.ensureSwarm(new Docker(runner), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending")
                .hasMessageContaining("docker swarm leave --force");
        assertThat(runner.lines()).noneMatch(line -> line.contains("swarm init"));
    }

    @Test
    void aLockedSwarmStopsTheRunUntouched() {
        ScriptedRunner runner = answering(ScriptedRunner.ok("locked true"));

        assertThatThrownBy(() -> SeedPhases.ensureSwarm(new Docker(runner), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("locked")
                .hasMessageContaining("docker swarm unlock");
        assertThat(runner.lines()).noneMatch(line -> line.contains("swarm init"));
    }

    /** A daemon that did not answer at all is a state like any other: named, and not repaired. */
    @Test
    void anUnreadableStateStopsTheRunUntouched() {
        ScriptedRunner runner = answering(ScriptedRunner.failed("Cannot connect to the daemon"));

        assertThatThrownBy(() -> SeedPhases.ensureSwarm(new Docker(runner), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown");
        assertThat(runner.lines()).noneMatch(line -> line.contains("swarm init"));
    }

    /**
     * The multi-interface host: docker will not choose the address, and neither can this run — it
     * is a container, so the routes it reads are docker's. What it owes the operator is the command.
     */
    @Test
    void anInitThatCannotChooseAnAddressSaysWhatToRunByHand() {
        ScriptedRunner runner = answering(
                ScriptedRunner.ok("inactive false"),
                ScriptedRunner.failed("Error response from daemon: could not choose an IP address "
                        + "to advertise since this system has multiple addresses on different "
                        + "interfaces (172.17.0.1 on docker0 and 192.168.1.10 on eth0) - specify "
                        + "one with --advertise-addr"));

        assertThatThrownBy(() -> SeedPhases.ensureSwarm(new Docker(runner), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("docker swarm init --advertise-addr <ip>");
    }

    @Test
    void anInitThatFailedForAnyOtherReasonSaysDockersOwnWords() {
        ScriptedRunner runner = answering(
                ScriptedRunner.ok("inactive false"),
                ScriptedRunner.failed("Error response from daemon: address already in use"));

        assertThatThrownBy(() -> SeedPhases.ensureSwarm(new Docker(runner), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("address already in use");
    }

    /** Read back rather than trusted: what the phases after this one need is a daemon that says so. */
    @Test
    void anInitThatChangedNothingIsStillAFailure() {
        ScriptedRunner runner = answering(
                ScriptedRunner.ok("inactive false"),
                ScriptedRunner.ok("Swarm initialized"),
                ScriptedRunner.ok("inactive false"));

        assertThatThrownBy(() -> SeedPhases.ensureSwarm(new Docker(runner), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still says inactive");
    }
}
