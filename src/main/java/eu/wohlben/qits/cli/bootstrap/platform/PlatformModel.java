package eu.wohlben.qits.cli.bootstrap.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Which repositories the platform is made of, and what each one is to the bootstrap. Ported from
 * the lists at the top of {@code qits-local-up.sh}, comments included: they are the reason the
 * order is what it is.
 * <p>
 * A <b>name</b> here is the repository's name without the {@code qits-} prefix, and it is also the
 * deployer's application name — {@code platform-idp} is qits-platform-idp is the application
 * {@code qits-platform-idp}. The 2026-08-08 rename moved the plane INTO the repository names, so a
 * name now says which plane its service is on and nothing else has to. The byte-plane split moved
 * three of them the other way on 2026-08-10: {@code artifacts}, {@code docs} and {@code githost}
 * are environment services and say so by carrying no plane at all.
 */
public final class PlatformModel {

    /**
     * The seed: hand-built for the FIRST boot only. On later runs any of these already replaced by
     * a qits-deployments deployment is skipped at compose-up, and the deploy loop hands the rest
     * over.
     * <p>
     * qits-platform-idp is in here because every service that enforces machine auth is: a seed ci
     * that cannot reach an issuer refuses this bootstrap's very first authenticated call.
     * qits-deployments is in here because it owns the topology AND the docker socket — nothing can
     * create the environment or deploy anything until it answers. It is one component: it replaces
     * the qits-cd and qits-serviceregistry pair the seed used to carry.
     * <p>
     * qits-platform-edge joined on 2026-08-08 and is the reason the set grew rather than moved:
     * the EDGE binds the host's only published port and projects deployment endpoints. The CLI
     * uses fixed service aliases until that projection is authoritative; a browser uses the
     * published edge port.
     * <p>
     * qits-oci-postgresql is in here because qits-deployments refuses to boot without the database
     * it holds. It is the one member that is not a service of this platform's own making — the
     * image adds nothing to upstream postgres — and it is still hand-built and hand-started for
     * the same reason as the rest: nothing can deploy it until the deployer answers.
     * <p>
     * <b>qits-events joined on 2026-08-10, because it is the BUS and the bus is now the only road a
     * green build takes.</b> The direct ci -&gt; deployer announcement is retired; what is left is
     * ci -&gt; outbox -&gt; qits-events -&gt; the deployer's durable subscriber. Every hop of that
     * chain has to exist before the FIRST deploy phase, and the pipeline used to bring the bus up
     * at phase 46 — six deployables after the first one announced. A seeded bus is one more seed
     * image and one more database; the alternative is a bootstrap whose first six deployments are
     * announced into nothing.
     * <p>
     * <b>The byte plane split into three on 2026-08-10, and all three are in the seed.</b>
     * qits-artifacts is what qits-platform-artifacts was minus the caches and minus git, and it is
     * an environment service again. qits-platform-mirror holds the pull-through caches, so it is
     * where every third-party byte a build resolves comes from — a seed publish included, which is
     * why it starts before the first one. qits-githost is the git host, and the first push of the
     * boot is a push to it: nothing can be built from a repository nothing hosts.
     * <p>
     * <b>qits-containers joined on 2026-08-11, and it is in the seed because qits-ci is.</b> It is
     * the platform's container orchestrator: one service holds the docker socket and every module
     * that needs a workload asks it, instead of each one shelling {@code docker run} with a
     * vocabulary of its own. qits-ci hands it the step containers, so a ci that starts a pipeline
     * before the orchestrator answers has nowhere to run a step — which is why this service is
     * HEALTHY BEFORE THE FIRST PIPELINE, in the seed and in the deploy order both.
     */
    public static final List<String> CORE = List.of(
            "platform-edge", "platform-mirror", "artifacts", "githost", "ci",
            "containers", "deployments", "platform-idp", "events",
            "oci-postgresql");

