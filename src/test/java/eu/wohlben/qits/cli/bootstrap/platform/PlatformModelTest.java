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
        assertThat(PlatformModel.repoPath("platform-spa-deployments"))
                .isEqualTo("frontends/qits-platform-spa-deployments");
        assertThat(PlatformModel.repoPath("platform-spa-docs"))
                .isEqualTo("frontends/qits-platform-spa-docs");
        // Framework glue is a lib; the wrapper has no integrations/ directory.
        assertThat(PlatformModel.repoPath("integrations-angular"))
                .isEqualTo("libs/qits-integrations-angular");
        assertThat(PlatformModel.repoPath("integrations-quarkus"))
                .isEqualTo("libs/qits-integrations-quarkus");
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
                "observability", "platform-docs");
        // Still three, and the count is the assertion: platform-docs joined the PLATFORM plane
        // rather than this list, because the docs repository it reads is one store for the whole
        // platform and a reader per environment would be two front doors onto it.
        assertThat(PlatformModel.DEPLOYABLES)
                .filteredOn(name -> !PlatformModel.isPlatformService(name))
                .containsExactlyInAnyOrder("gateway", "workspaces", "stt");
    }

    @Test
    void theRetiredDeployersAreGone() {
        assertThat(PlatformModel.platformRepos()).doesNotContain("cd", "serviceregistry");
    }

    @Test
    void aClientNotNamedAfterItsServiceIsSpelledOut() {
        // A bundle directory is the Angular project key, so it moves when the client is renamed —
        // and the service's Dockerfile checks this exact path with `test -f`. A stale spelling here
        // fails the seed build minutes in, which is how the deployments client's rename was found.
        assertThat(PlatformModel.seedUiPath("platform-deployments"))
                .isEqualTo("service/src/main/webui/dist/qits-platform-spa-deployments/browser");
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
