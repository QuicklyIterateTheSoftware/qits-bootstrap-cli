package eu.wohlben.qits.cli.bootstrap.phases;

import eu.wohlben.qits.cli.bootstrap.config.BootstrapConfig;
import eu.wohlben.qits.cli.bootstrap.config.TestConfig;
import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.proc.RunLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The plan is built before anything runs, which is what makes "9 of 47" a fact. */
class BootstrapPlanTest {

    @TempDir
    Path temp;

    private List<Phase> plan(Map<String, String> env) {
        BootstrapConfig config = TestConfig.from(env);
        return BootstrapPlan.build(new Boot(config, new RunLog(temp.resolve("run.log"))));
    }

    private static List<String> ids(List<Phase> phases) {
        return phases.stream().map(Phase::id).toList();
    }

    @Test
    void aColdBootHasEveryPhaseAndEachOneOnlyOnce() {
        List<Phase> phases = plan(Map.of());

        assertThat(ids(phases)).doesNotHaveDuplicates();
        // The network is joined second, right after the daemon is proved reachable and before the
        // first address is dialled: every address this CLI uses is a wire alias on qits-net.
        // The wrapper comes before the sources because the sources are read out of it, and a cold
        // machine has none: that phase clones it from the org.
        assertThat(ids(phases)).startsWith("preflight", "network", "bootstrap-ingress-prepare",
                "bootstrap-ingress", "wrapper", "sources", "recorded-state", "maven-seed");
        assertThat(ids(phases)).containsSubsequence(
                "git-repos", "release-train-push", "preseed");
        assertThat(ids(phases)).endsWith("deploy-deployments", "summary",
                "teardown-bootstrap-builder");
        assertThat(phases).allSatisfy(phase -> assertThat(phase.title()).isNotBlank());
    }

    @Test
    void theSeedOrderIsTheOneTheDependenciesForce() {
        List<String> ids = ids(plan(Map.of()));

        // artifacts is built and started before anything can be published into it, and every maven
        // publish lands before the ci image that consumes them is built.
        assertThat(ids).containsSubsequence("seed-image-artifacts", "seed-artifacts",
                // The integrations FIRST: qits-blobstore is built against qits-db-core since its
                // DbRetry release, and qits-eventstream since 2026-08-11 — one of their three
                // modules either way.
                "publish-qits-auth-core",
                "publish-qits-blobstore", "publish-qits-registries-oci",
                "publish-qits-eventstream",
                // The git host's vocabulary follows its own dependency: it is built against
                // qits-eventstream and against nothing else of this platform.
                "publish-qits-githost-events",
                // The orchestrator's two libraries, last of the publishes: they are built against
                // qits-db-core, qits-arch-rules, qits-auth-core and qits-eventstream, so everything
                // they resolve is already in the store.
                "publish-qits-containers-client", "publish-ui-components",
                "publish-angular", "seed-image-ci", "seed-image-deployments",
                "seed-image-platform-idp", "seed-image-containers", "seed-image-projects",
                "ci-daemon");
        // BEFORE the ci image, because ci pins qits-containers-client and a step-container image
        // build resolves from the platform's own Maven registry — there is no host ~/.m2 in this
        // run to fall back on.
        assertThat(ids).containsSubsequence("publish-qits-containers-client", "seed-image-ci");
        // The ci image consumes qits-githost-events, so the publish is before it — and qits-projects
        // consumes it too, which the deploy train reaches long before the git host's own deployment.
        assertThat(ids).containsSubsequence("publish-qits-githost-events", "seed-image-ci",
                "deploy-projects", "deploy-githost");
        // THE BYTE PLANE'S THREE IMAGES ARE BUILT TOGETHER, and all three before the store is
        // started: each is built out of qits-blobstore and qits-registries, which the maven seed
        // put in the temporary registry before the first image. There is nothing they could wait
        // for — the real store does not exist until one of them is running.
        assertThat(ids).containsSubsequence("maven-seed", "seed-image-platform-mirror",
                "seed-image-artifacts", "seed-image-githost", "seed-artifacts");
        // THE MIRROR IS STARTED BEFORE THE STORE, because every publish after it resolves its
        // third-party half through the mirror's caches — and postgres before the mirror, which
        // refuses to boot without its database.
        assertThat(ids).containsSubsequence("seed-image-platform-mirror", "seed-postgres",
                "seed-mirror", "seed-artifacts", "publish-qits-blobstore");
        // The edge needs nothing from the platform — no client bundle, no qits dependency — so its
        // image is built in the first half and needs no service image before it.
        assertThat(ids).containsSubsequence("seed-image-platform-edge", "seed-image-artifacts");
        // The bus is in the first half too: qits-events declares no qits Maven dependency, so it
        // waits on none of the publishes below it.
        assertThat(ids).containsSubsequence("seed-image-events", "seed-artifacts");
        // The daemon digest is written into the compose file and the deployer's extras, so it is
        // measured before either is generated.
        assertThat(ids).containsSubsequence("ci-daemon", "idp-secrets", "compose-file",
                "pd-extras", "seed-stack", "seed-health",
                "register-token");
        // postgres before every file that addresses it: the deployer refuses to boot without the
        // database, and seed-stack is what starts the deployer.
        assertThat(ids).containsSubsequence("seed-image-oci-postgresql", "seed-postgres",
                "idp-secrets", "compose-file", "pd-extras", "seed-stack");
        // The git host is in the seed stack rather than started by hand: nothing needs it before
        // compose brings it up, and git-repos — the first phase that PUTs against it — is after
        // the health wait.
        assertThat(ids).containsSubsequence("seed-image-githost", "seed-stack", "seed-health",
                "git-repos");
        // The retired pair is built by nothing: one component replaced both.
        assertThat(ids).doesNotContain("seed-image-cd", "seed-image-serviceregistry");
    }