    /**
     * Everything the platform deploys through itself. Order matters: observability first (quiets
     * OTLP warnings earliest), idp next (every later application's tokens are minted by it, and
     * its own cutover must not fall inside another application's deploy window), the seed's own
     * repos last, qits-deployments at the very end — its deployment is the self-update handoff.
     * <p>
     * <b>qits-platform-edge is second to last, immediately before the deployer.</b> It is the door,
     * so its cutover is the one deployment that takes THIS PROGRAM's own door away for a beat:
     * every remaining public request depends on it. Three consequences decide the position:
     * <ul>
     *   <li><b>Not early.</b> An edge deployed first would carry every later phase's traffic on a
     *       binder this run has not yet watched serve anything. Late, the applications it fronts
     *       are already deployed and a broken edge is the only thing left to look at.
     *   <li><b>Before qits-deployments.</b> The deployer's own deployment is the self-update
     *       handoff and has to stay last — and the edge's cutover is better performed by the seed
     *       deployer that has already done every other cutover in this run than by a successor
     *       whose first act it would be.
     * </ul>
     * The edge's own deploy wait needs no host port: it is a platform service, so its liveness is
     * read from {@code docker ps} rather than from a deployment row.
     * <p>
     * <b>qits-oci-postgresql is second, right after observability, and its position is the same
     * argument the idp's is.</b> It is the deployer's own database, so its cutover must never be
     * queued beside a consumer's: it goes before everything that might one day hold a connection
     * to it. The cutover replaces the compose-seeded postgres under the deployer's own feet —
     * stop-before-start frees the volume and the host port, the successor mounts the same volume,
     * {@code pg_isready} gates it, and the deployer's pool reconnects after the blip because a
     * Plan is plain values and no deployment reads the database inside the docker window. The same
     * self-referential class as the registry pulling its own successor before stopping itself.
     * <p>
     * <b>The byte plane's three sit together, and their order inside it is forced.</b>
     * qits-platform-mirror is first of the three: every image build and every dependency
     * resolution after it goes through the mirror, so its cutover belongs before the services whose
     * builds it feeds rather than in the middle of them. qits-githost comes after qits-artifacts
     * and before qits-ci, because ci reads pipeline config out of the git host and clones from it —
     * and because the githost's own deployment is the one that re-hosts the repository this train
     * pushes to, the same self-referential class as postgres and the deployer.
     * <p>
     * <b>qits-containers is immediately BEFORE qits-ci, and that pair's order is forced.</b> ci runs
     * every pipeline step as a container it asks this service for, so a ci cutover landing while the
     * orchestrator is mid-cutover is a pipeline with nowhere to run. Deploying the orchestrator first
     * puts its window before ci's rather than inside it, and leaves the run's own remaining
     * deployments — the edge and the deployer — behind a ci that already has a working step host.
     */
    public static final List<String> DEPLOYABLES = List.of(
            "observability", "oci-postgresql", "platform-idp", "stt", "projects", "workspaces",
            "events", "docs", "platform-mirror", "artifacts", "githost",
            "containers", "ci", "platform-edge", "deployments");

    /**
     * The deployables on the PLATFORM plane: one instance for the whole platform, deployed once
     * and joined to every environment's networks, rather than one copy per tier. The authority is
     * each repo's {@code .config/qits/deployments.yml} ({@code deployment_target: platform}); this
     * list is what tells the bootstrap which container name to expect and which wire alias to dial.
     * <p>
     * The word used to be 'singleton'. It named a cardinality where what is being said is which
     * plane a service lives on.
     * <p>
     * <b>The set shrank to four on 2026-08-08 and every member now says so in its own name.</b>
     * qits-ci, qits-events, qits-projects and qits-observability are environment services. What is left is
     * what genuinely cannot be per-tier — the edge (one host port), the idp (one issuer and one
     * signing key), qits-platform-artifacts (one registry, one git host, one blob store) and
     * qits-platform-docs (one docs repository inside that store, so a second reader per tier would
     * be two front doors onto one shelf).
     * <p>
     * <b>The byte-plane split settled that pair on 2026-08-10, and only the caches stayed up
     * here.</b> qits-platform-artifacts held the pull-through caches, and THAT was the whole reason
     * it could not be per-tier: a cache of Maven Central is one cache for a machine however many
     * tiers it runs. Those caches are qits-platform-mirror now, so the hosted registries went back
     * to being an environment service (qits-artifacts) and the docs reader followed the shelf it
     * reads (qits-docs). The git host left with them, as qits-githost, and it is an environment
     * service too: every one of its consumers already was one.
     * <p>
     * <b>It grew to five with a nameserver on 2026-08-09 and is back to three</b>: qits-platform-dns
     * is gone, and the platform serves no dns of its own. A domain's records are held by an external
     * provider now.
     * <p>
     * There is no platform deploy ref any more. Both planes answer the same question of a green
     * build — does an environment listen to this ref — so {@code environment/<name>} is the whole
     * set and {@code platform/main} is retired.
     */
    public static final List<String> PLATFORM_SERVICES = List.of(
            "platform-edge", "platform-idp", "platform-mirror");

