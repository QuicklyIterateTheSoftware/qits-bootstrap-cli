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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The plan is built before anything runs, which is what makes "9 of 47" a fact. */
class BootstrapPlanTest {

    @TempDir
    Path temp;

    /**
     * Every plan is a domain boot: a run without one is refused before the plan is built, so the
     * domain and its address are the fixture's, under whatever the test adds.
     */
    private List<Phase> plan(Map<String, String> env) {
        Map<String, String> withDomain = new java.util.HashMap<>(Map.of(
                "QITS_DOMAIN", "qits-dev.eu", "QITS_PUBLIC_IP", "203.0.113.7"));
        withDomain.putAll(env);
        return planExactly(withDomain);
    }

    private List<Phase> planExactly(Map<String, String> env) {
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
        // THE HOST RULE IS THIRD, before anything can dial the edge by name: every *.localhost
        // name resolves to ::1 and the swarm ingress mesh serves IPv4 only, so a client that meets
        // the landmine hangs rather than failing over.
        assertThat(ids(phases)).startsWith("preflight", "network", "ipv6-loopback", "host-oom",
                "bootstrap-ingress-prepare", "bootstrap-ingress", "wrapper", "sources",
                "recorded-state", "seed-golden-masters", "seed-pacts", "maven-seed");
        assertThat(ids(phases)).containsSubsequence(
                "git-repos", "release-train-push", "preseed");
        assertThat(ids(phases)).endsWith("deploy-deployments", "summary",
                "teardown-bootstrap-builder");
        assertThat(phases).allSatisfy(phase -> assertThat(phase.title()).isNotBlank());
    }

    /**
     * <b>Each protocol jar lands immediately before the image that compiles against it, and
     * nowhere on a warm rerun.</b> qits-ci pins the daemon's contract and qits-system the target
     * runner's, both as released coordinates, so neither image build resolves until its jar is
     * in the file repository; a run that builds no image seeds neither. The CI runner's contract
     * is gone with the CI runner (qits-1547).
     */
    @Test
    void theProtocolJarsAreSeededImmediatelyBeforeTheImagesThatCompileAgainstThem() {
        List<String> ids = ids(plan(Map.of()));

        int ci = ids.indexOf("seed-image-ci");
        assertThat(ids.get(ci - 1)).isEqualTo("seed-qits-ci-daemon-protocol");
        int system = ids.indexOf("seed-image-system");
        assertThat(ids.get(system - 1)).isEqualTo("seed-qits-system-runner-protocol");
        assertThat(ids).containsSubsequence("maven-seed", "seed-qits-ci-daemon-protocol");
        assertThat(ids).doesNotContain("seed-qits-ci-runner-protocol",
                "publish-qits-ci-daemon-protocol");

        assertThat(ids(plan(Map.of("QITS_SKIP_BUILD", "1")))).doesNotContain(
                "seed-qits-ci-daemon-protocol", "seed-qits-system-runner-protocol");
    }

    /**
     * <b>The golden masters and the runner library land before every build that resolves them</b>
     * (qits-1547): after maven-seed, whose containers-driver the runner build reads, before
     * qits-system's protocol seed and seed image, and long before the runner's native build.
     * Neither runs on a warm rerun, which builds nothing.
     */
    @Test
    void theRunnerLibraryIsSeededBeforeQitsSystemAndItsRunnerAreBuilt() {
        List<String> ids = ids(plan(Map.of()));

        assertThat(ids).containsSubsequence("seed-golden-masters", "maven-seed",
                "seed-qits-runner", "seed-qits-system-runner-protocol", "seed-image-system",
                "ci-daemon", "system-runner-image");
        assertThat(ids).containsSubsequence("seed-golden-masters", "seed-image-ci");
        assertThat(ids(plan(Map.of("QITS_SKIP_BUILD", "1"))))
                .doesNotContain("seed-golden-masters", "seed-qits-runner");
    }

