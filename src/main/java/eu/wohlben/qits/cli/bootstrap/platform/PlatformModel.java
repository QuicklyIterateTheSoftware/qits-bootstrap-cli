package eu.wohlben.qits.cli.bootstrap.platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
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
     * dependency that crash-loops it. It is no deploy for that reason ({@link BootOrder#SEED_HELD_RESOURCES}).
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
     * <p>
     * <b>qits-system joined on 2026-10-23 (qits-1547), and it is in the seed because a run needs a
     * TARGET.</b> Since the CI step of epic qits-1481 every CI run is a lease on a qits-system
     * target, so the first release replay of a cold boot is the localhost target's or nobody's —
     * and the target registers with qits-system, which therefore has to be up with its database,
     * its eventstream outbox and its idp client before it. No deployer exists yet to provision any
     * of the three, so this boot does, as it does for every seed service.
     * <p>
     * <b>qits-workspaces joined on 2026-10-30 (qits-889), because phase 1 builds everything the
     * platform needs.</b> It is not called during the seed window, but the platform runs it in
     * steady state, and step two of the boot is bringing it up as it runs in steady state. Built
     * here, a cold boot's first workspace no longer waits on a release build of the train; deployed
     * again in the train, from its release run's image, like every other seed service.
     * qits-configuration joined beside it and left again with its retirement (qits-1396): the
     * deployer reads its settings store and nothing reads qits-configuration, so the boot neither
     * builds, clones nor deploys it.
     */
    public static final List<String> CORE = List.of(
            "edge", "mirror", "artifacts", "githost", "projects", "ci",
            "containers", "deployments", "idp", "events", "system",
            "workspaces", "oci-postgresql");

    /**
     * <b>THE REPOSITORIES PHASE 1 DOES NOT BUILD BECAUSE NOTHING THE BOOT BRINGS UP CALLS THEM
     * YET, each with the reason</b> (qits-889). Phase 1 builds everything the platform needs to
     * run in steady state; these six are left to phase 4 — the release replays and the train —
     * and the summary prints this list so the gap is stated rather than discovered. Every other
     * repository the model clones is built in phase 1 ({@code SeedPhases.phaseOneBuilds}) or is in
     * {@link #NOT_BUILT}, and a test holds the three together. A frontend of a deferred service is
     * deferred with it: its bundle ships in that service's image.
     * <p>
     * The brief's six are genuine satellites, each wanted later; {@code bootstrap-javalib} joined them
     * in qits-3 on the same terms — its only consumers are a deferred service and this CLI. {@link #NOT_BUILT} is the
     * other half of what that coverage test found: repositories the model clones that nothing
     * built here is waiting on at all.
     */
    public static final Map<String, String> DEFERRED_TO_PHASE_4 = deferred(
            // Speech-to-text: nothing on the platform calls it at runtime.
            "stt", "speech-to-text — nothing calls it at runtime",
            // The documentation reader: no service the boot brings up reads documentation.
            "docs", "the documentation reader — nothing the boot brings up reads it",
            // Telemetry's sink: every service runs without it; its address only collects.
            "observability", "the telemetry sink — every service runs without it",
            // A scheduled caller of five peers; nothing calls it, so nothing waits on it.
            "maintenance", "the bump scanner — a scheduled caller nothing waits on",
            // Technical processes (the deletion run); a caller of four peers nothing calls.
            "orchestrator", "technical processes — a scheduled caller nothing waits on",
            // The apex browser app; no service depends on it. Its PACTS are packed in phase 1.
            "landing-app", "the apex browser app — no service depends on it (its pacts are packed)",
            // The boot-order library (qits-3): its jar reaches the store by its release replay.
            "bootstrap-javalib",
            "the boot-order library — only qits-maintenance (itself deferred) and this CLI link it");

    /**
     * <b>THE REPOSITORIES THE MODEL CLONES THAT ARE NOT DEFERRED WORK AT ALL, each with the
     * reason</b> (qits-889). {@link #DEFERRED_TO_PHASE_4} is six satellites phase 1 skips because
     * their turn is later; this is the unused entry the same coverage test found beside them (the
     * retired workspaces runner and client were two more until they left the wrapper, and the boot
     * stopped cloning them with it) — nothing ahead ever wants it built, so it is named here
     * rather than folded into the deferred list it does not belong to. The summary prints both, each under its
     * own heading, so the gap this list states is "not built (retired)" and not "deferred".
     */
    public static final Map<String, String> NOT_BUILT = deferred(
            // qits/workspace-editor: no consumer since qits-1152.
            "oci-workspace-editor", "qits/workspace-editor — no consumer since qits-1152");

    private static Map<String, String> deferred(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }

    /**
     * <b>The seed images the deployer puts live BEFORE the first build</b> — at their real release
     * tag, through its manual door, so the platform runs as it does in steady state before this
     * host's runner is started and before the first release replay is asked for.
     * <p>
     * It is {@link #CORE} in deploy order ({@link BootOrder}), less two, and written out rather
     * than read off the derivation because the seed deploys run before the train is planned.
     * qits-oci-postgresql is never deployed at all (see {@link BootOrder#SEED_HELD_RESOURCES}).
     * qits-deployments stays where it is, LAST of the
     * train: its own deployment is the self-update handoff, and nothing may follow it. The edge is
     * last of these for the train's own reason: its cutover takes the bootstrap ingress's
     * door away, so everything that still needs that door is done by then.
     * <p>
     * Each of them is deployed a SECOND time in the train, from the image its release run builds:
     * a seed image is the release's code built by this run rather than by the release's own run,
     * and the train is where the release image replaces it.
     */
    public static final List<String> SEED_DEPLOYED = List.of(
            "idp", "projects", "workspaces", "events", "mirror", "artifacts", "githost",
            "containers", "ci", "system", "edge");

    /**
     * <b>There is no platform plane, and this is where it used to be listed.</b>
     *
     * <p>A service was once one of two kinds. An ENVIRONMENT service got a copy per tier and
     * answered at {@code <env>-<app>}; a PLATFORM service was one process for the whole estate and
     * answered at the bare {@code <app>}, because there was nothing to qualify it against. That
     * second kind is deleted: every service is an ordinary service in the one environment, so
     * every address is {@code <env>-<app>} and {@link #wireAlias} needs no branch.
     *
     * <p><b>What the list cost while it survived the plane it named.</b> Nine entries kept
     * answering "bare" after the plane was gone, so this program still named those seed services —
     * and still minted their idp clients — at addresses that resolve nowhere. It was invisible
     * because nothing here runs against a live platform except a bootstrap, and the last one
     * predated the cutover.
     *
     * <p><b>The client ids move with it, and that is the part to sequence.</b>
     * {@code SeedPhases.idpClients} uses {@link #wireAlias} as a client id and compares it for
     * equality against the row already in the deployer's registry, so these nine are
     * {@code qits-<app>} on a live platform and become {@code <env>-qits-<app>} here. Against a
     * COLD platform there is nothing to collide with, which is the acceptance test this change is
     * written for. Against a warm one it mints a second client beside the live row, and that is a
     * cutover to run deliberately rather than a consequence to discover.
     */

    /** The group every qits jar is published under. */
    public static final String MAVEN_GROUP_PATH = "eu/wohlben/qits";

    private PlatformModel() {
    }

    /**
     * <b>The repositories this run READS and never hosts, deploys or replays</b> — cloned by the
     * {@code sources} phase beside the platform's own ({@link RunState#platformRepos}) and named
     * nowhere else.
     * <p>
     * They exist because a seed image build resolves every qits coordinate its poms pin, test
     * scope included, out of the run's own file repository (qits-889), and three of those
     * coordinates are cut from repositories that are no part of the platform's pipeline:
     * <ul>
     *   <li>{@code qits-bootstrap-cli} and {@code qits-landing-app} are pact CONSUMERS — nine seed
     *       services pin {@code qits-bootstrap-cli-pacts-<provider>}, qits-ci, qits-projects and
     *       qits-system pin {@code qits-landing-app-pacts-<provider>} — so their {@code pacts/} at
     *       the pinned release is the only source of those jars;
     *   <li>{@code qits-platform-access-monorepo} is a pact consumer too, and the producer of
     *       {@code qits-platform-access-cli-binary}, which qits-ci COMPILES against (the pin of the
     *       {@code qits} binary its release steps download).
     * </ul>
     * Kept out of the platform's repositories on purpose: those get a git-host repository,
     * a main push and an inventory row, and none of these is this platform's to host.
     */
    public static final List<String> READ_ONLY_REPOS =
            List.of("bootstrap-cli", "landing-app", "platform-access-monorepo");

    /**
     * The model name of a repository, by its repository name — {@link #nameOf} for the pipeline's
     * and the bare name for {@link #READ_ONLY_REPOS} — or null for a name that is no qits
     * repository. Whether the run clones it is membership's question ({@link Estate#sourceRepos}).
     */
    public static String sourceNameOf(String repository) {
        String name = nameOf(repository);
        if (name != null) {
            return name;
        }
        for (String readOnly : READ_ONLY_REPOS) {
            if (repo(readOnly).equals(repository)) {
                return readOnly;
            }
        }
        return null;
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
            case "ci-daemon", "workspace-daemon", "workspaces-runner-daemon",
                 "system-runner-daemon", "projects-daemon" ->
                    "daemons/" + repo(name);
            case "oci", "oci-postgresql", "oci-workspace", "oci-workspace-editor" ->
                    "images/" + repo(name);
            // Framework glue is shared code, so the integrations sit in libs/ like any other lib —
            // and so does the byte plane's own, a library by the same test: three services consume
            // it and it is not deployed.
            case "eventstream", "containers-driver", "registries", "spa-ui-components", "userflows",
                 "runner", "bootstrap-javalib",
                 "integrations-angular", "integrations-quarkus", "coding-agents" ->
                    "libs/" + repo(name);
            // Anything served at a URL is a frontend, and there is one spelling now. It was two
            // while a client took its service's PLANE as well as its component — qits-spa-artifacts
            // the first way, qits-platform-spa-mirror the second — and the plane is gone, so every
            // client is spa-<x>. The model key is what is tested here; the angular project inside
            // those five repositories is still named the old way and moves on its own.
            default -> name.startsWith("spa-")
                    ? "frontends/" + repo(name)
                    : "services/" + repo(name);
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
     * <b>qits-containers is the same shape, one module since qits-796.</b> Its consumers depend on
     * {@code qits-containers-client} — the wire records and the HttpClient behind them — which is
     * what the seed publishes; its {@code service} module is the deployable, a native image nobody
     * resolves, and the seed leaves it to its own image build, which saves a native compile inside a
     * maven container and keeps a jar only one image loads out of the registry.
     * {@code qits-containers-core} USED TO be a second published module, but qits-containers-service
     * stopped publishing it on 2026-10-04: it is an internal module of that reactor now — the
     * container registry, the entities and the Flyway migrations behind {@code qits-containers-client}
     * — with no consumer of its own, so publishing it bought nothing and cost a jar in the registry
     * nobody resolved. {@code qits-containers-driver} is the library that replaced the gap a consumer
     * had: a repository of its own ({@code qits-containers-javalib}), seeded and replayed on
     * qits-eventstream's terms rather than published by this method — see {@link BootOrder}.
     * <p>
     * <b>The daemons are the same shape again, with the wire contract as the module.</b>
     * qits-ci compiles against {@code qits-ci-daemon-protocol} and qits-system against
     * {@code qits-system-runner-protocol} — plain jars — and the other module of each reactor is a
     * native binary nobody resolves. {@code qits-ci-runner-protocol} was the third until the CI
     * runner retired (qits-1547).
     * <p>
     * {@code SeedPhases} turns this into {@code -pl <modules> -am}, and {@code -am} is not optional:
     * it carries the ROOT POM with the modules, and an artifact whose parent the registry does not
     * hold resolves nowhere.
     */
    public static String mavenModule(String name) {
        return switch (name) {
            case "githost" -> "githost-events";
            case "containers" -> "client";
            case "ci-daemon" -> "ci-daemon-protocol";
            case "system-runner-daemon" -> "system-runner-protocol";
            // qits-projects compiles against both daemons' contracts (and qits-system against the
            // workspace daemon's), and qits-ci against the binary pin — see READ_ONLY_REPOS.
            case "projects-daemon" -> "projects-daemon-protocol";
            case "workspace-daemon" -> "workspace-daemon-protocol";
            case "platform-access-monorepo" -> "platform-access-cli-binary";
            default -> "";
        };
    }

    /**
     * <b>Modules the FILE REPOSITORY seed builds beside {@link #mavenModule}, where the tree has
     * them.</b> {@code qits-containers-client-contracts} is the consumer pact kit qits-projects pins
     * test-scoped at the client's version; it compiles against
     * {@code qits-containers-golden-masters}, so it can only be built where those are resolvable —
     * the file repository, which {@code seed-golden-masters} fills before {@code maven-seed}. Each
     * entry is guarded by its {@code pom.xml}: the module joined the reactor on 2026-10-19, and a
     * lagging pin older than that has none.
     */
    public static List<String> seedOptionalModules(String name) {
        return switch (name) {
            case "containers" -> List.of("client-contracts");
            default -> List.of();
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
     * <b>The plane is gone from both halves.</b> A service's repository once said
     * {@code <component>-platform-<role>} and its application answered at a bare
     * {@code qits-<app>}; the repositories were renamed and the applications followed, so a model
     * key is now simply the component and nothing decides a plane. The application name is still
     * the wire alias, the container name and the client id — it just has one shape.
     * <p>
     * <b>Three applications keep one name</b>, and it is the same reason for all three: the daemons
     * are already in the grammar ({@code qits-ci-daemon}, {@code qits-workspace-daemon},
     * {@code qits-projects-daemon}).
     */
    private static final Map<String, String> REPOSITORIES = Map.ofEntries(
            Map.entry("oci", "qits-build-images-oci"),
            Map.entry("oci-postgresql", "qits-database-oci"),
            Map.entry("oci-workspace", "qits-workspace-oci"),
            Map.entry("oci-workspace-editor", "qits-workspace-editor-oci"),
            Map.entry("eventstream", "qits-eventstream-javalib"),
            Map.entry("containers-driver", "qits-containers-javalib"),
            Map.entry("runner", "qits-runner-javalib"),
            Map.entry("registries", "qits-registries-javalib"),
            Map.entry("userflows", "qits-userflows-javalib"),
            Map.entry("integrations-angular", "qits-integrations-angular-jslib"),
            Map.entry("integrations-quarkus", "qits-integrations-quarkus-javalib"),
            Map.entry("spa-ui-components", "qits-ui-components-jslib"),

            // The environment tier's services.
            Map.entry("artifacts", "qits-artifacts-service"),
            Map.entry("ci", "qits-ci-service"),
            Map.entry("containers", "qits-containers-service"),
            Map.entry("docs", "qits-docs-service"),
            Map.entry("githost", "qits-githost-service"),
            Map.entry("observability", "qits-observability-service"),
            Map.entry("projects", "qits-projects-service"),
            Map.entry("stt", "qits-stt-service"),
            Map.entry("workspaces", "qits-workspaces-service"),

            // What used to be the platform tier. Both halves of the plane have moved: these
            // repositories dropped the -platform- modifier and their applications dropped the
            // qits-platform- prefix, so there is nothing left here to distinguish them from the
            // nine above. They are kept as a block only because the history is worth reading.
            Map.entry("edge", "qits-edge-service"),
            Map.entry("idp", "qits-idp-service"),
            Map.entry("maintenance", "qits-maintenance-service"),
            Map.entry("mirror", "qits-mirror-service"),
            Map.entry("orchestrator", "qits-orchestrator-service"),
            Map.entry("system", "qits-system-service"),
            Map.entry("deployments", "qits-deployments-service"),
            Map.entry("events", "qits-events-service"),

            // The frontends. Both old spellings collapse into one: a client's repository takes its
            // SERVICE's component and plane, whether the model spelled it spa-<x> or
            // platform-spa-<x>. qits-spa-events and qits-spa-deployments are the pair that shows it
            // — their services moved plane on 2026-08-17 and the clients follow them here.
            // The model name each of them is keyed by moved for neither.
            Map.entry("spa-artifacts", "qits-artifacts-frontend"),
            Map.entry("spa-ci", "qits-ci-frontend"),
            Map.entry("spa-docs", "qits-docs-frontend"),
            Map.entry("spa-githost", "qits-githost-frontend"),
            Map.entry("spa-observability", "qits-observability-frontend"),
            Map.entry("spa-projects", "qits-projects-frontend"),
            Map.entry("spa-workspaces", "qits-workspaces-frontend"),
            Map.entry("spa-deployments", "qits-deployments-frontend"),
            Map.entry("spa-events", "qits-events-frontend"),
            Map.entry("spa-idp", "qits-idp-frontend"),
            Map.entry("spa-maintenance", "qits-maintenance-frontend"),
            Map.entry("spa-mirror", "qits-mirror-frontend"),
            Map.entry("spa-orchestrator", "qits-orchestrator-frontend"),
            Map.entry("spa-system", "qits-system-frontend"));

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
     * container the deployer manages, the settings rows, the seed image tag. It is derived from the
     * model name and nothing else, so a repository rename cannot move it. A renamed repository says
     * the same thing from its own side, with {@code application:} in its
     * {@code .config/qits/deployments.yml}, and the two have to agree.
     */
    public static String application(String name) {
        return "qits-" + name;
    }

    /**
     * <b>The model name a git-host repository belongs to</b> — the inverse of {@link #repo}: the
     * {@link #REPOSITORIES} entry, else the repository without its {@code qits-} prefix, and null
     * for a name with neither. It is what turns a {@code .gitmodules} entry (which names the
     * frontend REPOSITORY) into a checkout this run can read. Whether the platform HAS that
     * repository is not this method's question any more: membership is the wrapper's
     * ({@link RunState#platformRepos}, {@link Estate#repos}).
     */
    public static String nameOf(String repository) {
        for (Map.Entry<String, String> entry : REPOSITORIES.entrySet()) {
            if (entry.getValue().equals(repository)) {
                return entry.getKey();
            }
        }
        return repository.startsWith("qits-") ? repository.substring("qits-".length()) : null;
    }

    /** The one project every platform repository belongs to, and the one qits-projects self-seeds. */
    public static final String PROJECT = "qits";

    /**
     * The {@code qits} project has environments disabled, so its apps are {@code <app>.qits.<domain>}.
     * Hardcoded by owner decision 2026-10-01; an edge {@code /.well-known} may replace it someday.
     */
    public static final boolean PROJECT_HAS_ENVIRONMENTS = false;

    /**
     * <b>The innermost door of this platform's project</b> — the parent of every app host — and the
     * one place the hostname shape is chosen: {@code <env>.<projectAuthority>} for a project with
     * environments, {@code <projectAuthority>} for one without. Login and the closing report both
     * compose through it.
     */
    public static String innermostDoor(String environment, String projectAuthority) {
        return innermostDoor(PROJECT_HAS_ENVIRONMENTS, environment, projectAuthority);
    }

    /** The same, for either shape; {@link #innermostDoor(String, String)} passes the constant. */
    public static String innermostDoor(boolean hasEnvironments, String environment, String projectAuthority) {
        return hasEnvironments ? environment + "." + projectAuthority : projectAuthority;
    }

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

    /**
     * The <b>wire alias</b>: the address peers dial, and the name a cutover finds its predecessor
     * by. It is the deployer's {@code PdNetworks.alias}, restated here because the seed containers
     * have to answer to the same names the deployed ones will — a run-arg that injects
     * {@code http://prod-qits-ci:8080} is wrong for the seven minutes before ci is deployed unless
     * the seed ci is already reachable under that name.
     * <p>It is {@code <env>-<app>} for every application — {@code prod-qits-ci}, {@code
     * dev-qits-idp} — and the qualifier is what lets two tiers hold one application's address on
     * the network they share.
     *
     * <p><b>It used to have a second arm and does not need one.</b> A platform service kept the
     * bare {@code qits-<app>}, because one instance has nothing to qualify it against; deleting
     * that plane left one rule. The branch outlived the plane by long enough to matter: it went on
     * naming nine seed services at bare addresses after the live estate had stopped answering on
     * any of them.
     *
     * <p><b>This is also the idp CLIENT ID</b>, which is why the collapse is a cutover and not a
     * tidy-up. {@code SeedPhases.idpClients} creates a client under this name and compares it for
     * equality against the deployer's registry, so the nine move from {@code qits-<app>} to
     * {@code <env>-qits-<app>}. Cold, there is nothing to collide with. Warm, it mints a second
     * client beside the live row and every deployed peer still holds a credential for the first —
     * a 401 whose cause is unreadable from either end.
     */
    public static String wireAlias(String name, String envName) {
        return envName + "-" + application(name);
    }

    /**
     * A seed service's whole {@code networks:} block, in the stack file's own words.
     *
     * <p>Always the short form {@code networks: [qits-net]}, because a seed service's NAME is
     * already the address every consumer is configured with — {@link #wireAlias} is what both the
     * stack file and the deployer use.
     *
     * <p><b>There was a long form, and it existed for the plane.</b> A platform service's seed
     * container was named bare while configuration had begun addressing it as {@code <env>-<app>},
     * so it declared that second name as a network alias to make both spellings resolve during the
     * seed window. One name now, so there is no second one to declare. The method survives the
     * fragment it used to choose between because {@code ComposeTemplate} substitutes a whole
     * {@code networks:} block here, and a template that spelled it inline would be a copy of this
     * decision rather than a use of it.
     *
     * <p>The parameters are kept for that same reason — a caller passes the application and the
     * environment without having to know that the answer no longer varies by either.
     */
    public static String seedNetworks(String name, String envName, String indent) {
        return "networks: [qits-net]";
    }

    /**
     * What qits-deployments names the container it manages for this application — the twin of its
     * own {@code ContainerNames.of}. One shape, because the model has one: every application
     * carries its tier's name. There was a second for a service with no tier, which DROPPED the
     * segment rather than filling it — and before that a {@code qits-pd-platform-<app>-} that said
     * the word twice ({@code qits-pd-platform-qits-platform-idp-}).
     * <p>
     * The prefix is {@code qits-pd-}; the retired qits-cd's was {@code qits-cd-}.
     */
    public static String pdNamePrefix(String name, String envName) {
        return "qits-pd-" + envName + "-" + application(name) + "-";
    }

    /**
     * <b>The label an application publishes itself at</b> — the leftmost label of
     * {@code <app>[.<env>].<project>.<domain>}, which is how every browser name is read. It is the
     * application name with the {@code qits-} and {@code platform-} prefixes stripped, so
     * {@code platform-idp} is reached at {@code idp.<env>.<project>.<domain>} and {@code ci} at
     * {@code ci.<env>.<project>.<domain>}.
     * <p>
     * <b>The derivation is qits-deployments', restated.</b> {@code DeployService.browserHost} builds
     * the host the deployer projects for an application and strips exactly these two prefixes; this
     * is the same rule asked from the outside, because the bootstrap has to know which labels the
     * platform occupies before a single deployment exists. If the deployer's rule ever moves, this
     * one moves with it — a label the two disagree on is a name one side publishes and the other
     * side hands out as a project slug.
     * <p>
     * The plane prefix goes because it is a fact about where a service RUNS, not about where it is
     * reached: there is one idp for the platform and it answers at {@code idp.<env>.<project>},
     * not at {@code platform-idp.<env>.<project>}.
     */
    public static String browserLabel(String name) {
        String label = application(name);
        if (label.startsWith("qits-")) {
            label = label.substring("qits-".length());
        }
        if (label.startsWith("platform-")) {
            label = label.substring("platform-".length());
        }
        return label;
    }

    /**
     * <b>The labels the EDGE publishes that no application name spells</b> — the three app names
     * the edge itself derives its upstream host and audience patterns for, in code, keyed by the
     * label rather than by the service behind it. This program renders none of those patterns any
     * more ({@code qits.edge.apps.<app>.host-pattern} / {@code audience-pattern} are gone from the
     * edge's configuration); it only needs the three labels for {@link #reservedSlugs}.
     * <p>
     * Two of them are derivable and one is not, which is the whole reason this list exists.
     * {@code mirror} and {@code githost} are {@link #browserLabel}s of qits-platform-mirror and
     * qits-githost; {@code registry} is qits-artifacts under a name a docker client understands.
     * Nothing derives that one from any application name, so it is written here once.
     * <p>
     * All three are in {@link #reservedSlugs} for the same reason every other label is: a project may
     * not take a name the edge already answers at.
     */
    public static final List<String> EDGE_APPS =
            List.of("registry", "mirror", "githost");

    /**
     * <b>The slugs no project may take</b> — {@code QITS_PROJECTS_RESERVED_SLUGS}, rendered into
     * the seed stack, and the reason a project slug is not free-form on this platform.
     * <p>
     * <b>Two collisions, one list.</b> A project slug is position 1 of
     * {@code <app>.<project>.<env>.<domain>}, and the edge asks that label whether it names an
     * ENVIRONMENT before it reads it as a project — so a project called after a tier is read as that
     * tier, over an apex that is not the apex. That is the collision the environment names close,
     * and it is a real misroute. The SERVICE labels close a second one at position 0: a project
     * called {@code registry} can never claim {@code registry.<env>.<domain>}, because the edge
     * matches its configured app entry first and the service wins every time. Nothing misroutes
     * there — but the project owns a name it can never be reached at, and every link anybody builds
     * to it lands on the registry instead. Refusing the slug says so at the one moment it can still
     * be changed.
     * <p>
     * <b>Derived, never copied.</b> The labels are the {@link #browserLabel}s of everything this
     * bootstrap deploys (the derived deploys, {@link Estate#deploys}) plus the
     * edge's own four ({@link #EDGE_APPS}). {@code idp} and {@code edge} are named
     * again below because the platform is not reachable without them — the login host and the
     * gateway itself — and a list that lost them to a refactor of the deployables would hand the
     * login name to a project. They are already derived; the restatement costs a deduplication and
     * buys the guarantee.
     * <p>
     * <b>The order is the environment first and then the labels sorted</b>, and it is deterministic
     * because a bootstrap RERUN has to write the value it wrote last time: a value that reordered
     * itself would change the seed stack, and restart qits-projects, for no reason anybody could
     * see.
     * <p>
     * The environment name is dropped from the label set rather than repeated: a platform whose tier
     * is called {@code docs} reserves {@code docs} once.
     */
    public static String reservedSlugs(String envName, List<String> deploys) {
        return String.join(",", reservedSlugList(envName, deploys));
    }

    /** The same list unjoined, so a test can name one entry. */
    public static List<String> reservedSlugList(String envName, List<String> deploys) {
        SortedSet<String> labels = new TreeSet<>();
        deploys.forEach(app -> labels.add(browserLabel(app)));
        labels.addAll(EDGE_APPS);
        // The login host and the door. Both are already above; naming them is what stops a
        // refactor of the deployables from quietly handing either one to a project.
        labels.add(browserLabel("idp"));
        labels.add(browserLabel("edge"));
        labels.remove(envName);
        List<String> slugs = new ArrayList<>();
        slugs.add(envName);
        slugs.addAll(labels);
        return List.copyOf(slugs);
    }

    /**
     * Where a seed build places the service's client bundle, or empty when the service has no
     * client. The Dockerfiles consume an already-built SPA, and a clean checkout has no dist
     * directory, so the seed builds the gitlinked webui itself — from the {@code @qits} tarballs
     * phase 1 built and npmjs, never the platform's registries (qits-889, {@link NpmSeed}) — and
     * puts {@code dist/<project>/browser} here. The service has no placeholder to fall back to.
     * <p>
     * <b>Every seed service that has one is spelled out</b>, because a bundle directory is the
     * Angular project key and moves whenever its client is renamed — it does not follow the
     * repository name. The path is the one the service's Dockerfile checks with {@code test -f}, so
     * a stale spelling fails the seed build minutes in rather than at the edit.
     * <p>
     * Empty is a real answer, not a gap: qits-platform-edge serves no paths of its own at all.
     * {@code SeedPhases.seedImage} builds no client for it. A seed service added without a
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
            // seed image of either now needs a client built where it needed none the week before.
            //
            // The two paths differ in their FIRST segment, not by accident: qits-githost is a
            // reactor and its application is the `service` module, while qits-platform-mirror is
            // one module and its webui sits at the root.
            case "githost" -> "service/src/main/webui/dist/qits-spa-githost/browser";
            case "mirror" -> "src/main/webui/dist/qits-platform-spa-mirror/browser";
            // The idp grew its login/register client on 2026-08-14, in the prebuilt-dist shape
            // like the rest — found by the first bare-server boot, whose seed build stopped at
            // the Dockerfile's `test -f` on this path.
            case "idp" -> "service/src/main/webui/dist/qits-platform-spa-idp/browser";
            // qits-projects joined the seed on 2026-08-21 and it has had a client all along, in the
            // same prebuilt-dist shape: its Dockerfile stops the build with a `test -f` on this path
            // before the native compile, because the bundle depends on @qits/ui-components and a
            // docker build reaches no registry that holds it.
            case "projects" -> "service/src/main/webui/dist/qits-spa-projects/browser";
            // qits-system joined the seed on 2026-10-23 (qits-1547) with its client in the same
            // shape; the Angular project inside it still carries the plane-era name.
            case "system" -> "service/src/main/webui/dist/qits-platform-spa-system/browser";
            // qits-workspaces serves no client since qits-1245 — the workspace pages are
            // qits-landing-app's — so it has no line here and needs none.
            default -> "";
        };
    }

    /**
     * The webui submodule a seed client is built from — {@link #seedUiPath} up to its
     * {@code dist/} — or empty when the service has no client.
     */
    public static String seedWebuiPath(String name) {
        String ui = seedUiPath(name);
        int dist = ui.indexOf("/dist/");
        return dist < 0 ? "" : ui.substring(0, dist);
    }

    /** The bundle inside that webui, {@code dist/<project>/browser}, or empty. */
    public static String seedBundlePath(String name) {
        String webui = seedWebuiPath(name);
        return webui.isEmpty() ? "" : seedUiPath(name).substring(webui.length() + 1);
    }

    /** The seed images that carry a client, in {@link #CORE}'s order. */
    public static List<String> seedClients() {
        return CORE.stream().filter(name -> !seedUiPath(name).isEmpty()).toList();
    }

    /**
     * <b>The seed services whose idp clients this bootstrap creates against a RUNNING idp</b>, in
     * the order it creates them. Five, and the number is not a preference: these are the
     * applications that are already up before anything exists that could provision a credential for
     * them.
     * <p>
     * Every other application gets its client the ordinary way — it declares an {@code idp:client}
     * resource in its own {@code deployments.yml}, and qits-deployments creates the client, records
     * the secret in its {@code pd_resource} registry and injects the triple at deploy. That path
     * needs a deployer, a registry and a running idp, which is exactly what these five come up
     * BEFORE. qits-projects, qits-ci, qits-containers and qits-deployments boot from the seed stack
     * and speak to each other from their first second; qits-platform-edge is the door, and a door
     * with no session client refuses every browser that arrives while the train is still running.
     * <p>
     * A client id here is the WIRE ALIAS ({@link #wireAlias}) and the registry row is keyed by the
     * APPLICATION ({@link #application}) — the same two spellings of one identity every other part
     * of this model carries.
     * <p>
     * <b>qits-workspaces joined on 2026-10-30 (qits-889), with its seed image.</b> Its own
     * {@code deployments.yml} declares {@code idp:client}, and every git-host, qits-projects,
     * qits-system and qits-containers call it makes carries a bearer minted from that client — so a
     * seed container started without one answers every workspace start with a 401 from a peer.
     */
    public static final List<String> SEED_IDP_CLIENT_APPS =
            List.of("projects", "ci", "containers", "deployments", "edge", "system", "workspaces");

    /**
     * <b>The one client whose secret this program still records itself</b>, and the reason it is
     * the exception: it is the credential the bootstrap creates all the others WITH. There is
     * nowhere else to keep it — qits-deployments' registry does not exist on a cold boot, and the
     * idp issues a secret once and never says it again — so it stays in
     * {@code .qits-bootstrap.env} under {@code IDP_SECRET_<clientKey>}, exactly where every boot
     * before this one already wrote it.
     * <p>
     * It is a wire alias like every other client id, so it carries the environment name:
     * {@code <env>-qits-bootstrap}.
     */
    public static String bootstrapClientId(String envName) {
        return wireAlias("bootstrap", envName);
    }

    /** The env-var spelling of a client id: uppercase, dashes as underscores. */
    public static String clientKey(String clientId) {
        return clientId.toUpperCase().replace('-', '_');
    }

    /**
     * <b>Everything the generated files must not decide for themselves.</b> A wire alias, a
     * client-id-derived config key and whether an application is told its tier used to change when
     * an application moved plane, so the templates carry placeholders and this method answers them.
     * Nothing moves plane any more — there is one — but the placeholders stay earned: a wire alias
     * is still derived rather than spelled, and a template that pasted one would be a second copy
     * of {@link #wireAlias} that cannot follow it.
     * <p>
     * Two families keyed by the APPLICATION, because that name never moves:
     * <ul>
     *   <li>{@code ALIAS_<APP>} — the seed stack's SERVICE NAME, which is also the client id: an
     *       idp client is named after the wire alias, so a template that wants one asks for the
     *       other.
     *   <li>{@code DIAL_<APP>} — the address peers dial, and what every URL in a generated file
     *       must carry. <b>It is the same string as {@code ALIAS_<APP>} now</b>, and both survive
     *       because they are still two questions: what a service is CALLED and where it is
     *       REACHED. They parted company for the nine platform services, whose bare name was a
     *       client id long after it had stopped being an address, and they came back together when
     *       the plane was deleted. A template asking the wrong one would read correctly today and
     *       be wrong the moment they diverge again.
     *   <li>{@code SEED_NETWORKS_<APP>} — the seed service's whole {@code networks:} block. See
     *       {@link #seedNetworks}.
     *   <li>{@code TIER_ENV_<APP>} — the {@code QITS_ENVIRONMENT} line in the stack file's words.
     *       See {@link #tierEnv}.
     * </ul>
     * <p>
     * A third family went with the idp's config-file registry: {@code CLIENT_KEY_<APP>} was the
     * env-var infix of {@code QITS_IDP_CLIENT_<key>_SECRET}, and no generated file names a client
     * that way any more — a client is a row the idp holds, created against the running service.
     * And one that is keyed by nothing because it is a statement about the platform as a whole:
     * <ul>
     *   <li>{@code RESERVED_SLUGS} — the names no project may take, which is this environment plus
     *       every label the platform already publishes. See {@link #reservedSlugs}. It is here
     *       rather than in {@code SeedPhases.tokens} for the reason the three above are: it is
     *       derived from the deploys, so an application that starts deploying reserves its own
     *       label without anybody remembering to.
     * </ul>
     * <p>
     * It replaced a single {@code ENV_KEY} token the templates pasted a repository name after —
     * {@code ${ENV_KEY}_QITS_DEPLOYMENTS} — which was a copy of this derivation that could not
     * follow it. The 2026-08-17 flip is what proved that: the deployer's key is
     * {@code QITS_DEPLOYMENTS} now, with no tier in front of it.
     */
    public static Map<String, String> modelTokens(String envName, Estate estate) {
        Map<String, String> tokens = new LinkedHashMap<>();
        // The seed carries one application no pipeline deploys — the postgres image — so CORE is
        // asked as well as the deployables.
        List<String> applications = new ArrayList<>(estate.repos());
        CORE.stream().filter(app -> !applications.contains(app)).forEach(applications::add);
        for (String app : applications) {
            tokens.put("ALIAS_" + clientKey(app), wireAlias(app, envName));
            tokens.put("DIAL_" + clientKey(app), wireAlias(app, envName));
            tokens.put("SEED_NETWORKS_" + clientKey(app), seedNetworks(app, envName, "    "));
            tokens.put("TIER_ENV_" + clientKey(app), tierEnv(envName, "      "));
        }
        tokens.put("RESERVED_SLUGS", reservedSlugs(envName, estate.deploys()));
        return tokens;
    }

    /**
     * <b>{@code QITS_ENVIRONMENT} states which tier an application belongs to</b>, and every
     * application belongs to one now, so this is the whole line for all of them. The fragment
     * carries the OUTPUT's indentation, because a text block's own indent is stripped before a
     * value is substituted into it.
     *
     * <p><b>It used to be the empty string for a platform service, and the absence was
     * load-bearing.</b> qits-deployments records a resource row per application under the
     * environment this variable names, {@code orElse(null)}, and it looked a PLATFORM-target
     * service's rows up by that null key — which the unique index treats as one value rather than
     * as many. A platform service handed a tier therefore recorded rows nothing would look for
     * again: its first self-deploy found no null-keyed row, took the reconcile arm instead, and
     * rotated the deployer's own database passwords in the middle of a boot.
     *
     * <p><b>The hazard goes with the plane rather than being defused.</b> There is no PLATFORM
     * target left for a row to be keyed null under, so every row is keyed by a real environment
     * and every lookup finds it. Emitting the line for all of them is what makes that true rather
     * than merely likely — an application that got no line would be exactly the null-keyed case
     * again.
     *
     * <p>It is not how a postgres HOST is resolved, which is what this comment used to claim: the
     * deployer reads the TARGET application's environment for that.
     */
    private static String tierEnv(String envName, String indent) {
        return "\n"
                + indent + "# WHICH TIER THIS APPLICATION BELONGS TO. Every application has one:\n"
                + indent + "# the deployer keys each resource row by this value, and a row written\n"
                + indent + "# without it is keyed null — which the unique index treats as ONE row\n"
                + indent + "# however many applications share it. That was the platform plane's\n"
                + indent + "# shape and it is gone; the line is unconditional so it cannot return.\n"
                + indent + "QITS_ENVIRONMENT: " + envName;
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