    /**
     * Repositories that need a repository on the platform git host and a main push, but are not
     * applications of qits-deployments. qits-projects can then inventory them, qits-workspaces can
     * release them, and qits-ci can discover their event pipelines.
     * <p>
     * ci-daemon belongs here and nowhere else. It ships no image and no health endpoint, so it is
     * not a deployable — but its binary is an ordinary release-train artifact, and a release train
     * needs the repository on the git host.
     * <p>
     * <b>The three image publishers joined on 2026-08-10</b> for that same reason. qits-oci-workspace,
     * qits-workspace-daemon and qits-projects-daemon publish versioned docker images the platform
     * pins — qits/workspace-base, qits/workspace, qits/projects-daemon, qits/project-agent — and none
     * of them is an application of the deployer: an image nothing runs as a container has no
     * deployments.yml and no health endpoint. They need the repository, the history and the release
     * replay below, and nothing else.
     * <p>
     * <b>qits-blobstore and qits-registries joined on 2026-08-10</b>, and they are the byte plane's
     * own libraries: the content-addressed blob store, and one Maven module per registry format.
     * Three services build against them — qits-artifacts, qits-platform-mirror and qits-githost —
     * and none of those builds can run on the platform until the jars are IN the platform's Maven
     * registry, which is what a repository plus a release replay gets them.
     * <p>
     * <b>qits-spa-githost and qits-platform-spa-mirror joined on 2026-08-11</b>, the last two byte
     * services to grow a client. They are here for the reason every other frontend is: a checkout
     * this run can clone, a repository on the git host, and a main history — so qits-projects
     * inventories them and qits-workspaces can release them. Neither is a release publisher and
     * neither is a deployable: their bundles ship inside qits-githost's and qits-platform-mirror's
     * own images, as webui submodules.
     */
    public static final List<String> SEEDED_REPOS = List.of(
            "oci", "ci-daemon", "eventstream", "blobstore", "registries", "spa-ui-components",
            "userflows", "spa-docs", "spa-deployments",
            "integrations-angular", "integrations-quarkus", "spa-home", "spa-projects",
            "spa-workspaces", "spa-artifacts", "spa-observability", "spa-events",
            "spa-ci", "spa-githost", "platform-spa-idp", "platform-spa-mirror",
            "oci-workspace", "workspace-daemon", "projects-daemon");

    /**
     * The publishers whose released versions the platform pins, replayed on a fresh platform because
     * a pin is only as good as the registry behind it. <b>Order is dependency order</b>, and this
     * list is where that order lives — {@code BootstrapPlan} makes one phase per entry, in this
     * sequence.
     * <p>
     * The first four are the Maven and npm packages the wrapper's builds install. The last three are
     * DOCKER IMAGES, added on 2026-08-10 after a fresh registry was measured to hold no
     * qits/workspace-base at all, which fails every workspace launch:
     * <ul>
     *   <li><b>qits-oci-workspace strictly before qits-workspace-daemon.</b> The daemon's
     *       {@code docker/Dockerfile} carries {@code ARG WORKSPACE_BASE=…/qits/workspace-base:<pin>}
     *       and its build PULLS that image through the registry — which the base's own replay is what
     *       fills. The other order builds against a tag the registry has never seen.
     *   <li><b>qits-projects-daemon last</b>, and for the same reason: its layered
     *       {@code qits/project-agent} build reads the pin out of
     *       {@code .config/qits/workspace-base.version} and passes it as {@code --build-arg BASE}.
     *       It is independent of qits-workspace-daemon; only the base has to precede it.
     * </ul>
     * <p>
     * <b>The byte-plane libraries are NOT here yet, deliberately.</b> A replay restores a pin, and
     * nothing pins a calver of qits-blobstore or qits-registries: every consumer still names
     * {@code 1.0.0-SNAPSHOT}, which the seed publishes restore. They join this list — registries
     * after blobstore, both before qits-eventstream — the moment their first releases are cut and
     * the consumer poms move onto the calvers.
     */
    public static final List<String> RELEASE_PUBLISHERS =
            List.of("spa-ui-components", "integrations-angular", "eventstream",
                    "integrations-quarkus",
                    "oci-workspace", "workspace-daemon", "projects-daemon");

