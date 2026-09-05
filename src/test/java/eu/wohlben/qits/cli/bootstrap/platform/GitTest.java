package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The git commands whose flags a boot depends on. What is asserted is the command line, the same
 * way the launcher's {@code docker run} argv is: a flag dropped here is a rerun that dies at exit
 * 128 in the middle of a seed image build.
 */
class GitTest {

    /**
     * <b>{@code --checkout --force}, and both are load-bearing.</b> Every service repository
     * declares {@code update = merge} for its frontend submodule, so without {@code --checkout} a
     * rerun whose checkout moved to another release tag asks git to merge two shallow, unrelated
     * histories and gets {@code refusing to merge unrelated histories}; without {@code --force} the
     * previous shallow tree standing at another commit blocks the checkout that replaces it.
     */
    @Test
    void theSubmoduleRefreshStandsTheNestedTreeAtTheGitlinkWhateverWasThere() {
        assertThat(Git.submodulesShallowCommand(Path.of("/src/qits-githost-service")))
                .containsExactly("git", "-C", "/src/qits-githost-service", "submodule", "update",
                        "--init", "--checkout", "--force", "--depth", "1");
    }

    /**
     * {@code --depth 1} stays: a gitlink that is no longer its frontend's branch tip is fetched by
     * sha, which GitHub answers for any reachable commit — measured against a release four days
     * old, so there is no full-fetch fallback to add.
     */
    @Test
    void theRefreshStaysShallow() {
        assertThat(Git.submodulesShallowCommand(Path.of("/src/x")))
                .containsSequence("--depth", "1");
    }
}
