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
        // The two daemons that joined the replay set on 2026-08-10. The default arm would clone
        // services/ from GitHub and the sources phase would fail on a directory nobody has.
        assertThat(PlatformModel.repoPath("workspace-daemon"))
                .isEqualTo("daemons/qits-workspace-daemon");
        assertThat(PlatformModel.repoPath("projects-daemon"))
                .isEqualTo("daemons/qits-projects-daemon");
        assertThat(PlatformModel.repoPath("oci")).isEqualTo("images/qits-oci");
        assertThat(PlatformModel.repoPath("oci-workspace")).isEqualTo("images/qits-oci-workspace");
        // An image repository, not a service: the default arm would clone services/ from GitHub.
        assertThat(PlatformModel.repoPath("oci-postgresql"))
                .isEqualTo("images/qits-oci-postgresql");
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
    void thePlatformPlaneIsWhatCannotBePerTier() {
        // Five since 2026-08-09: the nameserver is of exactly this kind, because a zone is a row and
        // one delegation answers for every environment. Two of them would be two servers claiming
        // one public IP's port 53 and disagreeing about what exists.
        assertThat(PlatformModel.PLATFORM_SERVICES).containsExactlyInAnyOrder(
                "platform-edge", "platform-idp", "platform-artifacts", "platform-docs",
                "platform-dns");
        assertThat(PlatformModel.wireAlias("platform-dns", "prod")).isEqualTo("qits-platform-dns");
        assertThat(PlatformModel.pdNamePrefix("platform-dns", "prod"))
                .isEqualTo("qits-pd-qits-platform-dns-");
        assertThat(PlatformModel.repoPath("platform-dns")).isEqualTo("services/qits-platform-dns");
        // A service, so its Dockerfile is in docker/ and it has no client bundle to place.
        assertThat(PlatformModel.dockerfilePath("platform-dns")).isEqualTo("docker/Dockerfile");
        assertThat(PlatformModel.seedUiPath("platform-dns")).isEmpty();
        // Everything else is a service of the one environment — the gateway included, since
        // qits-platform-edge took the host port that was its only reason to be up here.
        assertThat(PlatformModel.DEPLOYABLES)
                .filteredOn(name -> !PlatformModel.isPlatformService(name))
                .containsExactlyInAnyOrder("observability", "oci-postgresql", "stt", "projects",
                        "workspaces", "events", "gateway", "ci", "deployments");
        // Every environment runs its own database, so postgres is an environment service like the
        // rest of them — the platform plane is what genuinely cannot be per-tier.
        assertThat(PlatformModel.isPlatformService("oci-postgresql")).isFalse();
    }

    @Test
    void theSeedIsEveryServiceTheRestIsBuiltAndReachedThrough() {
        // The nameserver is in the seed because the boot itself writes to it: with a domain set, the
        // zone row is created over its API hours before the pipeline could have deployed it.
        assertThat(PlatformModel.CORE).containsExactlyInAnyOrder(
                "gateway", "platform-edge", "platform-artifacts", "ci", "deployments",
                "platform-idp", "platform-dns", "oci-postgresql");
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
        assertThat(PlatformModel.seedUiPath("oci-postgresql")).isEmpty();
    }

    @Test
    void anImageRepositoryKeepsItsDockerfileAtItsRoot() {
        // A service keeps it in docker/; an image repository IS the Dockerfile. The seed build has
        // to agree with the repository's own pipeline config, which says -f Dockerfile.
        assertThat(PlatformModel.dockerfilePath("oci-postgresql")).isEqualTo("Dockerfile");
        assertThat(PlatformModel.dockerfilePath("ci")).isEqualTo("docker/Dockerfile");
        assertThat(PlatformModel.dockerfilePath("platform-idp")).isEqualTo("docker/Dockerfile");
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

    /**
     * Every publisher whose release is replayed needs a repository on the git host, a checkout and a
     * main history — which is what SEEDED_REPOS gets it. A publisher missing from that list has no
     * source directory to read a tag out of, and the replay phase fails on a path nobody cloned.
     */
    @Test
    void everyReleasePublisherIsARepositoryTheBootstrapSeeds() {
        assertThat(PlatformModel.SEEDED_REPOS).containsAll(PlatformModel.RELEASE_PUBLISHERS);
        // Dependency order, and the one pair that is load-bearing: the daemon builds pull
        // qits/workspace-base at a pinned version, and the base's own replay is what publishes it.
        assertThat(PlatformModel.RELEASE_PUBLISHERS)
                .containsSubsequence("oci-workspace", "workspace-daemon")
                .containsSubsequence("oci-workspace", "projects-daemon");
    }

    @Test
    void observabilityIsFirstTheEdgeIsSecondToLastAndTheDeployerIsLast() {
        // Order matters: observability quiets the OTLP warnings earliest, and the deployer's own
        // deployment is the self-update handoff.
        assertThat(PlatformModel.DEPLOYABLES.getFirst()).isEqualTo("observability");
        assertThat(PlatformModel.DEPLOYABLES.getLast()).isEqualTo("deployments");
        // The database goes before every application that might hold a connection to it, so its
        // cutover is never queued beside a consumer's.
        assertThat(PlatformModel.DEPLOYABLES.get(1)).isEqualTo("oci-postgresql");
        // The edge is the host port, so its cutover takes the CLI's own door away for a beat. It
        // goes as late as it can — after the gateway it forwards to, before the self-update.
        assertThat(PlatformModel.DEPLOYABLES).containsSubsequence(
                "gateway", "platform-edge", "deployments");
        // The nameserver goes with the other platform service nothing in this train dials, and its
        // window must not fall inside the edge's.
        assertThat(PlatformModel.DEPLOYABLES).containsSubsequence(
                "platform-docs", "platform-dns", "platform-edge");
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
