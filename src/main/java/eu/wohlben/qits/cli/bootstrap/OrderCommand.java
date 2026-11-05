package eu.wohlben.qits.cli.bootstrap;

import eu.wohlben.qits.bootstrap.graph.EdgeKind;
import eu.wohlben.qits.bootstrap.graph.ProjectGraph;
import eu.wohlben.qits.cli.bootstrap.platform.BootOrder;
import eu.wohlben.qits.cli.bootstrap.platform.CheckoutProjectSource;
import eu.wohlben.qits.cli.bootstrap.platform.PlatformModel;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/**
 * <b>What the wrapper's repositories declare about the boot's order, beside what
 * {@link PlatformModel} lists</b> (qits-3). It reads the checkouts and changes nothing — no git
 * write, no network, no docker — so it is safe on any wrapper, including a project agent's
 * {@code /workspace}.
 */
@CommandLine.Command(name = "order", mixinStandardHelpOptions = true,
        description = "Derive the push, replay and deploy order from the wrapper's declarations"
                + " and compare it with PlatformModel's lists. Reads only.")
public class OrderCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--wrapper", required = true,
            description = "The wrapper checkout (qits-qits) whose submodules are materialised.")
    Path wrapper;

    @Override
    public Integer call() {
        Report report = report(CheckoutProjectSource.Checkouts.workingTree(wrapper));
        System.out.print(report.text());
        return report.derived() ? 0 : 2;
    }

    /** The report's text, and whether a derivation was possible at all. */
    public record Report(String text, boolean derived) {}

    /** Everything {@code order} prints, for any form of the checkouts. */
    public static Report report(CheckoutProjectSource.Checkouts checkouts) {
        CheckoutProjectSource source = CheckoutProjectSource.read(checkouts);
        ProjectGraph graph = ProjectGraph.of(source, CheckoutProjectSource.RESOURCE_PROVIDERS);
        StringBuilder out = new StringBuilder();

        List<Set<String>> cycles = new ArrayList<>();
        for (Set<String> component : graph.components(EnumSet.of(EdgeKind.ARTIFACT,
                EdgeKind.RUNTIME))) {
            if (component.size() > 1) {
                cycles.add(new TreeSet<>(component));
            }
        }
        out.append("repositories: ").append(graph.nodes().size()).append('\n');
        out.append("cycles: ").append(cycles.isEmpty() ? "none" : cycles).append('\n');
        out.append("unresolved: ").append(graph.unresolved().isEmpty() ? "none" : "").append('\n');
        graph.unresolved().forEach(u -> out.append("  ").append(u.repository()).append(' ')
                .append(u.resource()).append(": ").append(u.reason()).append('\n'));
        out.append("ambiguous: ").append(graph.ambiguous().isEmpty() ? "none" : "").append('\n');
        graph.ambiguous().forEach(a -> out.append("  ").append(a.subject()).append(": ")
                .append(a.repositories()).append('\n'));

        BootOrder order;
        try {
            order = BootOrder.derive(graph, source);
        } catch (IllegalStateException e) {
            out.append("no order: ").append(e.getMessage()).append('\n');
            return new Report(out.toString(), false);
        }
        out.append("unnamed (no PlatformModel name): ")
                .append(order.unnamed().isEmpty() ? "none" : order.unnamed()).append('\n');
        section(out, "pushes", order.pushes(), PlatformModel.SEEDED_REPOS, false);
        section(out, "replays", order.replays(), PlatformModel.RELEASE_PUBLISHERS, true);
        section(out, "deploys", order.deploys(), PlatformModel.DEPLOYABLES, true);
        return new Report(out.toString(), true);
    }

    private static void section(StringBuilder out, String title, List<String> derived,
                                List<String> listed, boolean ordered) {
        out.append('\n').append(title).append(" (derived): ").append(derived).append('\n');
        out.append(title).append(" (listed):  ").append(listed).append('\n');
        out.append("  only derived: ").append(minus(derived, listed)).append('\n');
        out.append("  only listed:  ").append(minus(listed, derived)).append('\n');
        if (ordered) {
            List<String> inverted = new ArrayList<>();
            for (int i = 0; i < listed.size(); i++) {
                for (int j = i + 1; j < listed.size(); j++) {
                    int a = derived.indexOf(listed.get(i));
                    int b = derived.indexOf(listed.get(j));
                    if (a >= 0 && b >= 0 && a > b) {
                        inverted.add(listed.get(i) + " before " + listed.get(j));
                    }
                }
            }
            out.append("  listed pairs the derived order reverses: ")
                    .append(inverted.isEmpty() ? "none" : "").append('\n');
            inverted.forEach(pair -> out.append("    ").append(pair).append('\n'));
        }
    }

    private static List<String> minus(List<String> from, List<String> remove) {
        Set<String> left = new LinkedHashSet<>(from);
        remove.forEach(left::remove);
        return List.copyOf(left);
    }
}
