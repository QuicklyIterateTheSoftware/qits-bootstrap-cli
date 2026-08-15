package eu.wohlben.qits.cli.bootstrap.host;

import eu.wohlben.qits.cli.bootstrap.config.BootstrapConfig;
import eu.wohlben.qits.cli.bootstrap.config.TestConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code docker run} the host half composes. Every flag on it is there for a reason the boot
 * fails without, so the argv is asserted whole: a flag dropped in a refactor is a bootstrap that
 * gets further than it should before it breaks.
 */
class ContainerRunTest {

    private static final Path WRAPPER = Path.of("/home/dev/qits-qits");
    private static final String IMAGE = "qits-bootstrap:0123456789ab";

    private static ContainerRun.Plan plan(BootstrapConfig config, Map<String, String> environment,
                                          Path workDir, Path sources, boolean tty,
                                          List<String> args) {
        return plan(config, environment, WRAPPER, true, workDir, sources, tty, args);
    }

    private static ContainerRun.Plan plan(BootstrapConfig config, Map<String, String> environment,
                                          Path wrapper, boolean wrapperOnHost, Path workDir,
                                          Path sources, boolean tty, List<String> args) {
        return new ContainerRun.Plan(IMAGE, wrapper, wrapperOnHost, List.of(), workDir, sources,
                workDir.resolve("qits-bootstrap-cli.log"),
                workDir.resolve(".qits-bootstrap-progress.json"), "1000:1000", "988", tty,
                config, environment, args);
    }

    private static ContainerRun.Plan standard() {
        return plan(TestConfig.from(Map.of()), Map.of(), WRAPPER,
                WRAPPER.resolve("cli/qits-cli-bootstrap/.qits-bootstrap-src"), true,
                List.of("bootstrap"));
    }

    @Test
    void theWholeCommand() {
        assertThat(ContainerRun.command(standard())).containsExactly(
                "docker", "run", "--rm", "--name", "qits-bootstrap-cli",
                "-it",
                "-v", "/var/run/docker.sock:/var/run/docker.sock",
                "--user", "1000:1000",
                "--group-add", "988",
                "-v", "/home/dev/qits-qits:/home/dev/qits-qits",
                "-w", "/home/dev/qits-qits",
                "-e", "HOME=/tmp",
                "-e", "TZ=" + ZoneId.systemDefault().getId(),
                "-e", "QITS_IN_CONTAINER=1",
                "-e", "QITS_WRAPPER_DIR=/home/dev/qits-qits",
                "-e", "QITS_WEB_BIND=false",
                "-e", "QITS_WEB_HOST=0.0.0.0",
                "-e", "QITS_PROGRESS_FILE=/home/dev/qits-qits/.qits-bootstrap-progress.json",
                IMAGE, "bootstrap");
    }

    @Test
    void theArgumentsAreRelayedVerbatim() {
        List<String> args = List.of("unwrap", "--with-data-volumes", "--dry-run");
        List<String> argv = ContainerRun.command(plan(TestConfig.from(Map.of()), Map.of(), WRAPPER,
                WRAPPER.resolve(".qits-bootstrap-src"), true, args));
        assertThat(argv.subList(argv.indexOf(IMAGE), argv.size()))
                .containsExactly(IMAGE, "unwrap", "--with-data-volumes", "--dry-run");
    }

    @Test
    void noTerminalMeansNoTty() {
        // A pipe or a CI job. `-it` there is docker's "the input device is not a TTY", and the
        // display falls back to plain lines by itself.
        List<String> argv = ContainerRun.command(plan(TestConfig.from(Map.of()), Map.of(), WRAPPER,
                WRAPPER.resolve(".qits-bootstrap-src"), false, List.of("bootstrap")));
        assertThat(argv).doesNotContain("-it");
    }

    @Test
    void secretsAreHandedOverByNameAndNeverAsValues() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("QITS_PG_SUPERUSER_PASSWORD", "0123456789abcdef");
        environment.put("QITS_PUSH_TOKEN", "local-dev");
        environment.put("PATH", "/usr/bin");

        List<String> argv = ContainerRun.command(plan(TestConfig.from(Map.of()), environment,
                WRAPPER, WRAPPER.resolve(".qits-bootstrap-src"), true, List.of("bootstrap")));