    private PlatformModel() {
    }

    /** Every pipeline repository: the deployables and the seeded ones. */
    public static List<String> platformRepos() {
        List<String> all = new ArrayList<>(DEPLOYABLES);
        all.addAll(SEEDED_REPOS);
        return List.copyOf(all);
    }

    /**
     * Where a repository sits in the wrapper repository, by the role it plays.
     * <p>
     * <b>A wrong path here used to be silent.</b> The sources phase fell back to GitHub whenever
     * the wrapper path was not a checkout, so a misspelling ignored local commits and cloned last
     * week's platform instead. It is loud now: {@code SeedPhases.sources} fails when the directory
     * EXISTS, holds something and is not a checkout. An absent directory is answered by the org
     * URL, and so is an EMPTY one — git leaves one of those at every gitlink of a wrapper cloned
     * without its submodules, which is what a cold start has, and it hides no local work. The
     * rule below was walked against the wrapper on 2026-08-08 and every name in this file resolves
     * to a real checkout, so there is nothing left to special-case — but a rename that outruns
     * this method now stops the boot in the first minute rather than deploying the wrong sha.
     */
    public static String repoPath(String name) {
        return switch (name) {
            case "ci-daemon", "workspace-daemon", "projects-daemon" -> "daemons/qits-" + name;
            case "oci", "oci-postgresql", "oci-workspace" -> "images/qits-" + name;
            // Framework glue is shared code, so the integrations sit in libs/ like any other lib —
            // and so do the byte plane's two, which are libraries by the same test: three services
            // consume them and none of them is deployed.
            case "eventstream", "blobstore", "registries", "spa-ui-components", "userflows",
                 "integrations-angular", "integrations-quarkus" -> "libs/qits-" + name;
            // Anything served at a URL is a frontend, whether it is spelled qits-spa-<x> or
            // qits-platform-spa-<x>. Both spellings are live: the byte-plane split renamed two the
            // first way (qits-spa-artifacts, qits-spa-docs) and qits-platform-spa-mirror was born
            // the second way, because its service is on the platform plane. The second arm also
            // catches a wrapper checked out before that rename, whose directories still carry the
            // old names — and a path that resolves to nothing clones the org's copy in silence.
            default -> name.startsWith("spa-") || name.startsWith("platform-spa-")
                    ? "frontends/qits-" + name
                    : "services/qits-" + name;
        };
    }

    /**
     * Where a repository keeps the Dockerfile its seed image is built from, relative to its own
     * root.
     * <p>
     * A service keeps it in {@code docker/} beside the other build files. An image repository IS
     * the Dockerfile, so it keeps it at the root — and its own pipeline config says
     * {@code -f Dockerfile}, which is the answer this method has to agree with. The path used to
     * be a literal in {@code SeedPhases.seedImage}, where the first repository that spelled it
     * differently would have failed with "no such file" minutes into a run.
     */
    public static String dockerfilePath(String name) {
        return switch (name) {
            case "oci-postgresql" -> "Dockerfile";
            default -> "docker/Dockerfile";
        };
    }

    /**
     * The maven modules of a repository the seed publishes, comma-separated as maven's own
     * {@code -pl} takes them, or empty when it publishes the repository whole.
     * <p>
     * <b>qits-githost was the first entry, and the reason is what its {@code githost-events} module
     * is.</b> That module is a vocabulary: four records and one dependency (qits-eventstream), no
     * container. qits-ci and qits-projects consume it and nothing else of the git host — so the
     * seed owes them that jar and none of the rest. Publishing the repository whole would build
     * the git host's own service to hand over a data module, which is minutes of native-image
     * work for bytes nobody asked for, and would put a service jar in the registry that only its
     * own image ever loads.
     * <p>
     * <b>qits-containers is the same shape with two modules instead of one.</b> Its consumers depend
     * on {@code qits-containers-client} — the wire records and the HttpClient behind them — and
     * {@code qits-containers-core} is what the client's own reactor is built beside; its
     * {@code service} module is the deployable, a native image nobody resolves. So the seed publishes
     * the two libraries and leaves the service to its own image build, which saves a native compile
     * inside a maven container and keeps a jar only one image loads out of the registry.
     * <p>
     * {@code SeedPhases} turns this into {@code -pl <modules> -am}, and {@code -am} is not optional:
     * it carries the ROOT POM with the modules, and an artifact whose parent the registry does not
     * hold resolves nowhere.
     */
    public static String mavenModule(String name) {
        return switch (name) {
            case "githost" -> "githost-events";
            case "containers" -> "core,client";
            default -> "";
        };
    }

