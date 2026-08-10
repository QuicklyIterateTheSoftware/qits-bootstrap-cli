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
        assertThat(ids(phases)).startsWith("preflight", "network", "wrapper", "sources",
                "recorded-state", "auth-core-seed");
        assertThat(ids(phases)).endsWith("release-train-push", "summary");
        assertThat(phases).allSatisfy(phase -> assertThat(phase.title()).isNotBlank());
    }

    @Test
    void theSeedOrderIsTheOneTheDependenciesForce() {
        List<String> ids = ids(plan(Map.of()));

        // artifacts is built and started before anything can be published into it, and both maven
        // publishes land before the ci image that consumes them is built.
        assertThat(ids).containsSubsequence("seed-image-platform-artifacts", "seed-artifacts",
                "publish-qits-eventstream", "publish-qits-auth-core", "publish-ui-components",
                "publish-angular", "seed-image-ci", "seed-image-deployments",
                "seed-image-platform-idp", "ci-daemon");
        // The edge needs nothing from the platform — no client bundle, no qits dependency — so its
        // image is built in the first half, beside the gateway it fronts. The nameserver is there
        // for the same reason: a clone of that repository builds green on its own.
        assertThat(ids).containsSubsequence("seed-image-gateway", "seed-image-platform-edge",
                "seed-image-platform-artifacts");
        assertThat(ids).containsSubsequence("seed-image-platform-dns", "seed-artifacts");
        // The bus is in the first half too: qits-events declares no qits Maven dependency, so it
        // waits on none of the publishes below it.
        assertThat(ids).containsSubsequence("seed-image-events", "seed-artifacts");
        // The daemon digest is written into the compose file and the deployer's run-args, so it is
        // measured before either is generated.
        assertThat(ids).containsSubsequence("ci-daemon", "idp-secrets", "compose-file",
                "pd-run-args", "seed-stack", "seed-health");
        // postgres before every file that addresses it: the deployer refuses to boot without the
        // database, and seed-stack is what starts the deployer.
        assertThat(ids).containsSubsequence("seed-image-oci-postgresql", "seed-postgres",
                "idp-secrets", "compose-file", "pd-run-args", "seed-stack");
        // The retired pair is built by nothing: one component replaced both.
        assertThat(ids).doesNotContain("seed-image-cd", "seed-image-serviceregistry");
    }

    @Test
    void everyDeployableIsItsOwnPhaseInTheOrderTheTrainRunsIn() {
        List<String> ids = ids(plan(Map.of()));

        assertThat(ids).containsSubsequence("environment", "deploy-observability",
                "deploy-oci-postgresql",
                "deploy-platform-idp", "deploy-stt", "deploy-projects", "deploy-workspaces",
                "deploy-events", "deploy-platform-docs", "deploy-platform-dns", "deploy-gateway",
                "deploy-platform-artifacts", "deploy-ci", "deploy-platform-edge",
                "deploy-deployments");
        assertThat(ids).doesNotContain("deploy-cd", "deploy-serviceregistry");
        // Pre-rename spellings deploy nothing and would push to repositories nobody reads.
        assertThat(ids).doesNotContain("deploy-artifacts", "deploy-idp",
                "deploy-platform-deployments");
    }

    @Test
    void theReleasesTheWrapperBuildsInstallAreReplayedBeforeAnythingIsDeployed() {
        List<String> ids = ids(plan(Map.of()));

        assertThat(ids).containsSubsequence("preseed", "release-spa-ui-components",
                "release-integrations-angular", "release-eventstream",
                "release-integrations-quarkus", "environment", "deploy-observability");
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
     * The two phases a domain adds, and where they have to sit. The certificate goes BEFORE the seed
     * stack, because the edge is started there with a keystore and a keystore whose files are missing
     * fails startup. The zone goes AFTER the health wait, because it is written over the nameserver's
     * own API.
     */
    @Test
    void aDomainAddsTheCertificateBeforeTheStackAndTheZoneAfterIt() {
        List<String> ids = ids(plan(Map.of("QITS_DOMAIN", "qits-dev.eu")));

        assertThat(ids).containsSubsequence("pd-run-args", "edge-cert", "seed-stack", "seed-health",
                "dns-zone");
    }

    /** No domain is the default, and then neither phase exists at all — nothing to skip at runtime. */
    @Test
    void withNoDomainNeitherTheCertificateNorTheZoneIsInThePlan() {
        assertThat(ids(plan(Map.of()))).doesNotContain("edge-cert", "dns-zone");
        // The nameserver itself is unconditional: it is seeded, deployed and polled either way.
        assertThat(ids(plan(Map.of()))).contains("seed-image-platform-dns", "deploy-platform-dns");
    }

    @Test
    void skipBuildDropsTheSeedBuildsAndSaysSo() {
        List<String> cold = ids(plan(Map.of()));
        List<String> warm = ids(plan(Map.of("QITS_SKIP_BUILD", "1")));

        assertThat(warm).contains("seed-skipped")
                .doesNotContain("ci-daemon", "seed-image-ci", "seed-image-platform-edge",
                        "seed-image-oci-postgresql", "seed-image-events", "auth-core-seed");
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
}
