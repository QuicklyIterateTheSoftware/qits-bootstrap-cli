package eu.wohlben.qits.cli.bootstrap.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the payload image is built from. The rest of the host half needs a daemon; this one
 * question does not, and it is the one a COLD machine answers differently: there is no wrapper
 * until the run clones one, so the only checkout on the box is this CLI's own.
 */
class HostLauncherTest {

    @TempDir
    Path temp;

    /** A checkout of this CLI, as the launcher recognises one: it has the Dockerfile. */
    private Path cliCheckout(String at) throws IOException {
        Path root = Files.createDirectories(temp.resolve(at));
        Path dockerfile = root.resolve(PayloadImage.DOCKERFILE);
        Files.createDirectories(dockerfile.getParent());
        Files.writeString(dockerfile, "FROM scratch\n", StandardCharsets.UTF_8);
        return root;
    }

    @Test
    void theWrappersOwnSubmoduleIsTheOrdinaryAnswer() throws IOException {
        Path wrapper = Files.createDirectories(temp.resolve("qits-qits"));
        Path cli = cliCheckout("qits-qits/cli/qits-cli-bootstrap");

        assertThat(HostLauncher.imageContext(wrapper, temp)).contains(cli);
    }

    /**
     * <b>The component layout's path answers too, and this binary has to ship before the wrapper
     * flips.</b> The CLI moved to {@code components/qits-bootstrap/qits-cli-bootstrap}; a launcher
     * that knew only the old path would build the payload from whatever checkout the walk found
     * next, or from nothing at all.
     */
    @Test
    void theComponentLayoutsPathAnswersItToo() throws IOException {
        Path wrapper = Files.createDirectories(temp.resolve("qits-qits"));
        Path cli = cliCheckout("qits-qits/components/qits-bootstrap/qits-cli-bootstrap");

        assertThat(HostLauncher.imageContext(wrapper, temp)).contains(cli);
    }

    /**
     * <b>The renamed repository's path answers, and it answers FIRST.</b> Phase 2 renames this
     * repository qits-bootstrap-cli, so the checkout moves once more — and the order is what makes
     * a half-pulled wrapper build the tree it just moved to rather than the stale one beside it.
     */
    @Test
    void theRenamedPathIsTheFirstAnswer() throws IOException {
        Path wrapper = Files.createDirectories(temp.resolve("qits-qits"));
        Path cli = cliCheckout("qits-qits/components/qits-bootstrap/qits-bootstrap-cli");
        // The pre-rename checkout is still there, whole, as a stale pull leaves it.
        cliCheckout("qits-qits/components/qits-bootstrap/qits-cli-bootstrap");

        assertThat(HostLauncher.CLI_PATHS)
                .containsExactly("components/qits-bootstrap/qits-bootstrap-cli",
                        "components/qits-bootstrap/qits-cli-bootstrap",
                        "cli/qits-cli-bootstrap");
        assertThat(HostLauncher.imageContext(wrapper, temp)).contains(cli);
    }

    /** Both layouts on one machine — a flip in progress — and the newest that holds one wins. */
    @Test
    void thePathThatHoldsACheckoutIsTheOneUsed() throws IOException {
        Path wrapper = Files.createDirectories(temp.resolve("qits-qits"));
        // The old path exists as a bare gitlink with nothing in it, which is what a moved
        // submodule leaves until somebody sweeps it.
        Files.createDirectories(wrapper.resolve("cli/qits-cli-bootstrap"));
        Path cli = cliCheckout("qits-qits/components/qits-bootstrap/qits-cli-bootstrap");

        assertThat(HostLauncher.imageContext(wrapper, temp)).contains(cli);
    }

    @Test
    void aCloneOfThisCliAloneAnswersItOnAColdMachine() throws IOException {
        // curl … | bash: this repository is cloned, built and run, and the wrapper does not exist
        // yet. The wrapper path handed in is where the run WILL clone it.
        Path cli = cliCheckout("qits-cli-bootstrap");

        assertThat(HostLauncher.imageContext(temp.resolve("qits-qits"), cli)).contains(cli);
        // From a directory inside it, too: the walk goes up.
        Path deeper = Files.createDirectories(cli.resolve("target"));
        assertThat(HostLauncher.imageContext(temp.resolve("qits-qits"), deeper)).contains(cli);
    }

    @Test
    void aCloneBesideTheWorkingDirectoryAnswersItToo() throws IOException {
        // The same cold clone, from a script that stepped back out of it so the wrapper is cloned
        // beside this CLI rather than inside it.
        Path cli = cliCheckout("work/qits-cli-bootstrap");
        Path workDir = temp.resolve("work");

        assertThat(HostLauncher.imageContext(workDir.resolve("qits-qits"), workDir)).contains(cli);
    }

    /** The same clone under the renamed repository's directory, which is what GitHub serves now. */
    @Test
    void aCloneBesideTheWorkingDirectoryAnswersUnderTheNewNameToo() throws IOException {
        Path cli = cliCheckout("work/qits-bootstrap-cli");
        Path workDir = temp.resolve("work");

        assertThat(HostLauncher.imageContext(workDir.resolve("qits-qits"), workDir)).contains(cli);
    }

    /**
     * <b>The rule the launcher installs, asserted whole.</b> It is run and it is printed for the
     * operator, and both come from here — a flag dropped on one side would be a rule that either
     * does not take or cannot be typed. The REJECT is what makes a client fall back to IPv4 at
     * once; a DROP leaves it hanging exactly as the ingress mesh's own accept does.
     */
    @Test
    void theIpv6LoopbackRuleRejectsWithAReset() {
        assertThat(HostLauncher.ip6tablesReject(8080))
                .containsExactly("ip6tables", "-I", "INPUT", "-i", "lo", "-p", "tcp",
                        "--dport", "8080", "-j", "REJECT", "--reject-with", "tcp-reset");
    }

