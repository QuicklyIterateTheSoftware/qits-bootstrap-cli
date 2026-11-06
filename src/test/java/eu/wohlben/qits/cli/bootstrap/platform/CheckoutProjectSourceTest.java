package eu.wohlben.qits.cli.bootstrap.platform;

import eu.wohlben.qits.bootstrap.graph.Coordinate;
import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import eu.wohlben.qits.bootstrap.graph.Pin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CheckoutProjectSource#merge}, apart from a {@code RepositoryFiles} — the dedupe that
 * makes {@link CheckoutProjectSource#pins} robust to both qits-bootstrap-graph versions. Before the
 * release carrying qits-1914, {@code ManifestReader} never resolves a pnpm-locked npm coordinate,
 * so {@code resolved} never covers one and every lockless pin is kept (the case the other tests
 * in this package exercise through the fixture). From that release on, the library pins the same
 * coordinate itself, and this is what stops {@code lockless} from doubling it.
 */
class CheckoutProjectSourceTest {

    private static Pin npm(String name, String version, String manifestPath, String location) {
        return new Pin(new Coordinate(Ecosystem.NPM, name), version, manifestPath, location);
    }

    @Test
    void aLocklessPinAlreadyResolvedIsDropped() {
        Pin resolved = npm("@qits/angular", "2026.1103.125108", "package.json", "devDependencies");
        Pin duplicate = npm("@qits/angular", "^2026.1.0", "package.json", "devDependencies");

        assertThat(CheckoutProjectSource.merge(List.of(resolved), List.of(duplicate)))
                .as("the library's own resolution wins; the lockless range is not added beside it")
                .containsExactly(resolved);
    }

    @Test
    void aLocklessPinOfAnUnresolvedCoordinateIsKept() {
        Pin resolved = npm("@qits/angular", "2026.1103.125108", "package.json", "dependencies");
        Pin other = npm("@qits/qits-ui-components", "^2026.1.0", "package.json", "devDependencies");

        assertThat(CheckoutProjectSource.merge(List.of(resolved), List.of(other)))
                .as("today's whole-repository case: nothing resolved, so lockless covers everything")
                .containsExactly(resolved, other);
    }

    @Test
    void sameCoordinateAtADifferentManifestIsNotADuplicate() {
        Pin resolved = npm("@qits/angular", "2026.1103.125108", "package.json", "dependencies");
        Pin elsewhere = npm("@qits/angular", "^2026.1.0", "harness/package.json", "devDependencies");

        assertThat(CheckoutProjectSource.merge(List.of(resolved), List.of(elsewhere)))
                .as("manifestPath is part of the key: a pin from another manifest is its own pin")
                .containsExactly(resolved, elsewhere);
    }

    @Test
    void noLocklessPinsLeavesResolvedAsIs() {
        Pin resolved = npm("@qits/angular", "2026.1103.125108", "package.json", "dependencies");

        assertThat(CheckoutProjectSource.merge(List.of(resolved), List.of()))
                .containsExactly(resolved);
    }
}