    /**
     * <b>The contract jars are in the file repository before ANYTHING is built</b> (qits-889):
     * the edge, the first image, pins fourteen pacts and qits-idp's golden masters, and maven-seed
     * builds two contracts kits that compile against golden masters. Neither phase needs a server,
     * so both precede everything of the platform; the store gets them from store-seed.
     */
    @Test
    void theContractJarsAreInTheFileRepositoryBeforeTheFirstBuild() {
        List<String> ids = ids(plan(Map.of()));

        assertThat(ids).containsSubsequence("sources", "seed-golden-masters", "seed-pacts",
                "maven-seed", "seed-image-edge", "seed-image-mirror");
        assertThat(ids).doesNotContain("publish-golden-masters", "publish-pacts");
        assertThat(ids(plan(Map.of("QITS_SKIP_BUILD", "1"))))
                .doesNotContain("seed-pacts", "seed-golden-masters");
    }

    /**
     * <b>The @qits npm packages are built before the first image that carries a client</b>
     * (qits-889): the mirror is the first, and every one of the nine builds its client against the
     * qits-npm-seed tarballs in its own seed-image phase. A warm rerun builds no client and so no
     * package.
     */
    @Test
    void theNpmPackagesAreBuiltBeforeTheFirstSeedClient() {
        List<String> ids = ids(plan(Map.of()));

        assertThat(ids).containsSubsequence("sources", "maven-seed", "seed-npm-packages",
                "seed-image-edge", "seed-image-mirror");
        for (String client : eu.wohlben.qits.cli.bootstrap.platform.PlatformModel.seedClients()) {
            assertThat(ids.indexOf("seed-npm-packages"))
                    .isLessThan(ids.indexOf("seed-image-" + client));
        }
        assertThat(ids(plan(Map.of("QITS_SKIP_BUILD", "1")))).doesNotContain("seed-npm-packages");
    }

    /**
     * <b>Every compile-scoped coordinate a seed image takes from another repository's release lands
     * before that image</b> (qits-889): qits-ci's pin of the {@code qits} binary beside its daemon
     * contract, and qits-projects' two daemon contracts — the workspace daemon's at qits-system's
     * pin too, in the same phase, because qits-projects is built first.
     */
    @Test
    void theDaemonContractsQitsProjectsCompilesAgainstLandBeforeItsImage() {
        List<String> ids = ids(plan(Map.of()));

        assertThat(ids).containsSubsequence("maven-seed", "seed-qits-platform-access-cli-binary",
                "seed-qits-ci-daemon-protocol", "seed-image-ci");
        int projects = ids.indexOf("seed-image-projects");
        assertThat(ids.subList(projects - 2, projects)).containsExactly(
                "seed-qits-projects-daemon-protocol", "seed-qits-workspace-daemon-protocol");
        assertThat(ids).containsSubsequence("seed-qits-workspace-daemon-protocol",
                "seed-image-projects", "seed-image-system");
        assertThat(ids(plan(Map.of("QITS_SKIP_BUILD", "1")))).doesNotContain(
                "seed-qits-projects-daemon-protocol", "seed-qits-workspace-daemon-protocol",
                "seed-qits-platform-access-cli-binary");
    }

    /**
     * <b>NO PHASE BEFORE seed-idp TALKS TO A STORE OR A MIRROR</b> (qits-889). Nothing in phase 1
     * publishes, and no store or mirror is started by hand: the seed builds resolve from the
     * qits-maven-seed file repository and upstream. The two fixed-commit npm publishes are gone
     * with it — no lockfile at any pin resolves {@code 0.0.x} of either package.
     */
    @Test
    void noPhaseBeforeTheIdpPublishesOrStartsAStore() {
        for (Map<String, String> env : List.of(Map.<String, String>of(),
                Map.of("QITS_SKIP_BUILD", "1"))) {
            List<String> ids = ids(plan(env));
            List<String> beforeIdp = ids.subList(0, ids.indexOf("seed-idp"));

            assertThat(beforeIdp).noneMatch(id -> id.startsWith("publish-"));
            assertThat(beforeIdp).doesNotContain("seed-artifacts", "seed-mirror", "store-seed",
                    "images-publish", "seed-images-publish", "daemon-publish");
            assertThat(ids).doesNotContain("seed-artifacts", "seed-mirror", "publish-ui-components",
                    "publish-angular", "publish-golden-masters");
            assertThat(ids).noneMatch(id -> id.matches("publish-qits-(userflows|coding-agents|"
                    + "auth-core|registries-oci|eventstream|githost-events|containers-driver|"
                    + "containers-client|runner)"));
        }
    }

