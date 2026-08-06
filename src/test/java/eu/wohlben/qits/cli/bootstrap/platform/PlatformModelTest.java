package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformModelTest {

    @Test
    void everyRepositoryKnowsWhereItLivesInTheWrapper() {
        assertThat(PlatformModel.repoPath("platform-deployments"))
                .isEqualTo("services/qits-platform-deployments");
        assertThat(PlatformModel.repoPath("ci-daemon")).isEqualTo("daemons/qits-ci-daemon");
        assertThat(PlatformModel.repoPath("oci")).isEqualTo("images/qits-oci");
        assertThat(PlatformModel.repoPath("eventstream")).isEqualTo("libs/qits-eventstream");
        assertThat(PlatformModel.repoPath("spa-cd")).isEqualTo("frontends/qits-spa-cd");
        assertThat(PlatformModel.repoPath("integrations-angular"))
                .isEqualTo("integrations/qits-integrations-angular");
    }

    @Test
    void eachPlaneDeploysFromItsOwnBranchAndNeitherFromMain() {
        // main is the trunk on both planes and deploys nothing on its own.
        assertThat(PlatformModel.deployRef("idp", "environment/dev")).isEqualTo("platform/main");
        assertThat(PlatformModel.deployRef("platform-deployments", "environment/dev"))
                .isEqualTo("platform/main");
        assertThat(PlatformModel.deployRef("gateway", "environment/dev"))
                .isEqualTo("environment/dev");
        assertThat(PlatformModel.deployRef("workspaces", "environment/dev"))
                .isEqualTo("environment/dev");
        assertThat(PlatformModel.PLATFORM_BRANCH).isNotEqualTo("main");
    }

    @Test
    void theDeployerNamesPlatformAndEnvironmentContainersDifferently() {
        // qits-pd- is the namespace's abbreviation and stays that way: docker's name charset has
        // no dot, and this is what a person greps for on the host. The config keys and the labels
        // spell qits.platform.deployments in full; nothing resolves through the container name.
        assertThat(PlatformModel.pdNamePrefix("idp", "dev"))
                .isEqualTo("qits-pd-platform-qits-idp-");
        assertThat(PlatformModel.pdNamePrefix("gateway", "dev"))
                .isEqualTo("qits-pd-dev-qits-gateway-");
    }

    @Test
    void theThreeEnvironmentServicesAreTheOnesNotOnThePlatformPlane() {
        assertThat(PlatformModel.PLATFORM_SERVICES).containsExactlyInAnyOrder(
                "platform-deployments", "idp", "artifacts", "ci", "events", "projects",
                "observability");
        assertThat(PlatformModel.DEPLOYABLES)
                .filteredOn(name -> !PlatformModel.isPlatformService(name))
                .containsExactlyInAnyOrder("gateway", "workspaces", "stt");
    }

    @Test
    void theRetiredDeployersAreSeededButNotDeployed() {
        // qits-platform-deployments is the merge-back of both; nothing deploys them any more, and
        // their histories still belong on the git host.
        assertThat(PlatformModel.DEPLOYABLES).doesNotContain("cd", "serviceregistry");
        assertThat(PlatformModel.CORE).doesNotContain("cd", "serviceregistry");
        assertThat(PlatformModel.SEEDED_REPOS).contains("cd", "serviceregistry");
    }

    @Test
    void theDeployerPackagesTheClientItInheritedFromItsAncestor() {
        // service/src/main/webui is still the qits-spa-cd submodule, so the bundle directory is
        // named after that client. The Dockerfile's `test -f` guard checks this exact path.
        assertThat(PlatformModel.seedUiPath("platform-deployments"))
                .isEqualTo("service/src/main/webui/dist/qits-spa-cd/browser");
        assertThat(PlatformModel.seedUiPath("gateway"))
                .isEqualTo("src/main/webui/dist/qits-spa-home/browser");
        assertThat(PlatformModel.seedUiPath("ci"))
                .isEqualTo("service/src/main/webui/dist/qits-spa-ci/browser");
    }

    @Test
    void theDeployablesAndTheSeededReposAreDisjointAndTogetherAreEveryRepository() {
        assertThat(PlatformModel.DEPLOYABLES).doesNotContainAnyElementsOf(
                PlatformModel.SEEDED_REPOS);
        assertThat(PlatformModel.platformRepos())
                .hasSize(PlatformModel.DEPLOYABLES.size() + PlatformModel.SEEDED_REPOS.size())
                .containsAll(PlatformModel.DEPLOYABLES)
                .containsAll(PlatformModel.SEEDED_REPOS);
    }

    @Test
    void observabilityIsDeployedFirstAndTheDeployerLast() {
        // Order matters: observability quiets the OTLP warnings earliest, and the deployer's own
        // deployment is the self-update handoff.
        assertThat(PlatformModel.DEPLOYABLES.getFirst()).isEqualTo("observability");
        assertThat(PlatformModel.DEPLOYABLES.getLast()).isEqualTo("platform-deployments");
    }

    @Test
    void theDeployerNeedsNoIdpClientAndTheAudienceListGrantsIt() {
        // It mints nothing, so it holds no client — only the audience its callers may ask for.
        assertThat(PlatformModel.IDP_CLIENTS).doesNotContain("qits-platform-deployments");
        assertThat(PlatformModel.IDP_AUDIENCES).contains("qits-platform-deployments");
    }

    @Test
    void clientKeysAreTheEnvironmentSpelling() {
        assertThat(PlatformModel.clientKey("qits-ci")).isEqualTo("QITS_CI");
    }
}