    @Test
    void everyDeployableIsItsOwnPhaseInTheOrderTheTrainRunsIn() {
        List<String> ids = ids(plan(Map.of()));

        assertThat(ids).containsSubsequence("environment", "deploy-observability",
                "deploy-platform-idp", "deploy-configuration",
                "deploy-stt", "deploy-projects", "deploy-workspaces",
                "deploy-events", "deploy-docs",
                "deploy-platform-mirror", "deploy-artifacts", "deploy-githost",
                // The orchestrator immediately before ci: ci runs every step as a container it asks
                // that service for, so a ci cutover inside the orchestrator's window is a pipeline
                // with nowhere to run.
                "deploy-containers", "deploy-ci",
                // The two callers, after every service they call and before the door: the
                // technical processes, then the dependency inventory.
                "deploy-platform-orchestrator", "deploy-platform-maintenance",
                "deploy-platform-edge", "deploy-deployments");
        assertThat(ids).doesNotContain("deploy-cd", "deploy-serviceregistry");
        // postgres is the seed database, never a train phase: re-reading its spec from qits-githost
        // (whose storage is postgres) is a circular dependency that crash-loops it.
        assertThat(ids).doesNotContain("deploy-oci-postgresql");
        // Pre-rename spellings deploy nothing and would push to repositories nobody reads.
        assertThat(ids).doesNotContain("deploy-idp", "deploy-platform-deployments",
                "deploy-platform-artifacts", "deploy-platform-docs");
    }

    /**
     * <b>The one ordering in this plan where BOTH directions can lose a boot.</b>
     * <p>
     * qits-configuration holds deployment configuration as platform state, and the deployer treats
     * it as AUTHORITATIVE the moment it is given the url: an unreachable or unpopulated service
     * refuses the deployment rather than shipping a stale value. So:
     * <ul>
     *   <li><b>The flip must be after the deployment and after the import.</b> Flipped earlier, the
     *       deployer refuses every deployment left in the train — qits-configuration's own included,
     *       which is a boot that cannot recover by carrying on.
     *   <li><b>The flip must be before the deployer deploys itself.</b> The successor inherits the
     *       url from its own extras, so a deployer that starts holding it over a service nobody
     *       imported into refuses everything after it.
     * </ul>
     */
    @Test
    void theConfigurationServiceIsDeployedThenImportedThenFlippedAndOnlyThen() {
        List<String> ids = ids(plan(Map.of()));

        assertThat(ids).containsSubsequence("deploy-configuration", "configuration-import",
                "configuration-flip", "deploy-deployments");
        // Its own deployment is made by a deployer that is still reading the file — the flip is two
        // phases later, and nothing between them deploys anything.
        assertThat(ids.indexOf("configuration-flip"))
                .isGreaterThan(ids.indexOf("deploy-configuration"));
        // The issuer it validates against is already cut over, and its store is the seed postgres,
        // up before the train starts.
        assertThat(ids).containsSubsequence("deploy-platform-idp", "deploy-configuration");
        // Everything after the flip is deployed from what the service serves. The train is long
        // enough that this is a real proof rather than one deployment's.
        assertThat(ids).containsSubsequence("configuration-flip", "deploy-ci",
                "deploy-platform-edge", "deploy-deployments");
    }

