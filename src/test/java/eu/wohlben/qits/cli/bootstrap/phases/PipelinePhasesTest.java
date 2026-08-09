package eu.wohlben.qits.cli.bootstrap.phases;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What "the deployment landed" means to the platform-service wait, read off docker's status text.
 * The phases that shell docker are not unit-tested, but the sentence they judge a container by is
 * a pure function and it decided a boot wrongly once.
 */
class PipelinePhasesTest {

    @Test
    void healthyServes() {
        assertThat(PipelinePhases.serving("Up 3 minutes (healthy)")).isTrue();
    }

    @Test
    void startingDoesNotServeYet() {
        assertThat(PipelinePhases.serving("Up 6 seconds (health: starting)")).isFalse();
    }

    @Test
    void unhealthyDoesNotServe() {
        assertThat(PipelinePhases.serving("Up 2 minutes (unhealthy)")).isFalse();
    }

    /** The one that reported qits-platform-idp live while the deployer was failing it. */
    @Test
    void restartingDoesNotServe() {
        assertThat(PipelinePhases.serving("Restarting (1) 4 seconds ago")).isFalse();
    }

    /** No healthcheck, no verdict: being up is the whole test. */
    @Test
    void upWithoutAHealthcheckServes() {
        assertThat(PipelinePhases.serving("Up 11 seconds")).isTrue();
    }

    @Test
    void anythingNotUpDoesNotServe() {
        assertThat(PipelinePhases.serving("Exited (137) 2 seconds ago")).isFalse();
        assertThat(PipelinePhases.serving("Created")).isFalse();
        assertThat(PipelinePhases.serving("Dead")).isFalse();
    }
}
