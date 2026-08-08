package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformModelTest {

    @Test
    void everyRepositoryKnowsWhereItLivesInTheWrapper() {
        // The six repositories renamed on 2026-08-08, each resolving to the wrapper directory that
        // exists on disk. A wrong path here used to fall back to GitHub in silence; the sources
        // phase fails on it now, so these are the pins that keep the failure from ever being met.
        assertThat(PlatformModel.repoPath("deployments")).isEqualTo("services/qits-deployments");
        assertThat(PlatformModel.repoPath("platform-artifacts"))
                .isEqualTo("services/qits-platform-artifacts");
        assertThat(PlatformModel.repoPath("platform-idp")).isEqualTo("services/qits-platform-idp");
        assertThat(PlatformModel.repoPath("platform-edge")).isEqualTo("services/qits-platform-edge");
        assertThat(PlatformModel.repoPath("spa-deployments"))
                .isEqualTo("frontends/qits-spa-deployments");
        assertThat(PlatformModel.repoPath("platform-spa-artifacts"))
                .isEqualTo("frontends/qits-platform-spa-artifacts");

        assertThat(PlatformModel.repoPath("ci-daemon")).isEqualTo("daemons/qits-ci-daemon");
        assertThat(PlatformModel.repoPath("oci")).isEqualTo("images/qits-oci");
        assertThat(PlatformModel.repoPath("eventstream")).isEqualTo("libs/qits-eventstream");
        assertThat(PlatformModel.repoPath("platform-spa-docs"))
                .isEqualTo("frontends/qits-platform-spa-docs");
        // Framework glue is a lib; the wrapper has no integrations/ directory.
        assertThat(PlatformModel.repoPath("integrations-angular"))
                .isEqualTo("libs/qits-integrations-angular");
        assertThat(PlatformModel.repoPath("integrations-quarkus"))
                .isEqualTo("libs/qits-integrations-quarkus");
    }

    @Test
    void everyDeployableIsUnderTheOneDeployRefAndNoneUnderMain() {
        // There is no deployRef any more, and that is the assertion: both planes ask a green build
        // the same question, so environment/<name> is the whole set. Nothing may reintroduce a
        // second ref without this file saying so.
        assertThat(PlatformModel.platformRepos()).doesNotContain("platform-branch");
        assertThat(PlatformModel.PLATFORM_SERVICES)
                .allSatisfy(name -> assertThat(PlatformModel.isPlatformService(name)).isTrue());
    }

    @Test
    void theDeployerNamesPlatformAndEnvironmentContainersDifferently() {
        // qits-pd- is the namespace's abbreviation and stays that way: docker's name charset has
        // no dot, and this is what a person greps for on the host. The config keys and the labels
        // spell qits.platform.deployments in full; nothing resolves through the container name.
        //
        // The platform shape DROPS the tier segment rather than filling it — the repository name
        // carries the plane, so qits-pd-platform-qits-platform-idp- would say it twice.
        assertThat(PlatformModel.pdNamePrefix("platform-idp", "prod"))
                .isEqualTo("qits-pd-qits-platform-idp-");
        assertThat(PlatformModel.pdNamePrefix("platform-edge", "prod"))
                .isEqualTo("qits-pd-qits-platform-edge-");
        assertThat(PlatformModel.pdNamePrefix("gateway", "prod"))
                .isEqualTo("qits-pd-prod-qits-gateway-");
        assertThat(PlatformModel.pdNamePrefix("deployments", "prod"))
                .isEqualTo("qits-pd-prod-qits-deployments-");
    }

    @Test
    void aWireAliasCarriesTheTierOnlyWhenThereIsOneToCarry() {
        // The address peers dial, and the name a cutover finds its predecessor by. The seed
        // containers are named after it, so a wrong answer here is a seed nothing can reach.
        assertThat(PlatformModel.wireAlias("ci", "prod")).isEqualTo("prod-qits-ci");
        assertThat(PlatformModel.wireAlias("gateway", "prod")).isEqualTo("prod-qits-gateway");
        assertThat(PlatformModel.wireAlias("deployments", "prod"))
                .isEqualTo("prod-qits-deployments");
        assertThat(PlatformModel.wireAlias("platform-artifacts", "prod"))
                .isEqualTo("qits-platform-artifacts");
        assertThat(PlatformModel.wireAlias("platform-edge", "prod"))
                .isEqualTo("qits-platform-edge");
        // It follows the environment name, which is the whole reason it is derived and not spelled.
        assertThat(PlatformModel.wireAlias("ci", "preprod")).isEqualTo("preprod-qits-ci");
    }

    @Test
    void thePlatformPlaneIsTheFourThingsThatCannotBePerTier() {
        assertThat(PlatformModel.PLATFORM_SERVICES).containsExactlyInAnyOrder(
                "platform-edge", "platform-idp", "platform-artifacts", "platform-docs");
        // Everything else is a service of the one environment — the gateway included, since
        // qits-platform-edge took the host port that was its only reason to be up here.
        assertThat(PlatformModel.DEPLOYABLES)
                .filteredOn(name -> !PlatformModel.isPlatformService(name))
                .containsExactlyInAnyOrder("observability", "stt", "projects", "workspaces",
                        "events", "gateway", "ci", "deployments");
    }

    @Test
    void theSeedIsEveryServiceTheRestIsBuiltAndReachedThrough() {
        assertThat(PlatformModel.CORE).containsExactlyInAnyOrder(
                "gateway", "platform-edge", "platform-artifacts", "ci", "deployments",
                "platform-idp");
        // Every seed service is also deployed through the pipeline afterwards; nothing stays
        // hand-built.
        assertThat(PlatformModel.DEPLOYABLES).containsAll(PlatformModel.CORE);
    }

    @Test
    void theRetiredDeployersAreGone() {
        assertThat(PlatformModel.platformRepos()).doesNotContain("cd", "serviceregistry");
        // And so are the pre-rename spellings: a name that resolves to no repository on the git
        // host creates one, pushes to it, and waits an hour for a build nobody asked for.
        assertThat(PlatformModel.platformRepos()).doesNotContain(
                "artifacts", "idp", "platform-deployments", "spa-artifacts",
                "platform-spa-deployments");
    }

    @Test
    void aClientNotNamedAfterItsServiceIsSpelledOutAndOneWithNoneSaysSo() {
        // A bundle directory is the Angular project key, so it moves when the client is renamed —
        // and the service's Dockerfile checks this exact path with `test -f`. A stale spelling here
        // fails the seed build minutes in, which is how the deployments client's rename was found.
        assertThat(PlatformModel.seedUiPath("platform-artifacts"))
                .isEqualTo("service/src/main/webui/dist/qits-platform-spa-artifacts/browser");
        assertThat(PlatformModel.seedUiPath("deployments"))
                .isEqualTo("service/src/main/webui/dist/qits-spa-deployments/browser");
        assertThat(PlatformModel.seedUiPath("gateway"))
                .isEqualTo("src/main/webui/dist/qits-spa-home/browser");
        assertThat(PlatformModel.seedUiPath("ci"))
                .isEqualTo("service/src/main/webui/dist/qits-spa-ci/browser");
        // No client at all, and empty is the answer that says so: a seed build must not be made to
        // require a bundle that does not exist.
        assertThat(PlatformModel.seedUiPath("platform-idp")).isEmpty();
        assertThat(PlatformModel.seedUiPath("platform-edge")).isEmpty();
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
    void observabilityIsFirstTheEdgeIsSecondToLastAndTheDeployerIsLast() {
        // Order matters: observability quiets the OTLP warnings earliest, and the deployer's own
        // deployment is the self-update handoff.
        assertThat(PlatformModel.DEPLOYABLES.getFirst()).isEqualTo("observability");
        assertThat(PlatformModel.DEPLOYABLES.getLast()).isEqualTo("deployments");
        // The edge is the host port, so its cutover takes the CLI's own door away for a beat. It
        // goes as late as it can — after the gateway it forwards to, before the self-update.
        assertThat(PlatformModel.DEPLOYABLES).containsSubsequence(
                "gateway", "platform-edge", "deployments");
    }

    @Test
    void theDeployerNeedsNoIdpClientAndTheAudienceListGrantsIt() {
        // It mints nothing, so it holds no client — only the audience its callers may ask for.
        assertThat(PlatformModel.idpClients("prod")).doesNotContain("prod-qits-deployments");
        assertThat(PlatformModel.idpAudiences("prod")).contains("prod-qits-deployments");
    }

    @Test
    void aClientIdIsAWireAliasSoItFollowsTheEnvironment() {
        // These four are exactly what qits-platform-idp ships as its defaults for the default
        // environment name. The id is part of the config KEY, so a client the deployment spells
        // differently from the token request is invalid_client and nothing says it was a typo.
        assertThat(PlatformModel.idpClients("prod")).containsExactly(
                "prod-qits-ci", "qits-platform-artifacts", "prod-qits-workspaces",
                "prod-qits-gateway");
        assertThat(PlatformModel.idpAudiences("prod")).isEqualTo(
                "prod-qits-ci,qits-platform-artifacts,prod-qits-workspaces,prod-qits-gateway,"
                        + "prod-qits-deployments");
        // The platform client does not move; the three environment ones do.
        assertThat(PlatformModel.idpClients("preprod")).containsExactly(
                "preprod-qits-ci", "qits-platform-artifacts", "preprod-qits-workspaces",
                "preprod-qits-gateway");
    }

    @Test
    void clientKeysAreTheEnvironmentSpelling() {
        assertThat(PlatformModel.clientKey("prod-qits-ci")).isEqualTo("PROD_QITS_CI");
        assertThat(PlatformModel.clientKey("qits-platform-artifacts"))
                .isEqualTo("QITS_PLATFORM_ARTIFACTS");
    }
}