        assertThat(argv).contains("QITS_PG_SUPERUSER_PASSWORD", "QITS_PUSH_TOKEN");
        assertThat(String.join(" ", argv)).doesNotContain("0123456789abcdef");
        // Only QITS_* travels; the container's own PATH is the image's.
        assertThat(argv).doesNotContain("PATH");
    }

    @Test
    void whatTheLauncherDecidesIsNotHandedThroughTwice() {
        Map<String, String> environment = Map.of(
                "QITS_WRAPPER_DIR", "/somewhere/else",
                "QITS_WEB_HOST", "127.0.0.1");
        List<String> argv = ContainerRun.command(plan(TestConfig.from(Map.of()), environment,
                WRAPPER, WRAPPER.resolve(".qits-bootstrap-src"), true, List.of("bootstrap")));

        // The wrapper was resolved on the host and is told, not re-derived: the walk up from the
        // working directory must not be what decides it inside a container.
        assertThat(argv).contains("QITS_WRAPPER_DIR=/home/dev/qits-qits");
        assertThat(argv).doesNotContain("QITS_WRAPPER_DIR");
        assertThat(argv).doesNotContain("QITS_WEB_HOST");
    }

    @Test
    void keepItOffTheLanMovesToThePublish() {
        // QITS_WEB_HOST answers who can reach the view. Inside a container that boundary is the
        // publish: the server binds every interface it has, and the host side is where 127.0.0.1
        // still means loopback only.
        BootstrapConfig config = TestConfig.from(Map.of("QITS_WEB_HOST", "127.0.0.1"));
        List<String> argv = ContainerRun.command(plan(config, Map.of(), WRAPPER,
                WRAPPER.resolve(".qits-bootstrap-src"), true, List.of("bootstrap")));

        assertThat(argv).doesNotContain("-p");
        assertThat(argv).contains("QITS_WEB_HOST=0.0.0.0");
    }

    @Test
    void webOffPublishesNothing() {
        BootstrapConfig config = TestConfig.from(Map.of("QITS_WEB", "false"));
        List<String> argv = ContainerRun.command(plan(config, Map.of(), WRAPPER,
                WRAPPER.resolve(".qits-bootstrap-src"), true, List.of("bootstrap")));

        assertThat(argv).doesNotContain("-p");
        assertThat(argv).contains("QITS_WEB_HOST=0.0.0.0");
        // The payload is told not to bind either: only the launcher knows which half may.
        assertThat(argv).contains("QITS_WEB_BIND=false");
        assertThat(argv).doesNotContain("QITS_PROGRESS_FILE=/home/dev/qits-qits/"
                + ".qits-bootstrap-progress.json");
    }

    @Test
    void aMovedPortIsPublishedByTheSupervisorNotTheWorker() {
        BootstrapConfig config = TestConfig.from(Map.of("QITS_WEB_PORT", "8481"));
        List<String> argv = ContainerRun.command(plan(config, Map.of(), WRAPPER,
                WRAPPER.resolve(".qits-bootstrap-src"), true, List.of("bootstrap")));

        assertThat(argv).doesNotContain("-p", "8481:8481");
    }

    @Test
    void whatIsAlreadyInsideAMountIsNotASecondMount() {
        // The ordinary shape: the working directory, the clones and the log all sit under the
        // wrapper, so one bind covers the lot.
        assertThat(ContainerRun.mounts(standard())).containsExactly(WRAPPER);
    }

    @Test
    void aLinkedWorktreesGitDirectoriesAreMountedToo() {
        // The wrapper checked out as a `git worktree`: its .git is a pointer file into the primary
        // checkout, as is every submodule's, and phase 4 clones the sources through them. Most
        // point under the primary's .git; a submodule with an embedded .git points beside it, and
        // the duplicates collapse.
        Path common = Path.of("/home/dev/primary/.git");
        Path embedded = Path.of("/home/dev/primary/services/qits-platform-edge/.git");
        ContainerRun.Plan plan = new ContainerRun.Plan(IMAGE, WRAPPER, true,
                List.of(common, common, embedded), WRAPPER,
                WRAPPER.resolve(".qits-bootstrap-src"), WRAPPER.resolve("qits-bootstrap-cli.log"),
                WRAPPER.resolve(".qits-bootstrap-progress.json"), "1000:1000", "988", true,
                TestConfig.from(Map.of()), Map.of(),
                List.of("bootstrap"));
        assertThat(ContainerRun.mounts(plan)).containsExactly(common, embedded, WRAPPER);
    }

    @Test
    void stateThatLivesOutsideTheWrapperIsMountedToo() {
        // The clones on another disk. They have to mean the same path inside: the payload's build
        // contexts and `docker cp` sources are read by the CLIENT, in the container.
        Path sources = Path.of("/data/qits-src");
        assertThat(ContainerRun.mounts(plan(TestConfig.from(Map.of()), Map.of(), WRAPPER, sources,
                true, List.of("bootstrap"))))
                .containsExactly(Path.of("/data/qits-src"), WRAPPER);
    }

    /** The cold start: a bare machine, a working directory, and no checkout anywhere. */
    private static ContainerRun.Plan cold() {
        Path workDir = Path.of("/home/dev");
        return plan(TestConfig.from(Map.of()), Map.of(), workDir.resolve("qits-qits"), false,
                workDir, workDir.resolve(".qits-bootstrap-src"), true, List.of("bootstrap"));
    }

    @Test
    void aWrapperThatIsNotThereYetIsNotMounted() {
        // Docker creates a missing bind source as a ROOT-owned directory, and the run is
        // --user <uid>:<gid> — so mounting it would hand the clone a mount point it cannot write.
        // The working directory covers it: the wrapper is cloned inside it, in the container, and
        // the checkout lands on the host as the user who typed the command.
        assertThat(ContainerRun.mounts(cold())).containsExactly(Path.of("/home/dev"));
        assertThat(ContainerRun.command(cold()))
                .doesNotContain("/home/dev/qits-qits:/home/dev/qits-qits");
    }

    @Test
    void aColdStartIsStillToldWhereTheWrapperGoes() {
        // The path is not mounted, but it is still the agreed one: the payload clones it there and
        // the walk up from the working directory must not be what decides it.
        assertThat(ContainerRun.command(cold()))
                .contains("QITS_WRAPPER_DIR=/home/dev/qits-qits");
        assertThat(ContainerRun.command(cold())).containsSequence("-w", "/home/dev");
    }

    @Test
    void aWorkingDirectoryAboveTheWrapperSwallowsIt() {
        // Running from outside the checkout with QITS_WRAPPER_DIR set. The working directory has to
        // be mounted whatever it is — it is where `.env` is read from, and a missing one would
        // silently configure the two halves differently — so the wrapper's own bind is redundant.
        Path workDir = Path.of("/home/dev");
        assertThat(ContainerRun.mounts(plan(TestConfig.from(Map.of()), Map.of(), workDir,
                WRAPPER.resolve(".qits-bootstrap-src"), true, List.of("bootstrap"))))
                .containsExactly(workDir);
    }
}
