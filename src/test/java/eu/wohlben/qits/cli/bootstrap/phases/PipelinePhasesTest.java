package eu.wohlben.qits.cli.bootstrap.phases;

import eu.wohlben.qits.cli.bootstrap.proc.ProcessResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two sentences these phases read machine output by. The phases that shell docker and git are
 * not unit-tested, but "is this container serving" and "did this push move anything" are pure
 * functions — and each of them decided a boot wrongly once.
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

    /** git's own spelling — hyphens. The one the tenth proving run found unmatched. */
    @Test
    void gitsHyphenatedSpellingIsUpToDate() {
        assertThat(PipelinePhases.upToDate(push("Everything up-to-date"))).isTrue();
    }

    @Test
    void theSpacedSpellingIsUpToDateToo() {
        assertThat(PipelinePhases.upToDate(push("everything up to date"))).isTrue();
    }

    /** A push that moved a ref announced something, and both callers act on that. */
    @Test
    void aPushThatMovedARefIsNotUpToDate() {
        assertThat(PipelinePhases.upToDate(push(
                "To http://prod-qits-githost:8080/git/qits-eventstream",
                " * [new tag]   2026.812.101500 -> 2026.812.101500"))).isFalse();
    }

    /** git says it LAST, so a push whose captured head is full is read from the tail. */
    @Test
    void theTailIsReadAsWellAsTheHead() {
        ProcessResult tailOnly = new ProcessResult(0, List.of("counting objects"),
                List.of("Everything up-to-date"), false, true);
        assertThat(PipelinePhases.upToDate(tailOnly)).isTrue();
    }

    private static ProcessResult push(String... lines) {
        return new ProcessResult(0, List.of(lines), List.of(lines), false, false);
    }
}
