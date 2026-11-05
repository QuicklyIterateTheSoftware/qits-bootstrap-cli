package eu.wohlben.qits.cli.bootstrap.platform;

import eu.wohlben.qits.bootstrap.graph.Edge;
import eu.wohlben.qits.bootstrap.graph.EdgeKind;
import eu.wohlben.qits.bootstrap.graph.ProjectGraph;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cutover rules say only what no declaration says, and only about deploys that exist — over
 * the estate fixture.
 */
class CutoverRulesTest {

    private static Set<String> deploys;
    private static Map<String, Set<String>> runtime;

    @BeforeAll
    static void derive() {
        CheckoutProjectSource source = CheckoutProjectSource.read(
                CheckoutProjectSource.Checkouts.fixture(Path.of("src/test/resources/estate")));
        ProjectGraph graph = ProjectGraph.of(source, CheckoutProjectSource.RESOURCE_PROVIDERS);
        deploys = new LinkedHashSet<>(BootOrder.derive(graph, source).deploys());
        runtime = new HashMap<>();
        for (Edge edge : graph.edges()) {
            if (edge.kind() == EdgeKind.RUNTIME) {
                runtime.computeIfAbsent(BootOrder.name(edge.upstream()), k -> new HashSet<>())
                        .add(BootOrder.name(edge.downstream()));
            }
        }
    }

    @Test
    void everyRuleNamesDeploysThatExist() {
        for (CutoverRules.Rule rule : CutoverRules.RULES) {
            if (!CutoverRules.EVERY_OTHER.equals(rule.before())) {
                assertThat(deploys).as("before of %s", rule).contains(rule.before());
            }
            assertThat(deploys).as("after of %s", rule).contains(rule.after());
            assertThat(rule.reason()).as("reason of %s", rule).isNotBlank();
        }
    }

    /** A rule a RUNTIME path already implies is redundant, and a redundant rule is deleted. */
    @Test
    void noRuleIsImpliedByARuntimeEdge() {
        for (CutoverRules.Rule rule : CutoverRules.expand(CutoverRules.RULES, deploys)) {
            if (CutoverRules.EVERY_OTHER.equals(rule.before())) {
                continue;
            }
            boolean wildcard = CutoverRules.RULES.stream().anyMatch(r ->
                    CutoverRules.EVERY_OTHER.equals(r.before()) && r.after().equals(rule.after()));
            if (wildcard) {
                // An expanded wildcard pair is not a rule of its own; the wildcard is redundant
                // only if every one of its pairs is, which the next test checks.
                continue;
            }
            assertThat(reaches(rule.before(), rule.after()))
                    .as("%s is already implied by a RUNTIME edge; delete it", rule).isFalse();
        }
    }

    @Test
    void noWildcardIsWhollyImpliedByRuntimeEdges() {
        for (CutoverRules.Rule wildcard : CutoverRules.RULES) {
            if (!CutoverRules.EVERY_OTHER.equals(wildcard.before())) {
                continue;
            }
            boolean all = CutoverRules.expand(java.util.List.of(wildcard), deploys).stream()
                    .allMatch(pair -> reaches(pair.before(), pair.after()));
            assertThat(all).as("%s is already implied by RUNTIME edges; delete it", wildcard)
                    .isFalse();
        }
    }

    @Test
    void theWildcardsExpandSoTheDeployerIsLastAndTheEdgeLastOfTheRest() {
        var pairs = CutoverRules.expand(CutoverRules.RULES, deploys);
        assertThat(pairs).noneMatch(r -> r.before().equals("deployments"));
        assertThat(pairs).anyMatch(r -> r.before().equals("edge") && r.after().equals("deployments"));
        assertThat(pairs).noneMatch(r -> r.before().equals(r.after()));
    }

    private static boolean reaches(String from, String to) {
        Deque<String> stack = new ArrayDeque<>(runtime.getOrDefault(from, Set.of()));
        Set<String> seen = new HashSet<>();
        while (!stack.isEmpty()) {
            String node = stack.pop();
            if (node.equals(to)) {
                return true;
            }
            if (seen.add(node)) {
                stack.addAll(runtime.getOrDefault(node, Set.of()));
            }
        }
        return false;
    }
}