    /**
     * <b>store-seed fills the store once, after the idp and the stack, and is the first publish
     * of the boot</b>: after register-token — the seed stack is healthy and the idp answers, so the
     * publishing credential can be commissioned — and before images-publish, inside the publish
     * half that publish-credential-release closes. In both arms: a warm rerun uploads what the
     * volume still holds that the store lacks.
     */
    @Test
    void theStoreIsSeededAfterTheIdpAndBeforeTheImagesArePublished() {
        for (Map<String, String> env : List.of(Map.<String, String>of(),
                Map.of("QITS_SKIP_BUILD", "1"))) {
            List<String> ids = ids(plan(env));

            assertThat(ids).containsSubsequence("seed-idp", "seed-stack", "seed-health",
                    "register-token", "store-seed", "images-publish", "seed-images-publish",
                    "daemon-publish", "publish-credential-release");
            assertThat(ids.get(ids.indexOf("images-publish") - 1)).isEqualTo("store-seed");
        }
    }

    /**
     * The target's runner image is built straight after the ci-daemon — it is compiled on the
     * toolchain image that phase makes — and before the seed stack, so it exists by the time it
     * is started. The ci-slot image every run executes in is a step image built before both.
     */
    @Test
    void theRunnerImageIsBuiltOnTheDaemonsToolchainBeforeTheStack() {
        List<String> ids = ids(plan(Map.of()));

        assertThat(ids.get(ids.indexOf("ci-daemon") + 1)).isEqualTo("system-runner-image");
        assertThat(ids).containsSubsequence("step-image-ci-slot", "seed-toolchain", "ci-daemon",
                "system-runner-image", "seed-stack");
        assertThat(ids).doesNotContain("runner-image");
        assertThat(ids(plan(Map.of("QITS_SKIP_BUILD", "1"))))
                .doesNotContain("system-runner-image", "step-image-ci-slot");
    }

    /**
     * <b>The images go into the store after the seed stack is healthy and before the publishing
     * credential is handed back — in both arms.</b> The push goes through the edge, it presents
     * that credential, and qits-ci refuses a run whose step image the store cannot pin.
     */
    @Test
    void theImagesArePushedBetweenTheStackAndTheCredentialsReturn() {
        for (Map<String, String> env : List.of(Map.<String, String>of(),
                Map.of("QITS_SKIP_BUILD", "1"))) {
            List<String> ids = ids(plan(env));

            assertThat(ids).containsSubsequence("seed-stack", "seed-health", "register-token",
                    "images-publish", "seed-images-publish", "daemon-publish",
                    "publish-credential-release", "qits-project");
            assertThat(ids.subList(ids.indexOf("images-publish"),
                    ids.indexOf("images-publish") + 3)).containsExactly(
                    "images-publish", "seed-images-publish", "daemon-publish");
        }
    }

    /**
     * <b>THE TARGET IS STARTED AND CONNECTED BEFORE THE FIRST RUN IS ASKED FOR — in both arms.</b>
     * Every CI run is a lease on a qits-system target (qits-1547), so a release replay with no
     * target queues until its wait gives up. After the seed deploys, qits-system's among them,
     * because the target registers with the qits-system the platform runs in steady state; and it
     * is a container, never a deployment, so nothing in the plan deploys one — or takes it down
     * again. The CI runner's pair and the workspaces runner's are both gone.
     */
    @Test
    void theTargetIsStartedAndConnectedBeforeTheFirstRun() {
        for (Map<String, String> env : List.of(Map.<String, String>of(),
                Map.of("QITS_SKIP_BUILD", "1"))) {
            List<String> ids = ids(plan(env));

            assertThat(ids).containsSubsequence("seed-health", "images-publish",
                    "publish-credential-release", "qits-project", "git-repos",
                    "seed-deploy-system", "seed-deploy-edge", "edge-ready", "target-localhost",
                    "target-connected", "release-train-push");
            // The platform's own edge is proved to serve what the target dials, right before it
            // dials it.
            int target = ids.indexOf("target-localhost");
            assertThat(ids.subList(target - 2, target + 3)).containsExactly(
                    "seed-deploy-edge", "edge-ready", "target-localhost", "target-connected",
                    "release-train-push");
            assertThat(ids.stream().filter(id -> id.startsWith("target-"))).hasSize(2);
            assertThat(ids).doesNotContain("runner-localhost", "runner-connected",
                    "workspaces-runner-localhost", "workspaces-runner-connected");
            assertThat(ids).noneMatch(id -> id.contains("decommission"));
        }
    }

