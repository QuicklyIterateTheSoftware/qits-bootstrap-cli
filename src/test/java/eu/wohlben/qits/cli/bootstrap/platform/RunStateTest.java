package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Where the run looks for one repository's sources, and who decides.</b> The wrapper's own
 * {@code .gitmodules} does; the layout a name implies is what answers when there is no wrapper to
 * ask. Getting this wrong is the boot that builds the org's last push in silence, which is why it
 * is pinned rather than reasoned about.
 */
class RunStateTest {

    @TempDir
    Path temp;

    private RunState wrapperDeclaring(String gitmodules) throws IOException {
        Files.writeString(temp.resolve(GitModules.FILE), gitmodules, StandardCharsets.UTF_8);
        RunState state = new RunState();
        state.wrapperDir = temp;
        return state;
    }

    @Test
    void theWrapperSaysWhereARepositorySits() throws IOException {
        RunState state = wrapperDeclaring("""
                [submodule "qits-ci"]
                	path = components/qits-ci/qits-ci
                [submodule "qits-spa-ci"]
                	path = components/qits-ci/qits-spa-ci
                """);

        assertThat(state.wrapperPath("ci")).isEqualTo("components/qits-ci/qits-ci");
        assertThat(state.wrapperCheckout("spa-ci"))
                .isEqualTo(temp.resolve("components/qits-ci/qits-spa-ci"));
        assertThat(state.wrapperDeclares("ci")).isTrue();
        assertThat(state.wrapperDeclares("docs")).isFalse();
    }

    /** The old layout is read the same way, because it is read rather than derived. */
    @Test
    void anArchetypeWrapperIsFollowedJustAsLiterally() throws IOException {
        RunState state = wrapperDeclaring("""
                [submodule "qits-ci"]
                	path = services/qits-ci
                """);

        assertThat(state.wrapperCheckout("ci")).isEqualTo(temp.resolve("services/qits-ci"));
    }

    /**
     * A repository the wrapper does not declare falls back to the layout its name implies — the
     * cold start's answer, where there is no wrapper on the machine at all yet.
     */
    @Test
    void anUndeclaredRepositoryFallsBackToTheNamesLayout() throws IOException {
        RunState state = wrapperDeclaring("""
                [submodule "qits-ci"]
                	path = components/qits-ci/qits-ci
                """);

        assertThat(state.wrapperPath("spa-docs")).isEqualTo("frontends/qits-spa-docs");

        RunState bare = new RunState();
        bare.wrapperDir = temp.resolve("not-cloned-yet");
        assertThat(bare.wrapperPath("ci")).isEqualTo("services/qits-ci");
    }

    /**
     * <b>The file is re-read while there is still nothing to read.</b> A cold start asks before the
     * {@code wrapper} phase has cloned anything, and caching that answer would send the whole boot
     * to the fallback layout for a wrapper that had since arrived.
     */
    @Test
    void aWrapperThatArrivesMidRunIsPickedUp() throws IOException {
        RunState state = new RunState();
        state.wrapperDir = temp;
        assertThat(state.wrapperPath("ci")).isEqualTo("services/qits-ci");

        Files.writeString(temp.resolve(GitModules.FILE),
                "[submodule \"qits-ci\"]\n\tpath = components/qits-ci/qits-ci\n",
                StandardCharsets.UTF_8);

        assertThat(state.wrapperPath("ci")).isEqualTo("components/qits-ci/qits-ci");
    }

    /**
     * <b>"Has this wrapper any submodule checked out" is the line between a cold start and a
     * half-initialised checkout</b>, and it is what {@code SeedPhases.sources} refuses on.
     */
    @Test
    void aWrapperWithNoSubmoduleCheckedOutSaysSo() throws IOException {
        RunState state = wrapperDeclaring("""
                [submodule "qits-ci"]
                	path = components/qits-ci/qits-ci
                """);

        assertThat(state.wrapperInitialised(path -> false)).isFalse();
        assertThat(state.wrapperInitialised(
                path -> path.equals(temp.resolve("components/qits-ci/qits-ci")))).isTrue();
    }
}
