package eu.wohlben.qits.cli.bootstrap.phases;

import eu.wohlben.qits.cli.bootstrap.config.Acme;
import eu.wohlben.qits.cli.bootstrap.config.TestConfig;
import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseContext;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseSkipped;
import eu.wohlben.qits.cli.bootstrap.platform.ComposeTemplate;
import eu.wohlben.qits.cli.bootstrap.platform.Docker;
import eu.wohlben.qits.cli.bootstrap.platform.PlatformModel;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessResult;
import eu.wohlben.qits.cli.bootstrap.proc.RunLog;
import eu.wohlben.qits.cli.bootstrap.proc.ScriptedRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void theDeployerIsRecognisedUnderItsPlatformNames() {
        PipelinePhases.SeedPlan plan = PipelinePhases.seedPlan(
                List.of("qits-pd-qits-deployments-a1b2c3d4"),
                List.of("qits_qits-deployments"), ENV);

        assertThat(plan.managed()).contains("qits-deployments");
        assertThat(plan.deploy()).doesNotContain("qits-deployments");
        assertThat(plan.stale()).containsExactly("qits_qits-deployments");
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
     * <b>The records are stated as a step to CHECK, because this platform serves no dns.</b> They
     * are held at whatever provider holds the domain, and every one of them carries the address
     * this run was given.
     */
    @Test
    void theRecordsAreListedWithTheAddressTheyAllCarry() {
        String report = domainReport(Acme.Mode.STAGING, "staging");

        assertThat(report).contains("@").contains("*").contains("*.*");
        assertThat(report).contains("203.0.113.7");
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

    @TempDir
    Path temp;

    /** The extras exactly as a run renders them, with one client secret so the pair is readable. */
    private String renderedExtras() {
        Boot boot = new Boot(TestConfig.from(Map.of()), new RunLog(temp.resolve("run.log")));
        boot.state.secrets.put(PlatformModel.wireAlias("deployments", ENV), "s3cr3t");
        return ComposeTemplate.extras(new SeedPhases(boot).tokens());
    }

    /**
     * <b>The flip's values are READ BACK out of the rendered extras, never spelled again.</b> The
     * running deployer is patched live and its successor is configured from the same file, so a
     * second spelling here would be a platform whose two deployers read different services.
     */
    @Test
    void theFlipTakesItsValuesFromTheRenderedExtras() {
        List<String> env = PipelinePhases.flipEnv(renderedExtras(), "qits-deployments");

        assertThat(env).containsExactlyInAnyOrder(
                "QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL=http://prod-qits-configuration:8080",
                "QUARKUS_OIDC_CLIENT_CONFIGURATION_CLIENT_ENABLED=true",
                "QUARKUS_OIDC_CLIENT_CONFIGURATION_AUTH_SERVER_URL="
                        + "http://qits-platform-idp:8080/idp",
                // The deployer's own client id, which lost the tier when it moved plane, while
                // qits-configuration it reads is still one tier's — so the pair is asymmetric.
                "QUARKUS_OIDC_CLIENT_CONFIGURATION_CLIENT_ID=qits-deployments",
                "QUARKUS_OIDC_CLIENT_CONFIGURATION_CREDENTIALS_SECRET=s3cr3t",
                "QUARKUS_OIDC_CLIENT_CONFIGURATION_GRANT_OPTIONS_CLIENT_AUDIENCE="
                        + "prod-qits-configuration");
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

    /**
     * <b>The builder goes, and buildx is what removes its state volume.</b> That volume is the
     * whole reason for this phase — 13.7 GB on wohlben.eu — and {@code buildx rm} is the only
     * command that knows the name buildx gave it.
     */
    @Test
    void theBuilderAndTheSeedOnlyVolumesAreReclaimed() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command -> {
            if (String.join(" ", command).contains("volume ls")) {
                return ScriptedRunner.ok("qits-maven-seed", "qits-maven-cache",
                        "somebody-elses-volume");
            }
            return ScriptedRunner.ok();
        });
        Ctx ctx = new Ctx();

        teardown(runner, Map.of(), ctx);

        assertThat(runner.lines()).contains(
                "docker buildx rm " + Docker.BUILDER,
                "docker volume ls -q -f dangling=true",
                "docker volume rm qits-maven-seed",
                "docker volume rm qits-maven-cache");
        // A volume this program did not create is not this program's to remove, dangling or not.
        assertThat(runner.lines()).noneMatch(line -> line.contains("somebody-elses-volume"));
        assertThat(ctx.note).contains(Docker.BUILDER).contains("qits-maven-cache");
        assertThat(ctx.warnings).isEmpty();
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
     * reason lands on the header line. A rerun without the warm cache rebuilds every seed image
     * cold, which is ten to twenty minutes the dev loop pays on every iteration.
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
     * A run that built nothing, or a second run of this phase, has no builder to remove — and
     * buildx says so on the error stream rather than with a status of its own. That is not a
     * warning; the boot is not worse off for a machine that was already clean.
     */
    @Test
    void aMissingBuilderIsNotAFailure() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command -> {
            String line = String.join(" ", command);
            if (line.contains("buildx rm")) {
                return ScriptedRunner.failed("ERROR: no builder \"" + Docker.BUILDER + "\" found");
            }
            return ScriptedRunner.ok();
        });
        Ctx ctx = new Ctx();

        teardown(runner, Map.of(), ctx);

        assertThat(ctx.warnings).isEmpty();
        assertThat(ctx.note).isEqualTo("nothing to reclaim");
    }

    /** A removal that failed for any other reason is a warning: that volume is still on the host. */
    @Test
    void aBuilderThatWillNotGoIsReported() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command ->
                String.join(" ", command).contains("buildx rm")
                        ? ScriptedRunner.failed("Cannot connect to the Docker daemon")
                        : ScriptedRunner.ok());
        Ctx ctx = new Ctx();

        teardown(runner, Map.of(), ctx);

        assertThat(ctx.warnings).hasSize(1);
        assertThat(ctx.warnings.getFirst()).contains("docker buildx rm " + Docker.BUILDER);
    }

    /**
     * <b>Every earlier name of the builder, and NOT the node rows underneath them.</b> A
     * docker-container builder names its node after itself with an index appended, so
     * {@code qits-bootstrap-builder-v40} in this table is the NODE of {@code -v4} — asking buildx
     * to remove it as a builder is a command that can only fail. The indent is what separates the
     * two, and the current builder marks itself with a trailing star.
     */
    @Test
    void everyStrandedBuilderOfAnEarlierNameIsFoundAndTheNodesAreNot() {
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

        assertThat(Docker.staleBuilders(table, Docker.BUILDER))
                .containsExactly(
                    "qits-bootstrap-builder", "qits-bootstrap-builder-v3", "qits-bootstrap-builder-v4");
    }

    /**
     * <b>And the same table with the indent gone.</b> This class's own {@code lines} helper trims
     * every row of every other docker command, so a reader that took that path would see each node
     * at column zero and ask buildx to remove {@code qits-bootstrap-builder-v30} as a builder. The
     * {@code \_} token is what still says node, and it is why the check is doubled.
     */
    @Test
    void aNodeRowIsStillANodeRowWhenSomethingTrimmedIt() {
        List<String> trimmed = List.of(
                "qits-bootstrap-builder-v3     docker-container",
                "\\_ qits-bootstrap-builder-v30  \\_ unix:///var/run/docker.sock  running",
                "qits-bootstrap-builder-v4     docker-container",
                "\\_ qits-bootstrap-builder-v40  \\_ unix:///var/run/docker.sock  running");

        assertThat(Docker.staleBuilders(trimmed, Docker.BUILDER))
                .containsExactly("qits-bootstrap-builder-v3", "qits-bootstrap-builder-v4");
    }

    /** Somebody else's builder on a shared host is never ours, whatever it is called. */
    @Test
    void aBuilderOutsideThePrefixIsNeverSwept() {
        assertThat(Docker.staleBuilders(List.of("someone-elses-builder  docker-container",
                "default*  docker"), Docker.BUILDER)).isEmpty();
        // And the one in use is not stale, star or no star.
        assertThat(Docker.staleBuilders(List.of(Docker.BUILDER + "*  docker-container"),
                Docker.BUILDER)).isEmpty();
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
        boot.state.repositoryIds.put("qits-ci", "8b1f0f0e-9a0c-4c3a-9a5b-000000000001");

        // Before the pair is registered there is nothing to resolve a name through, so the storage
        // scheme is the only address the git host can answer.
        assertThat(boot.gitUrl("ci")).isEqualTo(
                "http://dev-qits-githost:8080/git/8b1f0f0e-9a0c-4c3a-9a5b-000000000001");

        boot.state.projectId = "1f0a-project";
        boot.state.repositoriesRegistered = true;

        assertThat(boot.gitUrl("ci"))
                .isEqualTo("http://dev-qits-githost:8080/git/1f0a-project/qits-ci");
    }

    /** A project id with no registration behind it changes nothing: both halves have to be true. */
    @Test
    void aProjectIdAloneDoesNotMoveThePushes(@TempDir Path temp) {
        Boot boot = boot(Map.of("QITS_ENV_NAME", "dev"), temp);
        boot.state.repositoryIds.put("qits-ci", "8b1f0f0e-9a0c-4c3a-9a5b-000000000001");
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
        boot.state.repositoryIds.put("qits-ci", "8b1f0f0e-9a0c-4c3a-9a5b-000000000001");

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

        assertThat(minted).matches("[0-9a-f-]{36}").isNotEqualTo("qits-events");
        assertThat(boot.storageId("events")).isEqualTo(minted);
        assertThat(boot.state.repositoryIds).containsEntry("qits-events", minted);
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
        Boot boot = new Boot(TestConfig.from(Map.of("QITS_ENV_NAME", "dev", "QITS_PORT", "8080")),
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
        Boot boot = new Boot(TestConfig.from(Map.of("QITS_ENV_NAME", "dev", "QITS_PORT", "8080")),
                new RunLog(temp.resolve("run.log")), runner);
        boot.state.wrapperDir = temp;
        Ctx ctx = new Ctx();

        new PipelinePhases(boot).summary().action().run(ctx);

        assertThat(ctx.lines).anyMatch(line -> line.contains("/git/<projectId>/<repo>,"));
        assertThat(ctx.lines).anyMatch(line -> line.contains("/git/qits/<repo>.git"));
    }
}
