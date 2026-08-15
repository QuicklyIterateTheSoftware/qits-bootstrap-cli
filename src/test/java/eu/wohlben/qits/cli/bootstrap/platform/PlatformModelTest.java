package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformModelTest {

    @Test
    void everyRepositoryKnowsWhereItLivesInTheWrapper() {
        // The six repositories renamed on 2026-08-08, each resolving to the wrapper directory that
        // exists on disk. A wrong path here used to fall back to GitHub in silence; the sources
        // phase fails on it now, so these are the pins that keep the failure from ever being met.
        assertThat(PlatformModel.repoPath("deployments")).isEqualTo("services/qits-deployments");
        assertThat(PlatformModel.repoPath("artifacts")).isEqualTo("services/qits-artifacts");
        assertThat(PlatformModel.repoPath("platform-idp")).isEqualTo("services/qits-platform-idp");
        assertThat(PlatformModel.repoPath("platform-edge")).isEqualTo("services/qits-platform-edge");
        assertThat(PlatformModel.repoPath("spa-deployments"))
                .isEqualTo("frontends/qits-spa-deployments");
        assertThat(PlatformModel.repoPath("spa-artifacts"))
                .isEqualTo("frontends/qits-spa-artifacts");
        // The byte plane's own, each in the directory its ROLE puts it in: two libraries and two
        // services, whatever plane the services are on.
        assertThat(PlatformModel.repoPath("blobstore")).isEqualTo("libs/qits-blobstore");
        assertThat(PlatformModel.repoPath("registries")).isEqualTo("libs/qits-registries");
        assertThat(PlatformModel.repoPath("platform-mirror"))
                .isEqualTo("services/qits-platform-mirror");
        assertThat(PlatformModel.repoPath("githost")).isEqualTo("services/qits-githost");
        assertThat(PlatformModel.repoPath("docs")).isEqualTo("services/qits-docs");
        // The container orchestrator: a service, so services/ and docker/Dockerfile, and its wire
        // alias carries the tier — one orchestrator per tier, holding that host's socket.
        assertThat(PlatformModel.repoPath("containers")).isEqualTo("services/qits-containers");
        assertThat(PlatformModel.dockerfilePath("containers")).isEqualTo("docker/Dockerfile");
        assertThat(PlatformModel.isPlatformService("containers")).isFalse();
        assertThat(PlatformModel.wireAlias("containers", "prod")).isEqualTo("prod-qits-containers");
        assertThat(PlatformModel.pdNamePrefix("containers", "prod"))
                .isEqualTo("qits-pd-prod-qits-containers-");
        // And their two clients, added on 2026-08-11. BOTH FRONTEND SPELLINGS ARE LIVE: the git
        // host's client is qits-spa-<x>, the mirror's is qits-platform-spa-<x> because its service
        // is on the platform plane. A wrong directory here clones the org's copy in silence.
        assertThat(PlatformModel.repoPath("spa-githost"))
                .isEqualTo("frontends/qits-spa-githost");
        assertThat(PlatformModel.repoPath("platform-spa-mirror"))
                .isEqualTo("frontends/qits-platform-spa-mirror");

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
        assertThat(PlatformModel.repoPath("spa-docs")).isEqualTo("frontends/qits-spa-docs");
        // The pre-split spelling still resolves, because a wrapper checked out before the rename
        // has that directory and a path that resolves to nothing clones the org's copy in silence.
        assertThat(PlatformModel.repoPath("platform-spa-artifacts"))
                .isEqualTo("frontends/qits-platform-spa-artifacts");
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
        assertThat(PlatformModel.wireAlias("platform-edge", "prod"))
                .isEqualTo("qits-platform-edge");
        // The byte plane, split across both shapes: the store and the git host went back to being
        // environment services, the caches stayed platform-scoped because one cache serves every
        // tier on the machine.
        assertThat(PlatformModel.wireAlias("artifacts", "prod")).isEqualTo("prod-qits-artifacts");
        assertThat(PlatformModel.wireAlias("githost", "prod")).isEqualTo("prod-qits-githost");
        assertThat(PlatformModel.wireAlias("docs", "prod")).isEqualTo("prod-qits-docs");
        assertThat(PlatformModel.wireAlias("platform-mirror", "prod"))
                .isEqualTo("qits-platform-mirror");
        // It follows the environment name, which is the whole reason it is derived and not spelled.
        assertThat(PlatformModel.wireAlias("ci", "preprod")).isEqualTo("preprod-qits-ci");
    }

    @Test
    void thePlatformPlaneIsWhatCannotBePerTier() {
        // Five since 2026-08-09: the nameserver is of exactly this kind, because a zone is a row and
        // one delegation answers for every environment. Two of them would be two servers claiming
        // one public IP's port 53 and disagreeing about what exists.
        assertThat(PlatformModel.PLATFORM_SERVICES).containsExactlyInAnyOrder(
                "platform-edge", "platform-idp", "platform-mirror", "platform-dns");
        // The byte-plane split settled the pair that used to be here: the caches were the only
        // reason either could not be per-tier, and they are qits-platform-mirror now.
        assertThat(PlatformModel.isPlatformService("artifacts")).isFalse();
        assertThat(PlatformModel.isPlatformService("docs")).isFalse();
        assertThat(PlatformModel.isPlatformService("githost")).isFalse();
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
                        "workspaces", "events", "gateway", "ci", "containers", "deployments",
                        "artifacts", "githost", "docs");
        // Every environment runs its own database, so postgres is an environment service like the
        // rest of them — the platform plane is what genuinely cannot be per-tier.
        assertThat(PlatformModel.isPlatformService("oci-postgresql")).isFalse();
    }

    @Test
    void theSeedIsEveryServiceTheRestIsBuiltAndReachedThrough() {
        // The nameserver is in the seed because the boot itself writes to it: with a domain set, the
        // zone row is created over its API hours before the pipeline could have deployed it.
        //
        // The bus is in it since 2026-08-10, when ci's direct POST to the deployer was retired: a
        // green build is ci -> outbox -> qits-events -> the deployer's subscriber, and every hop has
        // to exist before the FIRST deployment. Deployed at phase 46 it did not.
        // The orchestrator is in it since 2026-08-11, because qits-ci is: ci runs every pipeline
        // step as a container it asks that service for, and the first pipeline of a cold boot is
        // minutes after the seed comes up. Deployed at its own place in the train it would not be
        // there in time.
        assertThat(PlatformModel.CORE).containsExactlyInAnyOrder(
                "gateway", "platform-edge", "platform-mirror", "artifacts", "githost", "ci",
                "containers", "deployments", "platform-idp", "platform-dns", "events",
                "oci-postgresql");
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
                "idp", "platform-deployments", "platform-spa-deployments",
                // The byte-plane split retired these four on the other side of the same rule: a
                // name nothing hosts is a push into a repository nobody reads.
                "platform-artifacts", "platform-docs", "platform-spa-artifacts",
                "platform-spa-docs");
    }

    @Test
    void aClientNotNamedAfterItsServiceIsSpelledOutAndOneWithNoneSaysSo() {
        // A bundle directory is the Angular project key, so it moves when the client is renamed —
        // and the service's Dockerfile checks this exact path with `test -f`. A stale spelling here
        // fails the seed build minutes in, which is how the deployments client's rename was found.
        // The artifacts client is the second rename this assertion has caught: its project key
        // followed the repository to qits-spa-artifacts on 2026-08-13.
        assertThat(PlatformModel.seedUiPath("artifacts"))
                .isEqualTo("service/src/main/webui/dist/qits-spa-artifacts/browser");
        assertThat(PlatformModel.seedUiPath("deployments"))
                .isEqualTo("service/src/main/webui/dist/qits-spa-deployments/browser");
        assertThat(PlatformModel.seedUiPath("gateway"))
                .isEqualTo("src/main/webui/dist/qits-spa-home/browser");
        assertThat(PlatformModel.seedUiPath("ci"))
                .isEqualTo("service/src/main/webui/dist/qits-spa-ci/browser");
        // The bus joined the seed on 2026-08-10 and it HAS a client, so it needs a placeholder: its
        // Dockerfile stops the build with `test -f` on this exact path before the native compile.
        assertThat(PlatformModel.seedUiPath("events"))
                .isEqualTo("service/src/main/webui/dist/qits-spa-events/browser");
        // The last two byte services to grow a client, on 2026-08-11. Both took the prebuilt-dist
        // Dockerfile, so both now stop their seed build at a `test -f` on these exact paths. The
        // first segment differs because the repositories do: qits-githost is a reactor whose
        // application is the `service` module, qits-platform-mirror is one module.
        assertThat(PlatformModel.seedUiPath("githost"))
                .isEqualTo("service/src/main/webui/dist/qits-spa-githost/browser");
        assertThat(PlatformModel.seedUiPath("platform-mirror"))
                .isEqualTo("src/main/webui/dist/qits-platform-spa-mirror/browser");
        // The idp's login/register client landed on 2026-08-14, prebuilt-dist shape like the rest.
        assertThat(PlatformModel.seedUiPath("platform-idp"))
                .isEqualTo("service/src/main/webui/dist/qits-platform-spa-idp/browser");
        // No client at all, and empty is the answer that says so: a seed build must not be made to
        // require a bundle that does not exist. The orchestrator serves machines and has no SPA at
        // all, so it is in this half and not the one above.
        assertThat(PlatformModel.seedUiPath("containers")).isEmpty();
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

    /**
     * The one repository the seed publishes by module. Its event vocabulary is what qits-ci and
     * qits-projects consume; the git host's service is not for anyone to resolve.
     */
    @Test
    void theGitHostIsSeededByItsEventModuleAndEveryOtherRepositoryWhole() {
        assertThat(PlatformModel.mavenModule("githost")).isEqualTo("githost-events");
        // The orchestrator's two LIBRARIES, and not its service: consumers pin
        // qits-containers-client, `core` is what the reactor builds it beside, and the service
        // module is a native image nobody resolves. Comma-separated is maven's own -pl spelling.
        assertThat(PlatformModel.mavenModule("containers")).isEqualTo("core,client");
        assertThat(PlatformModel.mavenModule("eventstream")).isEmpty();
        assertThat(PlatformModel.mavenModule("blobstore")).isEmpty();
        assertThat(PlatformModel.mavenModule("registries")).isEmpty();
        assertThat(PlatformModel.mavenModule("integrations-quarkus")).isEmpty();
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
     * The byte plane's two clients, added on 2026-08-11. Seeded like every other frontend — a
     * checkout, a repository on the git host and a main history — and nothing more: their bundles
     * ship inside their services' images, so a deploy phase or a release replay for either would
     * wait on a deployment nobody makes and a tag nobody cut.
     */
    @Test
    void theByteplaneClientsAreSeededAndNeitherDeployedNorReplayed() {
        assertThat(PlatformModel.SEEDED_REPOS).contains("spa-githost", "platform-spa-mirror");
        assertThat(PlatformModel.DEPLOYABLES)
                .doesNotContain("spa-githost", "platform-spa-mirror");
        assertThat(PlatformModel.RELEASE_PUBLISHERS)
                .doesNotContain("spa-githost", "platform-spa-mirror");
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
        // The byte-plane libs are deliberately absent until their first calver release exists:
        // a replay restores a pin, and every consumer still pins 1.0.0-SNAPSHOT, which the seed
        // publishes restore. When they join, registries follows blobstore, both before eventstream.
        assertThat(PlatformModel.RELEASE_PUBLISHERS)
                .doesNotContain("blobstore", "registries");
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
                "docs", "platform-dns", "platform-edge");
        // The mirror before everything whose build resolves through it, and the git host between
        // the store and ci — ci reads pipeline config out of the git host and clones from it.
        assertThat(PlatformModel.DEPLOYABLES).containsSubsequence(
                "platform-mirror", "artifacts", "githost", "ci");
        // The orchestrator immediately before ci: ci runs every step as a container it asks that
        // service for, so the two cutovers have to be ordered rather than overlapping.
        assertThat(PlatformModel.DEPLOYABLES).containsSubsequence("containers", "ci");
    }

    /**
     * The deployer and the orchestrator are AUDIENCES and CLIENTS both, since 2026-08-14. They
     * validate exactly as they always did — every route of the orchestrator is behind the machine
     * gate, reads included — and each holds a credential now because each pulls images, and a
     * docker config.json is a client id and a secret. Neither asks the idp for a token.
     */
    @Test
    void thePullersValidateAndHoldACredentialOfTheirOwn() {
        assertThat(PlatformModel.idpClients("prod"))
                .contains("prod-qits-deployments", "prod-qits-containers");
        assertThat(PlatformModel.idpAudiences("prod"))
                .contains("prod-qits-deployments", "prod-qits-containers");
        // Each name once. The audience list is derived from the clients now, and a duplicate would
        // be a key that says the same thing twice to a service that replaces the shipped list.
        assertThat(PlatformModel.idpAudiences("prod").split(",")).doesNotHaveDuplicates();
        // Empty, and the seam is kept for the next service that validates without a credential.
        assertThat(PlatformModel.RECEIVE_ONLY_APPS).isEmpty();
    }

    @Test
    void aClientIdIsAWireAliasSoItFollowsTheEnvironment() {
        // The id is part of the config KEY, so a client the deployment spells differently from
        // the token request is invalid_client and nothing says it was a typo. qits-projects
        // joined for orchestration round 2: its agent containers start through qits-containers.
        assertThat(PlatformModel.idpClients("prod")).containsExactly(
                "prod-qits-ci", "prod-qits-artifacts", "prod-qits-workspaces",
                "prod-qits-gateway", "prod-qits-projects", "prod-qits-deployments",
                "prod-qits-containers", "prod-qits-edge");
        assertThat(PlatformModel.idpAudiences("prod")).isEqualTo(
                "prod-qits-ci,prod-qits-artifacts,prod-qits-workspaces,prod-qits-gateway,"
                        + "prod-qits-projects,prod-qits-deployments,prod-qits-containers,"
                        + "prod-qits-edge");
        // Every one of them follows the environment now: the artifacts client was the one platform
        // id in this list, and the byte-plane split made that service a tier's again.
        assertThat(PlatformModel.idpClients("preprod")).containsExactly(
                "preprod-qits-ci", "preprod-qits-artifacts", "preprod-qits-workspaces",
                "preprod-qits-gateway", "preprod-qits-projects", "preprod-qits-deployments",
                "preprod-qits-containers", "preprod-qits-edge");
        // The two new byte services hold no client at all: the mirror has no auth surface, and the
        // git host validates a push option rather than a token.
        assertThat(PlatformModel.idpClients("prod"))
                .doesNotContain("qits-platform-mirror", "prod-qits-githost");
    }

    /**
     * <b>The edge's client id is the one that is not its service's alias.</b> The service answers
     * to qits-platform-edge — one process for every environment — while the credential belongs to
     * the session gate, which is an environment's. The edge is handed the same pair as
     * QITS_EDGE_SESSIONS_CLIENT_ID and _SECRET, so the two sides agree with each other and with
     * nothing else.
     */
    @Test
    void theEdgesSessionClientCarriesTheEnvironmentAndNotTheServiceName() {
        assertThat(PlatformModel.idpClients("prod")).contains("prod-qits-edge")
                .doesNotContain("qits-platform-edge");
        assertThat(PlatformModel.clientKey("prod-qits-edge")).isEqualTo("PROD_QITS_EDGE");
        // Which is the key it is recorded under in .qits-bootstrap.env, and the spelling the idp's
        // own per-client config key embeds.
        assertThat("IDP_SECRET_" + PlatformModel.clientKey(
                PlatformModel.wireAlias("edge", "prod"))).isEqualTo("IDP_SECRET_PROD_QITS_EDGE");
    }

    @Test
    void clientKeysAreTheEnvironmentSpelling() {
        assertThat(PlatformModel.clientKey("prod-qits-ci")).isEqualTo("PROD_QITS_CI");
        assertThat(PlatformModel.clientKey("prod-qits-artifacts"))
                .isEqualTo("PROD_QITS_ARTIFACTS");
    }

    /**
     * WHO IS RESTORED, and it is narrower than "who is seeded". A deployable and a release
     * publisher each have a last release the platform can state; a step-image source or an SPA seed
     * source is rebuilt from source every boot and pinned by nobody, so its tags go stale unnoticed
     * — qits-oci's newest one predated the `build` user its step images grew, and the seed built
     * from it could not launch a step that declares `user: build`.
     */
    @Test
    void onlyDeployablesAndPublishersCarryVersionIdentity() {
        assertThat(PlatformModel.carriesVersionIdentity("ci")).isTrue();
        assertThat(PlatformModel.carriesVersionIdentity("platform-idp")).isTrue();
        assertThat(PlatformModel.carriesVersionIdentity("eventstream")).isTrue();
        assertThat(PlatformModel.carriesVersionIdentity("oci-workspace")).isTrue();
        // Seeded, and neither: the step images and the SPA seed sources.
        assertThat(PlatformModel.carriesVersionIdentity("oci")).isFalse();
        assertThat(PlatformModel.carriesVersionIdentity("spa-home")).isFalse();
        assertThat(PlatformModel.carriesVersionIdentity("ci-daemon")).isFalse();
        // Every entry of both sets, so a new deployable cannot be added without one.
        assertThat(PlatformModel.DEPLOYABLES).allSatisfy(name ->
                assertThat(PlatformModel.carriesVersionIdentity(name)).isTrue());
        assertThat(PlatformModel.RELEASE_PUBLISHERS).allSatisfy(name ->
                assertThat(PlatformModel.carriesVersionIdentity(name)).isTrue());
    }

    /**
     * WHICH TAG IS THE RELEASE, asked by the boot twice — the commit each checkout stands at and
     * the commit the deploy ref is moved to. git sorted the list newest-version-first; this picks
     * the newest that is a version.
     */
    @Test
    void theNewestCalverTagIsTheRelease() {
        assertThat(PlatformModel.newestRelease(
                List.of("2026.812.101500", "2026.811.090000"))).isEqualTo("2026.812.101500");
    }

    /**
     * A stray tag sorts above every CalVer under {@code --sort=-v:refname} — letters beat digits —
     * so without this filter a boot would build and deploy whatever commit it named.
     */
    @Test
    void aStrayTagIsNotARelease() {
        assertThat(PlatformModel.newestRelease(List.of("latest", "v2", "2026.812.101500")))
                .isEqualTo("2026.812.101500");
        assertThat(PlatformModel.newestRelease(List.of("latest", "nightly"))).isEmpty();
        assertThat(PlatformModel.newestRelease(List.of())).isEmpty();
    }
}