    public static String repo(String name) {
        return "qits-" + name;
    }

    public static boolean isPlatformService(String name) {
        return PLATFORM_SERVICES.contains(name);
    }

    /**
     * The <b>wire alias</b>: the address peers dial, and the name a cutover finds its predecessor
     * by. It is the deployer's {@code PdNetworks.alias}, restated here because the seed containers
     * have to answer to the same names the deployed ones will — a run-arg that injects
     * {@code http://prod-qits-ci:8080} is wrong for the seven minutes before ci is deployed unless
     * the seed ci is already reachable under that name.
     * <ul>
     *   <li><b>An environment service</b> is {@code <env>-<app>} — {@code prod-qits-ci}. The
     *       qualifier is what lets two tiers hold one application's address on the network they
     *       share.
     *   <li><b>A platform service</b> keeps the bare application name — {@code
     *       qits-platform-idp}. There is one instance, so there is nothing to qualify it against,
     *       and the repository name carries the plane already.
     * </ul>
     */
    public static String wireAlias(String name, String envName) {
        return isPlatformService(name) ? repo(name) : envName + "-" + repo(name);
    }

    /**
     * What qits-deployments names the container it manages for this application — the twin of its
     * own {@code ContainerNames.of}. Two shapes because the model has two: an environment service
     * carries its tier's name, a platform service has no tier and so DROPS the segment rather than
     * filling it. It used to read {@code qits-pd-platform-<app>-}, which said the word twice once
     * the repositories carried the plane themselves ({@code qits-pd-platform-qits-platform-idp-}).
     * <p>
     * The prefix is {@code qits-pd-}; the retired qits-cd's was {@code qits-cd-}.
     */
    public static String pdNamePrefix(String name, String envName) {
        return isPlatformService(name)
                ? "qits-pd-" + repo(name) + "-"
                : "qits-pd-" + envName + "-" + repo(name) + "-";
    }

    /**
     * The placeholder SPA bundle a seed build needs, or empty when the service has no client.
     * Seed services only need their APIs, but their Dockerfiles consume an already-built SPA — and
     * a clean checkout has no dist directory while the hosted npm registry does not exist yet. The
     * registry answers JSON only, so it has none.
     * <p>
     * <b>Every seed service that has one is spelled out</b>, because a bundle directory is the
     * Angular project key and moves whenever its client is renamed — it does not follow the
     * repository name. The path is the one the service's Dockerfile checks with {@code test -f}, so
     * a stale spelling fails the seed build minutes in rather than at the edit.
     * <p>
     * Empty is a real answer, not a gap: qits-platform-edge serves no paths of its own at all.
     * {@code SeedPhases.seedImage} writes no placeholder for it. A seed service added without a
     * line here also gets none — and the Dockerfile's own
     * {@code test -f} names the exact path it wanted, which is the clearest failure available.
     */
    public static String seedUiPath(String name) {
        return switch (name) {
            // The Angular PROJECT key names this directory, not the repository. The two agreed
            // again on 2026-08-13, when the project inside qits-spa-artifacts took the repository's
            // post-split name; they still move separately, so this path follows the project.
            case "artifacts" -> "service/src/main/webui/dist/qits-spa-artifacts/browser";
            case "deployments" -> "service/src/main/webui/dist/qits-spa-deployments/browser";
            case "ci" -> "service/src/main/webui/dist/qits-spa-ci/browser";
            case "events" -> "service/src/main/webui/dist/qits-spa-events/browser";
            // THE LAST TWO BYTE SERVICES TO GROW A CLIENT, on 2026-08-11. Both Dockerfiles took
            // qits-artifacts' prebuilt-dist shape in the same change: Quinoa's install and build
            // are neutered, the image STAGES a bundle built before it, and a `test -f` on the path
            // below stops the build rather than shipping a service whose own segment 404s. So a
            // seed image of either now needs a placeholder where it needed none the week before.
            //
            // The two paths differ in their FIRST segment, not by accident: qits-githost is a
            // reactor and its application is the `service` module, while qits-platform-mirror is
            // one module and its webui sits at the root.
            case "githost" -> "service/src/main/webui/dist/qits-spa-githost/browser";
            case "platform-mirror" -> "src/main/webui/dist/qits-platform-spa-mirror/browser";
            // The idp grew its login/register client on 2026-08-14, in the prebuilt-dist shape
            // like the rest — found by the first bare-server boot, whose seed build stopped at
            // the Dockerfile's `test -f` on this path.
            case "platform-idp" -> "service/src/main/webui/dist/qits-platform-spa-idp/browser";
            default -> "";
        };
    }

