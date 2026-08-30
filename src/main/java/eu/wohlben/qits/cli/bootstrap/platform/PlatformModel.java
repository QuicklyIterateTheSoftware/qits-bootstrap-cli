package eu.wohlben.qits.cli.bootstrap.platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Which repositories the platform is made of, and what each one is to the bootstrap. Ported from
 * the lists at the top of {@code qits-local-up.sh}, comments included: they are the reason the
 * order is what it is.
 * <p>
 * A <b>name</b> here is the deployer's APPLICATION name without the {@code qits-} prefix —
 * {@code platform-idp} is the application {@code qits-platform-idp}. The 2026-08-08 rename moved
 * the plane INTO the repository names, so a name now says which plane its service is on and nothing
 * else has to. The byte-plane split moved three of them the other way on 2026-08-10:
 * {@code artifacts}, {@code docs} and {@code githost} are environment services and say so by
 * carrying no plane at all.
 * <p>
 * <b>The repository is a SECOND name now, and {@link #REPOSITORIES} is where the two part
 * company.</b> A name here used to be both — the wire alias, the seed image tag and the deployer's
 * application key on one side, the git-host repository and the wrapper checkout on the other — and
 * the phase-2 renames break that in one direction only: the repository is renamed into the
 * {@code <component>-<role>} grammar while the running platform keeps every name it answers to,
 * which is what {@code deployments.yml}'s {@code application:} key exists to pin. So
 * {@link #application} derives the identity from the name unchanged, {@link #repo} answers the
 * repository, and the two differ for exactly the entries the table lists.
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
     * image adds nothing to upstream postgres — and it is hand-built and hand-started like the
     * rest. Unlike the rest it STAYS the seed service: the train never re-deploys the database,
     * because reading its spec back from qits-githost (whose storage is this database) is a circular
     * dependency that crash-loops it. It is absent from {@link #DEPLOYABLES} for that reason.
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
     * <p>
     * <b>qits-projects joined on 2026-08-21, and it is in the seed because a repository has a
     * NAME.</b> It owns the alias table, and {@code /git/<projectId>/<repoName>} — the one clone url
     * there is — resolves through it and nowhere else. Every repository of this platform is created
     * minutes into the boot, so a qits-projects deployed sixth of seventeen meant every push of the
     * boot's first half was id-addressed, carried no name onto its event, and had to be seeded under
     * a storage id shaped like its own name for anything downstream to match it. Seeded, it answers
     * before the first push: the storage ids are minted UUIDs, each pairing is registered before its
     * bare is pushed to, and every push of the run is name-addressed. Three databases, the way the
     * git host takes two — see {@link #seedStorageId}.
     */
    public static final List<String> CORE = List.of(
            "platform-edge", "platform-mirror", "artifacts", "githost", "projects", "ci",
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
     * <b>qits-oci-postgresql is deliberately NOT in this list — it is a seed service the train
     * never re-deploys.</b> It is the platform's one database, every service's store and
     * qits_githost's included, and it stays the seed-stack postgres that {@link ComposeTemplate}
     * already renders {@code pg_isready}-gated. Re-deploying it would make qits-deployments read
     * its spec from qits-githost, whose storage IS this database: during postgres's own cutover
     * that read misses, the deployer falls back to its HTTP-probe default, and swarm kills a bare
     * postgres that cannot answer it — a crash-loop out of a circular dependency. The bootstrapper
     * stands the database up directly, so the train leaves it alone. It is in {@link #SEEDED_REPOS}
     * for its checkout, its git-host repository and its seed-image build; it carries no deployment.
     * <p>
     * <b>The byte plane's three sit together, and their order inside it is forced.</b>
     * qits-platform-mirror is first of the three: every image build and every dependency
     * resolution after it goes through the mirror, so its cutover belongs before the services whose
     * builds it feeds rather than in the middle of them. qits-githost comes after qits-artifacts
     * and before qits-ci, because ci reads pipeline config out of the git host and clones from it —
     * and because the githost's own deployment is the one that re-hosts the repository this train
     * pushes to, the same self-referential class as the deployer.
     * <p>
     * <b>qits-containers is immediately BEFORE qits-ci, and that pair's order is forced.</b> ci runs
     * every pipeline step as a container it asks this service for, so a ci cutover landing while the
     * orchestrator is mid-cutover is a pipeline with nowhere to run. Deploying the orchestrator first
     * puts its window before ci's rather than inside it, and leaves the run's own remaining
     * deployments — the edge and the deployer — behind a ci that already has a working step host.
     * <p>
     * <b>qits-configuration is FOURTH, and every neighbour of that position is load-bearing.</b> It
     * holds deployment configuration as platform state, and the deployer READS it once the boot has
     * flipped {@code QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL} — so it has to be deployed and imported
     * before that flip, and the flip has to be before the deployer's own deployment, which inherits
     * the url from its extras. It goes after postgres, because its store is provisioned from the
     * {@code resources: postgresql:db} line in its own deployments.yml, and after the idp, whose
     * cutover must not fall inside the deploy window of a service that validates its tokens. Every
     * deployable BELOW it is what proves the flip: each of them is deployed from configuration the
     * service served rather than from the file on the volume.
     * <p>
     * <b>qits-platform-orchestrator is LATE, after every service it calls.</b> It runs technical
     * processes — multi-step jobs that only send requests to peers — so it is a caller and nothing
     * calls it. Its first process is the platform's unified deletion run, which drives
     * qits-artifacts, qits-containers, qits-ci and the deployer, and each of those four is above it
     * here. The order is not a startup dependency: it holds a scheduler, so a cutover that lands
     * while a peer is mid-cutover is a run whose steps fail against a service that is being
     * replaced. Deploying it after them puts its first scheduled window behind their last one.
     * It stays ABOVE the edge and the deployer for the reason everything does: the edge's cutover
     * takes this program's door away and the deployer's is the self-update handoff.
     * <p>
     * <b>qits-platform-maintenance is LATE for the orchestrator's reason and is NOT in the
     * seed.</b> It reads the catalog from qits-projects, the manifests from qits-githost, the
     * versions from qits-artifacts and qits-platform-mirror, and it asks qits-ci to apply a bump —
     * five callees, every one of them above it here. It holds a scheduler too, so a cutover landing
     * inside a peer's window is a scan whose reads fail against a service being replaced. Nothing
     * calls it, so no seed service waits on it: the train restores it from its last release like
     * any other platform-tier application.
     * <p>
     * <b>qits-platform-system calls NO peer at all, and that is why its place is the free one.</b>
     * The base system panels read the host's docker daemon and nothing else — {@code docker info},
     * the swarm lists, this node's containers — so no service in this list is a dependency of it
     * and nothing above it constrains where it goes. It is last of the platform-tier applications
     * because its own cutover DROPS every open terminal: put earlier, the successor would take the
     * sessions of an operator watching the rest of the train go by. It stays above the edge and the
     * deployer for the reason everything does. Not in the seed either: nothing calls it.
     */
    public static final List<String> DEPLOYABLES = List.of(
            "observability", "platform-idp", "configuration", "stt", "projects",
            "workspaces", "events", "docs", "platform-mirror", "artifacts", "githost",
            "containers", "ci", "platform-orchestrator", "platform-maintenance", "platform-system",
            "platform-edge", "deployments");

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
     * <b>qits-deployments and qits-events joined on 2026-08-17, and neither APPLICATION name says
     * its plane</b> — their wire alias is the bare {@code qits-deployments} and {@code qits-events}
     * rather than a {@code qits-platform-*} one, and it stays that way. Their repositories say it
     * ({@code qits-events-platform-service}), which is what makes this list the authority rather
     * than the spelling: a plane is decided here and read nowhere else.
     * Two reasons, one each. The DEPLOYER: an environment is becoming a cross-environment entity —
     * one tier gating another — and a hierarchy cannot live inside one tier's deployer. The BUS:
     * scoping today is which broker instance a service dials, so a platform deployer on a per-tier
     * bus could publish {@code DeploymentActive} onto one tier only.
     * <p>
     * There is no platform deploy ref any more. Both planes answer the same question of a green
     * build — does an environment listen to this ref — so {@code environment/<name>} is the whole
     * set and {@code platform/main} is retired.
     * <p>
     * <b>qits-platform-orchestrator joined on 2026-08-21, and a technical process is
     * platform-wide by construction.</b> Its first one is the deletion run, and what it deletes —
     * the host's image store, its volumes, its build cache, the registry's blobs — is ONE machine's
     * however many tiers run on it. A per-tier copy would be two schedulers pruning the same docker
     * daemon on their own clocks, each blind to what the other pinned.
     * <p>
     * <b>qits-platform-maintenance joined on 2026-08-22, because a dependency inventory is one
     * catalog's.</b> What it inventories is every repository of the platform and the versions their
     * manifests pin — facts of the git host and the registries, and neither is per-tier. A second
     * copy would scan the same repositories twice and push the same maintenance branch on two
     * clocks. Which CI applies a bump IS a tier's, and that is one configured address
     * ({@code QITS_MAINTENANCE_TARGETS_CI_URL}) rather than a second instance.
     * <p>
     * <b>qits-platform-system joined on 2026-08-23, because what it shows is a MACHINE.</b> The
     * host, the swarm, this node's containers and the terminals into them are facts of one docker
     * daemon, however many tiers run on it — there is no per-tier half of a node. Two copies would
     * be two services holding the same socket and two boot sweeps deleting each other's terminal
     * containers, which is why the sweep is scoped by an owner label even with one.
     */
    public static final List<String> PLATFORM_SERVICES = List.of(
            "platform-edge", "platform-idp", "platform-mirror", "deployments", "events",
            "platform-orchestrator", "platform-maintenance", "platform-system");

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
     * <b>qits-registries joined on 2026-08-10</b>, and it is the byte plane's own library: one
     * Maven module per registry format, and since 2026-08-30 the content-addressed blob store as
     * one more module of the same reactor. Three services build against it — qits-artifacts,
     * qits-platform-mirror and qits-githost — and none of those builds can run on the platform
     * until the jars are IN the platform's Maven registry, which is what a repository plus a
     * release replay gets them. qits-blobstore had its own entry here until the merge; the
     * repository is retired, and the reactor publishes its jar under the unchanged coordinate.
     * <p>
     * <b>qits-spa-githost and qits-platform-spa-mirror joined on 2026-08-11</b>, the last two byte
     * services to grow a client. They are here for the reason every other frontend is: a checkout
     * this run can clone, a repository on the git host, and a main history — so qits-projects
     * inventories them and qits-workspaces can release them. Neither is a release publisher and
     * neither is a deployable: their bundles ship inside qits-githost's and qits-platform-mirror's
     * own images, as webui submodules.
     * <p>
     * <b>qits-platform-spa-orchestrator joined on 2026-08-21</b>, and only the client is here: its
     * service is a {@link #DEPLOYABLES} entry, and that list already gets a repository, a checkout
     * and a main push. The two lists are disjoint on purpose — {@link #platformRepos} adds them —
     * so an application named in both would be created twice and pushed twice.
     * <p>
     * <b>qits-platform-spa-maintenance joined on 2026-08-22</b>, on exactly those terms: its bundle
     * ships inside qits-platform-maintenance's own image as a webui submodule, so it is a
     * repository and a main history and nothing more.
     * <p>
     * <b>qits-platform-spa-system joined on 2026-08-23</b>, on the same terms: the admin console's
     * bundle ships inside qits-platform-system's image.
     * <p>
     * <b>qits-oci-postgresql moved here from {@link #DEPLOYABLES}</b>, because it is a seed image
     * this run builds but the train never deploys — see the note there for the circular dependency
     * that forbids re-deploying the database. Seeded like every image publisher: a checkout, a
     * git-host repository and a main history feed its seed-image build; it carries no release, so it
     * is not restored to a tag.
     */
    public static final List<String> SEEDED_REPOS = List.of(
            "oci", "oci-postgresql", "ci-daemon", "eventstream", "registries", "spa-ui-components",
            "userflows", "spa-docs", "spa-deployments",
            "integrations-angular", "integrations-quarkus", "spa-projects",
            "spa-workspaces", "spa-artifacts", "spa-observability", "spa-events",
            "spa-ci", "spa-githost", "spa-configuration", "platform-spa-idp",
            "platform-spa-mirror", "platform-spa-orchestrator", "platform-spa-maintenance",
            "platform-spa-system",
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
     *   <li><b>qits-integrations-quarkus before qits-eventstream.</b> Eventstream's released POM
     *       pins {@code qits-db-core} to the Quarkus integration release; replaying eventstream
     *       first therefore asks the empty registry for an artifact the next phase would publish.
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
     * <b>qits-registries is NOT here yet, deliberately.</b> A replay restores a pin, and nothing
     * pins a calver of it or of the qits-blobstore jar its reactor carries: every consumer still
     * names {@code 1.0.0-SNAPSHOT}, which the seed publishes restore. It joins this list — before
     * qits-eventstream — the moment its first release is cut and the consumer poms move onto the
     * calvers.
     */
    public static final List<String> RELEASE_PUBLISHERS =
            List.of("spa-ui-components", "integrations-angular", "integrations-quarkus",
                    "eventstream",
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
     * <b>The FALLBACK answer to where a repository sits in the wrapper — the archetype layout,
     * derived from the name. The wrapper's own {@code .gitmodules} is the authority</b>, read by
     * {@link GitModules} and asked first by {@link RunState#wrapperCheckout}.
     * <p>
     * <b>A name cannot say where a repository sits, and the component layout is where that stops
     * being a harmless approximation.</b> {@code components/<component>/<repo>} groups by the
     * component a repository belongs to, and that is not derivable: {@code qits-spa-ci} belongs to
     * {@code qits-ci}. So this method answers only when the wrapper declares nothing — a wrapper
     * this machine has not cloned yet, or a repository that is no submodule of it — and then it
     * names the old shape's role directory holding the repository under its CURRENT name. Since the
     * renames no wrapper on disk has that pairing, which is the point: only a machine with no
     * wrapper at all may reach this, and the {@code wrapper} phase clones one before anything is
     * built.
     * <p>
     * <b>A wrong path here used to be silent.</b> The sources phase fell back to GitHub whenever
     * the wrapper path was not a checkout, so a misspelling ignored local commits and cloned last
     * week's platform instead — and after the layout flip that would have been EVERY repository.
     * It is loud now, twice over: a directory that exists, holds something and is not a checkout
     * stops the boot, and so does a module the wrapper DECLARES whose directory holds no checkout
     * while its siblings do. An absent directory is still answered by the org URL when the wrapper
     * has no submodules initialised at all — git leaves an empty one at every gitlink of a wrapper
     * cloned without them, which is exactly what a cold start has.
     */
    public static String repoPath(String name) {
        return switch (name) {
            case "ci-daemon", "workspace-daemon", "projects-daemon" -> "daemons/" + repo(name);
            case "oci", "oci-postgresql", "oci-workspace" -> "images/" + repo(name);
            // Framework glue is shared code, so the integrations sit in libs/ like any other lib —
            // and so does the byte plane's own, a library by the same test: three services consume
            // it and it is not deployed.
            case "eventstream", "registries", "spa-ui-components", "userflows",
                 "integrations-angular", "integrations-quarkus" -> "libs/" + repo(name);
            // Anything served at a URL is a frontend, whether it is spelled qits-spa-<x> or
            // qits-platform-spa-<x>. Both spellings are live: the byte-plane split renamed two the
            // first way (qits-spa-artifacts, qits-spa-docs) and qits-platform-spa-mirror was born
            // the second way, because its service is on the platform plane. The second arm also
            // catches a wrapper checked out before that rename, whose directories still carry the
            // old names — and a path that resolves to nothing clones the org's copy in silence.
            default -> name.startsWith("spa-") || name.startsWith("platform-spa-")
                    ? "frontends/" + repo(name)
                    : "services/" + repo(name);
        };
    }

    /**
     * <b>What KIND of component a repository is, in qits-projects' vocabulary</b> — the value the
     * registration hands it as {@code archetype}.
     * <p>
     * <b>Two derivations, and which one applies is decided by the wrapper's own path.</b> Under the
     * archetype layout the first segment IS the archetype, in both directions, and that is the
     * derivation qits-projects itself makes — so a wrapper of that shape is read that way and the
     * two sides cannot disagree. Under the component layout ({@code components/<component>/<repo>})
     * the first segment says nothing about kind, so the NAME answers instead.
     * <p>
     * <b>The name's answer has two arms, in this order.</b> First the grammar phase 2 of the
     * reorganisation renames every repository into — {@code -service}, {@code -frontend},
     * {@code -daemon}, {@code -oci}, {@code -cli}, {@code -javalib}/{@code -jslib} — which is what
     * makes this method outlive the renames. Then, for the names of today, a table derived from the
     * layout {@link #repoPath} still spells: the same knowledge keyed by name instead of by
     * directory. The lib set is asked BEFORE the {@code spa-} prefix, because
     * {@code qits-spa-ui-components} is a component library and not a frontend.
     * <p>
     * Anything nothing claims answers {@code SERVICE}, which is qits-projects' own default for an
     * adopted row with no archetype.
     *
     * @param wrapperPath where the wrapper actually puts it, or null when nothing knows
     */
    public static String archetype(String name, String wrapperPath) {
        String directory = wrapperPath == null || wrapperPath.indexOf('/') < 0
                ? ""
                : wrapperPath.substring(0, wrapperPath.indexOf('/'));
        return switch (directory) {
            case "libs" -> "LIBRARY";
            case "frontends" -> "FRONTEND";
            case "daemons" -> "DAEMON";
            case "images" -> "IMAGE";
            case "cli" -> "CLI";
            case "services" -> "SERVICE";
            default -> archetypeOfName(name);
        };
    }

    /** The same question when nobody has said where the repository sits. */
    public static String archetype(String name) {
        return archetype(name, repoPath(name));
    }

    /**
     * The names that are libraries today, and are none of them spelled so.
     * <p>
     * <b>These are APPLICATION names, so the renames do not touch them.</b> Their repositories are
     * {@code qits-eventstream-javalib} and its siblings — {@link #REPOSITORIES} holds the mapping —
     * and those spellings answer through the {@code -javalib}/{@code -jslib} arm above without an
     * entry here. Nothing is missing from this list; it is the table for the names that say nothing.
     */
    private static final List<String> LIBRARY_NAMES =
            List.of("eventstream", "registries", "spa-ui-components", "userflows",
                    "integrations-angular", "integrations-quarkus");

    /**
     * The names that are image builds today, and are none of them spelled so. Their renamed
     * repositories — {@code qits-build-images-oci}, {@code qits-database-oci},
     * {@code qits-workspace-oci} — answer through the {@code -oci} arm above.
     */
    private static final List<String> IMAGE_NAMES = List.of("oci", "oci-postgresql", "oci-workspace");

    private static String archetypeOfName(String name) {
        // The grammar the renames land on. It is asked first so a renamed repository needs no
        // entry in the tables below, which are what the un-renamed names of phase 1 need.
        if (name.endsWith("-service")) {
            return "SERVICE";
        }
        if (name.endsWith("-frontend")) {
            return "FRONTEND";
        }
        if (name.endsWith("-daemon")) {
            return "DAEMON";
        }
        if (name.endsWith("-oci")) {
            return "IMAGE";
        }
        if (name.endsWith("-cli")) {
            return "CLI";
        }
        if (name.endsWith("-javalib") || name.endsWith("-jslib")) {
            return "LIBRARY";
        }
        if (LIBRARY_NAMES.contains(name)) {
            return "LIBRARY";
        }
        if (IMAGE_NAMES.contains(name)) {
            return "IMAGE";
        }
        if (name.startsWith("spa-") || name.startsWith("platform-spa-")) {
            return "FRONTEND";
        }
        return "SERVICE";
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

    /**
     * <b>The repositories whose git-host name is not their application name.</b> Every entry is a
     * phase-2 rename: the repository moved into the {@code <component>[-<modifier>]-<role>} grammar
     * and nothing on the running platform moved with it.
     * <p>
     * <b>Keyed by the application name, because that is the one that never moves.</b> The published
     * coordinates the renamed repositories carry — {@code eu.wohlben.qits:qits-eventstream},
     * {@code @qits/ui-components}, {@code qits/build-images/*}, {@code qits/workspace-base},
     * {@code qits/qits-oci-postgresql} — are independent of both names and stay where they are.
     * <p>
     * <b>qits-oci-postgresql is the entry that shows why the two names cannot be one.</b> Its
     * application name is in every environment's wire alias, in the seed container this program
     * starts, in the seed image tag {@code qits/oci-postgresql:latest} the generated stack names,
     * and in the JDBC url of every consumer that stack renders. Its repository is
     * {@code qits-database-oci}. Renaming the model entry instead of adding it here would have
     * pointed the boot's own database at a host nothing answers to.
     * <p>
     * <b>The plane is a MODIFIER on the repository and stays a LIST on the application.</b> A
     * platform service's repository says {@code <component>-platform-<role>}, so the application
     * {@code events} is the repository {@code qits-events-platform-service} — and the application
     * still answers to the bare {@code qits-events}, which is its wire alias, its container name and
     * its client id. {@link #PLATFORM_SERVICES} remains the only place a plane is decided; the
     * repository name merely agrees with it.
     * <p>
     * <b>Three applications keep one name</b>, and it is the same reason for all three: the daemons
     * are already in the grammar ({@code qits-ci-daemon}, {@code qits-workspace-daemon},
     * {@code qits-projects-daemon}).
     */
    private static final Map<String, String> REPOSITORIES = Map.ofEntries(
            Map.entry("oci", "qits-build-images-oci"),
            Map.entry("oci-postgresql", "qits-database-oci"),
            Map.entry("oci-workspace", "qits-workspace-oci"),
            Map.entry("eventstream", "qits-eventstream-javalib"),
            Map.entry("registries", "qits-registries-javalib"),
            Map.entry("userflows", "qits-userflows-javalib"),
            Map.entry("integrations-angular", "qits-integrations-angular-jslib"),
            Map.entry("integrations-quarkus", "qits-integrations-quarkus-javalib"),
            Map.entry("spa-ui-components", "qits-ui-components-jslib"),

            // The environment tier's services.
            Map.entry("artifacts", "qits-artifacts-service"),
            Map.entry("ci", "qits-ci-service"),
            Map.entry("configuration", "qits-configuration-service"),
            Map.entry("containers", "qits-containers-service"),
            Map.entry("docs", "qits-docs-service"),
            Map.entry("githost", "qits-githost-service"),
            Map.entry("observability", "qits-observability-service"),
            Map.entry("projects", "qits-projects-service"),
            Map.entry("stt", "qits-stt-service"),
            Map.entry("workspaces", "qits-workspaces-service"),

            // The platform tier's. The application names of six of them already carry the plane as a
            // PREFIX and the repositories carry it as a modifier instead, so both halves move:
            // platform-idp is qits-idp-platform-service. The deployer and the bus say no plane at
            // all on the application side and never will — that is what PLATFORM_SERVICES is for.
            Map.entry("platform-edge", "qits-edge-platform-service"),
            Map.entry("platform-idp", "qits-idp-platform-service"),
            Map.entry("platform-maintenance", "qits-maintenance-platform-service"),
            Map.entry("platform-mirror", "qits-mirror-platform-service"),
            Map.entry("platform-orchestrator", "qits-orchestrator-platform-service"),
            Map.entry("platform-system", "qits-system-platform-service"),
            Map.entry("deployments", "qits-deployments-platform-service"),
            Map.entry("events", "qits-events-platform-service"),

            // The frontends. Both old spellings collapse into one: a client's repository takes its
            // SERVICE's component and plane, whether the model spelled it spa-<x> or
            // platform-spa-<x>. qits-spa-events and qits-spa-deployments are the pair that shows it
            // — their services moved plane on 2026-08-17 and the clients follow them here.
            Map.entry("spa-artifacts", "qits-artifacts-frontend"),
            Map.entry("spa-ci", "qits-ci-frontend"),
            Map.entry("spa-configuration", "qits-configuration-frontend"),
            Map.entry("spa-docs", "qits-docs-frontend"),
            Map.entry("spa-githost", "qits-githost-frontend"),
            Map.entry("spa-observability", "qits-observability-frontend"),
            Map.entry("spa-projects", "qits-projects-frontend"),
            Map.entry("spa-workspaces", "qits-workspaces-frontend"),
            Map.entry("spa-deployments", "qits-deployments-platform-frontend"),
            Map.entry("spa-events", "qits-events-platform-frontend"),
            Map.entry("platform-spa-idp", "qits-idp-platform-frontend"),
            Map.entry("platform-spa-maintenance", "qits-maintenance-platform-frontend"),
            Map.entry("platform-spa-mirror", "qits-mirror-platform-frontend"),
            Map.entry("platform-spa-orchestrator", "qits-orchestrator-platform-frontend"),
            Map.entry("platform-spa-system", "qits-system-platform-frontend"));

    /**
     * <b>The repository this name is hosted and checked out as</b> — the git-host repository, the
     * name a clone url carries, the wrapper's {@code .gitmodules} entry and the directory a source
     * is cloned into. It is the application name for everything the renames have not reached, and
     * {@link #REPOSITORIES} for everything they have.
     */
    public static String repo(String name) {
        return REPOSITORIES.getOrDefault(name, "qits-" + name);
    }

    /**
     * <b>The APPLICATION name: what the platform answers to</b> — the wire alias peers dial, the
     * container the deployer manages, the extras family, the seed image tag. It is derived from the
     * model name and nothing else, so a repository rename cannot move it. A renamed repository says
     * the same thing from its own side, with {@code application:} in its
     * {@code .config/qits/deployments.yml}, and the two have to agree.
     */
    public static String application(String name) {
        return "qits-" + name;
    }

    /** The one project every platform repository belongs to, and the one qits-projects self-seeds. */
    public static final String PROJECT = "qits";

    /**
     * <b>A FRESH STORAGE ID for a platform repository's bare</b> — the key {@code PUT /git/<id>}
     * uses, which is qits-githost's alone and never a clone url.
     * <p>
     * It is a minted UUID, which is what the ruling of 2026-08-21 says a storage id is: opaque, and
     * unrelated to the name the platform addresses the repository by. The public identity is
     * {@code (projectId, repoName)} and qits-projects' alias table is its only authority, so a
     * storage id shaped like a name would be one more place a name is spelled — and a second
     * project holding a repository of the same name would collide in the store.
     * <p>
     * <b>Every call mints, and {@link eu.wohlben.qits.cli.bootstrap.phases.Boot#storageId} is the
     * one place that remembers.</b> The pairing is recorded per run ({@code REPO_ID_<NAME>} in
     * {@code .qits-bootstrap.env}) as each bare is created and read back at {@code recorded-state},
     * so a resumed or repeated run addresses the bares it made rather than minting a second set
     * beside them. Nothing else may call this method: an id derived twice is two bares.
     * <p>
     * <b>What made a UUID reachable is qits-projects being in the seed</b> ({@link #CORE}). It
     * answers before the first push, so {@code git-repos} registers each (uuid, name) pair the
     * moment it creates the bare and every push of the run is name-addressed — which is what puts
     * {@code repoName} on each push's event, and therefore what lets qits-ci's trigger selection
     * match the release replays by name rather than falling back to the id.
     * <p>
     * It takes no repository name, and the absence is the point: a mint that read one would be a
     * derivation someone could repeat.
     */
    public static String seedStorageId() {
        return UUID.randomUUID().toString();
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
        return isPlatformService(name)
                ? application(name)
                : envName + "-" + application(name);
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
                ? "qits-pd-" + application(name) + "-"
                : "qits-pd-" + envName + "-" + application(name) + "-";
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
            // qits-projects joined the seed on 2026-08-21 and it has had a client all along, in the
            // same prebuilt-dist shape: its Dockerfile stops the build with a `test -f` on this path
            // before the native compile, because the bundle depends on @qits/ui-components and a
            // docker build reaches no registry that holds it.
            case "projects" -> "service/src/main/webui/dist/qits-spa-projects/browser";
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
     * <p>
     * <b>qits-platform-orchestrator joined on 2026-08-21, and it MINTS more than anything else
     * here.</b> A technical process is nothing but calls to peers, so this one service holds a
     * named oidc client per peer — qits-artifacts, qits-containers, qits-ci and the deployer — and
     * asks the idp for a token per audience. One client with one audience would not do: a bearer
     * minted for {@code <env>-qits-artifacts} is refused by every other peer, so a run's every step
     * but one would be a 401. All four present THIS application's id, never a borrowed one.
     * <p>
     * <b>qits-platform-maintenance joined on 2026-08-22, and its CLAIM is what is unusual about
     * it.</b> Three named clients — qits-projects for the catalog, qits-githost for the manifests,
     * qits-ci for the trigger — under the same one-client-per-audience rule. The registries it
     * reads versions from are unguarded on qits-net, so it holds no client for them. What it needs
     * beyond a token is {@code project=*}: qits-ci's trigger route demands every project, so a bump
     * that names ONE repository is refused without the wildcard. qits-artifacts held the only such
     * grant until now.
     * <p>
     * <b>qits-platform-system joined on 2026-08-23 as a THIRD PULLER.</b> It mints nothing — it
     * calls no peer at all — but the glances terminal it opens is a {@code docker run} of an image
     * behind the platform mirror, and the edge has granted no anonymous read since 2026-08-14. So
     * it needs the same thing the deployer and the container orchestrator need: a client id and a
     * secret in a docker {@code config.json}, its own and never a borrowed one, because a refused
     * pull has to name the service that was refused. Its file carries TWO hosts rather than one —
     * see {@code SeedPhases.dockerConfig} — since the image it pulls is the mirror's, not the
     * registry's.
     */
    public static final List<String> IDP_CLIENT_APPS =
            List.of("bootstrap", "ci", "artifacts", "workspaces", "projects", "deployments",
                    "containers", "edge", "platform-orchestrator", "platform-maintenance",
                    "platform-system");

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
     * <b>qits-deployments and qits-containers left it on 2026-08-14</b> — not because either mints
     * a token, but because each pulls images and a docker {@code config.json} is a client id and a
     * secret. They still validate exactly as they did; an audience is now something they get for
     * being clients.
     * <p>
     * <b>qits-configuration joined on 2026-08-17</b>, and it is the shape this list was kept for.
     * It validates the deployer's bearer on every read of an application's configuration and mints
     * nothing at all, so it holds no client — but the deployer asks for {@code
     * <env>-qits-configuration} as an audience, and an audience no client may ask for is
     * {@code invalid_target} rather than a call that reaches the service's own gate.
     */
    public static final List<String> RECEIVE_ONLY_APPS = List.of("githost", "configuration");

    /** The env-var spelling of a client id: uppercase, dashes as underscores. */
    public static String clientKey(String clientId) {
        return clientId.toUpperCase().replace('-', '_');
    }

    /**
     * <b>Everything the generated files must not decide for themselves.</b> A wire alias, a
     * client-id-derived config key and whether an application is told its tier all change when an
     * application moves plane, so the templates carry placeholders and this method answers them —
     * which is what makes {@link #PLATFORM_SERVICES} the one place a plane is decided.
     * <p>
     * Three families, every one keyed by the APPLICATION because that name never moves:
     * <ul>
     *   <li>{@code ALIAS_<APP>} — the address peers dial.
     *   <li>{@code CLIENT_KEY_<APP>} — the env-var infix the idp's per-client keys are built from
     *       ({@code QITS_IDP_CLIENT_<key>_SECRET}), which embeds the client id and so the alias.
     *   <li>{@code TIER_ENV_<APP>} and {@code TIER_ENV_EXTRAS_<APP>} — the {@code QITS_ENVIRONMENT}
     *       line in the stack file's words and in the extras', or NOTHING at all. See
     *       {@link #tierEnv}.
     * </ul>
     * <p>
     * It replaced a single {@code ENV_KEY} token the templates pasted a repository name after —
     * {@code ${ENV_KEY}_QITS_DEPLOYMENTS} — which was a copy of this derivation that could not
     * follow it. The 2026-08-17 flip is what proved that: the deployer's key is
     * {@code QITS_DEPLOYMENTS} now, with no tier in front of it.
     */
    public static Map<String, String> modelTokens(String envName) {
        Map<String, String> tokens = new LinkedHashMap<>();
        // The seed carries one application no pipeline deploys — the postgres image — so CORE is
        // asked as well as the deployables.
        List<String> applications = new ArrayList<>(platformRepos());
        CORE.stream().filter(app -> !applications.contains(app)).forEach(applications::add);
        for (String app : applications) {
            tokens.put("ALIAS_" + clientKey(app), wireAlias(app, envName));
            tokens.put("TIER_ENV_" + clientKey(app), tierEnv(app, envName, "      ", ""));
            tokens.put("TIER_ENV_EXTRAS_" + clientKey(app), tierEnv(app, envName, "",
                    "qits.platform.deployments.extras." + application(app) + ".env."));
        }
        for (String app : IDP_CLIENT_APPS) {
            tokens.put("CLIENT_KEY_" + clientKey(app), clientKey(wireAlias(app, envName)));
        }
        return tokens;
    }

    /**
     * <b>{@code QITS_ENVIRONMENT} states which tier an application belongs to, and a platform
     * service belongs to none</b> — so this is the whole line for an environment application and
     * the EMPTY STRING for a platform one. It is appended to the end of the line above it, the way
     * every conditional fragment in these templates is, so the absent case leaves no blank line and
     * no orphan comment behind. The fragment carries the OUTPUT's indentation, because a text
     * block's own indent is stripped before a value is substituted into it.
     * <p>
     * <b>The absence is load-bearing, and this is the hazard it exists to prevent.</b>
     * qits-deployments records a resource row per application under the environment this variable
     * names, {@code orElse(null)} — and it looks a PLATFORM-target service's rows up by that null
     * key, which the unique index treats as one value rather than as many. A platform service
     * handed a tier therefore records rows nothing will look for again: its first self-deploy finds
     * no null-keyed row, takes the reconcile arm instead, and rotates the deployer's own database
     * passwords in the middle of a boot. The rows the bootstrap records exist to prevent exactly
     * that, so a fresh boot must never write this line for a platform service.
     * <p>
     * It is not how a postgres HOST is resolved, which is what this comment used to claim: the
     * deployer reads the TARGET application's environment for that, or the platform-designated
     * environment's row for a platform target — never its own {@code QITS_ENVIRONMENT}.
     */
    private static String tierEnv(String app, String envName, String indent, String keyPrefix) {
        if (isPlatformService(app)) {
            return "";
        }
        return "\n"
                + indent + "# WHICH TIER THIS APPLICATION BELONGS TO. A PLATFORM SERVICE GETS NO\n"
                + indent + "# SUCH LINE: it belongs to no tier, and the deployer keys its platform\n"
                + indent + "# resource rows by the absence of this value. Written for one, those\n"
                + indent + "# rows land under a tier instead, the next self-deploy does not find\n"
                + indent + "# them, and it rotates the database passwords the bootstrap issued.\n"
                + indent + keyPrefix + "QITS_ENVIRONMENT" + (keyPrefix.isEmpty() ? ": " : "=")
                + envName;
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
