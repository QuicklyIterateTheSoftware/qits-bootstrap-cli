package eu.wohlben.qits.cli.bootstrap.platform;

import eu.wohlben.qits.bootstrap.graph.Coordinate;
import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import eu.wohlben.qits.bootstrap.graph.Edge;
import eu.wohlben.qits.bootstrap.graph.EdgeKind;
import eu.wohlben.qits.bootstrap.graph.Pin;
import eu.wohlben.qits.bootstrap.graph.ProjectGraph;
import eu.wohlben.qits.bootstrap.graph.ProjectSource;
import eu.wohlben.qits.bootstrap.graph.Verdict;
import eu.wohlben.qits.bootstrap.graph.Walk;
import eu.wohlben.qits.bootstrap.graph.WalkResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * <b>What the boot pushes, what it replays and the order it deploys in, DERIVED from what the
 * repositories declare</b> (qits-3). It replaced three hand-kept lists in {@link PlatformModel};
 * the {@code plan-train} phase makes one phase per entry, and {@code order} prints it.
 * <p>
 * <b>The pushes</b> are every repository the boot hosts that is no application of the deployer:
 * each needs a repository on the platform git host and a main push, so that qits-projects can
 * inventory it, qits-workspaces can release it and qits-ci can discover its event pipelines — and
 * nothing more. What that has meant, repository by repository, is the history the list kept:
 * <ul>
 *   <li><b>qits-ci-daemon</b> ships no image and no health endpoint, so it is no deployable — but
 *       its binary is an ordinary release-train artifact, and a release train needs the repository
 *       on the git host.
 *   <li><b>The image publishers</b> (2026-08-10): qits-workspace-oci, qits-workspace-daemon and
 *       qits-projects-daemon publish versioned docker images the platform pins —
 *       qits/workspace-base, qits/workspace, qits/projects-daemon, qits/project-agent — and none
 *       is an application of the deployer: an image nothing runs as a container has no
 *       deployments.yml and no health endpoint. <b>qits-workspace-editor-oci</b> (2026-09-06)
 *       publishes qits/workspace-editor, kept built while its integration is rethought (qits-1150);
 *       it runs as nobody's container either, and nothing pins it, so it is pushed and not
 *       replayed.
 *   <li><b>qits-registries-javalib</b> (2026-08-10) is the byte plane's own library: one Maven
 *       module per registry format, and since 2026-08-30 the content-addressed blob store as one
 *       more module (qits-blobstore's own repository is retired; the reactor publishes its jar under
 *       the unchanged coordinate). qits-artifacts, qits-mirror and qits-githost build against it, and
 *       none of those builds can run on the platform until the jars are in its Maven registry.
 *   <li><b>The frontends</b> (qits-githost's and qits-mirror's 2026-08-11, the orchestrator's
 *       2026-08-21, maintenance's 2026-08-22, system's 2026-08-23, and every other): a checkout,
 *       a git-host repository and a main history, so qits-projects inventories them and
 *       qits-workspaces can release them. None is a deployable: each bundle ships inside its
 *       service's own image as a webui submodule. Pushes and deploys are disjoint by
 *       construction, so a service and its client are never created or pushed twice.
 *   <li><b>qits-database-oci</b> ({@code oci-postgresql}) is a seed image this run builds and the
 *       train never deploys — {@link #SEED_HELD_RESOURCES} — so it is pushed for its checkout, its
 *       git-host repository and its seed-image build, and it carries deployments.yml for the seed
 *       stack alone.
 *   <li><b>qits-coding-agents</b> (2026-09-10) is the shared coding-agent harness (the
 *       {@code qits-agents} reactor: {@code qits-commands} and {@code qits-coding-agents}), which
 *       both agent daemons carried as in-repo modules until 2026-09-09 and now resolve as released
 *       jars. A boot that did not know it cloned nothing, published nothing, and the two daemon
 *       release replays died resolving a coordinate the fresh store had never seen.
 *   <li><b>qits-containers-javalib</b> (2026-10-04, qits-796) publishes
 *       {@code qits-containers-driver}, which qits-containers-service depends on; it has no
 *       dependency of its own on this platform. {@code qits-containers-core} stays an internal
 *       module of the service's reactor with no consumer, and therefore no seed publish of its own —
 *       see {@link PlatformModel#mavenModule}.
 *   <li><b>qits-runner-javalib</b> (2026-10-23, qits-1547): qits-system and its runner compile
 *       against qits-runner-protocol and qits-runner-toolkit, and nothing on a cold platform
 *       publishes them — so the boot clones it, and {@code SeedPhases.runnerSeed} builds the
 *       versions the two pin from their tags. Model name {@code runner}, after the
 *       {@code qits.runner.version} property both pin it by.
 *   <li><b>qits-system-runner-daemon</b> (2026-10-23, qits-1535): the {@code target-localhost}
 *       phase starts this host's target from {@code qits/qits-system-runner} at the version
 *       qits-system pins, and only that repository's own release run puts the image — and the
 *       {@code qits-system-runner-protocol} jar qits-system's build resolves — in the store.
 *   <li><b>qits-bootstrap-javalib</b> (qits-3) publishes {@code qits-bootstrap-graph}, the
 *       manifest readers and project graph qits-maintenance-service pins and this CLI links.
 *   <li><b>Gone, and why:</b> qits-ci-runner-daemon (2026-09-30, qits-588, to 2026-10-23,
 *       qits-1547) built the runner that executed every run of a cold boot until CI runs became
 *       leases on a qits-system target; qits-workspaces-runner-daemon (2026-10-05, qits-626) was a
 *       release publisher whose image the retired {@code workspaces-runner-localhost} phase started
 *       and whose {@code qits-workspaces-runner-protocol} jar a qits-workspaces release from before
 *       the target cutover resolved; both went with their submodules, and the derivation follows
 *       the wrapper.
 * </ul>
 * <p>
 * <b>The replays</b> are the pushes another repository pins at a release version, replayed on a
 * fresh platform because a pin is only as good as the registry behind it (decided in qits-3:
 * being seed-built is no exemption — qits-integrations-quarkus, qits-eventstream and
 * qits-containers-javalib are seed libraries and replays both). <b>Their order is dependency
 * order</b>, and each pair the list used to hold by position is now a manifest edge:
 * <ul>
 *   <li><b>qits-integrations-quarkus before qits-eventstream</b>: eventstream's pom pins
 *       {@code qits-db-core} to the Quarkus integration release, so replaying eventstream first
 *       asks the empty registry for an artifact the next phase would publish.
 *   <li><b>qits-workspace-oci before qits-workspace-daemon</b>: the daemon's
 *       {@code docker/Dockerfile} carries {@code ARG WORKSPACE_BASE=…/qits/workspace-base:<pin>}
 *       and its build PULLS that image through the registry, which the base's own replay fills.
 *       The other order builds against a tag the registry has never seen.
 *   <li><b>qits-workspace-oci before qits-projects-daemon</b>, for the same reason: its layered
 *       {@code qits/project-agent} build is {@code ARG BASE=qits/workspace-base:<pin>} in
 *       {@code docker/Dockerfile.projects}. It is independent of qits-workspace-daemon; only the
 *       base has to precede it.
 *   <li><b>qits-workspace-daemon before qits-workspace-editor-oci</b>, one link further down the
 *       same chain: the editor's {@code Dockerfile} is {@code FROM ${WORKSPACE_IMAGE}} with a
 *       committed default of {@code …/qits/workspace:<pin>}. It holds among the pushes; the editor
 *       is no replay while nothing pins it.
 *   <li><b>qits-integrations-angular before qits-ui-components-jslib</b>: the ui-components
 *       harness's {@code package.json} pins {@code @qits/angular} (devDependencies, which its
 *       release build installs) — read by name, the two jslibs lock with pnpm
 *       ({@link CheckoutProjectSource#lockless}).
 *   <li><b>qits-bootstrap-javalib</b>, qits-containers-javalib, qits-system-runner-daemon and the
 *       other replays nothing among the replays depends on go where the name tie-break puts them;
 *       every replay precedes every deploy, which is the one order their consumers need —
 *       qits-maintenance-service's build in the train resolves qits-bootstrap-graph,
 *       qits-containers-service resolves qits-containers-driver, qits-system's resolves
 *       qits-system-runner-protocol, all from the registry the replays fill.
 * </ul>
 * <b>Phase 1 builds the workspace-side images too since qits-889</b>, at the versions the
 * platform PINS, and images-publish pushes them — so a replay of a pinned version finds its images
 * held. The replays stay: the newest release is not always a pinned one, and its jars and binaries
 * are the replay's to publish. qits-registries joined once its consumers moved onto calvers
 * (2026.1103.145246), which is the condition the list waited for.
 * <p>
 * <b>The deploys</b> are the repositories with a deployments.yml, less the provider of a resource
 * the seed holds, in the order {@link CutoverRules} explains.
 *
 * @param pushes   every repository the boot hosts and pushes but does not deploy, in walk order
 * @param replays  the pushes whose released coordinates another repository pins at a release
 *                 version, in walk order — a subset of {@code pushes}
 * @param deploys  the deployables, in deploy order
 * @param unnamed  repositories {@link PlatformModel#nameOf} has no model name for; they appear in
 *                 the three lists under their repository name
 */
public record BootOrder(List<String> pushes, List<String> replays, List<String> deploys,
                        List<String> unnamed) {

    /**
     * A version this platform releases: {@code 2026.1105.103222}. Not {@code 1.0.0-SNAPSHOT}, not
     * {@code latest}, not a gitlink's sha.
     */
    static final Pattern RELEASE_VERSION = Pattern.compile("^\\d{4}\\.\\d{3,4}\\.\\d+$");

    /**
     * <b>The resource kinds the SEED holds and the train never re-deploys.</b> Their provider
     * carries a deployments.yml and is still no deploy. qits-oci-postgresql is the platform's one
     * database, every service's store and qits_githost's included, and it stays the seed-stack
     * postgres that {@link ComposeTemplate} renders {@code pg_isready}-gated. Re-deploying it would
     * make qits-deployments read its spec from qits-githost, whose storage IS this database: during
     * postgres's own cutover that read misses, the deployer falls back to its HTTP-probe default,
     * and swarm kills a bare postgres that cannot answer it — a crash-loop out of a circular
     * dependency. The bootstrapper stands the database up directly, so the train leaves it alone.
     */
    static final Set<String> SEED_HELD_RESOURCES = Set.of("postgresql");

    /** The ecosystems a release replay restores: everything but a gitlink. */
    private static final Set<Ecosystem> REPLAYED = EnumSet.of(Ecosystem.MAVEN, Ecosystem.NPM,
            Ecosystem.DOCKER);

    public BootOrder {
        pushes = List.copyOf(pushes);
        replays = List.copyOf(replays);
        deploys = List.copyOf(deploys);
        unnamed = List.copyOf(unnamed);
    }

    /** {@link #derive(ProjectGraph, ProjectSource, List)} over {@link CutoverRules#RULES}. */
    public static BootOrder derive(ProjectGraph graph, ProjectSource source) {
        return derive(graph, source, CutoverRules.RULES);
    }

    /**
     * @throws IllegalStateException when the graph has a dependency cycle, or the deploy
     *                               constraints do
     */
    public static BootOrder derive(ProjectGraph graph, ProjectSource source,
                                   List<CutoverRules.Rule> rules) {
        WalkResult walk = Walk.run(graph, EnumSet.of(EdgeKind.ARTIFACT, EdgeKind.RUNTIME),
                Comparator.naturalOrder(), repository -> Verdict.DONE);
        if (!walk.cycles().isEmpty()) {
            throw new IllegalStateException("dependency cycle: " + walk.cycles().stream()
                    .map(cycle -> String.join(", ", new TreeSet<>(cycle)))
                    .collect(Collectors.joining("; ")));
        }
        List<String> visited = walk.visited().stream().map(WalkResult.Visit::repository).toList();

        Set<String> seedHeldProviders = new HashSet<>();
        for (String kind : SEED_HELD_RESOURCES) {
            String application = CheckoutProjectSource.RESOURCE_PROVIDERS.get(kind);
            for (String node : graph.nodes()) {
                source.deployment(node).filter(d -> application.equals(d.application()))
                        .ifPresent(d -> seedHeldProviders.add(node));
            }
        }

        Set<String> deploySet = new LinkedHashSet<>();
        List<String> pushes = new ArrayList<>();
        List<String> replays = new ArrayList<>();
        for (String node : visited) {
            boolean deploys = source.deployment(node).isPresent();
            if (deploys && !seedHeldProviders.contains(node)) {
                deploySet.add(node);
                continue;
            }
            pushes.add(node);
            if (!deploys && pinnedAtARelease(node, graph, source)) {
                replays.add(node);
            }
        }

        List<String> unnamed = new ArrayList<>();
        for (String node : graph.nodes()) {
            if (PlatformModel.nameOf(node) == null) {
                unnamed.add(node);
            }
        }
        return new BootOrder(names(pushes), names(replays),
                names(deployOrder(graph, deploySet, rules)), unnamed);
    }

    /** Whether another node pins one of this node's maven, npm or docker coordinates at a release. */
    private static boolean pinnedAtARelease(String node, ProjectGraph graph, ProjectSource source) {
        Set<Coordinate> published = new HashSet<>();
        for (Coordinate coordinate : source.produces(node)) {
            if (REPLAYED.contains(coordinate.ecosystem())) {
                published.add(coordinate);
            }
        }
        if (published.isEmpty()) {
            return false;
        }
        for (String other : graph.nodes()) {
            if (other.equals(node)) {
                continue;
            }
            for (Pin pin : source.consumes(other)) {
                if (published.contains(pin.coordinate()) && pin.version() != null
                        && RELEASE_VERSION.matcher(pin.version()).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A stable topological sort of the deploys over the RUNTIME edges among them and the cutover
     * rules, ties broken by repository name.
     */
    static List<String> deployOrder(ProjectGraph graph, Set<String> deploys,
                                    List<CutoverRules.Rule> rules) {
        Map<String, Set<String>> after = new HashMap<>();
        for (String deploy : deploys) {
            after.put(deploy, new LinkedHashSet<>());
        }
        for (Edge edge : graph.edges()) {
            if (edge.kind() == EdgeKind.RUNTIME && deploys.contains(edge.upstream())
                    && deploys.contains(edge.downstream())) {
                after.get(edge.upstream()).add(edge.downstream());
            }
        }
        Map<String, String> byName = new HashMap<>();
        for (String deploy : deploys) {
            byName.put(name(deploy), deploy);
        }
        for (CutoverRules.Rule rule : CutoverRules.expand(rules, byName.keySet())) {
            String before = byName.get(rule.before());
            String later = byName.get(rule.after());
            if (before != null && later != null) {
                after.get(before).add(later);
            }
        }

        Map<String, Integer> waiting = new HashMap<>();
        for (String deploy : deploys) {
            waiting.putIfAbsent(deploy, 0);
            for (String later : after.get(deploy)) {
                waiting.merge(later, 1, Integer::sum);
            }
        }
        PriorityQueue<String> ready = new PriorityQueue<>(Comparator.naturalOrder());
        waiting.forEach((deploy, count) -> {
            if (count == 0) {
                ready.add(deploy);
            }
        });
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String next = ready.poll();
            order.add(next);
            for (String later : after.get(next)) {
                if (waiting.merge(later, -1, Integer::sum) == 0) {
                    ready.add(later);
                }
            }
        }
        if (order.size() < deploys.size()) {
            throw new IllegalStateException("deploy order cycle: "
                    + String.join(", ", cycleMembers(after, deploys)));
        }
        return order;
    }

    /** The deploys that can reach themselves over {@code after}: the members of every cycle. */
    private static Set<String> cycleMembers(Map<String, Set<String>> after, Set<String> deploys) {
        Set<String> members = new TreeSet<>();
        for (String start : deploys) {
            Set<String> seen = new HashSet<>();
            List<String> stack = new ArrayList<>(after.get(start));
            while (!stack.isEmpty()) {
                String node = stack.removeLast();
                if (node.equals(start)) {
                    members.add(start);
                    break;
                }
                if (seen.add(node)) {
                    stack.addAll(after.get(node));
                }
            }
        }
        return members;
    }

    private static List<String> names(List<String> repositories) {
        return repositories.stream().map(BootOrder::name).toList();
    }

    /** The model name, or the repository name for one the model does not know. */
    static String name(String repository) {
        String name = PlatformModel.nameOf(repository);
        return name == null ? repository : name;
    }
}
