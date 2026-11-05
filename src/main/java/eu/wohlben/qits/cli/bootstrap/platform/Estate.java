package eu.wohlben.qits.cli.bootstrap.platform;

import eu.wohlben.qits.bootstrap.graph.Coordinate;
import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import eu.wohlben.qits.bootstrap.graph.ProjectGraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>What the platform is made of, as its repositories declare it</b> (qits-3) — the derivation
 * that replaced the three hand-kept lists, and everything read off it.
 * <p>
 * Made by {@link #derive} from the wrapper's submodules ({@link CheckoutProjectSource}), so it
 * answers only once the checkouts exist; {@code Boot.estate()} derives it once per run, at the
 * revision the boot builds. Before that, membership alone — which repositories the platform has —
 * is the wrapper's {@code .gitmodules} ({@link RunState#platformRepos}).
 *
 * @param order  what to push, what to replay and the deploy order
 * @param source the facts the order was derived from
 * @param graph  the graph over them
 */
public record Estate(BootOrder order, CheckoutProjectSource source, ProjectGraph graph) {

    /**
     * @throws IllegalStateException naming every member when the declarations have a dependency
     *                               cycle, or the deploy constraints do
     */
    public static Estate derive(CheckoutProjectSource.Checkouts checkouts) {
        CheckoutProjectSource source = CheckoutProjectSource.read(checkouts);
        ProjectGraph graph = ProjectGraph.of(source, CheckoutProjectSource.RESOURCE_PROVIDERS);
        return new Estate(BootOrder.derive(graph, source), source, graph);
    }

    /** Every repository the boot hosts — the pushes and then the deploys, model names. */
    public List<String> repos() {
        List<String> all = new ArrayList<>(order.pushes());
        all.addAll(order.deploys());
        return List.copyOf(all);
    }

    /** The deployables, in deploy order. */
    public List<String> deploys() {
        return order.deploys();
    }

    /**
     * <b>Does this repository's output carry VERSION IDENTITY on the platform?</b> Only those are
     * stood at a release tag by a restoring boot; every other source stays on main.
     * <p>
     * A deploy becomes a deployed container whose release the deployer names, and a replay
     * publishes a registry coordinate another repository pins. For both, "the last release" is a
     * fact the platform can state, and a seed that disagrees with it breaks the successor —
     * qits-ci's seed applied a migration its released binary had never heard of and left it
     * crash-looping.
     * <p>
     * <b>Everything else has no such fact, and pinning it to a tag is actively wrong.</b> Measured
     * on the first scoped-boot run: qits-oci's newest tag was three days old and predated the
     * passwd-backed {@code build} user its step images grew when steps stopped running as root, so a
     * maven-base seed-built from that tag could not launch a step declaring {@code user: build} —
     * "unable to find user build: no matching entries in passwd file", phase 65. Step images are
     * consumed by bare local tag and rebuilt from source every boot, which is why main was their
     * only meaningful identity while nothing pinned a version of them. The SPA checkouts
     * are the same shape: a seed image's client is built from the service's own GITLINK of its webui
     * (qits-889), never from the frontend checkout, so nothing reads them at a tag. qits-oci itself
     * is a replay since qits-3 (qits-workspace-oci pins its node-browser-base at a calver), so it is
     * stood at its newest release now: that release has to carry what its step images need.
     */
    public boolean carriesVersionIdentity(String name) {
        return order.deploys().contains(name) || order.replays().contains(name);
    }

    /** The maven, npm and docker coordinates a repository's release declares, by model name. */
    public Set<Coordinate> publishes(String name) {
        String repository = repository(name);
        return repository == null ? Set.of() : source.produces(repository);
    }

    /**
     * The coordinates of {@link #publishes} its release declares {@code publish: if-changed}: absent
     * at a release version whenever that release found them unchanged.
     */
    public Set<Coordinate> publishedIfChanged(String name) {
        String repository = repository(name);
        return repository == null ? Set.of() : source.publishedIfChanged(repository);
    }

    /**
     * <b>Which replay publishes each coordinate of {@code ecosystem}</b>, coordinate name to model
     * name — read from each replay's {@code release.yml}, so a publisher that grows an image gets an
     * entry by saying so once, where its release already says what it publishes.
     */
    public Map<String, String> producers(Ecosystem ecosystem) {
        Map<String, String> byName = new LinkedHashMap<>();
        for (String replay : order.replays()) {
            for (Coordinate coordinate : publishes(replay)) {
                if (coordinate.ecosystem() == ecosystem) {
                    byName.put(coordinate.name(), replay);
                }
            }
        }
        return Map.copyOf(byName);
    }

    /** Every repository the boot clones: {@link #repos} then the read-only ones. */
    public List<String> sourceRepos() {
        Set<String> all = new LinkedHashSet<>(repos());
        all.addAll(PlatformModel.READ_ONLY_REPOS);
        return List.copyOf(all);
    }

    private String repository(String name) {
        for (String node : graph.nodes()) {
            if (BootOrder.name(node).equals(name)) {
                return node;
            }
        }
        return null;
    }
}
