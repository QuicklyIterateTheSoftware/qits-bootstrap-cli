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

    // --- where the deploy ref points ---------------------------------------------------------------

    private static final String MAIN = "1111111111111111111111111111111111111111";
    private static final String RELEASE = "2222222222222222222222222222222222222222";

    /** The default: the platform comes back as its last released self. */
    @Test
    void aReleaseTagIsWhatTheDeployRefPointsAt() {
        PipelinePhases.DeployPoint point = PipelinePhases.deployPoint(
                false, false, "2026.812.101500", RELEASE, RELEASE, MAIN);

        assertThat(point.sha()).isEqualTo(RELEASE);
        assertThat(point.tag()).isEqualTo("2026.812.101500");
        assertThat(point.restored()).isTrue();
        assertThat(point.warn()).isFalse();
        assertThat(point.why()).isEqualTo("release 2026.812.101500");
    }

    /** {@code --ship-mains}: the dev loop, and the tag is not even read. */
    @Test
    void shipMainsDeploysMainsHead() {
        PipelinePhases.DeployPoint point =
                PipelinePhases.deployPoint(true, false, "", "", MAIN, MAIN);

        assertThat(point.sha()).isEqualTo(MAIN);
        assertThat(point.restored()).isFalse();
        assertThat(point.warn()).isFalse();
        assertThat(point.why()).contains("--ship-mains");
    }

    /** Nothing released: main's head, and the run says it deployed unreleased code. */
    @Test
    void noReleaseTagFallsBackToMainsHeadAndWarns() {
        PipelinePhases.DeployPoint point =
                PipelinePhases.deployPoint(false, false, "", "", MAIN, MAIN);

        assertThat(point.sha()).isEqualTo(MAIN);
        assertThat(point.restored()).isFalse();
        assertThat(point.warn()).isTrue();
    }

    /** A tag whose commit could not be read is a tag that cannot be deployed. */
    @Test
    void aTagWithNoReadableCommitFallsBackTheSameWay() {
        PipelinePhases.DeployPoint point =
                PipelinePhases.deployPoint(false, false, "2026.812.101500", "", MAIN, MAIN);

        assertThat(point.sha()).isEqualTo(MAIN);
        assertThat(point.warn()).isTrue();
    }

    /**
     * qits-ci reads the pipeline out of the commit the deploy ref names, so a repository whose
     * config this run had to write deploys THAT commit — the checkout's own head, which in a
     * restore is the release plus one bootstrap-authored commit.
     */
    @Test
    void anOverlaidPipelineConfigDeploysTheCommitItWasWrittenInto() {
        String overlay = "3333333333333333333333333333333333333333";
        PipelinePhases.DeployPoint point = PipelinePhases.deployPoint(
                false, true, "2026.812.101500", RELEASE, overlay, MAIN);

        assertThat(point.sha()).isEqualTo(overlay);
        assertThat(point.restored()).isFalse();
        assertThat(point.warn()).isFalse();
    }
}
