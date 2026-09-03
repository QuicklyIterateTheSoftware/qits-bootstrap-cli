package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformModelTest {

    @Test
    void everyRepositoryKnowsWhereItLivesInTheWrapper() {
        // The FALLBACK layout, for a wrapper that declares nothing — a machine that has not cloned
        // one yet. What a wrapper on disk says beats every line below it; see RunStateTest.
        // The directory is the ROLE's and the last segment is the repository's CURRENT name, so
        // every line below moved with the phase-2 renames. A wrong path here used to fall back to
        // GitHub in silence; the sources phase fails on it now.
        assertThat(PlatformModel.repoPath("deployments"))
                .isEqualTo("services/qits-deployments-platform-service");
        assertThat(PlatformModel.repoPath("artifacts"))
                .isEqualTo("services/qits-artifacts-service");
        assertThat(PlatformModel.repoPath("platform-idp"))
                .isEqualTo("services/qits-idp-platform-service");
        assertThat(PlatformModel.repoPath("platform-edge"))
                .isEqualTo("services/qits-edge-platform-service");
        assertThat(PlatformModel.repoPath("spa-deployments"))
                .isEqualTo("frontends/qits-deployments-platform-frontend");
        assertThat(PlatformModel.repoPath("spa-artifacts"))
                .isEqualTo("frontends/qits-artifacts-frontend");
        // The byte plane's own, each in the directory its ROLE puts it in: one library and two
        // services, whatever plane the services are on.
        assertThat(PlatformModel.repoPath("registries"))
                .isEqualTo("libs/qits-registries-javalib");
        assertThat(PlatformModel.repoPath("platform-mirror"))
                .isEqualTo("services/qits-mirror-platform-service");
        assertThat(PlatformModel.repoPath("githost")).isEqualTo("services/qits-githost-service");
        assertThat(PlatformModel.repoPath("docs")).isEqualTo("services/qits-docs-service");
        // The container orchestrator: a service, so services/ and docker/Dockerfile, and its wire
        // alias carries the tier — one orchestrator per tier, holding that host's socket.
        assertThat(PlatformModel.repoPath("containers"))
                .isEqualTo("services/qits-containers-service");
        assertThat(PlatformModel.dockerfilePath("containers")).isEqualTo("docker/Dockerfile");
        assertThat(PlatformModel.isPlatformService("containers")).isFalse();
        assertThat(PlatformModel.wireAlias("containers", "prod")).isEqualTo("prod-qits-containers");
        assertThat(PlatformModel.pdNamePrefix("containers", "prod"))
                .isEqualTo("qits-pd-prod-qits-containers-");
        // And their two clients, added on 2026-08-11. Both MODEL spellings are live — the git
        // host's client is spa-<x>, the mirror's is platform-spa-<x> because its service is on the
        // platform plane — and both repositories now say the plane the same way.
        assertThat(PlatformModel.repoPath("spa-githost"))
                .isEqualTo("frontends/qits-githost-frontend");
        assertThat(PlatformModel.repoPath("platform-spa-mirror"))
                .isEqualTo("frontends/qits-mirror-platform-frontend");

        assertThat(PlatformModel.repoPath("ci-daemon")).isEqualTo("daemons/qits-ci-daemon");
        // The two daemons that joined the replay set on 2026-08-10. The default arm would clone
        // services/ from GitHub and the sources phase would fail on a directory nobody has.
        assertThat(PlatformModel.repoPath("workspace-daemon"))
                .isEqualTo("daemons/qits-workspace-daemon");
        assertThat(PlatformModel.repoPath("projects-daemon"))
                .isEqualTo("daemons/qits-projects-daemon");
        // The renamed repositories: the DIRECTORY is the repository's name, which is no longer the
        // application name the model is keyed by.
        assertThat(PlatformModel.repoPath("oci")).isEqualTo("images/qits-build-images-oci");
        assertThat(PlatformModel.repoPath("oci-workspace")).isEqualTo("images/qits-workspace-oci");
        // An image repository, not a service: the default arm would clone services/ from GitHub.
        assertThat(PlatformModel.repoPath("oci-postgresql")).isEqualTo("images/qits-database-oci");
        assertThat(PlatformModel.repoPath("eventstream"))
                .isEqualTo("libs/qits-eventstream-javalib");
        assertThat(PlatformModel.repoPath("spa-docs")).isEqualTo("frontends/qits-docs-frontend");
        // A model name the renames never reached is still answered, rather than left to the
        // default arm: a path that resolves to nothing clones the org's copy in silence.
        assertThat(PlatformModel.repoPath("platform-spa-artifacts"))
                .isEqualTo("frontends/qits-platform-spa-artifacts");
        // Framework glue is a lib; the wrapper has no integrations/ directory.
        assertThat(PlatformModel.repoPath("integrations-angular"))
                .isEqualTo("libs/qits-integrations-angular-jslib");
        assertThat(PlatformModel.repoPath("integrations-quarkus"))
                .isEqualTo("libs/qits-integrations-quarkus-javalib");
    }

    /**
     * <b>The repository and the application are two names now, and this is the whole of the
     * difference.</b> A phase-2 rename moves the repository — the git-host name, the clone url, the
     * wrapper entry, the checkout directory — and moves nothing the platform answers to. Getting
     * this backwards points the boot's own database at a host nothing serves.
     */
    @Test
    void aRenamedRepositoryKeepsItsApplicationName() {
        assertThat(PlatformModel.repo("oci-postgresql")).isEqualTo("qits-database-oci");
        assertThat(PlatformModel.application("oci-postgresql")).isEqualTo("qits-oci-postgresql");
        // The alias every consumer's JDBC url spells, and the container this program starts.
        assertThat(PlatformModel.wireAlias("oci-postgresql", "dev"))
                .isEqualTo("dev-qits-oci-postgresql");
        assertThat(PlatformModel.pdNamePrefix("oci-postgresql", "dev"))
                .isEqualTo("qits-pd-dev-qits-oci-postgresql-");

        assertThat(PlatformModel.repo("oci")).isEqualTo("qits-build-images-oci");
        assertThat(PlatformModel.repo("oci-workspace")).isEqualTo("qits-workspace-oci");
        assertThat(PlatformModel.repo("eventstream")).isEqualTo("qits-eventstream-javalib");
        assertThat(PlatformModel.repo("registries")).isEqualTo("qits-registries-javalib");
        assertThat(PlatformModel.repo("userflows")).isEqualTo("qits-userflows-javalib");
        assertThat(PlatformModel.repo("spa-ui-components")).isEqualTo("qits-ui-components-jslib");
        assertThat(PlatformModel.repo("integrations-angular"))
                .isEqualTo("qits-integrations-angular-jslib");
        assertThat(PlatformModel.repo("integrations-quarkus"))
                .isEqualTo("qits-integrations-quarkus-javalib");

        // A SERVICE, and the pair that matters most: the repository says the role, the application
        // says what every peer dials, and nothing the platform answers to moved.
        assertThat(PlatformModel.repo("ci")).isEqualTo("qits-ci-service");
        assertThat(PlatformModel.application("ci")).isEqualTo("qits-ci");
        assertThat(PlatformModel.wireAlias("ci", "dev")).isEqualTo("dev-qits-ci");
        assertThat(PlatformModel.pdNamePrefix("ci", "dev")).isEqualTo("qits-pd-dev-qits-ci-");

        // A PLATFORM service, where the plane changes sides: the repository carries it as a
        // modifier before the role, the application keeps the prefix it has always answered to.
        assertThat(PlatformModel.repo("platform-idp")).isEqualTo("qits-idp-platform-service");
        assertThat(PlatformModel.application("platform-idp")).isEqualTo("qits-platform-idp");
        assertThat(PlatformModel.wireAlias("platform-idp", "dev")).isEqualTo("qits-platform-idp");

        // The deployer and the bus say no plane at all on the application side and never will —
        // PLATFORM_SERVICES is the authority, not the spelling — while their repositories do.
        assertThat(PlatformModel.repo("events")).isEqualTo("qits-events-platform-service");
        assertThat(PlatformModel.application("events")).isEqualTo("qits-events");
        assertThat(PlatformModel.wireAlias("events", "dev")).isEqualTo("qits-events");
        assertThat(PlatformModel.repo("deployments"))
                .isEqualTo("qits-deployments-platform-service");
        assertThat(PlatformModel.application("deployments")).isEqualTo("qits-deployments");
        assertThat(PlatformModel.wireAlias("deployments", "dev")).isEqualTo("qits-deployments");
    }

    /**
     * <b>THE WHOLE TABLE, spelled the way the wrapper's {@code .gitmodules} declares it.</b> This is
     * the assertion that fails on the day a rename lands in the wrapper and not here — or here and
     * not there — and a name this model gets wrong is a repository the boot creates, pushes to and
     * waits an hour for a build of.
     */
    @Test
    void everyRepositoryIsSpelledTheWayTheWrapperDeclaresIt() {
        Map<String, String> expected = new LinkedHashMap<>();
        // The environment tier's services.
        expected.put("artifacts", "qits-artifacts-service");
        expected.put("ci", "qits-ci-service");
        expected.put("configuration", "qits-configuration-service");
        expected.put("containers", "qits-containers-service");
        expected.put("docs", "qits-docs-service");
        expected.put("githost", "qits-githost-service");
        expected.put("observability", "qits-observability-service");
        expected.put("projects", "qits-projects-service");
        expected.put("stt", "qits-stt-service");
        expected.put("workspaces", "qits-workspaces-service");
        // The platform tier's, plane as a modifier before the role.
        expected.put("deployments", "qits-deployments-platform-service");
        expected.put("events", "qits-events-platform-service");
        expected.put("platform-edge", "qits-edge-platform-service");
        expected.put("platform-idp", "qits-idp-platform-service");
        expected.put("platform-maintenance", "qits-maintenance-platform-service");
        expected.put("platform-mirror", "qits-mirror-platform-service");
        expected.put("platform-orchestrator", "qits-orchestrator-platform-service");
        expected.put("platform-system", "qits-system-platform-service");
        // The frontends. Both model spellings collapse into one grammar: a client takes its
        // service's component and plane.
        expected.put("spa-artifacts", "qits-artifacts-frontend");
        expected.put("spa-ci", "qits-ci-frontend");
        expected.put("spa-configuration", "qits-configuration-frontend");
        expected.put("spa-deployments", "qits-deployments-platform-frontend");
        expected.put("spa-docs", "qits-docs-frontend");
        expected.put("spa-events", "qits-events-platform-frontend");
        expected.put("spa-githost", "qits-githost-frontend");
        expected.put("spa-observability", "qits-observability-frontend");
        expected.put("spa-projects", "qits-projects-frontend");
        expected.put("spa-workspaces", "qits-workspaces-frontend");
        expected.put("platform-spa-idp", "qits-idp-platform-frontend");
        expected.put("platform-spa-maintenance", "qits-maintenance-platform-frontend");
        expected.put("platform-spa-mirror", "qits-mirror-platform-frontend");
        expected.put("platform-spa-orchestrator", "qits-orchestrator-platform-frontend");
        expected.put("platform-spa-system", "qits-system-platform-frontend");
        // The libraries and the image builds, renamed on 2026-08-30.
        expected.put("eventstream", "qits-eventstream-javalib");
        expected.put("integrations-angular", "qits-integrations-angular-jslib");
        expected.put("integrations-quarkus", "qits-integrations-quarkus-javalib");
        expected.put("registries", "qits-registries-javalib");
        expected.put("spa-ui-components", "qits-ui-components-jslib");
        expected.put("userflows", "qits-userflows-javalib");
        expected.put("oci", "qits-build-images-oci");
        expected.put("oci-postgresql", "qits-database-oci");
        expected.put("oci-workspace", "qits-workspace-oci");
        // The three that keep the name they have: the daemons, already in the grammar.
        expected.put("ci-daemon", "qits-ci-daemon");
        expected.put("projects-daemon", "qits-projects-daemon");
        expected.put("workspace-daemon", "qits-workspace-daemon");

        // Every repository of the platform, and nothing that is not one.
        assertThat(PlatformModel.platformRepos())
                .containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((name, repo) ->
                assertThat(PlatformModel.repo(name)).as(name).isEqualTo(repo));
    }

    /**
     * <b>The renames are complete, so every repository says its role in its own name.</b> There is
     * no exception left: the home page was the last one, and it is out of the model entirely since
     * 2026-08-30. That is what lets {@code archetype} outlive the tables it still carries for the
     * model's names, and it is the assertion a half-taught rename fails: a repository left at its
     * old spelling ends in none of these.
     */
    @Test
    void noRepositoryIsLeftAtAStaleName() {
        assertThat(PlatformModel.platformRepos())
                .allSatisfy(name -> assertThat(PlatformModel.repo(name)).as(name)
                        .matches(".*-(service|frontend|daemon|oci|cli|javalib|jslib)$"));
        // And the spellings the renames replaced are gone: a name that resolves to no repository on
        // the git host creates one, pushes to it, and waits an hour for a build nobody asked for.
        assertThat(PlatformModel.platformRepos().stream().map(PlatformModel::repo))
                .doesNotContain("qits-oci", "qits-oci-postgresql", "qits-oci-workspace",
                        "qits-eventstream", "qits-blobstore", "qits-registries", "qits-userflows",
                        "qits-spa-ui-components", "qits-integrations-angular",
                        "qits-integrations-quarkus", "qits-ci", "qits-artifacts", "qits-githost",
                        "qits-docs", "qits-projects", "qits-workspaces", "qits-configuration",
                        "qits-containers", "qits-observability", "qits-stt", "qits-events",
                        "qits-deployments", "qits-platform-idp", "qits-platform-edge",
                        "qits-platform-mirror", "qits-platform-orchestrator",
                        "qits-platform-maintenance", "qits-platform-system",
                        "qits-platform-events", "qits-platform-deployments",
                        "qits-spa-ci", "qits-spa-artifacts", "qits-spa-githost", "qits-spa-docs",
                        "qits-spa-projects", "qits-spa-workspaces", "qits-spa-configuration",
                        "qits-spa-observability", "qits-spa-events", "qits-spa-deployments",
                        "qits-platform-spa-idp", "qits-platform-spa-mirror",
                        "qits-platform-spa-orchestrator", "qits-platform-spa-maintenance",
                        "qits-platform-spa-system", "qits-platform-spa-events",
                        "qits-platform-spa-deployments");
    }

    /**
     * <b>The repository's role suffix and the model's own table must give one answer.</b> The
     * archetype is derived from the MODEL name — it is what every caller has — while the phase-2
     * ruling says a repository's name decides its archetype. The two are separate derivations, so
     * this is what keeps them from drifting apart.
     */
    @Test
    void theRoleSuffixAndTheModelsTableAgreeOnEveryRepository() {
        Map<String, String> role = Map.of(
                "-service", "SERVICE", "-frontend", "FRONTEND", "-daemon", "DAEMON",
                "-oci", "IMAGE", "-cli", "CLI", "-javalib", "LIBRARY", "-jslib", "LIBRARY");
        for (String name : PlatformModel.platformRepos()) {
            String repo = PlatformModel.repo(name);
            role.entrySet().stream()
                    .filter(entry -> repo.endsWith(entry.getKey()))
                    .findFirst()
                    .ifPresent(entry -> assertThat(PlatformModel.archetype(name))
                            .as(name + " is " + repo)
                            .isEqualTo(entry.getValue()));
        }
    }

    /**
     * <b>The archetype layout's first segment IS the archetype, and that arm stays.</b> It is the
     * derivation qits-projects makes from the same file, so a wrapper of that shape has one answer
     * rather than two that can drift.
     */
    @Test
    void anArchetypeDirectoryStillAnswersForItself() {
        assertThat(PlatformModel.archetype("ci", "services/qits-ci")).isEqualTo("SERVICE");
        assertThat(PlatformModel.archetype("spa-ci", "frontends/qits-spa-ci")).isEqualTo("FRONTEND");
        assertThat(PlatformModel.archetype("ci-daemon", "daemons/qits-ci-daemon"))
                .isEqualTo("DAEMON");
        assertThat(PlatformModel.archetype("eventstream", "libs/qits-eventstream"))
                .isEqualTo("LIBRARY");
        assertThat(PlatformModel.archetype("oci", "images/qits-oci")).isEqualTo("IMAGE");
        assertThat(PlatformModel.archetype("cli-bootstrap", "cli/qits-cli-bootstrap"))
                .isEqualTo("CLI");
        // The directory beats the name, which is the point of asking it: a wrapper that puts a
        // repository somewhere its name does not imply is followed rather than second-guessed.
        assertThat(PlatformModel.archetype("spa-ui-components", "libs/qits-spa-ui-components"))
                .isEqualTo("LIBRARY");
    }

    /**
     * <b>Under {@code components/<component>/<repo>} the first segment says nothing about kind, so
     * the NAME answers.</b> The component of a repository is not derivable from its name —
     * qits-spa-ci belongs to qits-ci — which is why nothing here tries to read one.
     */
    @Test
    void aComponentPathSendsTheQuestionToTheName() {
        assertThat(PlatformModel.archetype("ci", "components/qits-ci/qits-ci")).isEqualTo("SERVICE");
        assertThat(PlatformModel.archetype("spa-ci", "components/qits-ci/qits-spa-ci"))
                .isEqualTo("FRONTEND");
        assertThat(PlatformModel.archetype("ci-daemon", "components/qits-ci/qits-ci-daemon"))
                .isEqualTo("DAEMON");
        assertThat(PlatformModel.archetype("platform-spa-mirror",
                "components/qits-mirror/qits-platform-spa-mirror")).isEqualTo("FRONTEND");
        // The names the tables carry, because nothing in them is spelled with its role.
        assertThat(PlatformModel.archetype("oci-postgresql",
                "components/qits-database/qits-oci-postgresql")).isEqualTo("IMAGE");
        assertThat(PlatformModel.archetype("registries",
                "components/qits-registries/qits-registries")).isEqualTo("LIBRARY");
        assertThat(PlatformModel.archetype("userflows",
                "components/qits-userflows/qits-userflows")).isEqualTo("LIBRARY");
        // The lib set is asked BEFORE the spa- prefix: a component library is not a frontend.
        assertThat(PlatformModel.archetype("spa-ui-components",
                "components/qits-ui-components/qits-spa-ui-components")).isEqualTo("LIBRARY");
    }

    /**
     * <b>The grammar phase 2 renames into, which is what lets this outlive the renames.</b> A
     * repository whose name carries its role needs no entry in any table here.
     */
    @Test
    void theRenamedNamesCarryTheirOwnArchetype() {
        String components = "components/qits-ci/";
        assertThat(PlatformModel.archetype("ci-service", components + "qits-ci-service"))
                .isEqualTo("SERVICE");
        assertThat(PlatformModel.archetype("ci-frontend", components + "qits-ci-frontend"))
                .isEqualTo("FRONTEND");
        assertThat(PlatformModel.archetype("ci-daemon", components + "qits-ci-daemon"))
                .isEqualTo("DAEMON");
        assertThat(PlatformModel.archetype("workspace-oci",
                "components/qits-workspaces/qits-workspace-oci")).isEqualTo("IMAGE");
        assertThat(PlatformModel.archetype("bootstrap-cli",
                "components/qits-bootstrap/qits-bootstrap-cli")).isEqualTo("CLI");
        assertThat(PlatformModel.archetype("eventstream-javalib",
                "components/qits-eventstream/qits-eventstream-javalib")).isEqualTo("LIBRARY");
        assertThat(PlatformModel.archetype("ui-components-jslib",
                "components/qits-ui-components/qits-ui-components-jslib")).isEqualTo("LIBRARY");
        // The platform tier is a modifier before the role, so the suffix still decides.
        assertThat(PlatformModel.archetype("idp-platform-service",
                "components/qits-idp/qits-idp-platform-service")).isEqualTo("SERVICE");
    }

    /**
     * Nobody has said where it sits — a repository the wrapper does not declare — so the fallback
     * layout answers, and every name of today answers the same either way.
     */
    @Test
    void anUnplacedRepositoryFallsBackToTheLayoutItsNameImplies() {
        for (String name : PlatformModel.platformRepos()) {
            assertThat(PlatformModel.archetype(name))
                    .as(name)
                    .isEqualTo(PlatformModel.archetype(name,
                            "components/qits-x/" + PlatformModel.repo(name)));
        }
        assertThat(PlatformModel.archetype("ci", null)).isEqualTo("SERVICE");
        assertThat(PlatformModel.archetype("spa-ci", null)).isEqualTo("FRONTEND");
    }

    @Test
    void noPlaneCarriesADeployRefOfItsOwn() {
        // There is no deploy ref at all any more, and that is the assertion: a release deploys and
        // both planes enter at the one designated environment. Nothing may reintroduce a per-plane
        // ref without this file saying so.
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
        assertThat(PlatformModel.pdNamePrefix("workspaces", "prod"))
                .isEqualTo("qits-pd-prod-qits-workspaces-");
        // The deployer took the platform shape on 2026-08-17, and its own container name is what
        // this run's deploy wait matches on — DeployLogStream follows the same prefix.
        assertThat(PlatformModel.pdNamePrefix("deployments", "prod"))
                .isEqualTo("qits-pd-qits-deployments-");
        assertThat(PlatformModel.pdNamePrefix("events", "prod"))
                .isEqualTo("qits-pd-qits-events-");
    }

    @Test
    void aWireAliasCarriesTheTierOnlyWhenThereIsOneToCarry() {
        // The address peers dial, and the name a cutover finds its predecessor by. The seed
        // containers are named after it, so a wrong answer here is a seed nothing can reach.
        assertThat(PlatformModel.wireAlias("ci", "prod")).isEqualTo("prod-qits-ci");
        assertThat(PlatformModel.wireAlias("workspaces", "prod")).isEqualTo("prod-qits-workspaces");
        // The deployer and the bus dropped the tier on 2026-08-17, and neither carries the plane
        // in its name yet: the repository rename comes after the local proof.
        assertThat(PlatformModel.wireAlias("deployments", "prod")).isEqualTo("qits-deployments");
        assertThat(PlatformModel.wireAlias("events", "prod")).isEqualTo("qits-events");
        assertThat(PlatformModel.wireAlias("events", "preprod")).isEqualTo("qits-events");
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
        // Eight: the nameserver left with qits-platform-dns, the deployer and the bus joined on
        // 2026-08-17, the technical processes service on 2026-08-21, the dependency inventory on
        // 2026-08-22 and the base system panels on 2026-08-23. A cross-environment hierarchy
        // cannot live inside one tier's deployer, which broker a service dials WAS the bus's only
        // scoping, what a deletion run reclaims is one machine's however many tiers share it, what
        // an inventory inventories is one catalog's, and a NODE has no per-tier half at all.
        assertThat(PlatformModel.PLATFORM_SERVICES).containsExactlyInAnyOrder(
                "platform-edge", "platform-idp", "platform-mirror", "deployments", "events",
                "platform-orchestrator", "platform-maintenance", "platform-system");
        // The byte-plane split settled the pair that used to be here: the caches were the only
        // reason either could not be per-tier, and they are qits-platform-mirror now.
        assertThat(PlatformModel.isPlatformService("artifacts")).isFalse();
        assertThat(PlatformModel.isPlatformService("docs")).isFalse();
        assertThat(PlatformModel.isPlatformService("githost")).isFalse();
        // Everything else is a service of the one environment. postgres is not here: it is a
        // seed-only service the train never deploys, so it left DEPLOYABLES for SEEDED_REPOS.
        assertThat(PlatformModel.DEPLOYABLES)
                .filteredOn(name -> !PlatformModel.isPlatformService(name))
                .containsExactlyInAnyOrder("observability", "stt", "projects",
                        "workspaces", "ci", "containers",
                        "artifacts", "githost", "docs", "configuration");
        // And qits-configuration is one of them rather than a platform service, which is the whole
        // point of it: two tiers sharing one configuration store would make an edit in dev an edit
        // in prod.
        assertThat(PlatformModel.isPlatformService("configuration")).isFalse();
        // postgres is neither a platform service nor a deployable — it is the seed database.
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
        // qits-projects is in it since 2026-08-21, because a repository has a NAME: it owns the
        // alias table, /git/<projectId>/<repoName> resolves through it and nowhere else, and every
        // repository of this platform is created minutes into the boot. Deployed sixth of
        // seventeen it answered nothing until half the boot was over.
        assertThat(PlatformModel.CORE).containsExactlyInAnyOrder(
                "platform-edge", "platform-mirror", "artifacts", "githost", "projects", "ci",
                "containers", "deployments", "platform-idp", "events",
                "oci-postgresql");
        // Every seed service is also deployed through the pipeline afterwards, with ONE exception:
        // postgres stays the seed service for good, because re-reading its spec from qits-githost
        // (whose storage IS postgres) is a circular dependency that crash-loops it.
        assertThat(PlatformModel.DEPLOYABLES).containsAll(
                PlatformModel.CORE.stream().filter(name -> !name.equals("oci-postgresql")).toList());
        assertThat(PlatformModel.DEPLOYABLES).doesNotContain("oci-postgresql");
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
                "platform-spa-docs",
                // And the home page, removed from the platform on 2026-08-30: the wrapper declares
                // it no more, its row and its bare are deleted, and the org copy is archived. Left
                // here it would be created and pushed on every boot, with nobody maintaining it.
                "spa-home",
                // The blob store went the same way on 2026-08-30, by MERGE rather than by
                // archival: its jar is a module of the qits-registries reactor now, under the same
                // coordinate, so the seed still publishes it — through the registries entry. The
                // repository is retired, and a name here would seed a repository nothing hosts.
                "blobstore");
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
        // qits-projects joined the seed on 2026-08-21 and has had its client all along: its
        // Dockerfile stops the build at a `test -f` on this path before the native compile.
        assertThat(PlatformModel.seedUiPath("projects"))
                .isEqualTo("service/src/main/webui/dist/qits-spa-projects/browser");
        // No placeholder, and empty is the answer that says so: a seed build must not be made to
        // require a bundle that does not exist. Two different reasons here. qits-containers serves
        // machines and has no SPA at all. qits-platform-orchestrator HAS one — its process pages
        // are qits-platform-spa-orchestrator — but it is not in the seed, so no seed image of it is
        // ever built and there is nothing to place a bundle for.
        assertThat(PlatformModel.seedUiPath("containers")).isEmpty();
        assertThat(PlatformModel.seedUiPath("platform-orchestrator")).isEmpty();
        assertThat(PlatformModel.CORE).doesNotContain("platform-orchestrator");
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
     * <b>A STORAGE ID IS MINTED, and every call mints another one.</b> That is the whole of the
     * 2026-08-21 ruling at this seam: the key qits-githost stores a repository under is an opaque
     * uuid with nothing in it that anyone above the seam says. The name lives in qits-projects'
     * alias table and nowhere else, so nothing here may be derived from one — a second project
     * holding a repository of the same name would collide in the store the moment it could be.
     * <p>
     * The memory is {@code Boot.storageId}, which mints once per repository per run and records
     * what it minted. This method is asked exactly once per bare.
     */
    @Test
    void aStorageIdIsAFreshUuidEveryTimeItIsAskedFor() {
        String first = PlatformModel.seedStorageId();
        String second = PlatformModel.seedStorageId();

        assertThat(first).isNotEqualTo(second);
        assertThat(UUID.fromString(first)).hasToString(first);
        // And it is a value qits-projects' adopt route accepts: [A-Za-z0-9][A-Za-z0-9-]{0,63}.
        assertThat(first).matches("[A-Za-z0-9][A-Za-z0-9-]{0,63}");
        // Nothing about the repository is in it. The name used to BE it.
        assertThat(first).doesNotContain("qits");
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
        // Whole, and that is how the blob store is seeded: it is a module of this reactor.
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
        // The byte-plane lib is deliberately absent until its first calver release exists: a replay
        // restores a pin, and every consumer still pins 1.0.0-SNAPSHOT, which the seed publishes
        // restore. When it joins, it goes before eventstream.
        assertThat(PlatformModel.RELEASE_PUBLISHERS).doesNotContain("registries");
    }

    @Test
    void observabilityIsFirstTheEdgeIsSecondToLastAndTheDeployerIsLast() {
        // Order matters: observability quiets the OTLP warnings earliest, and the deployer's own
        // deployment is the self-update handoff.
        assertThat(PlatformModel.DEPLOYABLES.getFirst()).isEqualTo("observability");
        assertThat(PlatformModel.DEPLOYABLES.getLast()).isEqualTo("deployments");
        // The database is NOT in the train: re-reading its spec from qits-githost (whose storage
        // is postgres) is a circular dependency, so it stays the seed service. The idp is second.
        assertThat(PlatformModel.DEPLOYABLES).doesNotContain("oci-postgresql");
        assertThat(PlatformModel.DEPLOYABLES.get(1)).isEqualTo("platform-idp");
        // The edge is the host port, so its cutover takes the CLI's own door away for a beat. It
        // goes as late as it can, before the self-update.
        assertThat(PlatformModel.DEPLOYABLES).containsSubsequence(
                "platform-edge", "deployments");
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
        // The deployer's id lost its tier with the plane move; the orchestrator is still a tier's.
        assertThat(PlatformModel.idpClients("prod"))
                .contains("qits-deployments", "prod-qits-containers");
        assertThat(PlatformModel.idpAudiences("prod"))
                .contains("qits-deployments", "prod-qits-containers");
        // Each name once. The audience list is derived from the clients now, and a duplicate would
        // be a key that says the same thing twice to a service that replaces the shipped list.
        assertThat(PlatformModel.idpAudiences("prod").split(",")).doesNotHaveDuplicates();
        assertThat(PlatformModel.RECEIVE_ONLY_APPS).containsExactly("githost", "configuration");
    }

    /**
     * <b>qits-configuration is an audience and never a client.</b> It validates the deployer's
     * bearer on every read of an application's configuration and mints nothing at all — so it holds
     * no credential, and the one thing it needs from the idp is to be a value the deployer's client
     * may ASK for. An audience no client may ask for is {@code invalid_target} rather than a call
     * that reaches the service's own gate.
     */
    @Test
    void theConfigurationServiceIsAnAudienceTheDeployerMayAskFor() {
        assertThat(PlatformModel.idpAudiences("prod")).contains("prod-qits-configuration");
        assertThat(PlatformModel.idpClients("prod")).doesNotContain("prod-qits-configuration");
        // It follows the environment name like every other id here, which is why the deployer's
        // audience is spelled from the same derivation rather than defaulted in an image.
        assertThat(PlatformModel.idpAudiences("preprod")).contains("preprod-qits-configuration");
        assertThat(PlatformModel.wireAlias("configuration", "prod"))
                .isEqualTo("prod-qits-configuration");
        assertThat(PlatformModel.repoPath("configuration"))
                .isEqualTo("services/qits-configuration-service");
    }

    /**
     * <b>Where qits-configuration sits in the train, and every neighbour is load-bearing.</b> Its
     * store is the seed postgres, already up before the train starts; after the idp, whose cutover
     * must not fall inside the deploy window of a service that validates its tokens; and before the
     * deployer's own self-update, which inherits the extras url the boot flips.
     */
    @Test
    void configurationIsDeployedAfterTheIdpAndLongBeforeTheDeployer() {
        assertThat(PlatformModel.DEPLOYABLES).containsSubsequence(
                "platform-idp", "configuration", "deployments");
        // Everything below it is deployed from what it serves, which is what proves the read.
        assertThat(PlatformModel.DEPLOYABLES.indexOf("configuration"))
                .isLessThan(PlatformModel.DEPLOYABLES.indexOf("ci"));
    }

    /**
     * <b>The technical processes service is a CALLER, so it is deployed after everything it
     * calls.</b> Its gc process drives qits-artifacts, qits-containers, qits-ci and the deployer,
     * and it holds a scheduler — a cutover landing while a peer is mid-cutover is a run whose steps
     * fail against a service being replaced. It still stays above the edge and the deployer,
     * because the edge's cutover takes this program's own door away and the deployer's is the
     * self-update handoff.
     */
    @Test
    void theOrchestratorIsDeployedAfterEveryPeerItCalls() {
        assertThat(PlatformModel.DEPLOYABLES).containsSubsequence(
                "artifacts", "containers", "ci", "platform-orchestrator");
        assertThat(PlatformModel.DEPLOYABLES).containsSubsequence(
                "platform-orchestrator", "platform-edge", "deployments");
        // A platform service: what a deletion run reclaims is one machine's, however many tiers
        // share it, so there is one instance and its alias carries no tier.
        assertThat(PlatformModel.isPlatformService("platform-orchestrator")).isTrue();
        assertThat(PlatformModel.wireAlias("platform-orchestrator", "prod"))
                .isEqualTo("qits-platform-orchestrator");
        assertThat(PlatformModel.wireAlias("platform-orchestrator", "preprod"))
                .isEqualTo("qits-platform-orchestrator");
        assertThat(PlatformModel.pdNamePrefix("platform-orchestrator", "prod"))
                .isEqualTo("qits-pd-qits-platform-orchestrator-");
        // A service and its client, each in the directory its ROLE puts it in, and the Dockerfile
        // where every service keeps one.
        assertThat(PlatformModel.repoPath("platform-orchestrator"))
                .isEqualTo("services/qits-orchestrator-platform-service");
        assertThat(PlatformModel.repoPath("platform-spa-orchestrator"))
                .isEqualTo("frontends/qits-orchestrator-platform-frontend");
        assertThat(PlatformModel.dockerfilePath("platform-orchestrator"))
                .isEqualTo("docker/Dockerfile");
        // Published whole: it has no module a consumer resolves.
        assertThat(PlatformModel.mavenModule("platform-orchestrator")).isEmpty();
        // The client is seeded like every other frontend and deployed by nobody: its bundle ships
        // inside the service's own image.
        assertThat(PlatformModel.SEEDED_REPOS).contains("platform-spa-orchestrator");
        assertThat(PlatformModel.DEPLOYABLES).doesNotContain("platform-spa-orchestrator");
    }

    /**
     * <b>It mints against four peers, so it holds four named clients and one credential.</b> A
     * bearer minted for one peer's audience is refused by the other three, which is why the
     * audience list it may ask for has to hold all of them — an audience a client may not ask for
     * is {@code invalid_target} rather than a call that reaches the peer's own gate.
     */
    @Test
    void theOrchestratorMintsForEveryPeerItDrives() {
        assertThat(PlatformModel.IDP_CLIENT_APPS).contains("platform-orchestrator");
        assertThat(PlatformModel.idpClients("prod")).contains("qits-platform-orchestrator");
        assertThat(PlatformModel.idpAudiences("prod")).contains(
                "prod-qits-artifacts", "prod-qits-containers", "prod-qits-ci", "qits-deployments");
        // Its own audience too: every route of it is behind the machine gate, so the deployer's
        // health probe and a person's browser both arrive at a service that validates.
        assertThat(PlatformModel.idpAudiences("prod")).contains("qits-platform-orchestrator");
        // The secret is recorded under the APPLICATION's key, which is what the templates spell.
        assertThat("IDP_SECRET_" + PlatformModel.clientKey("platform-orchestrator"))
                .isEqualTo("IDP_SECRET_PLATFORM_ORCHESTRATOR");
    }

    /**
     * <b>The dependency inventory is a READER, so it is deployed after everything it reads.</b> It
     * takes the catalog from qits-projects, the manifests from qits-githost, the internal versions
     * from qits-artifacts and the external ones from qits-platform-mirror, and it asks qits-ci to
     * apply a bump. It holds a scheduler, so a cutover landing inside a peer's window is a scan
     * whose reads fail against a service being replaced. It stays above the edge and the deployer
     * for the reason everything does.
     */
    @Test
    void theDependencyInventoryIsDeployedAfterEveryPeerItReads() {
        assertThat(PlatformModel.DEPLOYABLES).containsSubsequence(
                "projects", "platform-mirror", "artifacts", "githost", "ci",
                "platform-maintenance");
        assertThat(PlatformModel.DEPLOYABLES).containsSubsequence(
                "platform-maintenance", "platform-edge", "deployments");
        // NOT A SEED SERVICE, and that is the whole difference from the orchestrator's rollout:
        // nothing calls it, so nothing waits on it. No seed block, no seed image, no placeholder
        // bundle — the train restores it from its last release like any other application.
        assertThat(PlatformModel.CORE).doesNotContain("platform-maintenance");
        assertThat(PlatformModel.seedUiPath("platform-maintenance")).isEmpty();
        // It carries version identity, so a restoring boot stands it at its newest release rather
        // than shipping main.
        assertThat(PlatformModel.carriesVersionIdentity("platform-maintenance")).isTrue();
        // A platform service: one inventory of one catalog, so its alias carries no tier.
        assertThat(PlatformModel.isPlatformService("platform-maintenance")).isTrue();
        assertThat(PlatformModel.wireAlias("platform-maintenance", "prod"))
                .isEqualTo("qits-platform-maintenance");
        assertThat(PlatformModel.wireAlias("platform-maintenance", "preprod"))
                .isEqualTo("qits-platform-maintenance");
        assertThat(PlatformModel.pdNamePrefix("platform-maintenance", "prod"))
                .isEqualTo("qits-pd-qits-platform-maintenance-");
        // A service and its client, each in the directory its ROLE puts it in.
        assertThat(PlatformModel.repoPath("platform-maintenance"))
                .isEqualTo("services/qits-maintenance-platform-service");
        assertThat(PlatformModel.repoPath("platform-spa-maintenance"))
                .isEqualTo("frontends/qits-maintenance-platform-frontend");
        assertThat(PlatformModel.archetype("platform-maintenance")).isEqualTo("SERVICE");
        assertThat(PlatformModel.archetype("platform-spa-maintenance")).isEqualTo("FRONTEND");
        assertThat(PlatformModel.dockerfilePath("platform-maintenance"))
                .isEqualTo("docker/Dockerfile");
        // Published whole: it has no module a consumer resolves.
        assertThat(PlatformModel.mavenModule("platform-maintenance")).isEmpty();
        // The client is seeded like every other frontend and deployed by nobody: its bundle ships
        // inside the service's own image, and it publishes no release for a replay to restore.
        assertThat(PlatformModel.SEEDED_REPOS).contains("platform-spa-maintenance");
        assertThat(PlatformModel.DEPLOYABLES).doesNotContain("platform-spa-maintenance");
        assertThat(PlatformModel.RELEASE_PUBLISHERS).doesNotContain("platform-maintenance",
                "platform-spa-maintenance");
    }

    /**
     * <b>It mints for three guarded peers and needs one CLAIM the others do not.</b> qits-ci's
     * trigger route calls {@code requireProject("*")}, which passes only for a token granted every
     * project — so a bump naming ONE repository is refused without the wildcard. The registries it
     * reads versions from are unguarded on qits-net, so it holds no client for them.
     */
    @Test
    void theDependencyInventoryMintsForItsThreeGuardedPeers() {
        assertThat(PlatformModel.IDP_CLIENT_APPS).contains("platform-maintenance");
        assertThat(PlatformModel.idpClients("prod")).contains("qits-platform-maintenance");
        assertThat(PlatformModel.idpAudiences("prod")).contains(
                "prod-qits-projects", "prod-qits-githost", "prod-qits-ci");
        // Its own audience too: every route of it is behind the machine gate, so the deployer's
        // health probe arrives at a service that validates.
        assertThat(PlatformModel.idpAudiences("prod")).contains("qits-platform-maintenance");
        // The secret is recorded under the APPLICATION's key, which is what the templates spell.
        assertThat("IDP_SECRET_" + PlatformModel.clientKey("platform-maintenance"))
                .isEqualTo("IDP_SECRET_PLATFORM_MAINTENANCE");
    }

    /**
     * <b>The base system panels call NO peer, so nothing in the train is a dependency of them.</b>
     * Every answer is the local docker daemon's. They are last of the platform-tier applications
     * for a different reason: their own cutover drops every terminal an operator has open, so it
     * lands after the rest of the train rather than in the middle of it.
     */
    @Test
    void theBaseSystemPanelsAreDeployedLastOfThePlatformTierAndCallNoPeer() {
        assertThat(PlatformModel.DEPLOYABLES).containsSubsequence(
                "containers", "ci", "platform-orchestrator", "platform-maintenance",
                "platform-system");
        assertThat(PlatformModel.DEPLOYABLES).containsSubsequence(
                "platform-system", "platform-edge", "deployments");
        // NOT A SEED SERVICE: nothing calls it, so nothing waits on it.
        assertThat(PlatformModel.CORE).doesNotContain("platform-system");
        assertThat(PlatformModel.seedUiPath("platform-system")).isEmpty();
        // It carries version identity, so a restoring boot stands it at its newest release.
        assertThat(PlatformModel.carriesVersionIdentity("platform-system")).isTrue();
        // A platform service: what it shows is a MACHINE, which has no per-tier half.
        assertThat(PlatformModel.isPlatformService("platform-system")).isTrue();
        assertThat(PlatformModel.wireAlias("platform-system", "prod"))
                .isEqualTo("qits-platform-system");
        assertThat(PlatformModel.wireAlias("platform-system", "preprod"))
                .isEqualTo("qits-platform-system");
        assertThat(PlatformModel.pdNamePrefix("platform-system", "prod"))
                .isEqualTo("qits-pd-qits-platform-system-");
        // A service and its console, each in the directory its ROLE puts it in.
        assertThat(PlatformModel.repoPath("platform-system"))
                .isEqualTo("services/qits-system-platform-service");
        assertThat(PlatformModel.repoPath("platform-spa-system"))
                .isEqualTo("frontends/qits-system-platform-frontend");
        assertThat(PlatformModel.archetype("platform-system")).isEqualTo("SERVICE");
        assertThat(PlatformModel.archetype("platform-spa-system")).isEqualTo("FRONTEND");
        assertThat(PlatformModel.dockerfilePath("platform-system")).isEqualTo("docker/Dockerfile");
        // Published whole: it has no module a consumer resolves.
        assertThat(PlatformModel.mavenModule("platform-system")).isEmpty();
        // The console is seeded like every other frontend and deployed by nobody: its bundle ships
        // inside the service's own image, and it publishes no release for a replay to restore.
        assertThat(PlatformModel.SEEDED_REPOS).contains("platform-spa-system");
        assertThat(PlatformModel.DEPLOYABLES).doesNotContain("platform-spa-system");
        assertThat(PlatformModel.RELEASE_PUBLISHERS).doesNotContain("platform-system",
                "platform-spa-system");
    }

    /**
     * <b>Its client exists to be a DOCKER CREDENTIAL, which is the third of its kind.</b> It mints
     * against no peer — it has none — but the glances image its Overview terminal runs is pulled
     * through the platform mirror, and the edge has granted no anonymous read since 2026-08-14. So
     * it gets a client and a secret for the same reason the deployer and the container
     * orchestrator did: the pull has to name the service that was refused.
     */
    @Test
    void theBaseSystemPanelsHoldAClientBecauseTheyPullGlancesThroughTheMirror() {
        assertThat(PlatformModel.IDP_CLIENT_APPS).contains("platform-system");
        assertThat(PlatformModel.idpClients("prod")).contains("qits-platform-system");
        // Its own audience: every route of it is behind the machine gate, so the deployer's health
        // probe arrives at a service that validates.
        assertThat(PlatformModel.idpAudiences("prod")).contains("qits-platform-system");
        // The secret is recorded under the APPLICATION's key, which is what the templates spell.
        assertThat("IDP_SECRET_" + PlatformModel.clientKey("platform-system"))
                .isEqualTo("IDP_SECRET_PLATFORM_SYSTEM");
        // And the alias the templates spell for its gate.
        assertThat(PlatformModel.modelTokens("prod"))
                .containsEntry("ALIAS_PLATFORM_SYSTEM", "qits-platform-system")
                .containsEntry("CLIENT_KEY_PLATFORM_SYSTEM", "QITS_PLATFORM_SYSTEM");
    }

    @Test
    void aClientIdIsAWireAliasSoItFollowsTheEnvironment() {
        // The id is part of the config KEY, so a client the deployment spells differently from
        // the token request is invalid_client and nothing says it was a typo. qits-projects
        // joined for orchestration round 2: its agent containers start through qits-containers.
        assertThat(PlatformModel.idpClients("prod")).containsExactly(
                "prod-qits-bootstrap", "prod-qits-ci", "prod-qits-artifacts", "prod-qits-workspaces",
                "prod-qits-projects", "qits-deployments",
                "prod-qits-containers", "prod-qits-edge", "qits-platform-orchestrator",
                "qits-platform-maintenance", "qits-platform-system");
        // The clients, then the receive-only applications: the git host, which validates and mints
        // nothing, and qits-configuration, which the deployer asks for on every deployment.
        assertThat(PlatformModel.idpAudiences("prod")).isEqualTo(
                "prod-qits-bootstrap,prod-qits-ci,prod-qits-artifacts,prod-qits-workspaces,"
                        + "prod-qits-projects,qits-deployments,prod-qits-containers,"
                        + "prod-qits-edge,qits-platform-orchestrator,qits-platform-maintenance,"
                        + "qits-platform-system,"
                        + "prod-qits-githost,prod-qits-configuration");
        // Every one of them follows the environment now: the artifacts client was the one platform
        // id in this list, and the byte-plane split made that service a tier's again.
        // Every one but the deployer's, whose service belongs to no tier and so takes no name from
        // one — which is exactly what makes this a derivation rather than a list.
        assertThat(PlatformModel.idpClients("preprod")).containsExactly(
                "preprod-qits-bootstrap", "preprod-qits-ci", "preprod-qits-artifacts", "preprod-qits-workspaces",
                "preprod-qits-projects", "qits-deployments",
                "preprod-qits-containers", "preprod-qits-edge", "qits-platform-orchestrator",
                "qits-platform-maintenance", "qits-platform-system");
        // The two new byte services hold no client at all: the mirror has no auth surface, and the
        // git host validates a push option rather than a token.
        assertThat(PlatformModel.idpClients("prod"))
                .doesNotContain("qits-platform-mirror", "prod-qits-githost");
    }

    /**
     * <b>The generated files spell no alias and no client-id key for themselves.</b> Both shapes
     * move when an application changes plane, so both are tokens the model fills — which is what
     * makes PLATFORM_SERVICES the one place a plane is decided. Before this, the templates pasted
     * a repository name after an ENV_KEY token and the deployer's flip landed in neither file.
     */
    @Test
    void everyAliasAndClientKeyTheTemplatesNeedComesOutOfTheModel() {
        Map<String, String> tokens = PlatformModel.modelTokens("prod");

        // An environment service carries the tier; a platform one has no tier to carry.
        assertThat(tokens).containsEntry("ALIAS_CI", "prod-qits-ci")
                .containsEntry("ALIAS_DEPLOYMENTS", "qits-deployments")
                .containsEntry("ALIAS_EVENTS", "qits-events")
                .containsEntry("ALIAS_PLATFORM_IDP", "qits-platform-idp");
        // The env-var infix of the idp's per-client keys, which embed the client ID — so the
        // deployer's key is QITS_IDP_CLIENT_QITS_DEPLOYMENTS_SECRET with no tier in front of it.
        assertThat(tokens).containsEntry("CLIENT_KEY_CI", "PROD_QITS_CI")
                .containsEntry("CLIENT_KEY_DEPLOYMENTS", "QITS_DEPLOYMENTS")
                .containsEntry("CLIENT_KEY_EDGE", "PROD_QITS_EDGE");
        // Every application, so a service added to the model needs no second edit here.
        assertThat(tokens.keySet())
                .containsAll(PlatformModel.platformRepos().stream()
                        .map(app -> "ALIAS_" + PlatformModel.clientKey(app)).toList());
        // And it follows the environment name, which the templates cannot.
        assertThat(PlatformModel.modelTokens("preprod"))
                .containsEntry("ALIAS_CI", "preprod-qits-ci")
                .containsEntry("ALIAS_EVENTS", "qits-events");
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
        assertThat(PlatformModel.carriesVersionIdentity("spa-projects")).isFalse();
        assertThat(PlatformModel.carriesVersionIdentity("ci-daemon")).isFalse();
        // The seed database too: it is not deployed and nobody pins a postgres release, so its
        // seed image builds from main.
        assertThat(PlatformModel.carriesVersionIdentity("oci-postgresql")).isFalse();
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