    /**
     * The static clients qits-platform-idp seeds from config, for this environment. Every one of
     * them gets a secret: a client without one is refused {@code invalid_client} exactly like a
     * wrong one, so an unused client costs nothing and a used one that was forgotten costs a
     * debugging session.
     * <p>
     * <b>A client id is a wire alias</b>, which is why this is a method and not a constant: an
     * environment service's identity carries its tier ({@code prod-qits-ci}), a platform service's
     * does not ({@code qits-platform-idp}). With the default environment name this is
     * exactly the list qits-platform-idp ships. With another one it follows, which the shipped
     * list cannot — and the id is part of the config KEY
     * ({@code qits.idp.client.<id>.secret}), so a client the deployment spells differently from
     * the token request is {@code invalid_client} with nothing in any log to say why.
     * <p>
     * qits-cd is gone for the opposite reason to the one that kept it: the client ids all moved on
     * 2026-08-08, so no id in this list is one a pre-rename platform would recognise anyway.
     */
    public static List<String> idpClients(String envName) {
        return IDP_CLIENT_APPS.stream().map(app -> wireAlias(app, envName)).toList();
    }

    /**
     * The APPLICATIONS behind those clients. The generated files are keyed by this name rather than
     * by the client id — {@code IDP_SECRET_CI}, not {@code IDP_SECRET_PROD_QITS_CI} — because the
     * id moves with the environment name and a placeholder cannot be spelled with a value that is
     * not known until the run starts.
     * <p>
     * <b>qits-artifacts is on this list under its own name and its id now carries the tier</b>: the
     * byte-plane split made it an environment service, so its client is {@code <env>-qits-artifacts}
     * where it used to be the bare qits-platform-artifacts. Every key derived from it moves with it,
     * which is exactly what a client id being a wire alias means.
     * <p>
     * <b>The two new byte services hold no client at all.</b> qits-platform-mirror has no auth
     * surface — it serves cached third-party bytes to anonymous clients and mints nothing — and
     * qits-githost validates but does not mint, so it remains receive-only. {@code bootstrap} is
     * the exception that proves the distinction: it is not a deployable application, but it does
     * make the first protected lifecycle calls and pushes before any service can do so.
     * <p>
     * <b>qits-deployments and qits-containers joined on 2026-08-14, and they are the PULLERS.</b>
     * Both were validate-only for as long as a registry read was anonymous; the flip made a pull
     * an authenticated request, and the thing that authenticates it is a docker {@code config.json}
     * holding a client id and a secret. So each one needs a credential of its own — the identity in
     * the file is the puller's, never a borrowed one, because a refused pull has to name the
     * service that was refused. Neither has a quarkus-oidc-client extension and neither asks the
     * idp for anything: the docker CLI performs the Bearer dance at the edge with these two values,
     * which is why they get a client and no {@code QUARKUS_OIDC_CLIENT_*} env.
     * <p>
     * <b>The edge joined on 2026-08-14, for the user sessions, and its entry is the one that is not
     * a repository name.</b> {@code edge} here derives {@code <env>-qits-edge}, while the service
     * itself answers to {@code qits-platform-edge} — the id is the SESSION GATE's, and a session
     * belongs to an environment, not to the one process that serves them all. The edge is told the
     * pair as {@code QITS_EDGE_SESSIONS_CLIENT_ID} and {@code _SECRET}, so the only agreement that
     * has to hold is between this generator and that service.
     * <p>
     * It gets a secret and no audience list: it introspects a browser session with Basic, the way a
     * static client calls the commission API, and asks the idp for no token at all. The full list
     * is one line away if that ever changes.
     */
    public static final List<String> IDP_CLIENT_APPS =
            List.of("bootstrap", "ci", "artifacts", "workspaces", "projects", "deployments",
                    "containers", "edge");

