package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wrapper's own account of where its repositories sit. It is the authority the name-derived
 * layout used to stand in for, and the reason a reorganised wrapper needs no release of this CLI to
 * be followed.
 */
class GitModulesTest {

    @TempDir
    Path temp;

    @Test
    void anEntryIsFoundByItsName() {
        GitModules modules = GitModules.parse("""
                [submodule "qits-ci"]
                	path = services/qits-ci
                	url = ../qits-ci.git
                	ignore = all
                	branch = main
                	update = merge
                [submodule "qits-spa-ci"]
                	path = frontends/qits-spa-ci
                	url = ../qits-spa-ci.git
                """);

        assertThat(modules.path("qits-ci")).contains("services/qits-ci");
        assertThat(modules.path("qits-spa-ci")).contains("frontends/qits-spa-ci");
        assertThat(modules.declares("qits-ci")).isTrue();
        assertThat(modules.declares("qits-nothing")).isFalse();
        assertThat(modules.isEmpty()).isFalse();
    }

    /**
     * <b>The component layout, which is the whole reason this class exists.</b> A component is not
     * derivable from a repository's name — qits-spa-ci belongs to qits-ci — so nothing but the file
     * can say where a repository sits.
     */
    @Test
    void aComponentPathIsReadBackWhole() {
        GitModules modules = GitModules.parse("""
                [submodule "qits-ci"]
                	path = components/qits-ci/qits-ci
                	url = ../qits-ci.git
                [submodule "qits-spa-ci"]
                	path = components/qits-ci/qits-spa-ci
                	url = ../qits-spa-ci.git
                """);

        assertThat(modules.path("qits-ci")).contains("components/qits-ci/qits-ci");
        assertThat(modules.path("qits-spa-ci")).contains("components/qits-ci/qits-spa-ci");
        assertThat(modules.declaredPaths())
                .containsExactly("components/qits-ci/qits-ci", "components/qits-ci/qits-spa-ci");
    }

    /**
     * <b>The entry git leaves when somebody adds a submodule without {@code --name}</b>: the name is
     * the whole path. The wrapper's convention is the bare repository name, but a checkout carrying
     * one of these still has to resolve, so the path's last segment answers too.
     */
    @Test
    void aFullPathNamedEntryIsStillFoundByItsRepository() {
        GitModules modules = GitModules.parse("""
                [submodule "components/qits-ci/qits-spa-ci"]
                	path = components/qits-ci/qits-spa-ci
                	url = ../qits-spa-ci.git
                """);

        assertThat(modules.path("qits-spa-ci")).contains("components/qits-ci/qits-spa-ci");
        assertThat(modules.declares("qits-spa-ci")).isTrue();
    }

    /**
     * <b>The wrapper of 2026-08-30 really carries these.</b> Four entries are named for a rename
     * that has not happened — qits-events is declared as {@code qits-platform-events} — so the name
     * and the path both miss and the URL is the only honest answer. Without this arm the boot
     * builds those four from the org while the operator's checkout sits in the wrapper.
     */
    @Test
    void anEntryNamedForARenameThatHasNotHappenedIsFoundByItsUrl() {
        GitModules modules = GitModules.parse("""
                [submodule "qits-platform-events"]
                	path = services/qits-platform-events
                	url = ../qits-events.git
                	ignore = all
                """);

        assertThat(modules.path("qits-events")).contains("services/qits-platform-events");
        assertThat(modules.declares("qits-events")).isTrue();
        // The name still answers for itself, so nothing that used it stops working.
        assertThat(modules.path("qits-platform-events")).contains("services/qits-platform-events");
    }

    /** An absolute url is read the same way, and a trailing slash is not a repository name. */
    @Test
    void aUrlIsReadDownToItsRepository() {
        GitModules modules = GitModules.parse("""
                [submodule "whatever"]
                	path = components/qits-ci/qits-ci
                	url = https://github.com/QuicklyIterateTheSoftware/qits-ci.git
                """);

        assertThat(modules.path("qits-ci")).contains("components/qits-ci/qits-ci");
    }

    /** A section with no path declares no submodule, whatever else it carries. */
    @Test
    void aSectionWithNoPathIsNoDeclaration() {
        assertThat(GitModules.parse("""
                [submodule "qits-ci"]
                	url = ../qits-ci.git
                """).isEmpty()).isTrue();
    }

    @Test
    void aMissingFileDeclaresNothingRatherThanThrowing() {
        assertThat(GitModules.of(temp.resolve("no-such-wrapper")).isEmpty()).isTrue();
        assertThat(GitModules.of(temp).isEmpty()).isTrue();
        assertThat(GitModules.of(null).isEmpty()).isTrue();
        assertThat(GitModules.none().path("qits-ci")).isEmpty();
    }

    @Test
    void theFileIsReadFromTheWrapperRoot() throws IOException {
        Path wrapper = Files.createDirectories(temp.resolve("qits-qits"));
        Files.writeString(wrapper.resolve(GitModules.FILE), """
                [submodule "qits-artifacts"]
                	path = components/qits-artifacts/qits-artifacts
                	url = ../qits-artifacts.git
                """, StandardCharsets.UTF_8);

        assertThat(GitModules.of(wrapper).declaresAny(List.of("qits-artifacts"))).isTrue();
        assertThat(GitModules.of(wrapper).declaresAny(List.of("qits-elsewhere"))).isFalse();
    }

    /** Comments and blank lines are the file's, not this parser's business. */
    @Test
    void commentsAndStraysAreIgnored() {
        GitModules modules = GitModules.parse("""
                # the wrapper's submodules
                ; another comment syntax git accepts

                [submodule "qits-ci"]
                	path = services/qits-ci
                """);

        assertThat(modules.path("qits-ci")).contains("services/qits-ci");
        assertThat(modules.declaredPaths()).containsExactly("services/qits-ci");
    }

    /** A path before any section belongs to nothing, and is not a declaration. */
    @Test
    void aPathWithNoSectionDeclaresNothing() {
        assertThat(GitModules.parse("path = services/qits-ci\n").isEmpty()).isTrue();
    }
}
