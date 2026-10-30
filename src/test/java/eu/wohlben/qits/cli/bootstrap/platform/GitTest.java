package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

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

    /**
     * <b>A release whose tag is gone is found by its release commit</b> — the subject the release
     * wrote, at its START: a bump that quotes a release in passing is not that release.
     */
    @Test
    void aReleaseCommitIsTheOneWhoseSubjectStartsWithIt() {
        assertThat(Git.releaseCommitIn(List.of(
                "0123abcd bump(qits-1): 1 dependencies after release(2026.1028.12758): x",
                "6dbeb40a release(2026.1028.12758): qits-1690: a rolled-back deployment",
                "9999aaaa release(2026.1028.12758): a second one is never read"),
                "2026.1028.12758")).isEqualTo("6dbeb40a");
        assertThat(Git.releaseCommitIn(List.of("6dbeb40a release(2026.1028.1275): near miss"),
                "2026.1028.12758")).isNull();
        // Fixed strings, every ref: the parentheses are no regex group, and a commit off main is
        // still the release's.
        assertThat(Git.releaseCommitSearch("2026.1028.12758")).containsExactly("log", "--all",
                "--format=%H %s", "--fixed-strings", "--grep=release(2026.1028.12758):");
    }

    /** One directory of one ref, as a tar file, and no checkout: {@code git archive -o}. */
    @Test
    void anArchiveIsOneDirectoryAtOneRef() {
        assertThat(Git.archiveCommand(Path.of("/src/qits-ci-frontend"), "2026.1021.191659",
                "pacts", Path.of("/src/.exports/x.tar")))
                .containsExactly("git", "-C", "/src/qits-ci-frontend", "archive", "--format=tar",
                        "-o", "/src/.exports/x.tar", "2026.1021.191659", "--", "pacts");
    }
}
