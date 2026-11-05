package eu.wohlben.qits.cli.bootstrap;

import eu.wohlben.qits.bootstrap.graph.EdgeKind;
import eu.wohlben.qits.bootstrap.graph.ProjectGraph;
import eu.wohlben.qits.cli.bootstrap.platform.BootOrder;
import eu.wohlben.qits.cli.bootstrap.platform.CheckoutProjectSource;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/**
 * <b>The boot's order, as the wrapper's repositories declare it</b> (qits-3): what is pushed, what
 * is replayed and the deploy order the {@code plan-train} phase would plan from this checkout. It
 * reads the checkouts and changes nothing — no git write, no network, no docker — so it is safe on
 * any wrapper, including a project agent's {@code /workspace}.
 */
@CommandLine.Command(name = "order", mixinStandardHelpOptions = true,
        description = "Derive the push, replay and deploy order from the wrapper's declarations."
                + " Reads only.")
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
        out.append("unnamed (no model name): ")
                .append(order.unnamed().isEmpty() ? "none" : order.unnamed()).append('\n');
        out.append("\npushes:  ").append(order.pushes()).append('\n');
        out.append("replays: ").append(order.replays()).append('\n');
        out.append("deploys: ").append(order.deploys()).append('\n');
        return new Report(out.toString(), true);
    }
}
