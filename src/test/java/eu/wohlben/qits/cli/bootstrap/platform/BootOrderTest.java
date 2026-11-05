package eu.wohlben.qits.cli.bootstrap.platform;

import eu.wohlben.qits.bootstrap.graph.Coordinate;
import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import eu.wohlben.qits.bootstrap.graph.Pin;
import eu.wohlben.qits.bootstrap.graph.ProjectGraph;
import eu.wohlben.qits.bootstrap.graph.ProjectSource;
import eu.wohlben.qits.bootstrap.graph.manifest.Deployment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BootOrderTest {

    /** Repositories pinning each other's gitlinks, deploying as given. */
    private record Source(Map<String, List<String>> gitlinks, Map<String, Deployment> deployments,
                          Map<String, Set<Coordinate>> produces, Map<String, List<Pin>> pins)
            implements ProjectSource {

        @Override
        public List<String> repositories() {
            return gitlinks.keySet().stream().sorted().toList();
        }

        @Override
        public List<Pin> consumes(String repository) {
            List<Pin> all = new java.util.ArrayList<>(pins.getOrDefault(repository, List.of()));
            for (String upstream : gitlinks.get(repository)) {
                all.add(new Pin(new Coordinate(Ecosystem.GITLINK, upstream), "abc123", ".gitmodules",
                        "gitlink:" + upstream));
            }
            return all;
        }

        @Override
        public Set<Coordinate> produces(String repository) {
            return produces.getOrDefault(repository, Set.of());
        }

        @Override
        public Optional<Deployment> deployment(String repository) {
            return Optional.ofNullable(deployments.get(repository));
        }
    }

    @Test
    void aGitlinkCycleIsRefusedNamingBothMembers() {
        Source source = new Source(Map.of("qits-a", List.of("qits-b"), "qits-b", List.of("qits-a")),
                Map.of(), Map.of(), Map.of());
        ProjectGraph graph = ProjectGraph.of(source, CheckoutProjectSource.RESOURCE_PROVIDERS);

        assertThatThrownBy(() -> BootOrder.derive(graph, source))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("dependency cycle: qits-a, qits-b");
    }

    @Test
    void aReplayNeedsAReleasePinFromAnotherRepository() {
        Coordinate lib = new Coordinate(Ecosystem.MAVEN, "eu.wohlben.qits:qits-lib");
        Coordinate snap = new Coordinate(Ecosystem.MAVEN, "eu.wohlben.qits:qits-snap");
        Source source = new Source(
                Map.of("qits-lib-javalib", List.of(), "qits-snap-javalib", List.of(),
                        "qits-x-service", List.of()),
                Map.of("qits-x-service", new Deployment("qits-x", List.of())),
                Map.of("qits-lib-javalib", Set.of(lib), "qits-snap-javalib", Set.of(snap)),
                Map.of("qits-x-service", List.of(
                        new Pin(lib, "2026.1105.103222", "pom.xml", "x"),
                        new Pin(snap, "1.0.0-SNAPSHOT", "pom.xml", "y"))));
        ProjectGraph graph = ProjectGraph.of(source, CheckoutProjectSource.RESOURCE_PROVIDERS);

        BootOrder order = BootOrder.derive(graph, source);

        assertThat(order.deploys()).containsExactly("qits-x-service");
        assertThat(order.pushes()).containsExactly("qits-lib-javalib", "qits-snap-javalib");
        assertThat(order.replays()).containsExactly("qits-lib-javalib");
        assertThat(order.unnamed()).containsExactlyInAnyOrder("qits-lib-javalib",
                "qits-snap-javalib", "qits-x-service");
    }

    @Test
    void aCycleAmongTheCutoverRulesIsRefusedNamingIt() {
        Source source = new Source(Map.of("qits-a", List.of(), "qits-b", List.of()),
                Map.of("qits-a", new Deployment("qits-a", List.of()),
                        "qits-b", new Deployment("qits-b", List.of())),
                Map.of(), Map.of());
        ProjectGraph graph = ProjectGraph.of(source, CheckoutProjectSource.RESOURCE_PROVIDERS);
        List<CutoverRules.Rule> rules = List.of(new CutoverRules.Rule("qits-a", "qits-b", "one"),
                new CutoverRules.Rule("qits-b", "qits-a", "two"));

        assertThatThrownBy(() -> BootOrder.derive(graph, source, rules))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("deploy order cycle: qits-a, qits-b");
    }

    @Test
    void releaseVersionsAreCalver() {
        assertThat(BootOrder.RELEASE_VERSION.matcher("2026.1105.103222").matches()).isTrue();
        assertThat(BootOrder.RELEASE_VERSION.matcher("2026.905.92439").matches()).isTrue();
        assertThat(BootOrder.RELEASE_VERSION.matcher("1.0.0-SNAPSHOT").matches()).isFalse();
        assertThat(BootOrder.RELEASE_VERSION.matcher("latest").matches()).isFalse();
    }

    @Test
    void internalIsMaintenancesNameRule() {
        assertThat(CheckoutProjectSource.internal(Ecosystem.MAVEN, "eu.wohlben.qits:qits-ci")).isTrue();
        assertThat(CheckoutProjectSource.internal(Ecosystem.MAVEN, "eu.wohlben.qits.x:y")).isTrue();
        assertThat(CheckoutProjectSource.internal(Ecosystem.MAVEN, "eu.wohlben.qitsy:y")).isFalse();
        assertThat(CheckoutProjectSource.internal(Ecosystem.NPM, "@qits/angular")).isTrue();
        assertThat(CheckoutProjectSource.internal(Ecosystem.NPM, "@angular/core")).isFalse();
        assertThat(CheckoutProjectSource.internal(Ecosystem.DOCKER, "qits/workspace")).isTrue();
        assertThat(CheckoutProjectSource.internal(Ecosystem.DOCKER, "library/postgres")).isFalse();
        assertThat(CheckoutProjectSource.internal(Ecosystem.GITLINK, "anything")).isTrue();
    }
}