    /**
     * <b>THE OWNER'S FOUR STEPS, in both arms.</b> Everything the platform needs is built locally
     * first; then the platform comes up as it runs in steady state — the seed images put live by
     * the deployer at their release tag, the edge last of them, which retires the bootstrap
     * ingress; then this host's target; and only then the first build, the release replays and
     * the train.
     * <ul>
     *   <li>The project, the repositories and the platform environment are all before the first
     *       seed deploy: the spec is read name-addressed at the tag, and the manual door deploys
     *       nothing without a designated environment.
     *   <li>The seed images are in the store before the publishing credential goes back — and so
     *       before the first deploy that pulls one.
     *   <li>The deployer is not seed-deployed: its self-update stays last of the train.
     * </ul>
     */
    @Test
    void theSeedImagesAreDeployedAfterTheRepositoriesAndBeforeTheTargetAndTheFirstBuild() {
        for (Map<String, String> env : List.of(Map.<String, String>of(),
                Map.of("QITS_SKIP_BUILD", "1"))) {
            List<String> ids = ids(plan(env));

            List<String> seedDeploys = ids.stream().filter(id -> id.startsWith("seed-deploy-"))
                    .toList();
            assertThat(seedDeploys).containsExactly("seed-deploy-idp",
                    "seed-deploy-configuration", "seed-deploy-projects",
                    "seed-deploy-workspaces", "seed-deploy-events", "seed-deploy-mirror", "seed-deploy-artifacts",
                    "seed-deploy-githost", "seed-deploy-containers", "seed-deploy-ci",
                    "seed-deploy-system", "seed-deploy-edge");
            // Contiguous: nothing builds, publishes or pushes in the middle of step two.
            int first = ids.indexOf("seed-deploy-idp");
            assertThat(ids.subList(first, first + seedDeploys.size())).isEqualTo(seedDeploys);
            assertThat(ids).containsSubsequence("seed-images-publish",
                    "publish-credential-release", "qits-project", "git-repos", "environment",
                    "seed-deploy-idp");
            assertThat(ids.get(first - 1)).isEqualTo("environment");
            assertThat(ids).containsSubsequence("seed-deploy-edge", "edge-ready", "target-localhost",
                    "target-connected", "release-train-push", "preseed",
                    "release-spa-ui-components", "deploy-observability", "deploy-idp",
                    "deploy-edge", "deploy-deployments", "summary");
            assertThat(ids).doesNotContain("seed-deploy-deployments",
                    "seed-deploy-oci-postgresql");
            // The environment is reconciled once, before the first deployment of either kind.
            assertThat(ids.stream().filter("environment"::equals)).hasSize(1);
        }
    }