    @Test
    void anOrdinaryCheckoutHasNoLinkedGitDirectory() throws IOException {
        // .git is a directory inside the wrapper, so the wrapper's own mount covers it.
        Path wrapper = Files.createDirectories(temp.resolve("qits-qits"));
        Files.createDirectories(wrapper.resolve(".git"));

        assertThat(HostLauncher.linkedGitDir(wrapper)).isEmpty();
    }

    @Test
    void aLinkedWorktreeResolvesToTheCommonGitDirectory() throws IOException {
        // The layout `git worktree add` leaves behind: a pointer file in the checkout, a slice
        // under <common>/worktrees/<name>, and `commondir` pointing back to the shared .git —
        // which is the answer, because the submodules' git directories live under its modules/.
        Path common = Files.createDirectories(temp.resolve("primary/.git"));
        Path slice = Files.createDirectories(common.resolve("worktrees/qits-qits-swarm"));
        Files.writeString(slice.resolve("commondir"), "../..\n", StandardCharsets.UTF_8);
        Path wrapper = Files.createDirectories(temp.resolve("qits-qits-swarm"));
        Files.writeString(wrapper.resolve(".git"), "gitdir: " + slice + "\n",
                StandardCharsets.UTF_8);

        assertThat(HostLauncher.linkedGitDir(wrapper)).contains(common);
    }

    @Test
    void aPointerWithoutACommondirStillMountsWhatItNames() throws IOException {
        // A submodule-style gitfile with no worktree bookkeeping: the target itself is the best
        // available answer rather than nothing.
        Path gitDir = Files.createDirectories(temp.resolve("elsewhere/.git/modules/thing"));
        Path checkout = Files.createDirectories(temp.resolve("thing"));
        Files.writeString(checkout.resolve(".git"), "gitdir: " + gitDir + "\n",
                StandardCharsets.UTF_8);

        assertThat(HostLauncher.linkedGitDir(checkout)).contains(gitDir);
    }

    @Test
    void everySubmodulesPointerIsResolvedNotOnlyTheWrappersOwn() throws IOException {
        // A worktree of a wrapper whose submodule carries an EMBEDDED .git: that submodule's
        // slice lives under the embedded directory, beside the primary's .git rather than in it.
        Path common = Files.createDirectories(temp.resolve("primary/.git"));
        Path wrapperSlice = Files.createDirectories(common.resolve("worktrees/w"));
        Files.writeString(wrapperSlice.resolve("commondir"), "../..\n", StandardCharsets.UTF_8);
        Path embedded = Files.createDirectories(
                temp.resolve("primary/services/qits-platform-edge/.git"));
        Path embeddedSlice = Files.createDirectories(embedded.resolve("worktrees/w"));
        Files.writeString(embeddedSlice.resolve("commondir"), "../..\n", StandardCharsets.UTF_8);

        Path wrapper = Files.createDirectories(temp.resolve("w"));
        Files.writeString(wrapper.resolve(".git"), "gitdir: " + wrapperSlice + "\n",
                StandardCharsets.UTF_8);
        Path sub = Files.createDirectories(wrapper.resolve("services/qits-platform-edge"));
        Files.writeString(sub.resolve(".git"), "gitdir: " + embeddedSlice + "\n",
                StandardCharsets.UTF_8);
        // An ordinary submodule directory without a pointer file answers nothing.
        Files.createDirectories(wrapper.resolve("services/qits-gateway"));

        assertThat(HostLauncher.linkedGitDirs(wrapper)).containsExactly(common, embedded);
    }

    @Test
    void aWrapperWithUninitialisedSubmodulesIsNotOneAndSaysSoByFindingNothing()
            throws IOException {
        // The gitlink is there, the checkout is not, so there is no Dockerfile to build from. The
        // launcher's message then names every place it looked.
        Files.createDirectories(temp.resolve("qits-qits/cli/qits-cli-bootstrap"));
        Files.createDirectories(
                temp.resolve("qits-qits/components/qits-bootstrap/qits-cli-bootstrap"));

        assertThat(HostLauncher.imageContext(temp.resolve("qits-qits"), temp)).isEmpty();
    }

    /**
     * <b>A worktree of a COMPONENT-layout wrapper: its submodules sit three levels down.</b> The
     * walk used to stop at two, so every source clone inside the container would have died with
     * "not a git repository" — the git directory the pointer names was never mounted.
     */
    @Test
    void aComponentLayoutsSubmodulePointersAreResolvedThreeLevelsDown() throws IOException {
        Path common = Files.createDirectories(temp.resolve("primary/.git"));
        Path wrapperSlice = Files.createDirectories(common.resolve("worktrees/w"));
        Files.writeString(wrapperSlice.resolve("commondir"), "../..\n", StandardCharsets.UTF_8);
        Path embedded = Files.createDirectories(
                temp.resolve("primary/components/qits-ci/qits-ci/.git"));
        Path embeddedSlice = Files.createDirectories(embedded.resolve("worktrees/w"));
        Files.writeString(embeddedSlice.resolve("commondir"), "../..\n", StandardCharsets.UTF_8);

        Path wrapper = Files.createDirectories(temp.resolve("w"));
        Files.writeString(wrapper.resolve(".git"), "gitdir: " + wrapperSlice + "\n",
                StandardCharsets.UTF_8);
        Path sub = Files.createDirectories(wrapper.resolve("components/qits-ci/qits-ci"));
        Files.writeString(sub.resolve(".git"), "gitdir: " + embeddedSlice + "\n",
                StandardCharsets.UTF_8);

        assertThat(HostLauncher.linkedGitDirs(wrapper)).containsExactly(common, embedded);
    }
}