    /**
     * <b>WHERE THE PUBLIC CLONE URL COMES INTO EXISTENCE, and it is before the first push rather
     * than forty phases into the deploy train.</b> qits-projects is a seed service, so the project
     * is read and every (storage id, name) pair is registered while the bares are being made — and
     * every push of the run is then name-addressed.
     * <ul>
     *   <li><b>{@code qits-project} before {@code git-repos}</b>: a repository is registered under
     *       a project, so the project has to be there before the first one is created.
     *   <li><b>Both before every push</b>, which is what puts {@code projectId} and {@code repoName}
     *       on each push's event.
     *   <li><b>Both long before qits-githost's own deployment</b>, whose extras close the
     *       id-addressed scheme to qits-projects' client alone. The lifecycle PUTs are the only
     *       thing this run addresses that way, and they are all above that cutover.
     * </ul>
     */
    @Test
    void theRepositoriesAreRegisteredBeforeTheyArePushedTo() {
        List<String> ids = ids(plan(Map.of()));

        assertThat(ids).containsSubsequence("qits-project", "git-repos", "release-train-push",
                "preseed");
        // And the whole train that follows pushes through the address it created.
        assertThat(ids).containsSubsequence("git-repos", "deploy-projects", "deploy-githost",
                "deploy-ci", "deploy-platform-edge", "deploy-deployments");
        // The phase that used to hang off qits-projects' deployment is gone with the wait it was:
        // the alias table answers from the seed on.
        assertThat(ids).doesNotContain("register-repos");
    }

    /** A warm rerun registers too: the alias table is asserted on every boot, never assumed. */
    @Test
    void aWarmRerunStillRegistersTheRepositories() {
        assertThat(ids(plan(Map.of("QITS_SKIP_BUILD", "1"))))
                .containsSubsequence("qits-project", "git-repos", "deploy-projects");
    }

    /** A warm rerun flips too: the seed stack deploy resets the env the last run added live. */
    @Test
    void aWarmRerunStillImportsAndFlips() {
        assertThat(ids(plan(Map.of("QITS_SKIP_BUILD", "1"))))
                .containsSubsequence("deploy-configuration", "configuration-import",
                        "configuration-flip");
    }

    @Test
    void theReleasesTheWrapperBuildsInstallAreReplayedBeforeAnythingIsDeployed() {
        List<String> ids = ids(plan(Map.of()));

        assertThat(ids).containsSubsequence("preseed",
                "release-spa-ui-components",
                "release-integrations-angular", "release-integrations-quarkus",
                "release-eventstream", "environment", "deploy-observability");
    }

    /**
     * The image publishers, and the one order among them that is not a preference: the workspace
     * base's replay is what puts qits/workspace-base in the registry, and both daemon builds PULL
     * it at a pinned version. Replaying a daemon first builds against a tag nothing has pushed.
     */
    @Test
    void theWorkspaceBaseIsReplayedBeforeEverythingThatLayersOnIt() {
        List<String> ids = ids(plan(Map.of()));

        assertThat(ids).containsSubsequence("release-oci-workspace", "release-workspace-daemon");
        assertThat(ids).containsSubsequence("release-oci-workspace", "release-projects-daemon");
        // Still before the deployables: qits-workspaces and qits-projects pin these images, and a
        // pin with nothing behind it fails at the first workspace launch rather than at deploy.
        assertThat(ids).containsSubsequence("release-projects-daemon", "environment",
                "deploy-workspaces");
    }

    /**
     * The register token is minted at the first point the idp answers, and on every path. The row
     * it writes is in postgres, so it outlives the redeploys that follow — there is nothing to be
     * gained by waiting for them, and a warm rerun deserves the same first account as a cold boot.
     */
    @Test
    void theRegisterTokenIsMintedAsSoonAsTheIdpAnswers() {
        assertThat(ids(plan(Map.of()))).containsSubsequence("seed-health", "register-token",
                "git-repos");
        assertThat(ids(plan(Map.of("QITS_SKIP_BUILD", "1")))).contains("register-token");
    }