    @Test
    void theSeedOrderIsTheOneTheDependenciesForce() {
        List<String> ids = ids(plan(Map.of()));

        // maven-seed fills the file repository every seed image resolves from, so every image is
        // after it — in the order the libraries' consumers force, not one a store would.
        assertThat(ids).containsSubsequence("maven-seed", "seed-image-edge", "seed-image-mirror",
                "seed-image-artifacts", "seed-image-githost", "seed-image-oci-postgresql",
                "seed-image-events", "seed-postgres", "seed-qits-runner",
                "seed-qits-platform-access-cli-binary", "seed-qits-ci-daemon-protocol",
                "seed-image-ci", "seed-image-deployments", "seed-image-idp",
                "seed-image-containers", "seed-qits-projects-daemon-protocol",
                "seed-qits-workspace-daemon-protocol", "seed-image-projects",
                "seed-qits-system-runner-protocol", "seed-image-system", "step-image-ci-base",
                "step-image-ci-slot", "seed-toolchain", "ci-daemon", "system-runner-image");
        // The ci image consumes qits-githost-events, which maven-seed builds — and qits-projects
        // consumes it too, which the deploy train reaches long before the git host's own
        // deployment; the store holds it by then, from store-seed.
        assertThat(ids).containsSubsequence("maven-seed", "seed-image-ci", "store-seed",
                "deploy-projects", "deploy-githost");
        // The edge needs nothing from the platform — no client bundle, no qits dependency — so its
        // image is built in the first half and needs no service image before it.
        assertThat(ids).containsSubsequence("seed-image-edge", "seed-image-artifacts");
        // The daemon digest is written into the compose file and the deployer's extras, so it is
        // measured before either is generated.
        // AND THE IDENTITY ORDER INSIDE IT. The seed services' clients are created against a
        // running idp, so the idp comes up alone between the bootstrap's own pair and the five it
        // makes — and compose-file is BELOW all three, because the secrets it renders do not exist
        // until the idp has issued them.
        assertThat(ids).containsSubsequence("ci-daemon", "idp-bootstrap-client", "seed-idp",
                "idp-clients", "compose-file", "pd-extras", "seed-stack", "seed-health",
                "register-token");
        // The phase that wrote a secret per client into the generated files is gone outright.
        assertThat(ids).doesNotContain("idp-secrets");
        // postgres before every file that addresses it: the deployer refuses to boot without the
        // database, and seed-stack is what starts the deployer.
        // postgres before seed-idp too, and for two reasons at once: the idp refuses to boot
        // without its database, and idp-clients records every issued secret in the deployer's
        // registry on that same server.
        assertThat(ids).containsSubsequence("seed-image-oci-postgresql", "seed-postgres",
                "idp-bootstrap-client", "seed-idp", "idp-clients", "compose-file", "pd-extras",
                "seed-stack");
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
                "deploy-idp", "deploy-configuration",
                "deploy-stt", "deploy-projects", "deploy-workspaces",
                "deploy-events",
                "deploy-mirror", "deploy-artifacts", "deploy-githost",
                // docs after artifacts: its declared serviceAddress key names that service, and
                // qits-configuration renders it only once the target has deployed.
                "deploy-docs",
                // The orchestrator immediately before ci: ci runs every step as a container it asks
                // that service for, so a ci cutover inside the orchestrator's window is a pipeline
                // with nowhere to run.
                "deploy-containers", "deploy-ci",
                // The three late ones, after every service they call and before the door: the
                // technical processes, the dependency inventory, then the base system panels —
                // which call no peer at all and go last because their cutover drops every open
                // terminal.
                "deploy-orchestrator", "deploy-maintenance",
                "deploy-system",
                "deploy-edge", "deploy-deployments");
        assertThat(ids).doesNotContain("deploy-cd", "deploy-serviceregistry");
        // postgres is the seed database, never a train phase: re-reading its spec from qits-githost
        // (whose storage is postgres) is a circular dependency that crash-loops it.
        assertThat(ids).doesNotContain("deploy-oci-postgresql");
        // Pre-rename spellings deploy nothing and would push to repositories nobody reads.
        assertThat(ids).doesNotContain("deploy-platform-idp", "deploy-platform-deployments",
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
        assertThat(ids).containsSubsequence("deploy-idp", "deploy-configuration");
        // Everything after the flip is deployed from what the service serves. The train is long
        // enough that this is a real proof rather than one deployment's.
        assertThat(ids).containsSubsequence("configuration-flip", "deploy-ci",
                "deploy-edge", "deploy-deployments");
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
                "deploy-ci", "deploy-edge", "deploy-deployments");
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
    void theReleasesTheWrapperBuildsInstallAreReplayedBeforeTheTrainDeploysAnything() {
        List<String> ids = ids(plan(Map.of()));

        assertThat(ids).containsSubsequence("preseed",
                "release-spa-ui-components",
                "release-integrations-angular", "release-integrations-quarkus",
                "release-eventstream", "deploy-observability");
    }

