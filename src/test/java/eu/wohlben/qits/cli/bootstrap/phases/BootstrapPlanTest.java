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
        assertThat(ids(phases)).startsWith("preflight", "sources", "recorded-state", "auth-core-seed");
        assertThat(ids(phases)).endsWith("release-train-push", "summary");
        assertThat(phases).allSatisfy(phase -> assertThat(phase.title()).isNotBlank());
    }

    @Test
    void theSeedOrderIsTheOneTheDependenciesForce() {
        List<String> ids = ids(plan(Map.of()));

        // artifacts is built and started before anything can be published into it, and both maven
        // publishes land before the ci image that consumes them is built.
        assertThat(ids).containsSubsequence("seed-image-artifacts", "seed-artifacts",
                "publish-qits-eventstream", "publish-qits-auth-core", "publish-ui-components",
                "publish-angular", "seed-image-ci", "seed-image-platform-deployments",
                "seed-image-idp", "ci-daemon");
        // The daemon digest is written into the compose file and the deployer's run-args, so it is
        // measured before either is generated.
        assertThat(ids).containsSubsequence("ci-daemon", "idp-secrets", "compose-file",
                "pd-run-args", "seed-stack", "seed-health");
        // The retired pair is built by nothing: one component replaced both.
        assertThat(ids).doesNotContain("seed-image-cd", "seed-image-serviceregistry");
    }

    @Test
    void everyDeployableIsItsOwnPhaseInTheOrderTheTrainRunsIn() {
        List<String> ids = ids(plan(Map.of()));

        assertThat(ids).containsSubsequence("environment", "deploy-observability", "deploy-idp",
                "deploy-stt", "deploy-projects", "deploy-workspaces", "deploy-events",
                "deploy-gateway", "deploy-artifacts", "deploy-ci",
                "deploy-platform-deployments");
        assertThat(ids).doesNotContain("deploy-cd", "deploy-serviceregistry");
    }

    @Test
    void theReleasesTheWrapperBuildsInstallAreReplayedBeforeAnythingIsDeployed() {
        List<String> ids = ids(plan(Map.of()));

        assertThat(ids).containsSubsequence("preseed", "release-spa-ui-components",
                "release-integrations-angular", "release-eventstream",
                "release-integrations-quarkus", "environment", "deploy-observability");
    }

    @Test
    void skipBuildDropsTheSeedBuildsAndSaysSo() {
        List<String> cold = ids(plan(Map.of()));
        List<String> warm = ids(plan(Map.of("QITS_SKIP_BUILD", "1")));

        assertThat(warm).contains("seed-skipped")
                .doesNotContain("ci-daemon", "seed-image-ci", "auth-core-seed");
        assertThat(warm.size()).isLessThan(cold.size());
        // The pipeline half is untouched: a warm rerun still pushes and still waits.
        assertThat(warm).contains("deploy-platform-deployments", "release-train-push", "summary");
    }
}
