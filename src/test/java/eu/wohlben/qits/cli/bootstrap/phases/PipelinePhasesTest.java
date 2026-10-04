package eu.wohlben.qits.cli.bootstrap.phases;

import eu.wohlben.qits.cli.bootstrap.api.CiApi;
import eu.wohlben.qits.cli.bootstrap.api.Http;
import eu.wohlben.qits.cli.bootstrap.config.Acme;
import eu.wohlben.qits.cli.bootstrap.config.TestConfig;
import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseContext;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseSkipped;
import eu.wohlben.qits.cli.bootstrap.engine.Waiter;
import eu.wohlben.qits.cli.bootstrap.platform.ComposeTemplate;
import eu.wohlben.qits.cli.bootstrap.platform.Docker;
import eu.wohlben.qits.cli.bootstrap.platform.PlatformModel;
import eu.wohlben.qits.cli.bootstrap.proc.Cmd;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessResult;
import eu.wohlben.qits.cli.bootstrap.proc.RunLog;
import eu.wohlben.qits.cli.bootstrap.proc.ScriptedRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** The one that reported qits-idp live while the deployer was failing it. */
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

    // --- what a ci run says ------------------------------------------------------------------------

    /**
     * The three red words. A word missing here reads as "still running", so the wait sits on a
     * finished run until its timeout — which is what {@code TIMED_OUT} did before it was added.
     */
    @Test
    void everyRedRunWordEndsTheWait() {
        assertThat(PipelinePhases.isRedRunStatus("FAILED")).isTrue();
        assertThat(PipelinePhases.isRedRunStatus("CONFIG_ERROR")).isTrue();
        assertThat(PipelinePhases.isRedRunStatus("TIMED_OUT")).isTrue();
    }

    /** Green and unfinished runs keep the wait going. */
    @Test
    void aGreenOrUnfinishedRunIsNotRed() {
        assertThat(PipelinePhases.isRedRunStatus("SUCCESS")).isFalse();
        assertThat(PipelinePhases.isRedRunStatus("RUNNING")).isFalse();
        assertThat(PipelinePhases.isRedRunStatus("")).isFalse();
    }

    // --- the release the bring-up announces ------------------------------------------------------

    private static final String PROJECT = "b03b84b1-1875-4071-9dbf-854550156258";
    private static final String STORAGE_ID = "0045af99-5245-49e7-9260-aba22ddcdb83";

    /**
     * <b>The payload, whole, in the shape qits-workspaces really publishes</b> — read out of the
     * live event store on 2026-09-05 and pinned here, because it is the one announcement this
     * program makes that another service's committed file has to select and another service's run
     * row is built from. Keys alphabetical, which is what the bus's canonical JSON stores.
     */
    @Test
    void theAnnouncedReleaseIsTheEventQitsWorkspacesPublishes() {
        assertThat(PipelinePhases.releaseEvent(PROJECT, STORAGE_ID, "qits-qits",
                "2026.905.141334", "3b095a54dcd21f8dee0993e0b1a4db03b9b13b8b"))
                .isEqualTo("{\"name\":\"SCMRelease\",\"payload\":{"
                        + "\"branch\":\"2026.905.141334\","
                        + "\"commitSha\":\"3b095a54dcd21f8dee0993e0b1a4db03b9b13b8b\","
                        + "\"projectId\":\"" + PROJECT + "\","
                        + "\"repository\":\"" + STORAGE_ID + "\","
                        + "\"repositoryName\":\"qits-qits\","
                        + "\"version\":\"2026.905.141334\"}}");
    }

    /**
     * <b>{@code repository} is the STORAGE UUID and {@code repositoryName} the name, exactly as a
     * real event spells them.</b> Selection is unaffected — qits-ci aliases a condition on
     * {@code repository} onto the name field whenever the payload carries one, which is what lets
     * the release document's {@code repository: {exact: qits-…}} match — the line qits-ci composes
     * for itself out of {@code .config/qits/release.yml}, and that a hand-written
     * {@code ci-event-release.yml} used to carry. What
     * the UUID buys is the platform pass: {@code evaluatePlatform} looks the field up among its
     * candidates by name first and by id second, and in the seed window that catalogue is the git
     * host's listing, which answers ids and no names.
     */
    @Test
    void theRepositoryFieldIsTheStorageIdAndTheNameTravelsBesideIt() {
        String event = PipelinePhases.releaseEvent(PROJECT, STORAGE_ID,
                PlatformModel.repo("eventstream"), "2026.905.25646", "abc123");

        assertThat(event).contains("\"repository\":\"" + STORAGE_ID + "\"")
                .contains("\"repositoryName\":\"qits-eventstream-javalib\"");
    }

    /**
     * <b>{@code branch} is the TAG.</b> A real event names the release request's backing branch,
     * and that branch is deleted in the same operation that creates the tag — so a replay naming it
     * would name a ref that no longer exists. A tag is a ref, {@code clone --branch} resolves one,
     * and every release recipe that declares {@code checkout:} points its branch at {@code version}
     * anyway.
     */
    @Test
    void theBranchIsTheTagBecauseTheBackingBranchIsGone() {
        String event = PipelinePhases.releaseEvent(PROJECT, STORAGE_ID, "qits-stt-service",
                "2026.901.90000", "def456");

        assertThat(event).contains("\"branch\":\"2026.901.90000\"")
                .doesNotContain("release/");
    }

    /**
     * <b>A value this run does not know is an absent key</b>, never an empty string and never a
     * JSON null. Absent is what a release published before the field existed looks like, and every
     * reader already handles it — {@code sha: commitSha} under {@code optional: true} falls back to
     * main's head rather than fetching the four characters {@code null}.
     */
    @Test
    void aFieldThisRunCannotFillIsLeftOutRatherThanEmptied() {
        String event = PipelinePhases.releaseEvent(null, STORAGE_ID, "qits-stt-service",
                "2026.901.90000", "");

        assertThat(event).doesNotContain("projectId").doesNotContain("commitSha")
                .doesNotContain("null").doesNotContain("\"\"");
        assertThat(event).isEqualTo("{\"name\":\"SCMRelease\",\"payload\":{"
                + "\"branch\":\"2026.901.90000\","
                + "\"repository\":\"" + STORAGE_ID + "\","
                + "\"repositoryName\":\"qits-stt-service\","
                + "\"version\":\"2026.901.90000\"}}");
    }

    /**
     * <b>A release replay says exactly what a deploy says.</b> Both callers reach the one helper,
     * so there is one shape and not two — which is what stopped the replays drifting when the
     * recipes moved off {@code SCMPublishTag}.
     */
    @Test
    void theReplayAndTheDeployBuildTheSameShape() {
        assertThat(PipelinePhases.releaseEvent(PROJECT, STORAGE_ID, "qits-ci-service",
                "2026.812.101500", "0123abc"))
                .isEqualTo(PipelinePhases.releaseEvent(PROJECT, STORAGE_ID, "qits-ci-service",
                        "2026.812.101500", "0123abc"));
        // One private method builds it, so the two phases cannot spell it differently: both call
        // announceRelease, which is the only caller of this.
        assertThat(PipelinePhases.class.getDeclaredMethods())
                .filteredOn(method -> method.getName().equals("announceRelease")).hasSize(1);
    }

    /**
     * <b>The door names the runs it started, and an empty list is the answer that WARNS.</b> No
     * recipe selected the event, so nothing will publish this version — said once and loudly rather
     * than waited out, which is what the whole release-timeout budget used to be spent on.
     */
    @Test
    void anAnswerWithNoRunsIsARepositoryWithNoReleaseRecipe() {
        assertThat(CiApi.triggeredRunIds(new Http.Response(200, "{\"runIds\":[]}"))).isEmpty();
        assertThat(CiApi.triggeredRunIds(new Http.Response(200, "{\"runIds\":[\"r-1\",\"r-2\"]}")))
                .containsExactly("r-1", "r-2");
        // A refusal is not "no recipe": the caller retries it and then stops the boot.
        assertThat(CiApi.triggeredRunIds(new Http.Response(503, ""))).isEmpty();
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
                List.of("qits_prod-qits-ci", "qits_qits-events"), ENV);

        assertThat(plan.stale()).containsExactly("qits_prod-qits-ci");
    }

    /**
     * <b>The deployer is a platform service since 2026-08-17, and every name this rule matches on
     * moved with it.</b> Its seed service is the bare {@code qits-deployments} and its deployed
     * container carries no tier segment — so a copy of either spelling left behind would have this
     * run start a seed deployer beside the one that already manages the application.
     */
    @Test
    void theDeployerIsRecognisedUnderItsDeployedName() {
        PipelinePhases.SeedPlan plan = PipelinePhases.seedPlan(
                List.of("qits-pd-" + ENV + "-qits-deployments-a1b2c3d4"),
                List.of("qits_" + ENV + "-qits-deployments"), ENV);

        assertThat(plan.managed()).contains(ENV + "-qits-deployments");
        assertThat(plan.deploy()).doesNotContain(ENV + "-qits-deployments");
        assertThat(plan.stale()).containsExactly("qits_" + ENV + "-qits-deployments");
    }

    /**
     * Every container carries the tier segment, and the prefix has to match that.
     *
     * <p>One did not, once: a platform service DROPPED the segment rather than filling it, so
     * qits-pd-qits-idp- was the prefix and the sweep had to know which kind it was looking at. The
     * plane is gone and so is the second prefix shape.
     */
    @Test
    void aDeployedContainerIsRecognisedByItsTierQualifiedPrefix() {
        PipelinePhases.SeedPlan plan = PipelinePhases.seedPlan(
                List.of("qits-pd-" + ENV + "-qits-idp-f325ef80"), List.of(ENV + "-qits-idp"), ENV);

        assertThat(plan.managed()).containsExactly(ENV + "-qits-idp");
        // The bare-alias service is the DEPLOYER'S OWN — the swarm driver deploys under the wire
        // alias — so it is never swept. Only the qits_-qualified twin is a seed leftover.
        assertThat(plan.stale()).isEmpty();
        assertThat(plan.deploy()).doesNotContain("qits-idp");
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
        List<String> lines = PipelinePhases.registerLines(
                "https://idp.qits.qits-dev.eu/idp/register", "rt-0123456789", false,
                "/home/me/code/qits-qits/.qits-bootstrap.env");

        assertThat(String.join("\n", lines))
                .contains("https://idp.qits.qits-dev.eu/idp/register")
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
        List<String> lines = PipelinePhases.registerLines(
                "https://idp.qits.qits-dev.eu/idp/register", null, true,
                "/home/me/code/qits-qits/.qits-bootstrap.env");

        assertThat(String.join("\n", lines)).contains("IDP_REGISTER_TOKEN")
                .contains("/home/me/code/qits-qits/.qits-bootstrap.env")
                .contains("Delete that line");
    }

    /** Nothing minted and nothing recorded: the phase warned, and the report stays quiet. */
    @Test
    void aBootThatMintedNothingSaysNothingHere() {
        assertThat(PipelinePhases.registerLines(
                "https://idp.qits.qits-dev.eu/idp/register", "", false,
                "/tmp/.qits-bootstrap.env")).isEmpty();
    }

    // --- the domain in the closing report ---------------------------------------------------------

    private static String domainReport(Acme.Mode mode, String certificate) {
        return domainReport(mode, certificate, List.of(), List.of());
    }

    private static String domainReport(Acme.Mode mode, String certificate,
            List<String> projectSlugs, List<String> extraSans) {
        return String.join("\n", PipelinePhases.domainLines("qits-dev.eu", ENV, "203.0.113.7", mode,
                "hostmaster@qits-dev.eu", certificate, projectSlugs, extraSans));
    }

    /**
     * <b>The records are stated as a step to CHECK, because this platform serves no dns.</b> They
     * are held at whatever provider holds the domain, and every one of them carries the address
     * this run was given.
     */
    @Test
    void theRecordsAreListedWithTheAddressTheyAllCarry() {
        String report = domainReport(Acme.Mode.STAGING, "staging");

        assertThat(report).contains("@").contains("*").contains("*.*").contains("*.*.*");
        assertThat(report).contains("203.0.113.7");
        // The reading they follow, so the person typing them in knows why there are four.
        assertThat(report).contains("RIGHT TO LEFT")
                .contains("<app>[.<env>].<project>.qits-dev.eu");
    }

    /**
     * <b>Nothing here claims this run wrote a record, or that a delegation is wanted.</b> There is
     * no nameserver to delegate to any more, and an instruction to point one at this host would send
     * the reader to set up something that does not exist.
     */
    @Test
    void theRetiredNameserverIsNotMentioned() {
        for (Acme.Mode mode : Acme.Mode.values()) {
            String report = domainReport(mode, null);

            assertThat(report).as("mode %s", mode)
                    .doesNotContain("ns1")
                    .doesNotContain("GLUE")
                    .doesNotContain("registrar")
                    .doesNotContain("cannot know");
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
     * the likeliest cause is named — the records, which nothing here can hurry.
     */
    @Test
    void anOrderThatDidNotGoThroughPrintsTheRetryWithEverythingFilledIn() {
        String report = domainReport(Acme.Mode.STAGING, null);

        assertThat(report).contains("NOT ISSUED").contains("PLACEHOLDER")
                .contains("records above")
                .contains("--staging")
                .contains("--domain=qits-dev.eu")
                .contains("--email=hostmaster@qits-dev.eu")
                .contains("--management-url=http://" + ENV + "-qits-edge:9000");
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

    // --- an environment may not be called after a project -----------------------------------------

    /**
     * <b>An environment name and a project slug no longer collide in the ROUTING, and the refusal
     * stands anyway.</b> The edge reads a host positionally — {@code <app>[.<env>].<project>.<domain>}
     * — so neither name can take the other's place. What is left is a pair of names no person can
     * tell apart, and this is the one moment either of them can still be changed.
     */
    @Test
    void anEnvironmentNamedAfterAProjectIsRefusedAndTheMessageSaysWhy() {
        assertThat(PipelinePhases.environmentNameRefusal("acme", List.of("qits", "acme")))
                .get(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("acme")
                .contains("editor.acme.acme.<domain>")
                .contains("--platform-env");
    }

    /** Case is not a difference: a DNS label is compared lowercase wherever it is read. */
    @Test
    void theComparisonIsNotCaseSensitive() {
        assertThat(PipelinePhases.environmentNameRefusal(" Acme ", List.of("acme"))).isPresent();
    }

    /** Every other name passes, including one that merely contains a slug. */
    @Test
    void aNameNoProjectHoldsIsFine() {
        assertThat(PipelinePhases.environmentNameRefusal("prod", List.of("qits", "acme")))
                .isEmpty();
        assertThat(PipelinePhases.environmentNameRefusal("acme-staging", List.of("acme")))
                .isEmpty();
        // No project list is no refusal: the check is evidence, not a gate.
        assertThat(PipelinePhases.environmentNameRefusal("acme", List.of())).isEmpty();
    }

    // --- the project tier, beside the records ------------------------------------------------------

    /**
     * <b>The editor is an application of its project, and both halves of its name are answered
     * without an operator.</b> {@code editor.<env>.<project>.<domain>} is the {@code *.*.*} depth,
     * so dns needs no step per project, and the edge derives the per-project wildcards from
     * qits-projects' events, so the certificate needs none either.
     */
    @Test
    void everyProjectsEditorHostIsPrintedAtTheProjectTiersDepth() {
        String report = domainReport(Acme.Mode.PRODUCTION, "production",
                List.of("qits", "acme"), List.of());

        assertThat(report).contains("editor.prod.qits.qits-dev.eu");
        assertThat(report).contains("editor.prod.acme.qits-dev.eu");
        assertThat(report).contains("ProjectCreated");
    }

    /**
     * <b>The covered / NOT-covered column is gone, and its instruction with it.</b> Every project
     * is covered by construction now, so a per-project verdict would be a column that always says
     * one thing — and the old advice, "add QITS_ACME_EXTRA_SANS=editor.<slug> and rerun", would
     * send a person to spend a SAN on a name the edge already derives.
     */
    @Test
    void noProjectIsPrintedAsMissingFromTheCertificateAnyMore() {
        String report = domainReport(Acme.Mode.PRODUCTION, "production",
                List.of("qits", "acme"), List.of());

        assertThat(report).doesNotContain("NOT on the certificate")
                .doesNotContain("on the certificate")
                .doesNotContain("QITS_ACME_EXTRA_SANS=editor");
    }

    /**
     * <b>A project created later needs nothing done to it, and the report says which act covers
     * it</b>: the creation event triggers the edge's next order. It is not a rerun of this boot and
     * not a restart of the edge, which is exactly what the retired advice used to ask for.
     */
    @Test
    void theReportSaysANewProjectIsCoveredByItsOwnCreationEvent() {
        String report = domainReport(Acme.Mode.PRODUCTION, "production", List.of("acme"),
                List.of());

        assertThat(report).contains("COVERED TOO")
                .contains("triggers the edge's next order")
                .doesNotContain("renewal or a restart, not a deploy")
                .doesNotContain("browser refuses");
    }

    /**
     * The listing is a courtesy read and may not answer. The block then says so and says that
     * nothing turns on it — the certificate follows the events, not this report — and no boot fails
     * over a report.
     */
    @Test
    void withNoProjectListTheReportSaysNothingTurnsOnIt() {
        String report = domainReport(Acme.Mode.PRODUCTION, "production");

        assertThat(report).contains("editor.<env>.<project>.qits-dev.eu")
                .contains("No project list was read")
                .contains("follows the events");
    }

    /**
     * <b>The 100-name cap is the live set's cost, and the report is where an operator meets it.</b>
     * The derived set grows with the projects — {@code 2 + E + P + P*E + extras} — so the arithmetic
     * is printed beside the count, because the arithmetic is what says which term moves.
     */
    @Test
    void theSanBudgetIsPrintedWithTheArithmeticAndTheCount() {
        String report = domainReport(Acme.Mode.PRODUCTION, "production",
                List.of("qits", "acme"), List.of("status.support.qits-dev.eu"));

        assertThat(report).contains("2 + P + P*E + extras").contains("100");
        // The apex and *.<domain>, 2 projects, 2 project-environments, 1 extra — and "at most",
        // because a project that supports no environments pays none of the P*E term and which
        // ones do is a live read this report does not make.
        assertThat(report).contains("E=1 and P=2 with 1 extra, so at most 7 of 100");
        assertThat(report).contains("status.support.qits-dev.eu");
    }

    /** No extras is the ordinary platform, and the knob says what it is FOR rather than nothing. */
    @Test
    void theExtraSansKnobIsPrintedAsAdHocNamesOnly() {
        String report = domainReport(Acme.Mode.PRODUCTION, "production", List.of("qits"),
                List.of());

        assertThat(report).contains("AD-HOC NAMES ONLY").contains("This run resolved: nothing");
    }

    /** The slug, not the name: the slug is the label that reaches DNS. */
    @Test
    void theProjectSlugsAreReadOutOfTheListingInOrder() {
        String listing = """
                {"entries":[
                  {"project":{"id":"p-other","name":"Check Out","slug":"checkout"}},
                  {"project":{"id":"p-qits","name":"qits","slug":"qits"}}
                ]}""";

        assertThat(PipelinePhases.projectSlugs(new eu.wohlben.qits.cli.bootstrap.api.Http.Response(
                200, listing))).containsExactly("checkout", "qits");
        // An answer that is not one is not read as an empty platform: the report then prints the
        // shape rather than claiming this platform holds no project.
        assertThat(PipelinePhases.projectSlugs(new eu.wohlben.qits.cli.bootstrap.api.Http.Response(
                503, ""))).isEmpty();
    }

    /** Everything deployed: there is nothing left for this phase to start. */
    @Test
    void aFullyDeployedPlatformDeploysNothing() {
        List<String> running = PlatformModel.CORE.stream()
                .map(app -> PlatformModel.pdNamePrefix(app, ENV) + "abcd1234")
                .toList();

        assertThat(PipelinePhases.seedPlan(running, List.of(), ENV).deploy()).isEmpty();
    }

    @TempDir
    Path temp;

    /** The extras exactly as a run renders them, with the deployer's own client secret resolved. */
    private String renderedExtras() {
        Boot boot = new Boot(TestConfig.from(Map.of("QITS_DOMAIN", "qits-dev.eu",
                "QITS_PUBLIC_IP", "203.0.113.7")), new RunLog(temp.resolve("run.log")));
        boot.state.serviceClientSecrets.put(PlatformModel.application("deployments"), "s3cr3t");
        return ComposeTemplate.extras(new SeedPhases(boot).tokens());
    }

    /**
     * <b>The flip's value is READ BACK out of the rendered extras, never spelled again.</b> The
     * running deployer is patched live and its successor is configured from the same file, so a
     * second spelling here would be a platform whose two deployers read different services.
     * <p>
     * ONE VALUE, and it used to be six: the five
     * {@code QUARKUS_OIDC_CLIENT_CONFIGURATION_*} pairs were the credential the moved read
     * presents, and no extras block carries a credential any more. The deployer's identity is not
     * an {@code idp:client} resource its own deployments.yml declares (D10) — it is one of the five
     * applications this bootstrap creates an idp client for directly — and the seed stack hands
     * this very container the triple, so the flip moves the authority and nothing else.
     */
    @Test
    void theFlipTakesItsValueFromTheRenderedExtras() {
        List<String> env = PipelinePhases.flipEnv(renderedExtras(), "qits-deployments");

        assertThat(env).containsExactly(
                "QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL=http://prod-qits-configuration:8080");
    }

    /**
     * <b>And no credential travels with it.</b> A flip that carried a client secret would be the
     * bootstrap writing identity onto a service whose own registry row is the authority for it —
     * the failure the extras' identity ban exists to stop, in the one place that patches a live
     * service rather than a file.
     */
    @Test
    void theFlipCarriesNoCredential() {
        assertThat(PipelinePhases.flipEnv(renderedExtras(), "qits-deployments"))
                .noneMatch(pair -> pair.contains("SECRET") || pair.contains("CLIENT_ID")
                        || pair.startsWith("QITS_RESOURCE_IDP_"));
    }

    /**
     * And nothing else of the deployer's block rides along. The rest is already in the file the
     * running deployer read at its own boot; re-applying it would be a live patch nobody asked for,
     * and one of those values is this platform's postgres superuser password.
     */
    @Test
    void theFlipTouchesNothingElseOnTheDeployer() {
        List<String> env = PipelinePhases.flipEnv(renderedExtras(), "qits-deployments");

        assertThat(env).noneMatch(pair -> pair.startsWith("QITS_RESOURCE_DB_"))
                .noneMatch(pair -> pair.contains("POSTGRES_ADMIN_PASSWORD"))
                .noneMatch(pair -> pair.startsWith("DOCKER_CONFIG="))
                .noneMatch(pair -> pair.startsWith("QITS_AUTH_MACHINE_"));
    }

    /** One application's keys only — the extras carry sixteen applications in one file. */
    @Test
    void theFlipReadsOneApplicationsKeys() {
        assertThat(PipelinePhases.flipEnv(renderedExtras(), "qits-configuration")).isEmpty();
    }

    // --- the image version seeds -------------------------------------------------------------------

    private static final String AGENT_VERSION = "2026.820.154053";

    private static final String WORKSPACE_VERSION = "2026.821.101530";

    private static final String EDITOR_VERSION = "2026.906.34347";

    /**
     * <b>A PIN THAT IS NOT PINNED — the fixture the seeding tests below are driven with.</b>
     * {@link PipelinePhases#IMAGE_PINS} is empty, so asserting the rendering against IT would be
     * four assertions that pass because nothing happened. The mechanism is kept for the next
     * consumer that cannot declare its key (see the list's own javadoc), so it is proven here
     * against a list shaped like the rows that left: one image landing on two applications, and a
     * second image of its own.
     * <p>
     * The keys and publishers are the retired ones deliberately — a name that never existed would
     * prove the same rendering and say less about what the rendering is for. Nothing in production
     * reads these; {@link #noPinIsAuthoredSoNothingIsSeeded} is what asserts that.
     */
    private static final List<PipelinePhases.ImagePin> FIXTURE_PINS = List.of(
            new PipelinePhases.ImagePin("qits/project-agent", "projects",
                    "QITS_PROJECTS_AGENT_IMAGE_VERSION", "projects-daemon"),
            new PipelinePhases.ImagePin("qits/workspace", "workspaces",
                    "QITS_WORKSPACE_IMAGE_VERSION", "workspace-daemon"),
            new PipelinePhases.ImagePin("qits/workspace", "projects",
                    "QITS_PROJECTS_REFINEMENT_IMAGE_VERSION", "workspace-daemon"),
            new PipelinePhases.ImagePin("qits/workspace-editor", "workspaces",
                    "QITS_EDITOR_IMAGE_VERSION", "oci-workspace-editor"));

    /** Every publisher released, which is what a green boot hands the renderer. */
    private static Map<String, String> allReleased() {
        return Map.of("projects-daemon", AGENT_VERSION,
                "workspace-daemon", WORKSPACE_VERSION,
                "oci-workspace-editor", EDITOR_VERSION);
    }

    /**
     * <b>THE TRIPWIRE. The master list is qits-configuration's {@code control/ImagePins.AUTHORED},
     * and it is empty — so this copy is too.</b> The copy exists because the CLI builds cold from
     * Maven Central before any platform exists, and because {@code GET /configuration/api/pins}
     * omits a mapping nothing has set, so the boot can neither depend on that repository nor ask a
     * fresh platform for the map. The two change together, and this is the assertion that fails on
     * the day they do not.
     * <p>
     * <b>Empty is a state of the list, not the end of it.</b> Nothing below special-cases it, which
     * is the same ruling {@code ImagePins} makes for itself — so this test asserts the count and
     * the publisher rule that any future row has to satisfy, and neither is written as a claim that
     * the seeding is gone.
     */
    @Test
    void noPinIsAuthoredSoNothingIsSeeded() {
        assertThat(PipelinePhases.IMAGE_PINS).isEmpty();

        // The consequence a bootstrapped platform is actually born with: the import is the rendered
        // extras whole, and not one of the four retired keys is written into it. Each of those
        // services takes its image version from a maven pin now and reads a `…_OVERRIDE` key, so a
        // seed here would be an entry nothing reads and nothing on this platform can delete.
        String seeded = PipelinePhases.withImageVersions(renderedExtras(), allReleased());

        assertThat(seeded).isEqualTo(renderedExtras());
        assertThat(seeded).doesNotContain("QITS_PROJECTS_AGENT_IMAGE_VERSION")
                .doesNotContain("QITS_WORKSPACE_IMAGE_VERSION")
                .doesNotContain("QITS_PROJECTS_REFINEMENT_IMAGE_VERSION")
                .doesNotContain("QITS_EDITOR_IMAGE_VERSION");

        // And the rule a row added back would have to satisfy: every publisher named is one the
        // plan replays a release of, or the version read would be the tag of a repository this boot
        // never restores.
        assertThat(PipelinePhases.IMAGE_PINS)
                .allSatisfy(pin -> assertThat(PlatformModel.RELEASE_PUBLISHERS)
                        .as(pin.image()).contains(pin.publisher()));
    }

    /**
     * The seeding itself, against {@link #FIXTURE_PINS}: each pin lands on the application that
     * reads it, so a first deploy pins the release this boot cut instead of waiting on the
     * SoftwareRelease event. This is the mechanism the empty list keeps.
     */
    @Test
    void everyPinIsSeededOntoTheApplicationThatReadsIt() {
        String seeded = PipelinePhases.withImageVersions(renderedExtras(), allReleased(),
                FIXTURE_PINS);

        assertThat(seeded).contains(
                "qits.deployments.extras.qits-projects.env."
                        + "QITS_PROJECTS_AGENT_IMAGE_VERSION=" + AGENT_VERSION);
        assertThat(seeded).contains(
                "qits.deployments.extras.qits-workspaces.env."
                        + "QITS_WORKSPACE_IMAGE_VERSION=" + WORKSPACE_VERSION);
        assertThat(seeded).contains(
                "qits.deployments.extras.qits-projects.env."
                        + "QITS_PROJECTS_REFINEMENT_IMAGE_VERSION=" + WORKSPACE_VERSION);
        assertThat(seeded).contains(
                "qits.deployments.extras.qits-workspaces.env."
                        + "QITS_EDITOR_IMAGE_VERSION=" + EDITOR_VERSION);
        // The whole import is still there — the seeds are appended, not a replacement.
        assertThat(seeded).startsWith(renderedExtras().stripTrailing());
    }

    /**
     * <b>ONE RELEASE OF ONE PUBLISHER MOVES TWO KEYS</b>, and the mechanism allows it on purpose —
     * {@code qits/workspace} was the worked example, on qits-workspaces and qits-projects at once,
     * because the image a refinement runs in is the image a workspace runs in. So one version read
     * is written to both, and they cannot drift apart.
     */
    @Test
    void oneReleaseSeedsEveryKeyItsImageMoves() {
        String seeded = PipelinePhases.withImageVersions(renderedExtras(),
                Map.of("workspace-daemon", WORKSPACE_VERSION), FIXTURE_PINS);

        assertThat(seeded).contains("QITS_WORKSPACE_IMAGE_VERSION=" + WORKSPACE_VERSION)
                .contains("QITS_PROJECTS_REFINEMENT_IMAGE_VERSION=" + WORKSPACE_VERSION);
    }

    /** The exact key the deployer injects, on the application that reads it and nothing else. */
    @Test
    void theSeedLineNamesTheApplicationsOwnKey() {
        assertThat(PipelinePhases.imageVersionSeed(FIXTURE_PINS.get(0), AGENT_VERSION))
                .isEqualTo("qits.deployments.extras.qits-projects.env."
                        + "QITS_PROJECTS_AGENT_IMAGE_VERSION=" + AGENT_VERSION);
        assertThat(PipelinePhases.imageVersionSeed(FIXTURE_PINS.get(3), EDITOR_VERSION))
                .isEqualTo("qits.deployments.extras.qits-workspaces.env."
                        + "QITS_EDITOR_IMAGE_VERSION=" + EDITOR_VERSION);
    }

    /**
     * A publisher that has never released seeds nothing — releaseReplay stops the boot first, and an
     * empty value would be a worse seed than the fallback default each service carries. The guard is
     * per PIN, so a boot that released one publisher still seeds what it can.
     */
    @Test
    void aBlankVersionSeedsOnlyItsOwnPinAway() {
        String extras = renderedExtras();

        assertThat(PipelinePhases.withImageVersions(extras, Map.of(), FIXTURE_PINS))
                .isEqualTo(extras);

        String noEditor = PipelinePhases.withImageVersions(extras,
                Map.of("projects-daemon", AGENT_VERSION,
                        "workspace-daemon", WORKSPACE_VERSION,
                        "oci-workspace-editor", ""), FIXTURE_PINS);
        assertThat(noEditor).contains("QITS_PROJECTS_AGENT_IMAGE_VERSION=" + AGENT_VERSION)
                .contains("QITS_WORKSPACE_IMAGE_VERSION=" + WORKSPACE_VERSION)
                .doesNotContain("QITS_EDITOR_IMAGE_VERSION");

        // A blank workspace release takes BOTH of its keys with it and leaves the agent's alone.
        String noWorkspace = PipelinePhases.withImageVersions(extras,
                Map.of("projects-daemon", AGENT_VERSION), FIXTURE_PINS);
        assertThat(noWorkspace).contains("QITS_PROJECTS_AGENT_IMAGE_VERSION=" + AGENT_VERSION)
                .doesNotContain("QITS_WORKSPACE_IMAGE_VERSION")
                .doesNotContain("QITS_PROJECTS_REFINEMENT_IMAGE_VERSION");
    }

    // --- the reclaim, and what it is allowed to touch ----------------------------------------------

    /** What a phase said, and nothing more. */
    private static final class Ctx implements PhaseContext {
        final List<String> lines = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        String note = "";

        @Override
        public void log(String line) {
            lines.add(line);
        }

        @Override
        public void status(String status) {
        }

        @Override
        public void note(String value) {
            note = value;
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }
    }

    private void teardown(ScriptedRunner runner, Map<String, String> env, Ctx ctx) throws Exception {
        Boot boot = new Boot(TestConfig.from(env), new RunLog(temp.resolve("teardown.log")), runner);
        Phase phase = new PipelinePhases(boot).teardownBootstrapBuilder();
        phase.action().run(ctx);
    }

    /** The one buildx table these tests answer `buildx ls` with. */
    private static final List<String> BUILDX_TABLE = List.of(
            "NAME/NODE                     DRIVER/ENDPOINT      STATUS",
            "qits-bootstrap-builder-v5*    docker-container",
            " \\_ qits-bootstrap-builder-v50  \\_ unix:///var/run/docker.sock  running",
            "default                       docker");

    /**
     * <b>The buildx era goes, and buildx is what removes its state volumes.</b> Those volumes are
     * the whole reason this phase still sweeps them — 13.7 GB on wohlben.eu — and {@code buildx rm}
     * is the only command that knows the name buildx gave one.
     */
    @Test
    void theLegacyBuildersAndTheSeedOnlyVolumesAreReclaimed() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command -> {
            String line = String.join(" ", command);
            if (line.contains("buildx ls")) {
                return ScriptedRunner.ok(BUILDX_TABLE.toArray(new String[0]));
            }
            if (line.contains("volume ls")) {
                return ScriptedRunner.ok("qits-maven-seed", "qits-maven-cache",
                        "somebody-elses-volume");
            }
            return ScriptedRunner.ok();
        });
        Ctx ctx = new Ctx();

        teardown(runner, Map.of(), ctx);

        assertThat(runner.lines()).contains(
                "docker buildx rm qits-bootstrap-builder-v5",
                "docker volume ls -q -f dangling=true",
                "docker volume rm qits-maven-seed",
                "docker volume rm qits-maven-cache");
        // A volume this program did not create is not this program's to remove, dangling or not.
        assertThat(runner.lines()).noneMatch(line -> line.contains("somebody-elses-volume"));
        assertThat(ctx.note).contains("qits-bootstrap-builder-v5").contains("qits-maven-cache");
        assertThat(ctx.warnings).isEmpty();
    }

    /**
     * <b>qits-buildkitd survives its own maker.</b> The bootstrap creates it because it has to
     * build the seed images before any platform exists, but from the first deployment on it is
     * qits-containers' container at the same name, image and state volume — and its cache is what
     * every CI build after this run reads. Removing it here would be this program throwing away
     * the platform's build cache on its way out.
     */
    @Test
    void theOwnedBuildkitdAndItsCacheAreLeftStanding() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command ->
                String.join(" ", command).contains("volume ls")
                        ? ScriptedRunner.ok("qits-maven-seed", Docker.BUILDKIT_STATE_VOLUME)
                        : ScriptedRunner.ok());
        Ctx ctx = new Ctx();

        teardown(runner, Map.of(), ctx);

        assertThat(runner.lines())
                .noneMatch(line -> line.contains(Docker.BUILDKITD))
                .noneMatch(line -> line.contains(Docker.BUILDKIT_STATE_VOLUME));
        assertThat(ctx.lines).anyMatch(line -> line.contains(Docker.BUILDKITD));
    }

    /**
     * <b>DANGLING IS THE WHOLE PERMISSION.</b> The seed's own containers are gone by the time this
     * runs, so a seed volume something still holds is held by something else — and this phase
     * leaves it exactly where it is.
     */
    @Test
    void aVolumeSomethingStillHoldsIsLeftAlone() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command ->
                String.join(" ", command).contains("volume ls")
                        ? ScriptedRunner.ok("qits-maven-seed")
                        : ScriptedRunner.ok());
        Ctx ctx = new Ctx();

        teardown(runner, Map.of(), ctx);

        assertThat(runner.lines()).contains("docker volume rm qits-maven-seed")
                .noneMatch(line -> line.contains("volume rm qits-maven-cache"));
        assertThat(ctx.warnings).isEmpty();
    }

    /**
     * <b>QITS_KEEP_BUILDER=1 is the dev loop's answer</b>, and the phase stays in the plan so the
     * reason lands on the header line. A rerun without the warm seed caches re-fetches the
     * dependency world from Maven Central, which is minutes the dev loop pays every iteration.
     */
    @Test
    void theDevLoopKeepsTheWarmCacheAndNothingIsRemoved() {
        ScriptedRunner runner = new ScriptedRunner(command -> ScriptedRunner.ok());

        assertThatThrownBy(() -> teardown(runner, Map.of("QITS_KEEP_BUILDER", "1"), new Ctx()))
                .isInstanceOf(PhaseSkipped.class)
                .hasMessageContaining("QITS_KEEP_BUILDER");
        assertThat(runner.argv).isEmpty();
    }

    /**
     * A host that never ran the buildx era, or a second run of this phase, has no builder to
     * remove. That is not a warning; the boot is not worse off for a machine that was already
     * clean.
     */
    @Test
    void aHostWithNoLegacyBuilderIsNotAFailure() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command -> ScriptedRunner.ok());
        Ctx ctx = new Ctx();

        teardown(runner, Map.of(), ctx);

        assertThat(runner.lines()).noneMatch(line -> line.contains("buildx rm"));
        assertThat(ctx.warnings).isEmpty();
        assertThat(ctx.note).isEqualTo("nothing to reclaim");
    }

    /** A removal that failed for any other reason is a warning: that volume is still on the host. */
    @Test
    void aBuilderThatWillNotGoIsReported() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command -> {
            String line = String.join(" ", command);
            if (line.contains("buildx ls")) {
                return ScriptedRunner.ok(BUILDX_TABLE.toArray(new String[0]));
            }
            return line.contains("buildx rm")
                    ? ScriptedRunner.failed("Cannot connect to the Docker daemon")
                    : ScriptedRunner.ok();
        });
        Ctx ctx = new Ctx();

        teardown(runner, Map.of(), ctx);

        assertThat(ctx.warnings).hasSize(1);
        assertThat(ctx.warnings.getFirst())
                .contains("docker buildx rm qits-bootstrap-builder-v5");
    }

    /**
     * <b>Every builder of the buildx era, and NOT the node rows underneath them.</b> A
     * docker-container builder names its node after itself with an index appended, so
     * {@code qits-bootstrap-builder-v40} in this table is the NODE of {@code -v4} — asking buildx
     * to remove it as a builder is a command that can only fail. The indent is what separates the
     * two, and the last builder in use marked itself with a trailing star.
     * <p>
     * The "current" argument is empty at every caller now: nothing creates one of these any more,
     * so there is none to spare.
     */
    @Test
    void everyStrandedBuilderIsFoundAndTheNodesAreNot() {
        List<String> table = List.of(
                "NAME/NODE                     DRIVER/ENDPOINT      STATUS",
                "qits-bootstrap-builder        docker-container",
                " \\_ qits-bootstrap-builder0    \\_ unix:///var/run/docker.sock   running",
                "qits-bootstrap-builder-v3     docker-container",
                " \\_ qits-bootstrap-builder-v30  \\_ unix:///var/run/docker.sock  running",
                "qits-bootstrap-builder-v4     docker-container",
                " \\_ qits-bootstrap-builder-v40  \\_ unix:///var/run/docker.sock  running",
                "qits-bootstrap-builder-v5*    docker-container",
                " \\_ qits-bootstrap-builder-v50  \\_ unix:///var/run/docker.sock  running",
                "default                       docker",
                " \\_ default                      \\_ default                     running");

        assertThat(Docker.staleBuilders(table, ""))
                .containsExactly("qits-bootstrap-builder", "qits-bootstrap-builder-v3",
                        "qits-bootstrap-builder-v4", "qits-bootstrap-builder-v5");
    }

    /**
     * <b>And the same table with the indent gone.</b> The Docker facade's own {@code lines} helper
     * trims every row of every other docker command, so a reader that took that path would see each
     * node at column zero and ask buildx to remove {@code qits-bootstrap-builder-v30} as a builder.
     * The {@code \_} token is what still says node, and it is why the check is doubled.
     */
    @Test
    void aNodeRowIsStillANodeRowWhenSomethingTrimmedIt() {
        List<String> trimmed = List.of(
                "qits-bootstrap-builder-v3     docker-container",
                "\\_ qits-bootstrap-builder-v30  \\_ unix:///var/run/docker.sock  running",
                "qits-bootstrap-builder-v4     docker-container",
                "\\_ qits-bootstrap-builder-v40  \\_ unix:///var/run/docker.sock  running");

        assertThat(Docker.staleBuilders(trimmed, ""))
                .containsExactly("qits-bootstrap-builder-v3", "qits-bootstrap-builder-v4");
    }

    /** Somebody else's builder on a shared host is never ours, whatever it is called. */
    @Test
    void aBuilderOutsideThePrefixIsNeverSwept() {
        assertThat(Docker.staleBuilders(List.of("someone-elses-builder  docker-container",
                "default*  docker"), "")).isEmpty();
        // And a named current one is still spared, star or no star.
        assertThat(Docker.staleBuilders(List.of("qits-bootstrap-builder-v5*  docker-container"),
                "qits-bootstrap-builder-v5")).isEmpty();
    }

    // --- the two coordinates ----------------------------------------------------------------------

    private Boot boot(Map<String, String> env, Path temp) {
        return new Boot(TestConfig.from(env), new RunLog(temp.resolve("run.log")));
    }

    /**
     * <b>WHERE THIS RUN PUSHES, before and after the aliases exist.</b> The switch is the one thing
     * that decides whether a push carries the repository's name onto its event and whether it is
     * still accepted once the git host's own deployment closes the storage scheme.
     * <p>
     * Both states are still reachable, but only one of them is ever pushed in: the id-addressed
     * form is what {@code git-repos} PUTs the bare at, and that phase registers the pair before it
     * returns — so every push of the run is on the far side of this flip.
     */
    @Test
    void aPushIsIdAddressedUntilTheAliasesAreRegisteredAndNameAddressedAfter(@TempDir Path temp) {
        Boot boot = boot(Map.of("QITS_ENV_NAME", "dev"), temp);
        boot.state.repositoryIds.put("qits-ci-service", "8b1f0f0e-9a0c-4c3a-9a5b-000000000001");

        // Before the pair is registered there is nothing to resolve a name through, so the storage
        // scheme is the only address the git host can answer.
        assertThat(boot.gitUrl("ci")).isEqualTo(
                "http://dev-qits-githost:8080/git/8b1f0f0e-9a0c-4c3a-9a5b-000000000001");

        boot.state.projectId = "1f0a-project";
        boot.state.repositoriesRegistered = true;

        assertThat(boot.gitUrl("ci"))
                .isEqualTo("http://dev-qits-githost:8080/git/1f0a-project/qits-ci-service");
    }

    /** A project id with no registration behind it changes nothing: both halves have to be true. */
    @Test
    void aProjectIdAloneDoesNotMoveThePushes(@TempDir Path temp) {
        Boot boot = boot(Map.of("QITS_ENV_NAME", "dev"), temp);
        boot.state.repositoryIds.put("qits-ci-service", "8b1f0f0e-9a0c-4c3a-9a5b-000000000001");
        boot.state.projectId = "1f0a-project";

        assertThat(boot.gitUrl("ci")).isEqualTo(
                "http://dev-qits-githost:8080/git/8b1f0f0e-9a0c-4c3a-9a5b-000000000001");
    }

    /**
     * The storage id is READ, never re-derived: a rerun addresses the bare it created, whatever an
     * earlier run minted for it.
     */
    @Test
    void aRecordedStorageIdWinsOverTheDefault(@TempDir Path temp) {
        Boot boot = boot(Map.of("QITS_ENV_NAME", "dev"), temp);
        boot.state.repositoryIds.put("qits-ci-service", "8b1f0f0e-9a0c-4c3a-9a5b-000000000001");

        assertThat(boot.storageId("ci")).isEqualTo("8b1f0f0e-9a0c-4c3a-9a5b-000000000001");
        assertThat(boot.gitUrl("ci")).isEqualTo(
                "http://dev-qits-githost:8080/git/8b1f0f0e-9a0c-4c3a-9a5b-000000000001");
    }

    /**
     * <b>A REPOSITORY NOTHING HAS RECORDED IS MINTED ONCE, and the answer is kept.</b> A storage id
     * is a uuid with nothing to derive it from, so a second call that minted again would address a
     * bare this run never created — and {@code git-repos} writes what this answers into
     * {@code .qits-bootstrap.env} before it makes the bare, which is what carries the pairing
     * across a resumed run.
     */
    @Test
    void anUnrecordedRepositoryIsMintedOnceAndRemembered(@TempDir Path temp) {
        Boot boot = boot(Map.of("QITS_ENV_NAME", "dev"), temp);

        String minted = boot.storageId("events");

        assertThat(minted).matches("[0-9a-f-]{36}").isNotEqualTo("qits-events-service");
        assertThat(boot.storageId("events")).isEqualTo(minted);
        assertThat(boot.state.repositoryIds)
                .containsEntry("qits-events-service", minted);
        // And two repositories never share one, which a name-shaped id could not have got wrong.
        assertThat(boot.storageId("ci")).isNotEqualTo(minted);
    }

    /**
     * <b>What a name resolves to, in the shape qits-projects answers</b> — the read {@code
     * git-repos} makes before it decides not to create a repository. Misread, it is either a bare
     * made a second time or a rerun that re-PUTs the whole platform.
     */
    @Test
    void theByNameAnswerNamesTheStorageIdAndAMissOnlyMeansNo() {
        assertThat(PipelinePhases.storageIdIn(new eu.wohlben.qits.cli.bootstrap.api.Http.Response(
                200, "{\"repositoryId\":\"8b1f0f0e-9a0c-4c3a-9a5b-000000000001\"}")))
                .isEqualTo("8b1f0f0e-9a0c-4c3a-9a5b-000000000001");
        // A name nothing holds. The 404 is the ordinary answer of a cold boot, once per repository.
        assertThat(PipelinePhases.storageIdIn(new eu.wohlben.qits.cli.bootstrap.api.Http.Response(
                404, ""))).isNull();
        // And an answer that is not one is not read as an id: a service that is up and says nothing
        // useful must not stop this run from creating the bare.
        assertThat(PipelinePhases.storageIdIn(new eu.wohlben.qits.cli.bootstrap.api.Http.Response(
                200, "{}"))).isNull();
    }

    /** The project the registration phase looks for, in the listing shape qits-projects answers. */
    @Test
    void theQitsProjectIsFoundByNameOrSlugAndNothingElseIs() {
        String listing = """
                {"entries":[
                  {"project":{"id":"p-other","name":"Checkout","slug":"checkout"}},
                  {"project":{"id":"p-qits","name":"qits","slug":"qits"}}
                ]}""";

        assertThat(PipelinePhases.qitsProjectId(new eu.wohlben.qits.cli.bootstrap.api.Http.Response(
                200, listing))).contains("p-qits");
        // A boot whose self-seed has not run yet gets no answer, and waits rather than inventing one.
        assertThat(PipelinePhases.qitsProjectId(new eu.wohlben.qits.cli.bootstrap.api.Http.Response(
                200, "{\"entries\":[]}"))).isEmpty();
        // And an answer that is not one is not read as an empty platform.
        assertThat(PipelinePhases.qitsProjectId(new eu.wohlben.qits.cli.bootstrap.api.Http.Response(
                503, ""))).isEmpty();
    }

    /**
     * <b>The closing report prints the PUBLIC clone url and no other, and it prints it by SLUG.</b>
     * qits-projects matches the project segment by id or slug and the git host passes it through
     * verbatim, so {@code /git/qits/<repo>.git} is the address a person can read and type. The id
     * form is printed beside it as what a service on qits-net dials. {@code /git/<repoId>} is the
     * storage scheme — the deployed git host serves it to qits-projects' client alone — so printing
     * it would be handing a person an address they are refused at.
     */
    @Test
    void theReportPrintsTheProjectScopedCloneUrl(@TempDir Path temp) throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command -> ScriptedRunner.ok());
        Boot boot = new Boot(TestConfig.from(Map.of("QITS_ENV_NAME", "dev", "QITS_PORT", "8080",
                "QITS_DOMAIN", "qits-dev.eu", "QITS_PUBLIC_IP", "203.0.113.7")),
                new RunLog(temp.resolve("run.log")), runner);
        boot.state.wrapperDir = temp;
        boot.state.projectId = "1f0a-project";
        Ctx ctx = new Ctx();

        new PipelinePhases(boot).summary().action().run(ctx);

        assertThat(ctx.lines).anyMatch(line -> line.startsWith(
                "git host:  http://githost.dev.localhost:8080/git/qits/<repo>.git"));
        assertThat(ctx.lines).anyMatch(line -> line.contains(
                "http://githost.dev.localhost:8080/git/qits/qits-qits.git"));
        // The machine form keeps the id, which is always valid whatever a slug is renamed to.
        assertThat(ctx.lines).anyMatch(line -> line.contains(
                "http://dev-qits-githost:8080/git/1f0a-project/<repo>"));
        // And nowhere does it hand a person a storage-scheme address to dial. The line that names
        // /git/<repoId> says what it is; no url printed here is one.
        assertThat(ctx.lines).noneMatch(line -> line.contains("localhost:8080/git/<repoId>"));
        assertThat(ctx.lines).noneMatch(line -> line.contains("qits-githost:8080/git/<repoId>"));
    }

    /**
     * A run that never got that far still prints a shape rather than a broken url — for the machine
     * form. The public one is the slug, which this program knows before the platform exists.
     */
    @Test
    void theReportNamesThePlaceholderWhenNothingWasRegistered(@TempDir Path temp) throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command -> ScriptedRunner.ok());
        Boot boot = new Boot(TestConfig.from(Map.of("QITS_ENV_NAME", "dev", "QITS_PORT", "8080",
                "QITS_DOMAIN", "qits-dev.eu", "QITS_PUBLIC_IP", "203.0.113.7")),
                new RunLog(temp.resolve("run.log")), runner);
        boot.state.wrapperDir = temp;
        Ctx ctx = new Ctx();

        new PipelinePhases(boot).summary().action().run(ctx);

        assertThat(ctx.lines).anyMatch(line -> line.contains("/git/<projectId>/<repo>,"));
        assertThat(ctx.lines).anyMatch(line -> line.contains("/git/qits/<repo>.git"));
    }

    /**
     * <b>A domain platform prints the PROJECT-QUALIFIED app host, flat because the {@code qits}
     * project has environments disabled, and names what it retired.</b>
     * Names are read right to left — {@code <app>[.<env>].<project>.<domain>} — so {@code ci.<domain>}
     * and {@code ci.<env>.<domain>} are both gone, and so is the bare apex as a door: the front
     * door is this platform's own project door, {@code qits.<domain>}, and its login host sits
     * inside it. The blocks the retired no-domain mode printed — a resolver check for
     * {@code *.localhost} browser names and a dead-passkey warning — are gone.
     */
    @Test
    void theReportPrintsTheProjectQualifiedAppHostOnADomainPlatform(@TempDir Path temp)
            throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command -> ScriptedRunner.ok());
        Boot boot = new Boot(TestConfig.from(Map.of("QITS_ENV_NAME", "dev", "QITS_PORT", "8080",
                "QITS_DOMAIN", "qits-dev.eu", "QITS_PUBLIC_IP", "203.0.113.7")),
                new RunLog(temp.resolve("run.log")), runner);
        boot.state.wrapperDir = temp;
        Ctx ctx = new Ctx();

        new PipelinePhases(boot).summary().action().run(ctx);

        assertThat(ctx.lines).anyMatch(line ->
                line.startsWith("edge:      https://qits.qits-dev.eu/"));
        assertThat(ctx.lines).anyMatch(line ->
                line.startsWith("sign in:   https://idp.qits.qits-dev.eu/idp/login"));
        // THE qits PROJECT HAS ENVIRONMENTS DISABLED, so no browser name spells the env:
        // idp.dev.qits.qits-dev.eu 404s on such a platform.
        assertThat(ctx.lines).noneMatch(line -> line.contains("idp.dev.qits.qits-dev.eu"));
        // THE LOGIN IS ON THE IDP'S OWN HOST, and no door is given an /idp path: it redirects /
        // and 404s everything else.
        assertThat(ctx.lines).noneMatch(line -> line.contains("https://qits.qits-dev.eu/idp"));
        // The session is the point of the move, so the report says what carries it.
        assertThat(ctx.lines).anyMatch(line -> line.contains("scoped to qits-dev.eu"));
        assertThat(ctx.lines).anyMatch(line ->
                line.contains("https://<app>.qits.qits-dev.eu/"));
        assertThat(ctx.lines).noneMatch(line -> line.contains("https://<app>.dev.qits.qits-dev.eu"));
        // The retired shapes are NAMED rather than left out: a person who knew the old platform
        // will type them, and a report that says nothing about them reads as a broken edge.
        assertThat(ctx.lines).anyMatch(line ->
                line.contains("<app>.qits-dev.eu and <app>.dev.qits-dev.eu are retired"));
        assertThat(ctx.lines).anyMatch(line ->
                line.contains("and so is the bare apex qits-dev.eu"));
        // Another project's hosts have the same shape with its own slug.
        assertThat(ctx.lines).anyMatch(line ->
                line.contains("<app>.<env>.<project>.qits-dev.eu"));
        // Both depths of this project are on the idp's return list whatever its shape: it is an
        // allow-list, not a router.
        assertThat(ctx.lines).anyMatch(line ->
                line.contains("*.qits.qits-dev.eu and *.dev.qits.qits-dev.eu"));
        // No browser name under *.localhost: no hosts-file fallback and no passkey warning.
        assertThat(ctx.lines).noneMatch(line -> line.contains("getent hosts"));
        assertThat(ctx.lines).noneMatch(line -> line.startsWith("passkeys:"));
        assertThat(ctx.lines).noneMatch(line -> line.contains("qits.localhost"));
    }

    /**
     * <b>Which publishers the pinned-tag replay is for.</b> Three of the nine are seed libraries,
     * and the maven publishes of phases 18-25 already put every pinned version of those in the
     * store out of the same closure — so replaying their older tags would cost a ci run per tag
     * for bytes the registry has. The six below are the ones nothing else publishes.
     */
    @Test
    void theSeedLibrariesAreExcludedFromThePinnedTagReplay() {
        List<String> alsoSeeded = PlatformModel.RELEASE_PUBLISHERS.stream()
                .filter(SeedPhases.SEED_LIBRARIES::contains)
                .toList();

        assertThat(alsoSeeded).containsExactlyInAnyOrder(
                "integrations-quarkus", "eventstream", "containers-driver");
        assertThat(PlatformModel.RELEASE_PUBLISHERS)
                .filteredOn(name -> !SeedPhases.SEED_LIBRARIES.contains(name))
                .containsExactly("spa-ui-components", "integrations-angular", "oci-workspace",
                        "workspace-daemon", "oci-workspace-editor", "projects-daemon");
    }

    // --- when a tag that already stands still needs its build ------------------------------------

    /**
     * <b>The four cases, and the third one is the defect.</b> On 2026-09-05 qits-oci-workspace's
     * release run built {@code qits/workspace-base:2026.905.92439} and then died pushing it. The
     * tag stood, so the rerun skipped the version — and every later phase that pulls that image
     * failed instead. A tag on the git host is evidence of a PUSH, never of a publish.
     */
    @Test
    void aTagThatStandsStillNeedsItsBuildWhenTheRegistryIsMissingThePackage() {
        // A fresh tag always does, whatever the registry says.
        assertThat(PipelinePhases.needsRelease(true, true, false)).isTrue();
        assertThat(PipelinePhases.needsRelease(true, true, true)).isTrue();
        // The tag stands and the packages are there: nothing left to restore.
        assertThat(PipelinePhases.needsRelease(false, true, true)).isFalse();
        // The tag stands and a package is missing: ask for the build again.
        assertThat(PipelinePhases.needsRelease(false, true, false)).isTrue();
        // Not addressable — the npm publishers — so the tag is all the evidence there is.
        assertThat(PipelinePhases.needsRelease(false, false, false)).isFalse();
        assertThat(PipelinePhases.needsRelease(false, false, true)).isFalse();
    }

    /**
     * <b>What each publisher's run leaves behind, addressed by the RELEASE version.</b> The image
     * publishers tag with the version itself, which is what makes the question answerable; the two
     * npm ones publish their own semver, and answering nothing is honest rather than a gap.
     */
    @Test
    void everyPublisherSaysWhatItsRunPublishes() {
        assertThat(PlatformModel.releasePackages("oci-workspace")).singleElement()
                .isEqualTo(new PlatformModel.ReleasePackage(
                        PlatformModel.ReleasePackage.Kind.OCI, "qits/workspace-base"));
        assertThat(PlatformModel.releasePackages("workspace-daemon")).singleElement()
                .isEqualTo(new PlatformModel.ReleasePackage(
                        PlatformModel.ReleasePackage.Kind.OCI, "qits/workspace"));
        assertThat(PlatformModel.releasePackages("oci-workspace-editor")).singleElement()
                .isEqualTo(new PlatformModel.ReleasePackage(
                        PlatformModel.ReleasePackage.Kind.OCI, "qits/workspace-editor"));
        // Two images from one run, so either one missing is a run to ask for again.
        assertThat(PlatformModel.releasePackages("projects-daemon")).hasSize(2)
                .extracting(PlatformModel.ReleasePackage::coordinate)
                .containsExactly("qits/projects-daemon", "qits/project-agent");
        assertThat(PlatformModel.releasePackages("eventstream")).singleElement()
                .isEqualTo(new PlatformModel.ReleasePackage(
                        PlatformModel.ReleasePackage.Kind.MAVEN, "qits-eventstream"));
        assertThat(PlatformModel.releasePackages("containers-driver")).singleElement()
                .isEqualTo(new PlatformModel.ReleasePackage(
                        PlatformModel.ReleasePackage.Kind.MAVEN, "qits-containers-driver"));
        assertThat(PlatformModel.releasePackages("integrations-quarkus")).singleElement()
                .extracting(PlatformModel.ReleasePackage::coordinate).isEqualTo("qits-auth-core");
        // The npm pair publishes one version per release tag, so they answer like the rest.
        assertThat(PlatformModel.releasePackages("spa-ui-components")).singleElement()
                .isEqualTo(new PlatformModel.ReleasePackage(
                        PlatformModel.ReleasePackage.Kind.NPM, "@qits/ui-components"));
        assertThat(PlatformModel.releasePackages("integrations-angular")).singleElement()
                .extracting(PlatformModel.ReleasePackage::coordinate).isEqualTo("@qits/angular");
        // Every publisher the plan makes a phase for is answered one way or the other.
        assertThat(PlatformModel.RELEASE_PUBLISHERS)
                .allSatisfy(name -> assertThat(PlatformModel.releasePackages(name)).isNotNull());
    }

    /**
     * The Distribution API lives at the SERVICE root and not under {@code /artifacts} — measured on
     * the live store, where the second spelling answers 404 and the first 200.
     */
    @Test
    void theRegistryIsAskedAtTheDistributionApiRoot() {
        assertThat(CiApi.class).isNotNull();
        assertThat(eu.wohlben.qits.cli.bootstrap.api.ArtifactsApi.manifestUrl(
                "http://prod-qits-artifacts:8080", "qits/workspace-base", "2026.905.92439"))
                .isEqualTo("http://prod-qits-artifacts:8080/v2/qits/workspace-base/manifests/"
                        + "2026.905.92439");
        assertThat(new eu.wohlben.qits.cli.bootstrap.api.ArtifactsApi(
                new Http(), "http://prod-qits-artifacts:8080/artifacts").registryBase())
                .isEqualTo("http://prod-qits-artifacts:8080");
    }

    /**
     * <b>An npm version is probed at its TARBALL</b> — the path {@code npm ci} fetches and the one
     * a failing install's 404 names. This registry does not serve {@code <package>/<version>}
     * (measured: 404 for a version its own packument lists), and the file name drops the scope.
     */
    @Test
    void anNpmVersionIsAskedForAtTheTarballItsInstallWouldFetch() {
        assertThat(eu.wohlben.qits.cli.bootstrap.api.ArtifactsApi.npmTarballUrl(
                "http://prod-qits-artifacts:8080/artifacts", "@qits/ui-components",
                "2026.902.204627"))
                .isEqualTo("http://prod-qits-artifacts:8080/artifacts/npm/npm/@qits/ui-components"
                        + "/-/ui-components-2026.902.204627.tgz");
    }

    /**
     * The per-phase note says both numbers: a boot that replayed five npm versions and one that
     * found all five already there did the same right thing and cost wildly different minutes.
     */
    @Test
    void thePhaseSaysHowManyItReplayedAndHowManyWereAlreadyThere() {
        assertThat(PipelinePhases.pinnedNote(0, 0)).isEmpty();
        assertThat(PipelinePhases.pinnedNote(3, 2))
                .isEqualTo(" (3 pinned replayed, 2 already held)");
        assertThat(PipelinePhases.pinnedNote(0, 5))
                .isEqualTo(" (0 pinned replayed, 5 already held)");
    }

    /**
     * <b>The re-announce guard reads the SLOT file, and the fixtures are real ones.</b> The
     * predicate behind it asked whether a recipe spelled {@code event: SCMRelease}, which is the
     * retired trigger file's grammar: a {@code release.yml} declares no {@code event:} at all, so a
     * repointed constant alone would have left this guard permanently false and the
     * IMAGE_MISSING race of 2026-09-05 unguarded again, with nothing red to say so.
     */
    @Test
    void aRepositoryDeclaringItsOwnReleaseSlotDeclaresAReleasePhase(
            @org.junit.jupiter.api.io.TempDir Path src) throws Exception {
        Path slots = src.resolve(eu.wohlben.qits.cli.bootstrap.api.CiApi.RELEASE_SLOTS);
        Files.createDirectories(slots.getParent());
        Files.writeString(slots, """
                archetype: java-service
                artifacts:
                  - { type: docker, name: qits/qits-ci, sbom: .sbom/sbom.json }
                release:
                  - image: qits/build-images/node-docker-base:latest
                    build: true
                    script: |
                      buildctl build --output "type=image,name=$ref,push=true"
                """);

        assertThat(PipelinePhases.declaresReleasePhase(src)).isTrue();
    }

    /**
     * <b>An archetype counts on its own, and erring toward yes is the decision.</b> The steps of
     * this repository's phase two live in qits-ci's
     * {@code .config/qits/release-archetypes/java-service.yml} (shipped in qits-ci-service, or in a
     * repository's own copy at that path), which one checkout cannot resolve —
     * so a declared archetype is read as a declared phase. A false positive costs five more calls
     * to a door that publishes nothing; a false negative costs the deploy.
     */
    @Test
    void anArchetypeAloneDeclaresAReleasePhase(@org.junit.jupiter.api.io.TempDir Path src)
            throws Exception {
        Path slots = src.resolve(eu.wohlben.qits.cli.bootstrap.api.CiApi.RELEASE_SLOTS);
        Files.createDirectories(slots.getParent());
        Files.writeString(slots, "archetype: cli\n");

        assertThat(PipelinePhases.declaresReleasePhase(src)).isTrue();
    }

    /**
     * Phase one is not phase two. A slot file carrying only {@code release-request:} — the QA run,
     * which publishes nothing — declares no release phase, and the column-zero anchoring is what
     * keeps the prefix from answering for the key.
     */
    @Test
    void aQaSlotAloneDeclaresNoReleasePhase(@org.junit.jupiter.api.io.TempDir Path src)
            throws Exception {
        Path slots = src.resolve(eu.wohlben.qits.cli.bootstrap.api.CiApi.RELEASE_SLOTS);
        Files.createDirectories(slots.getParent());
        Files.writeString(slots, """
                release-request:
                  - image: qits/build-images/maven-base:latest
                    script: |
                      ./mvnw -B -ntp verify
                """);

        assertThat(PipelinePhases.declaresReleasePhase(src)).isFalse();
    }

    /** No slot file is the one answer that is really a no: nothing declares, nothing is owed. */
    @Test
    void aCheckoutWithNoSlotFileDeclaresNothing(@org.junit.jupiter.api.io.TempDir Path src) {
        assertThat(PipelinePhases.declaresReleasePhase(src)).isFalse();
    }

    // --- the images a run is started from --------------------------------------------------------

    private static final String PIN = "2026.930.132340";

    private static final String MANIFESTS = "GET http://prod-qits-artifacts:8080/v2/";

    private static final List<String> STORE_IMAGES = List.of(
            "qits/build-images/ci-base/manifests/latest",
            "qits/build-images/maven-base/manifests/latest",
            "qits/build-images/userflows-base/manifests/latest",
            "qits/build-images/node-base/manifests/latest",
            "qits/build-images/node-docker-base/manifests/latest",
            "qits/qits-ci-runner/manifests/" + PIN);

    /** A boot whose ci checkout pins the runner, with the process runner and the http in hand. */
    private Boot runnerBoot(ScriptedRunner runner, CannedHttp http, Map<String, String> env)
            throws Exception {
        Map<String, String> config = new java.util.HashMap<>(env);
        config.put("QITS_SRC", temp.resolve("src").toString());
        Boot boot = new Boot(TestConfig.from(config), new RunLog(temp.resolve("run.log")), runner,
                http);
        boot.state.srcDir = temp.resolve("src");
        boot.state.wrapperDir = temp;
        Path ci = Files.createDirectories(boot.state.repoDir("ci"));
        Files.writeString(ci.resolve("pom.xml"), "<project><properties>"
                + "<qits.ci-runner-protocol.version>" + PIN + "</qits.ci-runner-protocol.version>"
                + "</properties></project>", StandardCharsets.UTF_8);
        return boot;
    }

    /** A store that answers 200 for the listed manifests and 404 for the rest. */
    private static CannedHttp store(List<String> held) {
        CannedHttp http = new CannedHttp();
        for (String image : STORE_IMAGES) {
            http.answer(MANIFESTS + image, held.contains(image) ? 200 : 404, "");
        }
        return http;
    }

    /**
     * <b>A cold store: all six are pushed, under the registry host, with the publishing pair in a
     * config of the phase's own.</b> The credential is a file the command names — never an
     * argument — it is the pair docker's token dance needs, and the directory is gone when the
     * phase ends.
     */
    @Test
    void aColdStoreIsPushedEveryStepImageAndTheRunnerImage() throws Exception {
        List<String> configs = new ArrayList<>();
        ScriptedRunner runner = new ScriptedRunner(command -> {
            if (command.contains("push")) {
                try {
                    configs.add(Files.readString(Path.of(command.get(2)).resolve("config.json")));
                } catch (java.io.IOException e) {
                    configs.add("unreadable: " + e);
                }
            }
            return ScriptedRunner.ok();
        });
        CannedHttp http = store(List.of())
                .answer("GET http://prod-qits-idp:8080/idp/q/health/ready", 200, "")
                .answer("FORM http://prod-qits-idp:8080/idp/token", 200,
                        "{\"access_token\":\"read-token\",\"expires_in\":3600}")
                .answer("POST http://prod-qits-idp:8080/idp/api/clients", 201,
                        "{\"clientId\":\"dyn-bootstrap-publish-1\",\"secret\":\"s3cr3t-pair\"}");
        Boot boot = runnerBoot(runner, http, Map.of());
        boot.state.bootstrapClientId = "prod-qits-bootstrap";
        boot.state.bootstrapSecret = "boot";
        CiLogStreamTest.Recorder ctx = new CiLogStreamTest.Recorder();

        new PipelinePhases(boot).imagesPublish().action().run(ctx);

        List<List<String>> pushes = runner.argv.stream()
                .filter(command -> command.contains("push")).toList();
        assertThat(pushes).hasSize(6).allSatisfy(command -> {
            assertThat(command).hasSize(5);
            assertThat(command.subList(0, 2)).containsExactly("docker", "--config");
            assertThat(command.get(3)).isEqualTo("push");
        });
        assertThat(pushes.stream().map(List::getLast)).containsExactly(
                "registry.prod.localhost:8080/qits/build-images/ci-base:latest",
                "registry.prod.localhost:8080/qits/build-images/maven-base:latest",
                "registry.prod.localhost:8080/qits/build-images/userflows-base:latest",
                "registry.prod.localhost:8080/qits/build-images/node-base:latest",
                "registry.prod.localhost:8080/qits/build-images/node-docker-base:latest",
                "registry.prod.localhost:8080/qits/qits-ci-runner:" + PIN);
        // The config names the registry host and carries the pair as docker login would.
        String auth = java.util.Base64.getEncoder().encodeToString(
                "dyn-bootstrap-publish-1:s3cr3t-pair".getBytes(StandardCharsets.UTF_8));
        assertThat(configs).hasSize(6).allSatisfy(config -> assertThat(config)
                .contains("\"registry.prod.localhost:8080\"").contains(auth));
        // Neither the secret nor its base64 is on a command line or in the log.
        assertThat(runner.lines()).noneMatch(line -> line.contains("s3cr3t-pair")
                || line.contains(auth));
        assertThat(ctx.logs).noneMatch(line -> line.contains("s3cr3t-pair") || line.contains(auth));
        // And a line docker echoed either back on would be masked.
        Cmd push = runner.cmds.stream().filter(cmd -> cmd.command().contains("push")).findFirst()
                .orElseThrow();
        assertThat(push.maskText("denied for s3cr3t-pair / " + auth)).isEqualTo(
                "denied for *** / ***");
        // The directory is the phase's own and is gone with it.
        assertThat(Path.of(pushes.getFirst().get(2))).doesNotExist();
    }

    /** What the store holds is left alone — every rerun, and every live platform. */
    @Test
    void aStoreThatHoldsEveryImageIsPushedNothing() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command -> ScriptedRunner.ok());
        Boot boot = runnerBoot(runner, store(STORE_IMAGES), Map.of());

        assertThatThrownBy(() -> new PipelinePhases(boot).imagesPublish().action()
                .run(new CiLogStreamTest.Recorder()))
                .isInstanceOf(PhaseSkipped.class)
                .hasMessageContaining("the registry holds all 6 images");
        assertThat(runner.argv).isEmpty();
    }

    /** Only the missing ones are pushed: a released {@code :latest} is never written over. */
    @Test
    void onlyTheImagesTheStoreLacksArePushed() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command -> ScriptedRunner.ok());
        Boot boot = runnerBoot(runner, store(STORE_IMAGES.subList(0, 5)),
                Map.of("QITS_MACHINE_AUTH", "0"));

        new PipelinePhases(boot).imagesPublish().action().run(new CiLogStreamTest.Recorder());

        assertThat(runner.argv.stream().filter(command -> command.contains("push"))
                .map(List::getLast))
                .containsExactly("registry.prod.localhost:8080/qits/qits-ci-runner:" + PIN);
    }

    /** An image that is nowhere stops the boot and says which flag hid its build. */
    @Test
    void anImageThatIsNeitherStoredNorBuiltStopsTheBoot() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command ->
                command.contains("inspect") ? ScriptedRunner.failed("No such image")
                        : ScriptedRunner.ok());
        Boot boot = runnerBoot(runner, store(List.of()), Map.of("QITS_MACHINE_AUTH", "0"));

        assertThatThrownBy(() -> new PipelinePhases(boot).imagesPublish().action()
                .run(new CiLogStreamTest.Recorder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("registry.prod.localhost:8080/qits/build-images/ci-base:latest")
                .hasMessageContaining("rerun without QITS_SKIP_BUILD");
        assertThat(runner.argv).noneMatch(command -> command.contains("push"));
    }

    // --- the seed images, at their release tag ---------------------------------------------------

    private static final String RELEASE = "2026.930.120000";

    /** git answers every seed-deployed checkout with one release tag, docker answers everything. */
    private static ScriptedRunner seedImagesDocker(boolean localImages) {
        return new ScriptedRunner(command -> {
            String line = String.join(" ", command);
            if (line.startsWith("git -C") && line.contains(" tag --list")) {
                return ScriptedRunner.ok(RELEASE, "not-a-release");
            }
            if (line.startsWith("docker image inspect")) {
                return localImages ? ScriptedRunner.ok("sha256:abc")
                        : ScriptedRunner.failed("No such image");
            }
            return ScriptedRunner.ok();
        });
    }

    /** A store that answers 404 for every seed-deployed image except the listed applications. */
    private static CannedHttp seedStore(Map<String, Integer> answers) {
        CannedHttp http = new CannedHttp();
        for (String name : eu.wohlben.qits.cli.bootstrap.platform.PlatformModel.SEED_DEPLOYED) {
            String application = eu.wohlben.qits.cli.bootstrap.platform.PlatformModel
                    .application(name);
            http.answer(MANIFESTS + "qits/" + application + "/manifests/" + RELEASE,
                    answers.getOrDefault(name, 404), "");
        }
        return http;
    }

    /**
     * <b>A cold store: every seed-deployed application's seed build goes in under the name the
     * deployer pulls</b> — {@code qits/<application>:<release>}, tagged from
     * {@code qits/<name>:latest} and pushed with the publishing credential. The version is the
     * checkout's newest RELEASE tag, never a stand-in: the deployer reads the spec at that tag.
     */
    @Test
    void aColdStoreIsPushedEverySeedDeployedImageAtItsReleaseTag() throws Exception {
        ScriptedRunner runner = seedImagesDocker(true);
        Boot boot = runnerBoot(runner, seedStore(Map.of()), Map.of("QITS_MACHINE_AUTH", "0"));

        new PipelinePhases(boot).seedImagesPublish().action().run(new CiLogStreamTest.Recorder());

        assertThat(runner.lines()).contains(
                "docker tag qits/idp:latest registry.prod.localhost:8080/qits/qits-idp:" + RELEASE,
                "docker tag qits/edge:latest registry.prod.localhost:8080/qits/qits-edge:"
                        + RELEASE);
        assertThat(runner.argv.stream().filter(command -> command.contains("push"))
                .map(List::getLast)).containsExactly(
                "registry.prod.localhost:8080/qits/qits-idp:" + RELEASE,
                "registry.prod.localhost:8080/qits/qits-projects:" + RELEASE,
                "registry.prod.localhost:8080/qits/qits-events:" + RELEASE,
                "registry.prod.localhost:8080/qits/qits-mirror:" + RELEASE,
                "registry.prod.localhost:8080/qits/qits-artifacts:" + RELEASE,
                "registry.prod.localhost:8080/qits/qits-githost:" + RELEASE,
                "registry.prod.localhost:8080/qits/qits-containers:" + RELEASE,
                "registry.prod.localhost:8080/qits/qits-ci:" + RELEASE,
                "registry.prod.localhost:8080/qits/qits-edge:" + RELEASE);
        // The deployer's own image is not among them: its deployment stays last of the train.
        assertThat(runner.lines()).noneMatch(line -> line.contains("qits-deployments:"));
    }

    /**
     * <b>What the store holds is never written over</b> — on a live platform that tag holds what
     * a release run published, and a seed build over it would be a placeholder over a release.
     */
    @Test
    void aSeedImageTheStoreHoldsIsLeftAlone() throws Exception {
        ScriptedRunner runner = seedImagesDocker(true);
        Map<String, Integer> held = new java.util.HashMap<>();
        eu.wohlben.qits.cli.bootstrap.platform.PlatformModel.SEED_DEPLOYED.stream()
                .filter(name -> !name.equals("ci")).forEach(name -> held.put(name, 200));
        Boot boot = runnerBoot(runner, seedStore(held), Map.of("QITS_MACHINE_AUTH", "0"));

        new PipelinePhases(boot).seedImagesPublish().action().run(new CiLogStreamTest.Recorder());

        assertThat(runner.argv.stream().filter(command -> command.contains("push"))
                .map(List::getLast))
                .containsExactly("registry.prod.localhost:8080/qits/qits-ci:" + RELEASE);

        ScriptedRunner again = seedImagesDocker(true);
        eu.wohlben.qits.cli.bootstrap.platform.PlatformModel.SEED_DEPLOYED
                .forEach(name -> held.put(name, 200));
        Boot rerun = runnerBoot(again, seedStore(held), Map.of("QITS_MACHINE_AUTH", "0"));
        assertThatThrownBy(() -> new PipelinePhases(rerun).seedImagesPublish().action()
                .run(new CiLogStreamTest.Recorder()))
                .isInstanceOf(PhaseSkipped.class)
                .hasMessageContaining("the registry holds all 9 seed-deployed images");
        assertThat(again.lines()).noneMatch(line -> line.startsWith("docker tag")
                || line.contains(" push "));
    }

    /**
     * <b>A store that does not SAY is not a store that lacks the image.</b> Only a 404 is "not
     * there"; anything else stops the phase before one push, because the tag may hold a release.
     */
    @Test
    void aStoreThatDoesNotAnswerIsPushedNothing() throws Exception {
        ScriptedRunner runner = seedImagesDocker(true);
        Boot boot = runnerBoot(runner, seedStore(Map.of("projects", 503)),
                Map.of("QITS_MACHINE_AUTH", "0"));

        assertThatThrownBy(() -> new PipelinePhases(boot).seedImagesPublish().action()
                .run(new CiLogStreamTest.Recorder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("qits/qits-projects:" + RELEASE)
                .hasMessageContaining("503");
        assertThat(runner.lines()).noneMatch(line -> line.startsWith("docker tag")
                || line.contains(" push "));
    }

    /** A seed build that is on neither side stops the boot and names the flag that hid it. */
    @Test
    void aSeedImageThatIsNeitherStoredNorBuiltStopsTheBoot() throws Exception {
        ScriptedRunner runner = seedImagesDocker(false);
        Boot boot = runnerBoot(runner, seedStore(Map.of()), Map.of("QITS_MACHINE_AUTH", "0"));

        assertThatThrownBy(() -> new PipelinePhases(boot).seedImagesPublish().action()
                .run(new CiLogStreamTest.Recorder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("qits/idp:latest")
                .hasMessageContaining("rerun without QITS_SKIP_BUILD");
        assertThat(runner.lines()).noneMatch(line -> line.contains(" push "));
    }

    // --- step two: the seed images, put live by the deployer ------------------------------------
    //
    // Driven like the runner phases below: git and docker over a scripted runner, the deployer and
    // the idp over a canned http. The deployment listing is a supplier, so a test can have it
    // change the moment the door is posted — which is what the deployer does.

    private static final String DEPLOYMENTS =
            "GET http://prod-qits-deployments:8080/deployments/api/deployments?environmentId=env-1";

    private static final String DOOR =
            "POST http://prod-qits-deployments:8080/deployments/api/events/software-released";

    private static final String MAIN_SHA = "5ee5ee5ee5ee5ee5ee5ee5ee5ee5ee5ee5ee5ee5";

    private static final String CI_STORAGE_ID = "8b1f0f0e-9a0c-4c3a-9a5b-000000000001";

    /** One deployment row, as the deployer's listing answers it. */
    private static String deploymentRow(String id, String application, String version,
                                        String status, String container) {
        return "{\"id\":\"" + id + "\",\"applicationName\":\"" + application + "\",\"version\":\""
                + version + "\",\"status\":\"" + status + "\",\"containerName\":\"" + container
                + "\",\"detail\":\"\"}";
    }

    private static Http.Response deployments(String... rows) {
        return new Http.Response(200, "{\"deployments\":[" + String.join(",", rows) + "]}");
    }

    /**
     * git answers every checkout with {@link #RELEASE} and {@link #MAIN_SHA}, a push is accepted,
     * and {@code docker ps} lists the given containers as {@code name|status}.
     */
    private static ScriptedRunner deployDocker(List<String> containers) {
        return new ScriptedRunner(command -> {
            String line = String.join(" ", command);
            if (line.startsWith("git -C") && line.contains(" tag --list")) {
                return ScriptedRunner.ok(RELEASE);
            }
            if (line.startsWith("git -C") && line.contains(" rev-list")) {
                return ScriptedRunner.ok(MAIN_SHA);
            }
            if (line.startsWith("docker ps --format")) {
                return ScriptedRunner.ok(containers.toArray(new String[0]));
            }
            return ScriptedRunner.ok();
        });
    }

    /** A boot past git-repos and environment, with a bootstrap client and a fast poll. */
    private Boot deployBoot(ScriptedRunner runner, CannedHttp http, Map<String, String> env)
            throws Exception {
        Map<String, String> config = new java.util.HashMap<>(env);
        config.putIfAbsent("QITS_POLL_INTERVAL", "PT0.001S");
        config.putIfAbsent("QITS_DEPLOY_TIMEOUT", "PT0.3S");
        http.answer("FORM http://prod-qits-idp:8080/idp/token", 200,
                "{\"access_token\":\"bootstrap-bearer\",\"expires_in\":3600}");
        Boot boot = runnerBoot(runner, http, config);
        boot.state.bootstrapClientId = "prod-qits-bootstrap";
        boot.state.bootstrapSecret = "boot";
        boot.state.environmentId = "env-1";
        boot.state.projectId = "p-qits";
        boot.state.repositoriesRegistered = true;
        boot.state.repositoryIds.put("qits-ci-service", CI_STORAGE_ID);
        return boot;
    }

    private String recordedState() throws Exception {
        Path file = temp.resolve(".qits-bootstrap.env");
        return Files.exists(file) ? Files.readString(file) : "";
    }

    /**
     * <b>A COLD PLATFORM: the seed image goes live through the manual door, at its real
     * version.</b> Main and the tag are pushed quietly first — the deployer reads the spec at that
     * tag, name-addressed — and the application is RECORDED as seed-deployed before the door is
     * posted, because from that moment its release run's own SoftwareRelease deploys nothing.
     */
    @Test
    void aColdPlatformGetsTheSeedImageDeployedAtItsReleaseTagThroughTheManualDoor()
            throws Exception {
        ScriptedRunner runner = deployDocker(List.of());
        List<String> recordedAtTheDoor = new ArrayList<>();
        boolean[] posted = {false};
        CannedHttp http = new CannedHttp();
        http.answer(DEPLOYMENTS, () -> posted[0]
                ? deployments(deploymentRow("d-1", "qits-ci", RELEASE, "ACTIVE", "prod-qits-ci.1"))
                : deployments());
        http.answer(DOOR, () -> {
            posted[0] = true;
            try {
                recordedAtTheDoor.add(recordedState());
            } catch (Exception e) {
                recordedAtTheDoor.add("unreadable: " + e);
            }
            return new Http.Response(202, "");
        });
        Boot boot = deployBoot(runner, http, Map.of());
        CiLogStreamTest.Recorder ctx = new CiLogStreamTest.Recorder();

        new PipelinePhases(boot).seedDeploy("ci").action().run(ctx);

        assertThat(runner.lines()).anyMatch(line -> line.contains(" push -o qits.token=")
                && line.endsWith("main:refs/heads/main"));
        assertThat(runner.lines()).anyMatch(line -> line.contains(" push -o qits.token=")
                && line.endsWith("refs/tags/" + RELEASE));
        assertThat(http.calls.stream().filter(DOOR::equals)).hasSize(1);
        assertThat(http.bodies.get(DOOR)).isEqualTo("{\"repoId\":\"" + CI_STORAGE_ID
                + "\",\"projectId\":\"p-qits\",\"repoName\":\"qits-ci-service\","
                + "\"application\":\"qits-ci\",\"version\":\"" + RELEASE + "\"}");
        // qits:system: the bootstrap's own client, on the platform audience.
        assertThat(http.headers.get(DOOR)).containsEntry("Authorization", "Bearer bootstrap-bearer");
        assertThat(recordedAtTheDoor).singleElement().asString()
                .contains("SEED_DEPLOYED_QITS_CI=" + RELEASE);
        assertThat(ctx.logs).noneMatch(line -> line.startsWith("!! "));
        // Nothing is built: step two asks qits-ci for nothing at all.
        assertThat(http.calls).noneMatch(call -> call.contains("-qits-ci:"));
    }

    /**
     * An application already live at this version was not put there by a seed deploy — it runs a
     * release. Nothing is handed over, and nothing is recorded: the train must not redeploy it.
     */
    @Test
    void anApplicationAlreadyLiveAtItsReleaseIsNeitherDeployedNorRecorded() throws Exception {
        ScriptedRunner runner = deployDocker(List.of("prod-qits-ci.1.abc|Up 3 hours (healthy)"));
        CannedHttp http = new CannedHttp().answer(DEPLOYMENTS, 200, deployments(
                deploymentRow("d-0", "qits-ci", RELEASE, "ACTIVE", "prod-qits-ci.1.abc")).body());
        Boot boot = deployBoot(runner, http, Map.of());

        new PipelinePhases(boot).seedDeploy("ci").action().run(new CiLogStreamTest.Recorder());

        assertThat(http.calls).doesNotContain(DOOR);
        assertThat(recordedState()).doesNotContain("SEED_DEPLOYED_");
    }

    /** A door that refuses stops the boot: nothing after step two can run on half a platform. */
    @Test
    void aDoorThatRefusesStopsTheBoot() throws Exception {
        CannedHttp http = new CannedHttp().answer(DEPLOYMENTS, 200, deployments().body())
                .answer(DOOR, 403, "qits:system required");
        Boot boot = deployBoot(deployDocker(List.of()), http, Map.of());

        assertThatThrownBy(() -> new PipelinePhases(boot).seedDeploy("ci").action()
                .run(new CiLogStreamTest.Recorder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("qits-ci " + RELEASE)
                .hasMessageContaining("403");
    }

    /**
     * <b>The edge's seed deploy retires the bootstrap ingress</b> — after its source is on the git
     * host and before its service is created, as the train's own edge deploy always did.
     */
    @Test
    void theEdgesSeedDeployRetiresTheBootstrapIngress() throws Exception {
        ScriptedRunner base = deployDocker(List.of());
        ScriptedRunner runner = new ScriptedRunner(command ->
                String.join(" ", command).startsWith("docker ps -a --format")
                        ? ScriptedRunner.ok("qits-bootstrap-edge")
                        : base.run(Cmd.of(command), null));
        boolean[] posted = {false};
        CannedHttp http = new CannedHttp();
        http.answer(DEPLOYMENTS, () -> posted[0] ? deployments(deploymentRow("d-1",
                "qits-edge", RELEASE, "ACTIVE", "prod-qits-edge.1")) : deployments());
        http.answer(DOOR, () -> {
            posted[0] = true;
            return new Http.Response(202, "");
        });
        Boot boot = deployBoot(runner, http, Map.of());

        new PipelinePhases(boot).seedDeploy("edge").action().run(new CiLogStreamTest.Recorder());

        assertThat(runner.lines()).containsSubsequence(
                runner.lines().stream().filter(line -> line.endsWith("refs/tags/" + RELEASE))
                        .findFirst().orElseThrow(),
                "docker rm -f qits-bootstrap-edge");
        assertThat(http.calls).contains(DOOR);
    }

    // --- step four: the train replaces a seed image -----------------------------------------------

    private static final String TRIGGER = "POST http://prod-qits-ci:8080/ci/api/events/trigger";

    private static final String RUN = "GET http://prod-qits-ci:8080/ci/api/runs/r-1";

    /**
     * The train's deploy of qits-ci over a platform whose seed image of it is ACTIVE at the release
     * — with a healthy container, so "already live" would say yes. The run goes green on its
     * second read; the listing gains a row once the door is posted.
     */
    private CannedHttp trainOverSeed(int[] doors) {
        CannedHttp http = new CannedHttp();
        String seedRow = deploymentRow("d-seed", "qits-ci", RELEASE, "ACTIVE", "prod-qits-ci.1.abc");
        http.answer(DEPLOYMENTS, () -> doors[0] > 0
                ? deployments(deploymentRow("d-release", "qits-ci", RELEASE, "ACTIVE",
                        "prod-qits-ci.1.def"), seedRow)
                : deployments(seedRow));
        http.answer(TRIGGER, 200, "{\"runIds\":[\"r-1\"]}");
        int[] reads = {0};
        http.answer(RUN, () -> new Http.Response(200,
                "{\"id\":\"r-1\",\"status\":\"" + (++reads[0] > 2 ? "SUCCESS" : "RUNNING")
                        + "\",\"steps\":[]}"));
        http.answer(DOOR, () -> {
            doors[0]++;
            return new Http.Response(202, "");
        });
        return http;
    }

    /**
     * <b>A SEED-DEPLOYED APPLICATION IS DEPLOYED AGAIN FROM ITS RELEASE RUN'S IMAGE.</b> The
     * release is announced as for any deployable, and once — only once — the run is green, the
     * same version goes to the deployer's manual door: its SoftwareRelease is not newer than the
     * seed deploy's request, so nothing else would put the real image live. "Already live" is not
     * asked, because a seed image under the version answers yes. The record goes once the new row
     * is ACTIVE.
     */
    @Test
    void aSeedDeployedApplicationIsHandedOverAgainOnceItsReleaseRunIsGreen() throws Exception {
        Files.writeString(temp.resolve(".qits-bootstrap.env"),
                "SEED_DEPLOYED_QITS_CI=" + RELEASE + "\n", StandardCharsets.UTF_8);
        int[] doors = {0};
        CannedHttp http = trainOverSeed(doors);
        Boot boot = deployBoot(deployDocker(List.of("prod-qits-ci.1.abc|Up 3 hours (healthy)")),
                http, Map.of("QITS_DEPLOY_TIMEOUT", "PT5S"));
        CiLogStreamTest.Recorder ctx = new CiLogStreamTest.Recorder();

        new PipelinePhases(boot).deploy("ci").action().run(ctx);

        assertThat(http.calls).contains(TRIGGER);
        assertThat(doors[0]).isEqualTo(1);
        // Not before the run is green: the door comes after the reads that answered SUCCESS.
        int door = http.calls.indexOf(DOOR);
        assertThat(http.calls.subList(0, door).stream().filter(RUN::equals).count())
                .isGreaterThanOrEqualTo(3);
        assertThat(http.bodies.get(DOOR)).contains("\"application\":\"qits-ci\"",
                "\"version\":\"" + RELEASE + "\"");
        assertThat(ctx.logs).noneMatch(line -> line.startsWith("!! "));
        assertThat(recordedState()).doesNotContain("SEED_DEPLOYED_QITS_CI=" + RELEASE);
    }

    /**
     * <b>Every other deployment keeps the rule: a green run with no row is WAITED OUT, never handed
     * over.</b> An application not recorded as seed-deployed is not given to the manual door,
     * however long its row takes.
     */
    @Test
    void anApplicationThatIsNotSeedDeployedIsNeverHandedOver() throws Exception {
        int[] doors = {0};
        CannedHttp http = trainOverSeed(doors);
        // The same listing, but no container behind the ACTIVE row: not live, so it is built.
        Boot boot = deployBoot(deployDocker(List.of()), http, Map.of());
        CiLogStreamTest.Recorder ctx = new CiLogStreamTest.Recorder();

        new PipelinePhases(boot).deploy("ci").action().run(ctx);

        assertThat(http.calls).contains(TRIGGER).doesNotContain(DOOR);
        assertThat(ctx.logs).anyMatch(line -> line.startsWith("!! qits-ci: no terminal deployment"));
    }

    /** A seed recorded at an OLDER version is not this case: the newer release deploys itself. */
    @Test
    void onlyASeedOfTheSameVersionIsHandedOverAgain() {
        assertThat(PipelinePhases.redeploysAfterGreen(java.util.Optional.of(RELEASE), RELEASE))
                .isTrue();
        assertThat(PipelinePhases.redeploysAfterGreen(java.util.Optional.of("2026.901.10000"),
                RELEASE)).isFalse();
        assertThat(PipelinePhases.redeploysAfterGreen(java.util.Optional.empty(), RELEASE))
                .isFalse();
    }

    // --- the edge the runner dials ---------------------------------------------------------------

    private static final Map<String, String> DOMAIN = Map.of("QITS_DOMAIN", "qits-dev.eu",
            "QITS_PUBLIC_IP", "203.0.113.7", "QITS_ACME_MODE", "production");

    private static final List<String> PUBLIC_NAMES = List.of(
            "GET https://ci.qits.qits-dev.eu/ci/api/runners/install.sh",
            "GET https://idp.qits.qits-dev.eu/idp/token",
            "GET https://registry.qits.qits-dev.eu/v2/");

    private static final String UNTRUSTED =
            "javax.net.ssl.SSLHandshakeException: PKIX path building failed";

    private Boot edgeBoot(CannedHttp http, Map<String, String> env) throws Exception {
        Map<String, String> config = new java.util.HashMap<>(env);
        config.put("QITS_POLL_INTERVAL", "PT0.001S");
        config.putIfAbsent("QITS_EDGE_READY_TIMEOUT", "PT0.3S");
        return runnerBoot(new ScriptedRunner(command -> ScriptedRunner.ok()), http, config);
    }

    /**
     * <b>With a domain: the three names qits-ci tells an EDGE runner, over a certificate the JVM
     * trusts.</b> A failed handshake is no answer and the wait goes on; any answer of the service's
     * own — the 401 of a guarded door, the 405 of a POST-only one — ends it.
     */
    @Test
    void withADomainTheGateWaitsForTheThreePublicNamesOverATrustedCertificate() throws Exception {
        int[] asked = {0};
        CannedHttp http = new CannedHttp();
        http.answer(PUBLIC_NAMES.get(0), () -> ++asked[0] < 3
                ? new Http.Response(0, UNTRUSTED) : new Http.Response(401, ""));
        http.answer(PUBLIC_NAMES.get(1), 405, "");
        http.answer(PUBLIC_NAMES.get(2), 401, "");
        CiLogStreamTest.Recorder ctx = new CiLogStreamTest.Recorder();

        new PipelinePhases(edgeBoot(http, DOMAIN)).edgeReady().action().run(ctx);

        assertThat(asked[0]).isEqualTo(3);
        assertThat(http.calls).containsAll(PUBLIC_NAMES);
    }

    /** A certificate that never becomes trusted stops the boot, naming the host and the reason. */
    @Test
    void anEdgeThatNeverServesATrustedCertificateStopsTheBoot() throws Exception {
        CannedHttp http = new CannedHttp()
                .answer(PUBLIC_NAMES.get(0), 0, UNTRUSTED)
                .answer(PUBLIC_NAMES.get(1), 405, "")
                .answer(PUBLIC_NAMES.get(2), 404, "");

        assertThatThrownBy(() -> new PipelinePhases(edgeBoot(http, DOMAIN)).edgeReady().action()
                .run(new CiLogStreamTest.Recorder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https://ci.qits.qits-dev.eu/ci/api/runners/install.sh: "
                        + "no answer (" + UNTRUSTED)
                // A 404 is a name the edge does not route yet.
                .hasMessageContaining("https://registry.qits.qits-dev.eu/v2/: 404")
                .hasMessageContaining("DNS-01");
    }

    /** A domain on staging or with issuance off can never pass, so it is refused before a poll. */
    @Test
    void aDomainWithoutAProductionCertificateIsRefusedBeforeAnythingIsAsked() throws Exception {
        for (String mode : List.of("staging", "off")) {
            Map<String, String> env = new java.util.HashMap<>(DOMAIN);
            env.put("QITS_ACME_MODE", mode);
            CannedHttp http = new CannedHttp();

            assertThatThrownBy(() -> new PipelinePhases(edgeBoot(http, env)).edgeReady().action()
                    .run(new CiLogStreamTest.Recorder()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("'" + mode + "'")
                    .hasMessageContaining("production");
            assertThat(http.calls).isEmpty();
        }
    }

    /**
     * <b>Without a domain the gate can never pass</b>: the runner reaches the platform only through
     * its public names. Refused before a poll, in the same words as the command's own refusal.
     */
    @Test
    void withoutADomainTheGateIsRefusedBeforeAnythingIsAsked() throws Exception {
        CannedHttp http = new CannedHttp();

        assertThatThrownBy(() -> new PipelinePhases(edgeBoot(http, Map.of())).edgeReady().action()
                .run(new CiLogStreamTest.Recorder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(eu.wohlben.qits.cli.bootstrap.config.DomainName.missingRefusal(false));
        assertThat(http.calls).isEmpty();
    }

    @Test
    void theEdgeServesANameWhenTheServiceItselfAnswers() {
        assertThat(PipelinePhases.edgeServes(new Http.Response(401, ""))).isTrue();
        assertThat(PipelinePhases.edgeServes(new Http.Response(200, ""))).isTrue();
        assertThat(PipelinePhases.edgeServes(new Http.Response(405, ""))).isTrue();
        assertThat(PipelinePhases.edgeServes(new Http.Response(404, ""))).isFalse();
        assertThat(PipelinePhases.edgeServes(new Http.Response(503, ""))).isFalse();
        assertThat(PipelinePhases.edgeServes(new Http.Response(0, UNTRUSTED))).isFalse();
    }

    // --- this host's runner -----------------------------------------------------------------------
    //
    // The two phases, driven through the two things they talk to: qits-ci over a canned http and
    // docker over a scripted runner. What is asserted is what went over the wire and onto a command
    // line, because that is what a live boot would have done.

    private static final String RUNNER_ID = "7c1d2f0e-5a8b-4c3d-9e1f-0a2b3c4d5e6f";

    private static final String OTHER_ID = "11111111-2222-4333-8444-555555555555";

    private static final String TOKEN = "qits_tok_abc123DEF456";

    private static final String RUNNERS = "http://prod-qits-ci:8080/ci/api/runners";

    private static final String RUNNER_CONTAINER = "qits-ci-runner-7c1d2f0e-" + PIN;

    private static final String RUNNER_IMAGE_REF =
            "registry.prod.localhost:8080/qits/qits-ci-runner:" + PIN;

    /** An install line shaped exactly as qits-ci's {@code RunnerInstallScript.line} renders it. */
    private static String installLine(String token) {
        return "curl -fsSL -H 'Authorization: Bearer " + token + "' https://ci.qits.qits-dev.eu"
                + "/ci/api/runners/install.sh | sudo env QITS_CI_RUNNER_URL='https://ci.qits.qits-dev.eu'"
                + " QITS_CI_RUNNER_ID='" + RUNNER_ID + "' QITS_CI_RUNNER_REGISTRATION_TOKEN='" + token
                + "' QITS_CI_RUNNER_SLOTS='2' sh";
    }

    /** One row of the listing. {@code lastSeenAt} null is a runner nothing has ever run as. */
    private static String row(String id, String name, String plane, boolean registered,
                              boolean connected, String lastSeenAt, String more) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"slots\":2,\"plane\":\"" + plane
                + "\",\"registered\":" + registered + ",\"connected\":" + connected
                + ",\"lastSeenAt\":" + (lastSeenAt == null ? "null" : "\"" + lastSeenAt + "\"")
                + (more == null ? "" : "," + more) + "}";
    }

    private static String localhost(String id, boolean registered, boolean connected,
                                    String lastSeenAt) {
        return row(id, "localhost", "EDGE", registered, connected, lastSeenAt, null);
    }

    private static final String EXTERNAL = row(OTHER_ID, "qits-ci", "EDGE", true, true,
            "2026-09-30T16:00:00Z", null);

    private static String listing(String... runners) {
        return "{\"runners\":[" + String.join(",", runners) + "]}";
    }

    private static List<com.fasterxml.jackson.databind.JsonNode> runners(String... rows) {
        return CiApi.runnersIn(new Http.Response(200, listing(rows)));
    }

    private static PipelinePhases.RunnerClaim claim(java.util.Optional<String> recorded,
                                                    String... rows) {
        return PipelinePhases.runnerDecision(runners(rows), recorded).claim();
    }

    // --- whose runners are these ---------------------------------------------------------------

    /** A cold platform lists no runner, and a platform with no runner executes nothing. */
    @Test
    void aPlatformWithNoRunnerGetsOneDeclared() {
        assertThat(claim(java.util.Optional.empty()))
                .isEqualTo(PipelinePhases.RunnerClaim.DECLARE);
        // Whatever an earlier installation recorded: the platform it named is gone.
        assertThat(claim(java.util.Optional.of(OTHER_ID)))
                .isEqualTo(PipelinePhases.RunnerClaim.DECLARE);
    }

    /** The id this installation recorded is what makes a row ours — beside other runners too. */
    @Test
    void theLocalhostRowWithTheRecordedIdIsOurs() {
        assertThat(claim(java.util.Optional.of(RUNNER_ID),
                localhost(RUNNER_ID, true, true, "2026-09-30T16:00:00Z")))
                .isEqualTo(PipelinePhases.RunnerClaim.OURS);
        PipelinePhases.RunnerDecision beside = PipelinePhases.runnerDecision(
                runners(EXTERNAL, localhost(RUNNER_ID, true, false, "2026-09-30T16:00:00Z")),
                java.util.Optional.of(RUNNER_ID));
        assertThat(beside.claim()).isEqualTo(PipelinePhases.RunnerClaim.OURS);
        assertThat(beside.row().path("id").asText()).isEqualTo(RUNNER_ID);
    }

    /**
     * <b>THE LIVE ESTATE: an external runner and no recorded id.</b> A re-bootstrap there starts
     * nothing — the runner is somebody's, on another machine, and this host was never sized for
     * one.
     */
    @Test
    void aPlatformWhoseRunnersAreSomebodyElsesIsLeftAlone() {
        PipelinePhases.RunnerDecision live = PipelinePhases.runnerDecision(runners(EXTERNAL),
                java.util.Optional.empty());

        assertThat(live.claim()).isEqualTo(PipelinePhases.RunnerClaim.NOT_OURS);
        assertThat(live.row()).isNull();
        assertThat(live.reason()).contains("1 runner").contains("no localhost")
                .contains("recorded no runner of its own");
        // A stale recorded id changes nothing: no localhost row carries it.
        assertThat(claim(java.util.Optional.of(RUNNER_ID), EXTERNAL))
                .isEqualTo(PipelinePhases.RunnerClaim.NOT_OURS);
    }

    /** A localhost somebody else declared — an operator, by hand — is theirs whatever its state. */
    @Test
    void aLocalhostThisInstallationDidNotRecordIsSomebodyElses() {
        // Registered, or connected, or merely seen once: something runs as it.
        assertThat(claim(java.util.Optional.empty(), localhost(RUNNER_ID, true, false, null)))
                .isEqualTo(PipelinePhases.RunnerClaim.NOT_OURS);
        assertThat(claim(java.util.Optional.empty(), localhost(RUNNER_ID, false, true, null)))
                .isEqualTo(PipelinePhases.RunnerClaim.NOT_OURS);
        assertThat(claim(java.util.Optional.empty(),
                localhost(RUNNER_ID, false, false, "2026-09-30T16:00:00Z")))
                .isEqualTo(PipelinePhases.RunnerClaim.NOT_OURS);
        // Recorded, but another id: this installation's runner was replaced by somebody's.
        PipelinePhases.RunnerDecision replaced = PipelinePhases.runnerDecision(
                runners(localhost(RUNNER_ID, true, true, "2026-09-30T16:00:00Z")),
                java.util.Optional.of(OTHER_ID));
        assertThat(replaced.claim()).isEqualTo(PipelinePhases.RunnerClaim.NOT_OURS);
        assertThat(replaced.reason()).contains(RUNNER_ID).contains("recorded " + OTHER_ID);
        // Never used, but NOT alone: a platform with a runner is a platform somebody set up.
        assertThat(claim(java.util.Optional.empty(), EXTERNAL,
                localhost(RUNNER_ID, false, false, null)))
                .isEqualTo(PipelinePhases.RunnerClaim.NOT_OURS);
    }

    /**
     * <b>THE CRASH WINDOW.</b> A boot that died between qits-ci answering the create and the id
     * reaching the state file leaves exactly this: the only runner, never registered, never seen.
     * Nothing runs as it, so adopting it takes nobody's runner.
     */
    @Test
    void aLoneLocalhostNothingEverRanAsIsAdopted() {
        assertThat(claim(java.util.Optional.empty(), localhost(RUNNER_ID, false, false, null)))
                .isEqualTo(PipelinePhases.RunnerClaim.OURS);
        // Whatever an earlier installation recorded.
        assertThat(claim(java.util.Optional.of(OTHER_ID),
                localhost(RUNNER_ID, false, false, null)))
                .isEqualTo(PipelinePhases.RunnerClaim.OURS);
        // A listing that says nothing about lastSeenAt says it was never seen.
        assertThat(claim(java.util.Optional.empty(), "{\"id\":\"" + RUNNER_ID
                + "\",\"name\":\"localhost\",\"registered\":false,\"connected\":false}"))
                .isEqualTo(PipelinePhases.RunnerClaim.OURS);
    }

    // --- the phase --------------------------------------------------------------------------------

    /** Docker, as the runner phase asks it: which containers, images and volumes exist. */
    private static ScriptedRunner docker(List<String> containers, boolean image, boolean volume) {
        return new ScriptedRunner(command -> {
            String line = String.join(" ", command);
            if (line.startsWith("docker ps -a")) {
                return ScriptedRunner.ok(containers.toArray(new String[0]));
            }
            if (line.startsWith("docker image inspect")) {
                return image ? ScriptedRunner.ok("sha256:abc") : ScriptedRunner.failed("no image");
            }
            if (line.startsWith("docker volume inspect")) {
                return volume ? ScriptedRunner.ok("[]") : ScriptedRunner.failed("no volume");
            }
            return ScriptedRunner.ok();
        });
    }

    /**
     * The boot a runner phase runs in: a bootstrap client, so a machine write has a token, and the
     * domain every boot has.
     */
    private Boot runnerPhaseBoot(ScriptedRunner runner, CannedHttp http, Map<String, String> env)
            throws Exception {
        Map<String, String> config = new java.util.HashMap<>(DOMAIN);
        config.putAll(env);
        config.putIfAbsent("QITS_CI_CONCURRENT_BUILDS", "2");
        http.answer("FORM http://prod-qits-idp:8080/idp/token", 200,
                "{\"access_token\":\"bootstrap-bearer\",\"expires_in\":3600}");
        Boot boot = runnerBoot(runner, http, config);
        boot.state.bootstrapClientId = "prod-qits-bootstrap";
        boot.state.bootstrapSecret = "boot";
        return boot;
    }

    private void record(String id) throws Exception {
        Files.writeString(temp.resolve(".qits-bootstrap.env"), "CI_RUNNER_ID=" + id + "\n",
                StandardCharsets.UTF_8);
    }

    /** What was asked of qits-ci, leaving out the bootstrap's own token mint at the idp. */
    private static List<String> ciCalls(CannedHttp http) {
        return http.calls.stream().filter(call -> call.contains("-qits-ci:")).toList();
    }

    private static List<String> runOf(ScriptedRunner runner) {
        return runner.argv.stream().filter(command -> command.size() > 2
                && command.get(1).equals("run") && command.get(2).equals("-d")).findFirst()
                .orElse(null);
    }

    /**
     * <b>A COLD PLATFORM, whole: the container is the install line's own.</b> The row is declared
     * with this host's slot count and no plane — EDGE is qits-ci's only one — its id is recorded
     * before anything else, the volume's stale client is cleared, and the container is started
     * dialling the public address the line names. No network — docker's default bridge — and no
     * registry lists, which qits-ci hands a runner on every Ack. The one addition is the builder's
     * own state volume, which the line cannot pass and without which the runner's buildkitd
     * collides with this host's qits-buildkitd.
     */
    @Test
    void aColdPlatformGetsItsRunnerDeclaredRecordedAndStartedOnTheInstallLinesOwnContract()
            throws Exception {
        ScriptedRunner runner = docker(List.of(), true, false);
        CannedHttp http = new CannedHttp()
                .answer("GET " + RUNNERS, 200, listing())
                .answer("POST " + RUNNERS, 201, row(RUNNER_ID, "localhost", "EDGE", false,
                        false, null, "\"installScript\":"
                                + eu.wohlben.qits.cli.bootstrap.api.Json.quote(installLine(TOKEN))));
        Boot boot = runnerPhaseBoot(runner, http, Map.of());
        CiLogStreamTest.Recorder ctx = new CiLogStreamTest.Recorder();

        new PipelinePhases(boot).localhostRunner().action().run(ctx);

        assertThat(http.bodies.get("POST " + RUNNERS))
                .isEqualTo("{\"name\":\"localhost\",\"slots\":2}");
        // The machine write presents this run's own token — qits:system, audience qits-platform.
        assertThat(http.headers.get("POST " + RUNNERS))
                .containsEntry("Authorization", "Bearer bootstrap-bearer");
        assertThat(Files.readString(temp.resolve(".qits-bootstrap.env")))
                .contains("CI_RUNNER_ID=" + RUNNER_ID + "\n");

        assertThat(runOf(runner)).containsExactly(
                "docker", "run", "-d",
                "--name", RUNNER_CONTAINER,
                "--restart", "unless-stopped",
                "--label", "qits.ci.runner.process=" + RUNNER_ID,
                "--label", "qits.ci.runner.version=" + PIN,
                "-v", "/var/run/docker.sock:/var/run/docker.sock",
                "-v", "qits-ci-runner-state-7c1d2f0e:/var/lib/qits-ci-runner",
                "-e", "QITS_CI_RUNNER_URL=https://ci.qits.qits-dev.eu",
                "-e", "QITS_CI_RUNNER_ID=" + RUNNER_ID,
                "-e", "QITS_CI_RUNNER_SLOTS=2",
                "-e", "QITS_CI_RUNNER_REGISTRATION_TOKEN",
                "-e", "QITS_CI_RUNNER_BUILDKIT_STATE_VOLUME=qits-ci-runner-buildkitd-state",
                RUNNER_IMAGE_REF);
        // The stale client goes before the runner starts; the volume stays.
        assertThat(runner.lines()).containsSubsequence(
                "docker run --rm --entrypoint rm -v qits-ci-runner-state-7c1d2f0e:"
                        + "/var/lib/qits-ci-runner " + RUNNER_IMAGE_REF
                        + " -f /var/lib/qits-ci-runner/client.json",
                String.join(" ", runOf(runner)));

        // THE TOKEN: in the process's environment by name, masked, and nowhere a person reads.
        Cmd run = runner.cmds.stream().filter(cmd -> cmd.command().equals(runOf(runner)))
                .findFirst().orElseThrow();
        assertThat(run.environment()).containsEntry("QITS_CI_RUNNER_REGISTRATION_TOKEN", TOKEN);
        assertThat(run.maskText("refused " + TOKEN)).isEqualTo("refused ***");
        assertThat(runner.lines()).noneMatch(line -> line.contains(TOKEN));
        assertThat(ctx.logs).noneMatch(line -> line.contains(TOKEN));

        assertThat(boot.state.ciRunnerId).isEqualTo(RUNNER_ID);
        assertThat(boot.state.ciRunnerContainer).isEqualTo(RUNNER_CONTAINER);
    }

    /**
     * A registered runner restarted on its volume gets no install line, so its address is composed
     * as qits-ci composes it: {@code https://ci.qits.<domain>}.
     */
    @Test
    void aRegisteredRunnerIsRestartedDiallingThePublicName() throws Exception {
        record(RUNNER_ID);
        ScriptedRunner runner = docker(List.of(), true, true);
        CannedHttp http = new CannedHttp().answer("GET " + RUNNERS, 200, listing(row(RUNNER_ID,
                "localhost", "EDGE", true, false, "2026-09-30T16:00:00Z", null)));
        Boot boot = runnerPhaseBoot(runner, http, DOMAIN);

        new PipelinePhases(boot).localhostRunner().action().run(new CiLogStreamTest.Recorder());

        assertThat(runOf(runner)).contains("QITS_CI_RUNNER_URL=https://ci.qits.qits-dev.eu")
                .doesNotContain("--network", "QITS_CI_RUNNER_REGISTRATION_TOKEN");
    }

    /**
     * <b>THE HARD RULE: a listing that is not ours starts NOTHING.</b> Not a create, not a token,
     * not one docker command — and the wait that follows skips with it, without asking qits-ci
     * anything at all.
     */
    @Test
    void aPlatformWhoseRunnersAreNotOursIsNeitherDeclaredNorStartedNorWaitedFor() throws Exception {
        for (String rows : List.of(
                listing(EXTERNAL),
                listing(localhost(RUNNER_ID, true, true, "2026-09-30T16:00:00Z")),
                listing(EXTERNAL, localhost(RUNNER_ID, false, false, null)))) {
            ScriptedRunner runner = docker(List.of(RUNNER_CONTAINER + " exited"), true, true);
            CannedHttp http = new CannedHttp().answer("GET " + RUNNERS, 200, rows);
            Boot boot = runnerPhaseBoot(runner, http, Map.of());
            boot.state.ciRunnerId = "left-by-an-earlier-phase";

            assertThatThrownBy(() -> new PipelinePhases(boot).localhostRunner().action()
                    .run(new CiLogStreamTest.Recorder()))
                    .as(rows)
                    .isInstanceOf(PhaseSkipped.class);

            assertThat(http.calls).as(rows).containsExactly("GET " + RUNNERS);
            assertThat(runner.argv).as(rows).isEmpty();
            assertThat(boot.state.ciRunnerId).isNull();
            assertThat(temp.resolve(".qits-bootstrap.env")).doesNotExist();

            assertThatThrownBy(() -> new PipelinePhases(boot).localhostRunnerConnected().action()
                    .run(new CiLogStreamTest.Recorder()))
                    .isInstanceOf(PhaseSkipped.class)
                    .hasMessageContaining("started no runner");
            assertThat(http.calls).as(rows).containsExactly("GET " + RUNNERS);
        }
    }

    /** Not knowing whose runners there are is not a reason to start one. */
    @Test
    void aListingThatDoesNotAnswerStartsNothing() throws Exception {
        ScriptedRunner runner = docker(List.of(), true, false);
        CannedHttp http = new CannedHttp().answer("GET " + RUNNERS, 503, "mid cutover");
        Boot boot = runnerPhaseBoot(runner, http, Map.of());

        assertThatThrownBy(() -> new PipelinePhases(boot).localhostRunner().action()
                .run(new CiLogStreamTest.Recorder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not list its runners");
        assertThat(http.calls).containsExactly("GET " + RUNNERS);
        assertThat(runner.argv).isEmpty();
        assertThat(boot.state.ciRunnerId).isNull();
    }

    /** Our runner, stopped — a host that rebooted, a person who stopped it: it is started again. */
    @Test
    void ourRunnersStoppedContainerIsStartedAndNothingIsMinted() throws Exception {
        record(RUNNER_ID);
        ScriptedRunner runner = docker(List.of(RUNNER_CONTAINER + " exited"), true, true);
        CannedHttp http = new CannedHttp().answer("GET " + RUNNERS, 200,
                listing(EXTERNAL, localhost(RUNNER_ID, true, false, "2026-09-30T16:00:00Z")));
        Boot boot = runnerPhaseBoot(runner, http, Map.of());

        new PipelinePhases(boot).localhostRunner().action().run(new CiLogStreamTest.Recorder());

        assertThat(runner.lines()).contains("docker ps -a --filter label=qits.ci.runner.process="
                + RUNNER_ID + " --format {{.Names}} {{.State}}", "docker start " + RUNNER_CONTAINER);
        assertThat(runOf(runner)).isNull();
        assertThat(http.calls).containsExactly("GET " + RUNNERS);
        assertThat(boot.state.ciRunnerId).isEqualTo(RUNNER_ID);
        assertThat(boot.state.ciRunnerContainer).isEqualTo(RUNNER_CONTAINER);
    }

    /**
     * Our runner, running — under whatever name: a runner that rolled itself over carries the next
     * version in its name and the same label. It is left exactly as it is.
     */
    @Test
    void ourRunnersRunningContainerIsLeftAlone() throws Exception {
        record(RUNNER_ID);
        String successor = "qits-ci-runner-7c1d2f0e-2026.1001.90000";
        ScriptedRunner runner = docker(List.of(successor + " running",
                RUNNER_CONTAINER + " exited"), true, true);
        CannedHttp http = new CannedHttp().answer("GET " + RUNNERS, 200,
                listing(localhost(RUNNER_ID, true, true, "2026-09-30T16:00:00Z")));
        Boot boot = runnerPhaseBoot(runner, http, Map.of());

        new PipelinePhases(boot).localhostRunner().action().run(new CiLogStreamTest.Recorder());

        assertThat(runner.lines()).noneMatch(line -> line.startsWith("docker start")
                || line.startsWith("docker run"));
        assertThat(boot.state.ciRunnerContainer).isEqualTo(successor);
    }

    /**
     * Ours, never registered, and no container: the first token's value was answered once, to
     * whichever run asked, so a fresh one is minted and the container started with it.
     */
    @Test
    void ourUnregisteredRunnerWithNoContainerIsGivenAFreshTokenAndStarted() throws Exception {
        record(RUNNER_ID);
        ScriptedRunner runner = docker(List.of(), true, true);
        String rotate = "POST " + RUNNERS + "/" + RUNNER_ID + "/registration-token";
        CannedHttp http = new CannedHttp()
                .answer("GET " + RUNNERS, 200, listing(localhost(RUNNER_ID, false, false, null)))
                .answer(rotate, 200, row(RUNNER_ID, "localhost", "EDGE", false, false, null,
                        "\"installScript\":" + eu.wohlben.qits.cli.bootstrap.api.Json.quote(
                                installLine("qits_tok_rotated789"))));
        Boot boot = runnerPhaseBoot(runner, http, Map.of());

        new PipelinePhases(boot).localhostRunner().action().run(new CiLogStreamTest.Recorder());

        assertThat(ciCalls(http)).containsExactly("GET " + RUNNERS, rotate);
        assertThat(http.headers.get(rotate))
                .containsEntry("Authorization", "Bearer bootstrap-bearer");
        Cmd run = runner.cmds.stream().filter(cmd -> cmd.command().equals(runOf(runner)))
                .findFirst().orElseThrow();
        assertThat(run.environment())
                .containsEntry("QITS_CI_RUNNER_REGISTRATION_TOKEN", "qits_tok_rotated789");
        assertThat(runner.lines()).noneMatch(line -> line.contains("qits_tok_rotated789"));
    }

    /**
     * <b>The crash window, carried through.</b> The lone, never-used row is adopted: its id is
     * recorded now, and it goes down the same road as any unregistered runner of ours.
     */
    @Test
    void aRowALostBootDeclaredIsAdoptedRecordedAndStarted() throws Exception {
        ScriptedRunner runner = docker(List.of(), true, false);
        String rotate = "POST " + RUNNERS + "/" + RUNNER_ID + "/registration-token";
        CannedHttp http = new CannedHttp()
                .answer("GET " + RUNNERS, 200, listing(localhost(RUNNER_ID, false, false, null)))
                .answer(rotate, 200, row(RUNNER_ID, "localhost", "EDGE", false, false, null,
                        "\"installScript\":" + eu.wohlben.qits.cli.bootstrap.api.Json.quote(
                                installLine(TOKEN))));
        Boot boot = runnerPhaseBoot(runner, http, Map.of());

        new PipelinePhases(boot).localhostRunner().action().run(new CiLogStreamTest.Recorder());

        assertThat(Files.readString(temp.resolve(".qits-bootstrap.env")))
                .contains("CI_RUNNER_ID=" + RUNNER_ID);
        assertThat(ciCalls(http)).containsExactly("GET " + RUNNERS, rotate);
        assertThat(runOf(runner)).isNotNull();
    }

    /**
     * Ours, registered, and its container is gone while its volume is not: it is started WITHOUT a
     * token — the name is not on the line at all — and lives on the client the volume holds. The
     * client is NOT cleared, because it is the only credential the runner has.
     */
    @Test
    void ourRegisteredRunnerIsRestartedOnItsVolumeWithNoToken() throws Exception {
        record(RUNNER_ID);
        ScriptedRunner runner = docker(List.of(), true, true);
        CannedHttp http = new CannedHttp().answer("GET " + RUNNERS, 200,
                listing(localhost(RUNNER_ID, true, false, "2026-09-30T16:00:00Z")));
        Boot boot = runnerPhaseBoot(runner, http, Map.of());

        new PipelinePhases(boot).localhostRunner().action().run(new CiLogStreamTest.Recorder());

        assertThat(http.calls).containsExactly("GET " + RUNNERS);
        assertThat(runOf(runner)).isNotNull()
                .doesNotContain("QITS_CI_RUNNER_REGISTRATION_TOKEN")
                .contains("QITS_CI_RUNNER_ID=" + RUNNER_ID)
                .endsWith(RUNNER_IMAGE_REF);
        Cmd run = runner.cmds.stream().filter(cmd -> cmd.command().equals(runOf(runner)))
                .findFirst().orElseThrow();
        assertThat(run.environment()).isEmpty();
        assertThat(runner.lines()).noneMatch(line -> line.contains("client.json"));
    }

    /**
     * Ours, registered, and neither a container nor its volume: the credential is gone for good.
     * Nothing a rerun does can mend that, so the boot stops and names the row.
     */
    @Test
    void ourRegisteredRunnerWithNoVolumeStopsTheBootNamingTheRow() throws Exception {
        record(RUNNER_ID);
        ScriptedRunner runner = docker(List.of(), true, false);
        CannedHttp http = new CannedHttp().answer("GET " + RUNNERS, 200,
                listing(localhost(RUNNER_ID, true, false, "2026-09-30T16:00:00Z")));
        Boot boot = runnerPhaseBoot(runner, http, Map.of());

        assertThatThrownBy(() -> new PipelinePhases(boot).localhostRunner().action()
                .run(new CiLogStreamTest.Recorder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(RUNNER_ID)
                .hasMessageContaining("qits-ci-runner-state-7c1d2f0e")
                .hasMessageContaining("delete the row");
        assertThat(runOf(runner)).isNull();
        assertThat(boot.state.ciRunnerId).isNull();
    }

    /**
     * A rotation answered 409 is a runner that registered between the listing and the rotation:
     * the registered arm, not a failure.
     */
    @Test
    void aRunnerThatRegisteredMeanwhileIsStartedWithNoToken() throws Exception {
        record(RUNNER_ID);
        ScriptedRunner runner = docker(List.of(), true, true);
        CannedHttp http = new CannedHttp()
                .answer("GET " + RUNNERS, 200, listing(localhost(RUNNER_ID, false, false, null)))
                .answer("POST " + RUNNERS + "/" + RUNNER_ID + "/registration-token", 409, "{}");
        Boot boot = runnerPhaseBoot(runner, http, Map.of());

        new PipelinePhases(boot).localhostRunner().action().run(new CiLogStreamTest.Recorder());

        assertThat(runOf(runner)).isNotNull()
                .doesNotContain("QITS_CI_RUNNER_REGISTRATION_TOKEN");
    }

    /** With the machine gate off a runner registers and can never connect: said, not waited out. */
    @Test
    void withTheMachineGateOffTheRunnerPhaseStopsTheBoot() throws Exception {
        ScriptedRunner runner = docker(List.of(), true, false);
        CannedHttp http = new CannedHttp().answer("GET " + RUNNERS, 200, listing());
        Boot boot = runnerPhaseBoot(runner, http, Map.of("QITS_MACHINE_AUTH", "0"));

        assertThatThrownBy(() -> new PipelinePhases(boot).localhostRunner().action()
                .run(new CiLogStreamTest.Recorder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QITS_MACHINE_AUTH=0");
        assertThat(http.calls).containsExactly("GET " + RUNNERS);
        assertThat(runner.argv).isEmpty();
    }

    /**
     * No image, no runner — and nothing is declared for a container that cannot be started: the
     * row would otherwise be left for the next run to find.
     */
    @Test
    void aMissingRunnerImageStopsTheBootBeforeAnythingIsDeclared() throws Exception {
        ScriptedRunner runner = docker(List.of(), false, false);
        CannedHttp http = new CannedHttp().answer("GET " + RUNNERS, 200, listing());
        Boot boot = runnerPhaseBoot(runner, http, Map.of());

        assertThatThrownBy(() -> new PipelinePhases(boot).localhostRunner().action()
                .run(new CiLogStreamTest.Recorder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("qits/qits-ci-runner:" + PIN)
                .hasMessageContaining("rerun without QITS_SKIP_BUILD");
        assertThat(http.calls).containsExactly("GET " + RUNNERS);
        assertThat(runOf(runner)).isNull();
    }

    /**
     * A qits-ci that refuses the create stops the boot in its own words — a 503 is a ci whose oidc
     * client is off — and a create that answered no token stops it without printing the answer.
     */
    @Test
    void aRefusedOrTokenlessCreateStopsTheBoot() throws Exception {
        ScriptedRunner runner = docker(List.of(), true, false);
        CannedHttp refused = new CannedHttp().answer("GET " + RUNNERS, 200, listing())
                .answer("POST " + RUNNERS, 503, "This qits-ci commissions no credentials");
        Boot boot = runnerPhaseBoot(runner, refused, Map.of());

        assertThatThrownBy(() -> new PipelinePhases(boot).localhostRunner().action()
                .run(new CiLogStreamTest.Recorder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("503").hasMessageContaining("commissions no credentials");
        assertThat(runOf(runner)).isNull();

        CannedHttp tokenless = new CannedHttp().answer("GET " + RUNNERS, 200, listing())
                .answer("POST " + RUNNERS, 201, row(RUNNER_ID, "localhost", "EDGE", false,
                        false, null, "\"installScript\":\"curl | sh # secret-looking-body\""));
        Boot second = runnerPhaseBoot(runner, tokenless, Map.of());

        assertThatThrownBy(() -> new PipelinePhases(second).localhostRunner().action()
                .run(new CiLogStreamTest.Recorder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no registration token")
                .hasMessageNotContaining("secret-looking-body");
        assertThat(runOf(runner)).isNull();
    }

    // --- connected, and in service ----------------------------------------------------------------

    private static final String QUARANTINED =
            "\"quarantined\":true,\"quarantineReason\":\"awaiting its first health check\"";

    /**
     * <b>Connected, then greenlit, then proven in service — in that order.</b> A freshly registered
     * runner is quarantined awaiting a health check qits-ci cannot queue yet, so the bootstrap
     * lifts it through the operator's door: the forwarded admin identity, because no machine role
     * opens it.
     */
    @Test
    void aConnectedQuarantinedRunnerIsGreenlitAndThenProvenInService() throws Exception {
        List<String> listings = new ArrayList<>(List.of(
                listing(row(RUNNER_ID, "localhost", "EDGE", true, true,
                        "2026-09-30T16:00:00Z", QUARANTINED)),
                listing(row(RUNNER_ID, "localhost", "EDGE", true, true,
                        "2026-09-30T16:00:00Z", "\"quarantined\":false"))));
        String greenlight = "POST " + RUNNERS + "/" + RUNNER_ID + "/greenlight";
        CannedHttp http = new CannedHttp()
                .answer("GET " + RUNNERS, () -> new Http.Response(200, listings.size() > 1
                        ? listings.removeFirst() : listings.getFirst()))
                .answer(greenlight, 200, "{}");
        Boot boot = runnerPhaseBoot(docker(List.of(), true, true), http, Map.of());
        boot.state.ciRunnerId = RUNNER_ID;

        new PipelinePhases(boot).localhostRunnerConnected().action()
                .run(new CiLogStreamTest.Recorder());

        assertThat(http.calls).containsExactly("GET " + RUNNERS, greenlight, "GET " + RUNNERS);
        assertThat(http.headers.get(greenlight))
                .containsEntry("X-Qits-Roles", "qits:admin")
                .doesNotContainKey("Authorization");
    }

    /** A runner already in service is not greenlit again: its failure streak is qits-ci's. */
    @Test
    void aRunnerAlreadyInServiceIsNotGreenlitAgain() throws Exception {
        CannedHttp http = new CannedHttp().answer("GET " + RUNNERS, 200,
                listing(row(RUNNER_ID, "localhost", "EDGE", true, true,
                        "2026-09-30T16:00:00Z", "\"quarantined\":false")));
        Boot boot = runnerPhaseBoot(docker(List.of(), true, true), http, Map.of());
        boot.state.ciRunnerId = RUNNER_ID;

        new PipelinePhases(boot).localhostRunnerConnected().action()
                .run(new CiLogStreamTest.Recorder());

        assertThat(http.calls).containsExactly("GET " + RUNNERS);
    }

    /** A greenlight that did not take stops the boot, with qits-ci's own reason. */
    @Test
    void aRunnerStillQuarantinedAfterTheGreenlightStopsTheBoot() throws Exception {
        CannedHttp http = new CannedHttp()
                .answer("GET " + RUNNERS, 200, listing(row(RUNNER_ID, "localhost", "EDGE",
                        true, true, "2026-09-30T16:00:00Z", QUARANTINED)))
                .answer("POST " + RUNNERS + "/" + RUNNER_ID + "/greenlight", 200, "{}");
        Boot boot = runnerPhaseBoot(docker(List.of(), true, true), http, Map.of());
        boot.state.ciRunnerId = RUNNER_ID;

        assertThatThrownBy(() -> new PipelinePhases(boot).localhostRunnerConnected().action()
                .run(new CiLogStreamTest.Recorder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still quarantined")
                .hasMessageContaining("awaiting its first health check");

        CannedHttp refused = new CannedHttp()
                .answer("GET " + RUNNERS, 200, listing(row(RUNNER_ID, "localhost", "EDGE",
                        true, true, "2026-09-30T16:00:00Z", QUARANTINED)))
                .answer("POST " + RUNNERS + "/" + RUNNER_ID + "/greenlight", 403, "admin only");
        Boot second = runnerPhaseBoot(docker(List.of(), true, true), refused, Map.of());
        second.state.ciRunnerId = RUNNER_ID;

        assertThatThrownBy(() -> new PipelinePhases(second).localhostRunnerConnected().action()
                .run(new CiLogStreamTest.Recorder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("403");
    }

    /**
     * A runner that never connects fails the phase with what a person needs: what qits-ci says of
     * the row, and the container's own last words.
     */
    @Test
    void aRunnerThatNeverConnectsFailsWithItsLogAndItsRow() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command -> command.contains("logs")
                ? ScriptedRunner.ok("ci-runner registration refused: 401")
                : ScriptedRunner.ok());
        CannedHttp http = new CannedHttp().answer("GET " + RUNNERS, 200,
                listing(localhost(RUNNER_ID, false, false, null)));
        Boot boot = runnerPhaseBoot(runner, http, Map.of("QITS_HEALTH_TIMEOUT", "0"));
        boot.state.ciRunnerId = RUNNER_ID;
        boot.state.ciRunnerContainer = RUNNER_CONTAINER;

        assertThatThrownBy(() -> new PipelinePhases(boot).localhostRunnerConnected().action()
                .run(new CiLogStreamTest.Recorder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not registered yet")
                .hasMessageContaining("registered=false, connected=false")
                .hasMessageContaining("docker logs --tail 20 " + RUNNER_CONTAINER)
                .hasMessageContaining("ci-runner registration refused: 401");
        assertThat(runner.lines()).contains("docker logs --tail 20 " + RUNNER_CONTAINER);
        assertThat(http.calls).noneMatch(call -> call.contains("greenlight"));
    }

    /** The wait ends on OUR runner being connected, and on nothing weaker. */
    @Test
    void theConnectionWaitEndsOnOurRunnerConnectedAndOnlyThen() {
        assertThat(PipelinePhases.runnerConnection(new Http.Response(200,
                listing(localhost(RUNNER_ID, true, true, "2026-09-30T16:00:00Z"))), RUNNER_ID)
                .value().path("id").asText()).isEqualTo(RUNNER_ID);

        Waiter.Poll<com.fasterxml.jackson.databind.JsonNode> registered =
                PipelinePhases.runnerConnection(new Http.Response(200,
                        listing(localhost(RUNNER_ID, true, false, null))), RUNNER_ID);
        assertThat(registered.value()).isNull();
        assertThat(registered.observed()).isEqualTo("registered, not connected");

        assertThat(PipelinePhases.runnerConnection(new Http.Response(200,
                listing(localhost(RUNNER_ID, false, false, null))), RUNNER_ID).observed())
                .isEqualTo("not registered yet");
        assertThat(PipelinePhases.runnerConnection(new Http.Response(200, listing(EXTERNAL)),
                RUNNER_ID).observed()).isEqualTo("qits-ci lists no runner localhost");
        // A connected localhost of another id is somebody else's runner, not the one waited for.
        Waiter.Poll<com.fasterxml.jackson.databind.JsonNode> other =
                PipelinePhases.runnerConnection(new Http.Response(200,
                        listing(localhost(OTHER_ID, true, true, "2026-09-30T16:00:00Z"))),
                        RUNNER_ID);
        assertThat(other.value()).isNull();
        assertThat(other.observed()).contains(OTHER_ID);
        assertThat(PipelinePhases.runnerConnection(new Http.Response(503, "down"), RUNNER_ID)
                .value()).isNull();
    }
}