    /**
     * The image publishers, and the one order among them that is not a preference: the workspace
     * base's replay is what puts qits/workspace-base in the registry, and both daemon builds PULL
     * it at a pinned version. Replaying a daemon first builds against a tag nothing has pushed.
     * <p>
     * The editor is one link further down the same chain — its Dockerfile is
     * {@code FROM ${WORKSPACE_IMAGE}} with a committed qits/workspace pin — so the daemon that
     * publishes that image has to precede it for exactly the same reason.
     */
    @Test
    void theWorkspaceBaseIsReplayedBeforeEverythingThatLayersOnIt() {
        List<String> ids = ids(plan(Map.of()));

        assertThat(ids).containsSubsequence("release-oci-workspace", "release-workspace-daemon");
        assertThat(ids).containsSubsequence("release-oci-workspace", "release-projects-daemon");
        assertThat(ids).containsSubsequence("release-workspace-daemon",
                "release-oci-workspace-editor");
        // Still before the deployables: qits-workspaces and qits-projects pin these images, and a
        // pin with nothing behind it fails at the first workspace launch rather than at deploy.
        assertThat(ids).containsSubsequence("release-projects-daemon", "deploy-workspaces");
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
     * <b>The publishing identity goes back immediately after the last publish.</b> Only CI may
     * publish to qits-artifacts (user ruling 2026-09-13); the bootstrap holds the one exception and
     * holds it for minutes. The daemon binary is the last thing this program publishes, so the
     * hand-back sits directly behind it — everything below is a push, a deployment or a read. On
     * both paths, because {@code --skip-build} still publishes the daemon.
     */
    @Test
    void thePublishingCredentialIsHandedBackTheMomentTheLastPublishIsDone() {
        assertThat(ids(plan(Map.of())))
                .containsSubsequence("daemon-publish", "publish-credential-release", "git-repos");
        assertThat(ids(plan(Map.of("QITS_SKIP_BUILD", "1"))))
                .containsSubsequence("daemon-publish", "publish-credential-release");
        // Directly behind it: a phase in between would be one more phase that can fail and leave a
        // publishing credential standing.
        List<String> ids = ids(plan(Map.of()));
        assertThat(ids.get(ids.indexOf("daemon-publish") + 1))
                .isEqualTo("publish-credential-release");
    }

    /**
     * The phase the domain brings, and where it has to sit.
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
    void theDomainAddsThePlaceholderAndTheRunningEdgeOwnsIssuance() {
        List<String> ids = ids(plan(Map.of()));

        assertThat(ids).containsSubsequence("pd-extras", "edge-cert", "seed-stack", "seed-health");
        assertThat(ids).doesNotContain("dns-zone", "edge-acme");
    }

    /**
     * <b>There is no plan without a domain.</b> The command refuses such a run before it builds
     * one; a caller that got ahead of the refusal is told the refusal's own words rather than
     * handed a plan with the domain's phases quietly missing.
     */
    @Test
    void thereIsNoPlanWithoutADomain() {
        assertThatThrownBy(() -> planExactly(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QITS_DOMAIN");
        // The nameserver is retired outright: no seed image and no deployment.
        assertThat(ids(plan(Map.of())))
                .doesNotContain("seed-image-platform-dns", "deploy-platform-dns");
    }

    @Test
    void skipBuildDropsTheSeedBuildsAndSaysSo() {
        List<String> cold = ids(plan(Map.of()));
        List<String> warm = ids(plan(Map.of("QITS_SKIP_BUILD", "1")));

        assertThat(warm).contains("seed-skipped")
                .doesNotContain("ci-daemon", "seed-image-ci", "seed-image-edge",
                        "seed-image-oci-postgresql", "seed-image-events",
                        "seed-image-containers", "seed-qits-runner", "seed-toolchain",
                        "maven-seed");
        assertThat(warm.size()).isLessThan(cold.size());
        // The pipeline half is untouched: a warm rerun still pushes and still waits.
        assertThat(warm).contains("deploy-deployments", "release-train-push", "summary");
        // postgres is NOT a build, so a warm rerun still runs it: it resolves the passwords both
        // generated files carry, and the server has to answer before the deployer starts.
        assertThat(warm).contains("seed-postgres");
        // And the identity order is in BOTH arms: a warm rerun's seed containers need the same
        // five clients, and a rerun is exactly where the recorded-row and the 409/rotate arms
        // earn their keep.
        assertThat(warm).containsSubsequence("seed-postgres", "idp-bootstrap-client", "seed-idp",
                "idp-clients", "compose-file", "seed-stack");
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

    /**
     * <b>qits-configuration and qits-workspaces are built in phase 1 like every seed service</b>
     * (qits-889): after the workspace daemon's contract, which qits-workspaces compiles against at
     * its own pin, and before the identity order. A warm rerun builds neither.
     */
    @Test
    void configurationAndWorkspacesAreSeedImages() {
        List<String> ids = ids(plan(Map.of()));

        assertThat(ids).containsSubsequence("seed-npm-packages",
                "seed-qits-workspace-daemon-protocol", "seed-image-projects", "seed-image-system",
                "seed-image-configuration", "seed-image-workspaces", "idp-bootstrap-client");
        assertThat(ids(plan(Map.of("QITS_SKIP_BUILD", "1"))))
                .doesNotContain("seed-image-configuration", "seed-image-workspaces");
    }

    /**
     * <b>The images the platform starts workspaces and agents from are built in phase 1, each
     * producer before its consumer</b> (qits-889): the qits binary after the toolchain it compiles
     * on, the browser base before the workspace bases, those before the workspace and agent
     * images — and all of it before the idp, so nothing is published before there is a credential
     * to publish with. images-publish pushes them after the idp.
     */
    @Test
    void theWorkspaceImagesAreBuiltProducersFirstBeforeTheIdp() {
        List<String> ids = ids(plan(Map.of()));

        assertThat(ids).containsSubsequence("seed-toolchain", "ci-daemon", "system-runner-image",
                "seed-qits-cli", "seed-node-browser-base", "seed-workspace-base", "seed-workspace",
                "seed-project-agent", "idp-bootstrap-client", "seed-idp", "store-seed",
                "images-publish", "daemon-publish", "publish-credential-release");
        assertThat(ids(plan(Map.of("QITS_SKIP_BUILD", "1")))).doesNotContain("seed-qits-cli",
                "seed-node-browser-base", "seed-workspace-base", "seed-workspace",
                "seed-project-agent");
    }

    /**
     * <b>Every repository the model clones is built in phase 1, deferred to phase 4, or not built
     * at all, by name and with a reason</b> (qits-889) — a frontend of a deferred or not-built
     * service with it, since its bundle ships in that service's image. A repository added to the
     * model without any of the three is a gap the summary would not state.
     */
    @Test
    void everyRepositoryIsBuiltInPhaseOneOrDeferredOrNotBuilt() {
        java.util.SortedMap<String, String> built = SeedPhases.phaseOneBuilds();
        Map<String, String> deferred =
                eu.wohlben.qits.cli.bootstrap.platform.PlatformModel.DEFERRED_TO_PHASE_4;
        Map<String, String> notBuilt =
                eu.wohlben.qits.cli.bootstrap.platform.PlatformModel.NOT_BUILT;

        for (String name : eu.wohlben.qits.cli.bootstrap.platform.PlatformModel.sourceRepos()) {
            boolean covered = built.containsKey(name) || deferred.containsKey(name)
                    || notBuilt.containsKey(name)
                    || (name.startsWith("spa-")
                            && (deferred.containsKey(name.substring(4))
                                    || notBuilt.containsKey(name.substring(4))));
            assertThat(covered)
                    .as("%s is neither built in phase 1, deferred to phase 4, nor not built", name)
                    .isTrue();
        }
        // The brief's six, each with its one-line reason.
        assertThat(deferred).containsKeys("stt", "docs", "observability", "maintenance",
                "orchestrator", "landing-app");
        assertThat(deferred.values()).allSatisfy(reason -> assertThat(reason).isNotBlank());
        // The three retired or unused entries, each with its one-line reason, and exactly these.
        assertThat(notBuilt).containsOnlyKeys("oci-workspace-editor", "workspaces-runner-daemon",
                "spa-workspaces");
        assertThat(notBuilt.values()).allSatisfy(reason -> assertThat(reason).isNotBlank());
        // Nothing is both: a deferred repository phase 1 also built would be a stale entry —
        // except landing-app, whose PACTS phase 1 packs while the app itself is deferred.
        assertThat(deferred.keySet().stream().filter(built::containsKey))
                .containsExactly("landing-app");
        // Nothing not-built is also built in phase 1: that would be a stale entry too.
        assertThat(notBuilt.keySet().stream().filter(built::containsKey)).isEmpty();
        // configuration and workspaces are among the built, with their client and their images.
        assertThat(built).containsKeys("configuration", "workspaces", "spa-configuration",
                "oci-workspace", "workspace-daemon", "projects-daemon", "platform-access-cli");
    }
}
