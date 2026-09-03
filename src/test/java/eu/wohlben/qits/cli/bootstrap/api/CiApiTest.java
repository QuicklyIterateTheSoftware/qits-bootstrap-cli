package eu.wohlben.qits.cli.bootstrap.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the manual door's answer is read for, and it is one question: did any pipeline select this
 * release?
 * <p>
 * The two answers take different roads in the bring-up — a run to wait for, or a release handed
 * straight to the deployer — so reading an empty list as "started something" would leave a phase
 * waiting out its whole hour on a build nobody ever queued.
 */
class CiApiTest {

    @Test
    void theRunsATriggerStartedAreTheOnesItNames() {
        assertThat(CiApi.triggeredRunIds(new Http.Response(200,
                "{\"eventId\":\"e1\",\"runIds\":[\"r1\",\"r2\"],\"repositoriesRead\":19}")))
                .containsExactly("r1", "r2");
    }

    /** 200 with nothing in it: every candidate was read and no trigger file selected the event. */
    @Test
    void aTriggerThatSelectedNothingStartedNothing() {
        assertThat(CiApi.triggeredRunIds(new Http.Response(200,
                "{\"eventId\":\"e1\",\"runIds\":[],\"repositoriesRead\":19}")))
                .isEmpty();
    }

    /** A refusal is not an empty run list — the caller decides what to do about it, not this. */
    @Test
    void aRefusedTriggerNamesNoRuns() {
        assertThat(CiApi.triggeredRunIds(new Http.Response(503, "the candidate read timed out")))
                .isEmpty();
        assertThat(CiApi.triggeredRunIds(new Http.Response(0, "connection refused"))).isEmpty();
    }
}