    /**
     * The two phases a domain adds, and where each of them has to sit.
     * <p>
     * The PLACEHOLDER certificate goes before the seed stack, because the edge is started there with
     * a keystore and a keystore whose files are missing fails startup. The ACME order goes after the
     * health wait: the CA fetches the HTTP-01 challenge over the public name, so the edge has to be
     * holding port 80 before there is anything to order against.
     * <p>
     * There is no zone phase between them any more. This platform serves no dns — the records are
     * held at the domain's own provider, before the run.
     */
    @Test
    void aDomainAddsThePlaceholderAndTheRunningEdgeOwnsIssuance() {
        List<String> ids = ids(plan(Map.of("QITS_DOMAIN", "qits-dev.eu",
                "QITS_PUBLIC_IP", "203.0.113.7")));

        assertThat(ids).containsSubsequence("pd-extras", "edge-cert", "seed-stack", "seed-health");
        assertThat(ids).doesNotContain("dns-zone", "edge-acme");
    }

    /** No domain is the default, and then neither phase exists — nothing to skip at runtime. */
    @Test
    void withNoDomainNeitherIsInThePlan() {
        assertThat(ids(plan(Map.of()))).doesNotContain("edge-cert", "dns-zone", "edge-acme");
        // The nameserver is retired outright: no seed image and no deployment, domain or not.
        assertThat(ids(plan(Map.of())))
                .doesNotContain("seed-image-platform-dns", "deploy-platform-dns");
    }

    @Test
    void skipBuildDropsTheSeedBuildsAndSaysSo() {
        List<String> cold = ids(plan(Map.of()));
        List<String> warm = ids(plan(Map.of("QITS_SKIP_BUILD", "1")));

        assertThat(warm).contains("seed-skipped")
                .doesNotContain("ci-daemon", "seed-image-ci", "seed-image-platform-edge",
                        "seed-image-oci-postgresql", "seed-image-events",
                        "seed-image-containers", "publish-qits-containers-client", "maven-seed",
                        // The mirror is started by hand only on the build path; a warm rerun's
                        // compose file starts it like every other seed service.
                        "seed-mirror", "seed-artifacts");
        assertThat(warm.size()).isLessThan(cold.size());
        // The pipeline half is untouched: a warm rerun still pushes and still waits.
        assertThat(warm).contains("deploy-deployments", "release-train-push", "summary");
        // postgres is NOT a build, so a warm rerun still runs it: it resolves the passwords both
        // generated files carry, and the server has to answer before the deployer starts.
        assertThat(warm).contains("seed-postgres");
        // The wrapper phase is in every plan. An existing checkout is skipped when it RUNS, which
        // is what keeps the skip visible on the header line instead of silent in the plan.
        assertThat(warm).contains("wrapper");
    }

    /**
     * <b>The reclaim is the run's last act, and it is in every plan.</b> The builder is
     * bootstrap-time only — after this run every build goes through qits-containers to the host's
     * default builder — so what it leaves is a container and a multi-gigabyte state volume that
     * nothing will ask for again.
     * <p>
     * It is last for two reasons: nothing above it may build after it, and the summary phase only
     * BUILDS the closing account (BootstrapCommand prints it once the engine has finished), so a
     * phase below it still reads before the text a person is left with.
     */
    @Test
    void theBuilderIsTornDownLastAndInEveryPlan() {
        assertThat(ids(plan(Map.of())).getLast()).isEqualTo("teardown-bootstrap-builder");
        // A warm rerun built nothing, and it still carries the phase: a machine that reran the
        // boot is exactly the one holding last run's builder.
        assertThat(ids(plan(Map.of("QITS_SKIP_BUILD", "1"))))
                .contains("teardown-bootstrap-builder");
        // KEPT IN THE PLAN when the flag says keep, and skipped where every other conditional
        // absence in this program is: at RUN time, so the reason lands on the header line rather
        // than the phase vanishing without a word. Same rule as the wrapper phase above.
        assertThat(ids(plan(Map.of("QITS_KEEP_BUILDER", "1"))))
                .contains("teardown-bootstrap-builder");
        assertThat(plan(Map.of("QITS_KEEP_BUILDER", "1")))
                .hasSameSizeAs(plan(Map.of()));
    }
}
