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
 * <b>The three lists of {@link PlatformModel}, DERIVED from what the repositories declare</b>
 * (qits-3) — what to push, what to replay and what to deploy in which order. Nothing reads this
 * yet: {@code order} prints it beside the lists, and {@code BootOrderDiffTest} holds the two
 * together until the lists can go.
 *
 * @param pushes   every repository the boot hosts and pushes but does not deploy, in walk order
 * @param replays  the pushes whose released coordinates another repository pins at a release
 *                 version, in walk order — a subset of {@code pushes}, as {@code RELEASE_PUBLISHERS}
 *                 is of {@code SEEDED_REPOS}
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
     * The resource kinds the SEED holds and the train never re-deploys. Their provider carries a
     * deployments.yml and is still no deploy — see {@link PlatformModel#DEPLOYABLES} on the database.
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