    /**
     * The {@code aud} values the platform's clients may ask for: every client above plus the
     * RECEIVE-ONLY applications below, which are an audience without being a client. An audience
     * a client may not ask for is {@code invalid_target}, not a silent bare call — and the key
     * REPLACES the shipped list rather than extending it, so it is restated in full.
     */
    public static String idpAudiences(String envName) {
        List<String> audiences = new ArrayList<>(idpClients(envName));
        RECEIVE_ONLY_APPS.forEach(app -> audiences.add(wireAlias(app, envName)));
        return String.join(",", audiences);
    }

    /**
     * The applications that VALIDATE a bearer and hold no client of their own. They are an audience
     * and nothing else, which is what this list adds to {@link #idpAudiences}: a caller refused
     * {@code invalid_target} never reaches the service's own gate at all.
     * <p>
     * <b>It is empty since 2026-08-14, and the seam stays.</b> qits-deployments and qits-containers
     * were its two members, and both are clients now — not because either mints a token, but
     * because each pulls images and a docker {@code config.json} is a client id and a secret. They
     * still validate exactly as they did; an audience is now something they get for being clients.
     * The list is kept because "validates and holds no credential" is a real shape for the next
     * service that has it, and because an audience that is not derived from a client has nowhere
     * else to be named.
     */
    public static final List<String> RECEIVE_ONLY_APPS = List.of("githost");

    /** The env-var spelling of a client id: uppercase, dashes as underscores. */
    public static String clientKey(String clientId) {
        return clientId.toUpperCase().replace('-', '_');
    }

    /**
     * Does this repository's output carry VERSION IDENTITY on the platform? Only those are stood at
     * a release tag by a restoring boot; every other source stays on main in both modes.
     * <p>
     * The two sets are the ones already declared above, and that is the whole rule: a
     * {@link #DEPLOYABLES} entry becomes a deployed container the deploy ref names a commit for,
     * and a {@link #RELEASE_PUBLISHERS} entry becomes a registry coordinate somebody pins. For both,
     * "the last release" is a fact the platform can state, and a seed that disagrees with it breaks
     * the successor — qits-ci's seed applied a migration its released binary had never heard of and
     * left it crash-looping.
     * <p>
     * <b>Everything else in {@link #SEEDED_REPOS} has no such fact, and pinning it to a tag is
     * actively wrong.</b> Measured on the first scoped-boot run: qits-oci's newest tag was three
     * days old and predated the passwd-backed {@code build} user its step images grew when steps
     * stopped running as root, so a maven-base seed-built from that tag could not launch a step
     * declaring {@code user: build} — "unable to find user build: no matching entries in passwd
     * file", phase 65. Step images are consumed by bare local tag and rebuilt from source every
     * boot, so nothing pins a version of them and main is their only meaningful identity. The SPA
     * sources are the same shape: they feed a placeholder bundle into a seed image, and the real
     * client is built by the pipeline afterwards.
     */
    public static boolean carriesVersionIdentity(String name) {
        return DEPLOYABLES.contains(name) || RELEASE_PUBLISHERS.contains(name);
    }

    /** A release version on this platform: CalVer, which is digits and dots. */
    private static final Pattern CALVER = Pattern.compile("[0-9][0-9.]*");

    /**
     * THE NEWEST RELEASE in a list of tags git has already sorted newest-version-first — the one
     * fact a restore is built on, asked by the boot in two places: which commit a checkout is put
     * at, and which commit the deploy ref is moved to. One answer, so the seed and its successor
     * can never disagree about what "the last release" is.
     * <p>
     * The CalVer filter is what keeps a stray tag out of both. Version sort orders by refname, so a
     * {@code latest} or a {@code v2} sorts above every {@code 2026.812.101500} — letters beat
     * digits — and a boot would build and deploy whatever commit it named.
     */
    public static String newestRelease(List<String> tagsNewestFirst) {
        for (String tag : tagsNewestFirst) {
            if (CALVER.matcher(tag).matches()) {
                return tag;
            }
        }
        return "";
    }
}
