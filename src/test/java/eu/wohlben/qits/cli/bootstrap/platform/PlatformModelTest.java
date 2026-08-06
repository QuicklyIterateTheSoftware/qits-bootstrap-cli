package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformModelTest {

    @Test
    void everyRepositoryKnowsWhereItLivesInTheWrapper() {
        assertThat(PlatformModel.repoPath("cd")).isEqualTo("services/qits-cd");
        assertThat(PlatformModel.repoPath("ci-daemon")).isEqualTo("daemons/qits-ci-daemon");
        assertThat(PlatformModel.repoPath("oci")).isEqualTo("images/qits-oci");
        assertThat(PlatformModel.repoPath("eventstream")).isEqualTo("libs/qits-eventstream");
        assertThat(PlatformModel.repoPath("spa-cd")).isEqualTo("frontends/qits-spa-cd");
        assertThat(PlatformModel.repoPath("integrations-angular"))
                .isEqualTo("integrations/qits-integrations-angular");
    }

    @Test
    void singletonsDeployFromMainAndEverythingElseFromTheEnvironmentBranch() {
        assertThat(PlatformModel.deployRef("idp", "environment/dev")).isEqualTo("main");
        assertThat(PlatformModel.deployRef("serviceregistry", "environment/dev")).isEqualTo("main");
        assertThat(PlatformModel.deployRef("cd", "environment/dev")).isEqualTo("environment/dev");
    }

    @Test
    void cdNamesSingletonsAndEnvironmentApplicationsDifferently() {
        assertThat(PlatformModel.cdNamePrefix("idp", "dev"))
                .isEqualTo("qits-cd-singleton-qits-idp-");
        assertThat(PlatformModel.cdNamePrefix("ci", "dev")).isEqualTo("qits-cd-dev-qits-ci-");
    }

    @Test
    void theRegistryHasNoClientToStandInFor() {
        assertThat(PlatformModel.seedUiPath("serviceregistry")).isEmpty();
        assertThat(PlatformModel.seedUiPath("gateway"))
                .isEqualTo("src/main/webui/dist/qits-spa-home/browser");
        assertThat(PlatformModel.seedUiPath("ci"))
                .isEqualTo("service/src/main/webui/dist/qits-spa-ci/browser");
    }

    @Test
    void theDeployablesAndTheReleaseTrainsAreDisjointAndTogetherAreEveryRepository() {
        assertThat(PlatformModel.DEPLOYABLES).doesNotContainAnyElementsOf(
                PlatformModel.RELEASE_TRAIN_REPOS);
        assertThat(PlatformModel.platformRepos())
                .hasSize(PlatformModel.DEPLOYABLES.size() + PlatformModel.RELEASE_TRAIN_REPOS.size())
                .containsAll(PlatformModel.DEPLOYABLES)
                .containsAll(PlatformModel.RELEASE_TRAIN_REPOS);
    }

    @Test
    void observabilityIsDeployedFirstAndCdLast() {
        // Order matters: observability quiets the OTLP warnings earliest, and cd's own deployment
        // is the self-update handoff.
        assertThat(PlatformModel.DEPLOYABLES.getFirst()).isEqualTo("observability");
        assertThat(PlatformModel.DEPLOYABLES.getLast()).isEqualTo("cd");
    }

    @Test
    void clientKeysAreTheEnvironmentSpelling() {
        assertThat(PlatformModel.clientKey("qits-ci")).isEqualTo("QITS_CI");
    }
}
