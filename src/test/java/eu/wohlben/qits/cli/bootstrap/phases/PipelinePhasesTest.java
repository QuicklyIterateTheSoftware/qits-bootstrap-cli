package eu.wohlben.qits.cli.bootstrap.phases;

import eu.wohlben.qits.cli.bootstrap.config.Acme;
import eu.wohlben.qits.cli.bootstrap.platform.PlatformModel;
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

    // --- what a deployment row says ----------------------------------------------------------------
    //
    // The wait itself needs a deployer, a ci and a docker, so what is tested is the sentence it
    // reads a row by: ACTIVE ends the wait as a success, every terminal word ends it as a warning,
    // and anything else keeps it going. The outcome's SHAPE is the warning — the wait notes an
    // outcome starting with ACTIVE and warns about the rest — so these assertions read it that way.

    @Test
    void anActiveRowEndsTheWaitWithItsContainer() {
        assertThat(PipelinePhases.deploymentVerdict("ACTIVE", "qits-pd-qits-ci-f325ef80", ""))
                .isEqualTo("ACTIVE qits-pd-qits-ci-f325ef80");
    }

    @Test
    void aFailedRowEndsTheWaitAsAWarning() {
        assertThat(PipelinePhases.deploymentVerdict("FAILED", "", "the container never got healthy"))
                .isEqualTo("DEPLOY FAILED: the container never got healthy")
                .doesNotStartWith("ACTIVE");
    }

    /**
     * The deployer's refined words, and the reason this list leads rather than follows: a status
     * this program does not know reads as "still working" and costs the wait its whole timeout.
     * A rolled-back deployment left the SERVICE serving, but not this commit — same warning.
     */
    @Test
    void theRefinedTerminalWordsEndTheWaitTheSameWay() {
        assertThat(PipelinePhases.deploymentVerdict("ROLLED_BACK", "", "restored the predecessor"))
                .isEqualTo("DEPLOY ROLLED_BACK: restored the predecessor")
                .doesNotStartWith("ACTIVE");
        assertThat(PipelinePhases.deploymentVerdict("SUPERSEDED", "", "a newer deployment took over"))
                .isEqualTo("DEPLOY SUPERSEDED: a newer deployment took over")
                .doesNotStartWith("ACTIVE");
        assertThat(PipelinePhases.deploymentVerdict("GONE", "", "no container answers for this row"))
                .isEqualTo("DEPLOY GONE: no container answers for this row")
                .doesNotStartWith("ACTIVE");
    }

    /** A terminal row with nothing to say still says something. */
    @Test
    void aTerminalRowWithoutADetailStillReads() {
        assertThat(PipelinePhases.deploymentVerdict("IMAGE_MISSING", "", ""))
                .isEqualTo("DEPLOY IMAGE_MISSING: no detail");
    }

    /** Everything the deployer is still working on keeps the wait waiting. */
    @Test
    void anUnfinishedRowIsNoVerdict() {
        assertThat(PipelinePhases.deploymentVerdict("PENDING", "", "")).isNull();
        assertThat(PipelinePhases.deploymentVerdict("DEPLOYING", "", "")).isNull();
        assertThat(PipelinePhases.deploymentVerdict("", "", "")).isNull();
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

    // --- what a rerun deploys ----------------------------------------------------------------------

    private static final String ENV = "prod";

    private static String alias(String app) {
        return PlatformModel.wireAlias(app, ENV);
    }

    /** A cold machine: nothing is running, so the whole seed is this run's to deploy. */
    @Test
    void withNothingDeployedTheWholeSeedIsDeployed() {
        PipelinePhases.SeedPlan plan = PipelinePhases.seedPlan(List.of(), List.of(), ENV);

        assertThat(plan.deploy()).containsExactlyElementsOf(
                PlatformModel.CORE.stream()
                        .map(PipelinePhasesTest::alias).toList());
        assertThat(plan.managed()).isEmpty();
        assertThat(plan.stale()).isEmpty();
    }

    /** The rule the stack file has no depends_on for: never a seed service beside a deployment. */
    @Test
    void anApplicationWithADeployedContainerIsLeftAlone() {
        PipelinePhases.SeedPlan plan = PipelinePhases.seedPlan(
                List.of("qits-pd-prod-qits-ci-a1b2c3d4"), List.of(), ENV);

        assertThat(plan.managed()).containsExactly(alias("ci"));
        assertThat(plan.deploy()).doesNotContain(alias("ci")).contains(alias("events"));
    }

    /**
     * And the seed SERVICE of that application goes, which is the half swarm added: a compose
     * sibling stayed down once the deployer removed its container, while a service's task is
     * restarted within seconds.
     */
    @Test
    void thePredecessorServiceOfADeployedApplicationIsRemoved() {
        PipelinePhases.SeedPlan plan = PipelinePhases.seedPlan(
                List.of("qits-pd-prod-qits-ci-a1b2c3d4"),
                List.of("qits_prod-qits-ci", "qits_prod-qits-events"), ENV);

        assertThat(plan.stale()).containsExactly("qits_prod-qits-ci");
    }

    /** A platform service's container drops the tier segment, and the prefix has to match that. */
    @Test
    void aPlatformServiceIsRecognisedByItsOwnPrefix() {
        PipelinePhases.SeedPlan plan = PipelinePhases.seedPlan(
                List.of("qits-pd-qits-platform-idp-f325ef80"), List.of("qits-platform-idp"), ENV);

        assertThat(plan.managed()).containsExactly("qits-platform-idp");
        // The bare-alias service is the DEPLOYER'S OWN — the swarm driver deploys under the wire
        // alias — so it is never swept. Only the qits_-qualified twin is a seed leftover.
        assertThat(plan.stale()).isEmpty();
        assertThat(plan.deploy()).doesNotContain("qits-platform-idp");
    }

    /**
     * The swarm driver's shape: the deployed application is a SERVICE under the bare wire alias,
     * and its task containers carry swarm's own names — no qits-pd- container anywhere. The
     * container test alone would call this application undeployed and stack a seed twin over it,
     * which then holds the alias and the host ports against the next deployment.
     */
    @Test
    void aSwarmDeployedApplicationIsManagedByItsServiceName() {
        PipelinePhases.SeedPlan plan = PipelinePhases.seedPlan(
                List.of("prod-qits-ci.1.x8x1yz"),
                List.of("prod-qits-ci", "qits_prod-qits-ci"), ENV);

        assertThat(plan.managed()).contains(alias("ci"));
        assertThat(plan.deploy()).doesNotContain(alias("ci"));
        // And the seed twin left beside it from an interrupted boot is still reaped.
        assertThat(plan.stale()).containsExactly("qits_prod-qits-ci");
    }

    // --- the register token in the closing report --------------------------------------------------

    /**
     * The run that minted it prints it, and says the two things that make it usable: where it is
     * spent, and that it is spent only once.
     */
    @Test
    void theRunThatMintedTheTokenPrintsIt() {
        List<String> lines = PipelinePhases.registerLines("http://localhost:8080/idp/register",
                "rt-0123456789", false, "/home/me/code/qits-qits/.qits-bootstrap.env");

        assertThat(String.join("\n", lines))
                .contains("http://localhost:8080/idp/register")
                .contains("rt-0123456789")
                .contains("ONE-TIME")
                .contains("/home/me/code/qits-qits/.qits-bootstrap.env");
    }

    /**
     * <b>A rerun points at the file instead of reprinting the credential.</b> A token on every
     * screen and in every run log for the life of the platform is worse than one line saying where
     * it is — and the run that made it already printed it once.
     */
    @Test
    void aRerunPointsAtTheStateFileRatherThanReprintingTheToken() {
        List<String> lines = PipelinePhases.registerLines("http://localhost:8080/idp/register",
                null, true, "/home/me/code/qits-qits/.qits-bootstrap.env");

        assertThat(String.join("\n", lines)).contains("IDP_REGISTER_TOKEN")
                .contains("/home/me/code/qits-qits/.qits-bootstrap.env")
                .contains("Delete that line");
    }

    /** Nothing minted and nothing recorded: the phase warned, and the report stays quiet. */
    @Test
    void aBootThatMintedNothingSaysNothingHere() {
        assertThat(PipelinePhases.registerLines("http://localhost:8080/idp/register", "", false,
                "/tmp/.qits-bootstrap.env")).isEmpty();
    }

    // --- the domain in the closing report ---------------------------------------------------------

    private static String domainReport(Acme.Mode mode, String certificate) {
        return String.join("\n", PipelinePhases.domainLines("qits-dev.eu", "203.0.113.7", mode,
                "hostmaster@qits-dev.eu", certificate));
    }

    /**
     * <b>The records are stated as rows that EXIST</b>, because they do now — and the registrar line
     * carries the same address they were written with, which is the value the glue record needs.
     */
    @Test
    void theRecordsAreListedAndTheRegistrarLineCarriesTheAddress() {
        String report = domainReport(Acme.Mode.STAGING, "staging");

        // Every record the zone phase writes, in the report the operator reads.
        assertThat(report).contains("@").contains("ns1").contains("*").contains("*.*");
        assertThat(report).contains("203.0.113.7");
        // The delegation, both halves of it, and the nameserver spelled as an fqdn there.
        assertThat(report).contains("NS  qits-dev.eu  ->  ns1.qits-dev.eu")
                .contains("A   ns1.qits-dev.eu  ->  203.0.113.7")
                .contains("GLUE");
    }

    /**
     * <b>The step that told the operator to write the records is gone, and stays gone.</b> This run
     * writes them, so an instruction to write them by hand would be an instruction to redo work —
     * and the "which this run cannot know" clause it carried is simply no longer true.
     */
    @Test
    void theRemovedStepStaysRemoved() {
        for (Acme.Mode mode : Acme.Mode.values()) {
            String report = domainReport(mode, null);

            assertThat(report).as("mode %s", mode)
                    .doesNotContain("cannot know")
                    .doesNotContain("no records")
                    .doesNotContain("has no records yet")
                    .doesNotContain("2. Write the records");
        }
    }

    /**
     * A staging certificate is a success that a browser still refuses, so the line says both — and
     * says the flip is a rerun rather than a redeploy, because that is the thing worth knowing.
     */
    @Test
    void aStagingCertificateIsStatedAsIssuedAndAsNotYetTrusted() {
        String report = domainReport(Acme.Mode.STAGING, "staging");

        assertThat(report).contains("ISSUED").contains("STAGING")
                .contains("QITS_ACME_MODE=production").contains("NO redeploy");
        // Nothing to retry: the order went through.
        assertThat(report).doesNotContain("NOT ISSUED");
    }

    @Test
    void aProductionCertificateIsStatedAsTheLiveOne() {
        String report = domainReport(Acme.Mode.PRODUCTION, "production");

        assertThat(report).contains("ISSUED").contains("https://qits-dev.eu")
                .contains("browsers accept it");
        assertThat(report).doesNotContain("NOT ISSUED").doesNotContain("PLACEHOLDER");
    }

    /**
     * <b>A failed order is a report line, not a failed boot.</b> The retry is printed with the mode
     * and the contact already filled in, so it is a command to run rather than one to compose, and
     * the likeliest cause is named — the delegation, which nothing here can hurry.
     */
    @Test
    void anOrderThatDidNotGoThroughPrintsTheRetryWithEverythingFilledIn() {
        String report = domainReport(Acme.Mode.STAGING, null);

        assertThat(report).contains("NOT ISSUED").contains("PLACEHOLDER")
                .contains("delegation")
                .contains("--staging")
                .contains("--domain=qits-dev.eu")
                .contains("--email=hostmaster@qits-dev.eu")
                .contains("--management-url=http://qits-platform-edge:9000");
    }

    /** Issuance off is a choice, so it reads as one rather than as a failure. */
    @Test
    void issuanceOffSaysSoAndDoesNotReadAsAFailure() {
        String report = domainReport(Acme.Mode.OFF, null);

        assertThat(report).contains("ISSUANCE OFF").contains("QITS_ACME_MODE")
                .contains("PLACEHOLDER");
        assertThat(report).doesNotContain("NOT ISSUED");
    }

    /** A production order that failed offers the production retry, not a staging one. */
    @Test
    void theRetryCommandFollowsTheModeThatWasAskedFor() {
        assertThat(domainReport(Acme.Mode.PRODUCTION, null)).doesNotContain("--staging");
    }

    /** Everything deployed: there is nothing left for this phase to start. */
    @Test
    void aFullyDeployedPlatformDeploysNothing() {
        List<String> running = PlatformModel.CORE.stream()
                .map(app -> PlatformModel.pdNamePrefix(app, ENV) + "abcd1234")
                .toList();

        assertThat(PipelinePhases.seedPlan(running, List.of(), ENV).deploy()).isEmpty();
    }
}
