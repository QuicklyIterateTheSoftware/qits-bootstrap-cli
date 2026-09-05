package eu.wohlben.qits.cli.bootstrap.platform;

import eu.wohlben.qits.cli.bootstrap.config.BootstrapConfig;
import eu.wohlben.qits.cli.bootstrap.config.TestConfig;
import eu.wohlben.qits.cli.bootstrap.phases.Boot;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessResult;
import eu.wohlben.qits.cli.bootstrap.proc.ScriptedRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>What an image build of this bootstrap actually runs, asserted whole.</b>
 * <p>
 * The builds go to a buildkitd container rather than to the host daemon, and the client that drives
 * it is the same pinned image with its entrypoint replaced — the host has no {@code buildctl}
 * binary. Nothing about that is provable without a daemon except the command lines, and a flag
 * dropped in one of them is a bootstrap that gets minutes in before it breaks.
 * <p>
 * The build argument they all carry is asserted here for a second reason: the repositories declare
 * {@code ARG QITS_MAVEN_REPOSITORY_URL} and their maven settings read it, and the committed default
 * now names the edge vhost — the address of a platform that is RUNNING. A seed build runs minutes
 * before any edge exists, so a build that rode that default died at qits-platform-mirror with a
 * connection refused.
 */
class DockerBuildTest {

    private static final String ARG =
            "build-arg:QITS_MAVEN_REPOSITORY_URL=http://localhost:8081/artifacts/maven/maven";

    private static final String IMAGE = "moby/buildkit:v0.33.0";

    /**
     * Where the scratch directories are made. It is a real directory rather than a name because the
     * facade writes the Dockerfile and the secret files into it for real.
     */
    @TempDir
    Path temp;

    private static BootstrapConfig config(Map<String, String> env) {
        return TestConfig.from(env);
    }

    private Docker docker(ScriptedRunner runner, BootstrapConfig config) {
        return new Docker(runner).withBuildArgs(Boot.imageBuildArgs(config)).withBuildScratch(temp);
    }

    /** A host with no buildkitd and no legacy builders: the ordinary first build of a cold run. */
    private static ScriptedRunner coldHost() {
        return new ScriptedRunner(command ->
                command.contains("inspect") ? ScriptedRunner.failed("No such container")
                        : ScriptedRunner.ok("done"));
    }

    /**
     * The temp directory names are random, so they are replaced by what they ARE before the argv is
     * compared. Everything else is asserted literally.
     */
    private List<String> scrubbed(List<String> argv) {
        String root = Pattern.quote(temp.toString());
        return argv.stream()
                .map(arg -> arg.replaceAll(root + "/dockerfile-[^:]+", "<dockerfiles>")
                        .replaceAll(root + "/image-[^:/]+", "<images>")
                        .replaceAll(root + "/secrets-[^:]+", "<secrets>"))
                .toList();
    }

    /** The build itself, which is the second-to-last command: the last one is the docker load. */
    private List<String> buildArgv(ScriptedRunner runner) {
        return scrubbed(runner.argv.get(runner.argv.size() - 2));
    }

    // --- the builder container ---------------------------------------------------------------

    /**
     * <b>The bounds the buildx driver-opts used to carry, as docker run flags.</b> There is no
     * driver to hold them any more, and a build with no cgroup around it is what takes a machine
     * down rather than a boot.
     * <p>
     * Host network is two requirements at once: a Dockerfile's {@code ADD http://localhost:8081/…}
     * has to resolve during a bootstrap, and the buildctl client dials the daemon on the same
     * loopback.
     */
    @Test
    void theFirstBuildOfARunCreatesTheBuildkitd() {
        ScriptedRunner runner = coldHost();

        docker(runner, config(Map.of())).buildFromStdin("qits/platform-mirror:latest",
                "FROM quay.io/x\n", Path.of("/src/qits-platform-mirror"), List.of(), null);

        assertThat(runner.argv).containsSubsequence(
                List.of("docker", "volume", "create", "qits-buildkitd-state"),
                List.of("docker", "run", "-d", "--name", "qits-buildkitd",
                        "--privileged", "--network", "host",
                        "--restart", "unless-stopped",
                        "--oom-score-adj", "500",
                        "--memory", "9g", "--cpu-quota", "400000", "--cpuset-cpus", "0-3",
                        "-v", "qits-buildkitd-state:/var/lib/buildkit",
                        IMAGE, "--addr", "tcp://0.0.0.0:1234"));
    }

    /**
     * <b>Rerun-safe, and the second run is the interesting one.</b> After a first bootstrap the
     * container is qits-containers', re-ensured onto qits-net under the same name — it answers the
     * same address, and replacing it would throw the platform's warm cache away.
     */
    @Test
    void aRunningBuildkitdIsAdoptedAndNothingIsCreated() {
        ScriptedRunner runner = new ScriptedRunner(command ->
                command.contains("{{.Names}}") ? ScriptedRunner.ok("qits-buildkitd")
                        : ScriptedRunner.ok("done"));

        docker(runner, config(Map.of())).buildFromStdin("qits/platform-mirror:latest",
                "FROM quay.io/x\n", Path.of("/src/qits-platform-mirror"), List.of(), null);

        assertThat(runner.lines()).noneMatch(line -> line.startsWith("docker run -d"))
                .noneMatch(line -> line.startsWith("docker start"));
    }

    /** A stopped one is started rather than made again: its state volume is the cache. */
    @Test
    void aStoppedBuildkitdIsStarted() {
        ScriptedRunner runner = new ScriptedRunner(command -> ScriptedRunner.ok("done"));

        docker(runner, config(Map.of())).buildFromStdin("qits/platform-mirror:latest",
                "FROM quay.io/x\n", Path.of("/src/qits-platform-mirror"), List.of(), null);

        assertThat(runner.lines()).contains("docker start qits-buildkitd")
                .noneMatch(line -> line.startsWith("docker run -d"));
    }

    /**
     * <b>The buildx era is swept on the first build, and every name of it now.</b> Nothing creates
     * a {@code qits-bootstrap-builder-*} any more, so there is no current one to spare — and each
     * one still standing holds a multi-gigabyte state volume that {@code buildx rm} is the only
     * command able to name.
     */
    @Test
    void everyLegacyBuildxBuilderIsSweptOnTheFirstBuild() {
        ScriptedRunner runner = new ScriptedRunner(command -> {
            if (command.equals(List.of("docker", "buildx", "ls"))) {
                return ScriptedRunner.ok(
                        "NAME/NODE                  DRIVER/ENDPOINT",
                        "qits-bootstrap-builder-v4  docker-container",
                        " \\_ qits-bootstrap-builder-v40  \\_ unix:///var/run/docker.sock",
                        "qits-bootstrap-builder-v5* docker-container",
                        "somebody-elses             docker-container");
            }
            return command.contains("inspect") ? ScriptedRunner.failed("No such container")
                    : ScriptedRunner.ok("done");
        });

        docker(runner, config(Map.of())).buildFromStdin("qits/platform-mirror:latest",
                "FROM quay.io/x\n", Path.of("/src/qits-platform-mirror"), List.of(), null);

        assertThat(runner.lines())
                .contains("docker buildx rm qits-bootstrap-builder-v4",
                        "docker buildx rm qits-bootstrap-builder-v5")
                .noneMatch(line -> line.contains("qits-bootstrap-builder-v40"))
                .noneMatch(line -> line.contains("somebody-elses"));
    }

    // --- the builds ---------------------------------------------------------------------------

    /**
     * <b>The seed images, whole.</b> The Dockerfile was rewritten in memory and is written to a
     * scratch directory of its own — buildctl has no {@code -f -} — and the image comes back as a
     * tar the host daemon is then made to load, which is what replaces buildx's {@code --load}.
     */
    @Test
    void aSeedBuildDrivesBuildctlAndCarriesTheSeedMavenRepository() {
        ScriptedRunner runner = coldHost();

        docker(runner, config(Map.of())).buildFromStdin("qits/platform-mirror:latest",
                "FROM quay.io/x\n", Path.of("/src/qits-platform-mirror"), List.of(), null);

        assertThat(buildArgv(runner)).containsExactly(
                "docker", "run", "--rm", "--network", "host",
                "-v", "/src/qits-platform-mirror:/ctx:ro",
                "-v", "<dockerfiles>:/dfdir:ro",
                "-v", "<images>:/out",
                "-v", "<secrets>:/secrets:ro",
                "--entrypoint", "buildctl", IMAGE,
                "--addr", "tcp://127.0.0.1:1234",
                "build", "--frontend", "dockerfile.v0",
                "--local", "context=/ctx",
                "--local", "dockerfile=/dfdir",
                "--opt", "filename=Dockerfile",
                "--opt", ARG,
                "--output", "type=docker,name=qits/platform-mirror:latest,dest=/out/image.tar");
        assertThat(scrubbed(runner.argv.getLast()))
                .containsExactly("docker", "load", "-i", "<images>/image.tar");
    }

    /** The rewritten text really is on disk where the mount points, under the name buildctl reads. */
    @Test
    void theRewrittenDockerfileIsTheFileTheFrontendIsPointedAt() {
        List<String> written = new ArrayList<>();
        ScriptedRunner runner = new ScriptedRunner(command -> {
            if (command.contains("buildctl")) {
                written.add(readMounted(command, "/dfdir", "Dockerfile"));
            }
            return command.contains("inspect") ? ScriptedRunner.failed("No such container")
                    : ScriptedRunner.ok("done");
        });

        docker(runner, config(Map.of())).buildFromStdin("qits/platform-edge:latest",
                "FROM quay.io/rewritten\n", Path.of("/src/qits-platform-edge"), List.of(), null);

        assertThat(written).containsExactly("FROM quay.io/rewritten\n");
    }

    /** And the build argument is carried BESIDE a build's own, never instead of them. */
    @Test
    void anImagesOwnBuildArgsRideAlongside() {
        ScriptedRunner runner = coldHost();

        docker(runner, config(Map.of())).buildFromStdin("qits/platform-edge:latest",
                "FROM quay.io/x\n", Path.of("/src/qits-platform-edge"),
                List.of("QITS_VARIANT=local"), null);

        assertThat(buildArgv(runner)).containsSubsequence(
                "--opt", ARG,
                "--opt", "build-arg:QITS_VARIANT=local",
                "--output", "type=docker,name=qits/platform-edge:latest,dest=/out/image.tar");
    }

    /**
     * <b>The step images name a Dockerfile that IS in the checkout</b>, and buildctl takes the
     * directory and the file name apart. So the caller hands both over rather than assembling a
     * {@code -f <path>} somebody would have to parse back.
     */
    @Test
    void aStepImageBuildNamesTheDockerfileInsideItsContext() {
        ScriptedRunner runner = coldHost();

        docker(runner, config(Map.of())).buildWithFile("qits/build-images/ci-base:latest",
                Path.of("/src/qits-build-images-oci/ci-base/Dockerfile"),
                Path.of("/src/qits-build-images-oci"), List.of(), null);

        assertThat(buildArgv(runner)).containsExactly(
                "docker", "run", "--rm", "--network", "host",
                "-v", "/src/qits-build-images-oci:/ctx:ro",
                "-v", "/src/qits-build-images-oci/ci-base:/dfdir:ro",
                "-v", "<images>:/out",
                "-v", "<secrets>:/secrets:ro",
                "--entrypoint", "buildctl", IMAGE,
                "--addr", "tcp://127.0.0.1:1234",
                "build", "--frontend", "dockerfile.v0",
                "--local", "context=/ctx",
                "--local", "dockerfile=/dfdir",
                "--opt", "filename=Dockerfile",
                "--opt", ARG,
                "--output",
                "type=docker,name=qits/build-images/ci-base:latest,dest=/out/image.tar");
    }

    /** A build that failed leaves nothing to load, and the failure is what the caller gets. */
    @Test
    void aFailedBuildIsNeverLoaded() {
        ScriptedRunner runner = new ScriptedRunner(command -> {
            if (command.contains("buildctl")) {
                return ScriptedRunner.failed("failed to solve");
            }
            return command.contains("inspect") ? ScriptedRunner.failed("No such container")
                    : ScriptedRunner.ok("done");
        });

        ProcessResult result = docker(runner, config(Map.of())).buildFromStdin(
                "qits/platform-mirror:latest", "FROM quay.io/x\n",
                Path.of("/src/qits-platform-mirror"), List.of(), null);

        assertThat(result.ok()).isFalse();
        assertThat(runner.lines()).noneMatch(line -> line.startsWith("docker load"));
    }

    // --- the credentials ----------------------------------------------------------------------

    /**
     * <b>The capability reaches the build as a FILE, and the Dockerfiles' own secret mounts are
     * unchanged.</b> buildctl's {@code env=} form cannot be used here: the client runs in a
     * container of its own and inherits none of this process's environment. The values are written
     * to a directory mounted read-only, and they stay off the screen through {@code Cmd.mask}.
     */
    @Test
    void bootstrapRepositoryCredentialsBecomeSecretFiles() {
        List<String> read = new ArrayList<>();
        ScriptedRunner runner = new ScriptedRunner(command -> {
            if (command.contains("buildctl")) {
                read.add(readMounted(command, "/secrets", "qits-client-id"));
                read.add(readMounted(command, "/secrets", "qits-client-secret"));
            }
            return command.contains("inspect") ? ScriptedRunner.failed("No such container")
                    : ScriptedRunner.ok("done");
        });
        Docker docker = new Docker(runner).withBuildScratch(temp).withBootstrapMavenRepository(
                "https://wohlben.eu/artifacts/maven/maven", "bootstrap", "run-secret");

        docker.buildFromStdin("qits/platform-edge:latest", "FROM quay.io/x\n",
                Path.of("/src/qits-platform-edge"), List.of(), null);

        assertThat(buildArgv(runner)).containsSubsequence(
                "--opt", "build-arg:QITS_MAVEN_REPOSITORY_URL="
                        + "https://wohlben.eu/artifacts/maven/maven",
                "--secret", "id=qits-client-id,src=/secrets/qits-client-id",
                "--secret", "id=qits-client-secret,src=/secrets/qits-client-secret");
        assertThat(read).containsExactly("bootstrap", "run-secret");
        // The value is on no command line, and it is masked in the one that mounts it anyway.
        assertThat(String.join(" ", buildArgv(runner))).doesNotContain("run-secret");
        assertThat(runner.cmds.get(runner.cmds.size() - 2).maskText("run-secret")).isEqualTo("***");
    }

    /** Reads a file the run mounted, out of the host path the {@code -v} names. */
    private static String readMounted(List<String> command, String at, String name) {
        for (int i = 0; i < command.size() - 1; i++) {
            if ("-v".equals(command.get(i)) && command.get(i + 1).contains(":" + at)) {
                Path dir = Path.of(command.get(i + 1).split(":")[0]);
                try {
                    return Files.readString(dir.resolve(name), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "unreadable: " + e.getMessage();
                }
            }
        }
        return "no " + at + " mount";
    }

    // --- the build argument itself --------------------------------------------------------------

    /** The knob moves it, because the seed containers publish whatever the knob says. */
    @Test
    void theSeedRegistryPortKnobMovesTheUrl() {
        assertThat(Boot.imageBuildArgs(config(Map.of("QITS_REGISTRY_PORT", "9091"))))
                .containsExactly(
                        "QITS_MAVEN_REPOSITORY_URL=http://localhost:9091/artifacts/maven/maven");
    }

    /**
     * <b>It is the SEED's loopback port and never the edge's vhost</b>, whatever the environment is
     * called: an edge that does not exist yet cannot serve the build that is making it.
     */
    @Test
    void theUrlIsNeverTheEdgeVhost() {
        BootstrapConfig config = config(Map.of("QITS_ENV_NAME", "dev"));

        assertThat(config.seedMavenRepositoryUrl())
                .isEqualTo("http://localhost:8081/artifacts/maven/maven")
                .doesNotContain(config.registryVhost());
    }

    /**
     * <b>The HOST half builds the payload image on the host daemon, and it is the one build that
     * does.</b> It makes the container the phases run in, so it happens before any phase and
     * therefore before any buildkitd exists. It also carries none of the build arguments above: the
     * payload is this CLI, which resolves nothing of the platform.
     */
    @Test
    void thePayloadImageIsBuiltOnTheHostDaemon() {
        ScriptedRunner runner = new ScriptedRunner(command -> ScriptedRunner.ok("done"));

        new Docker(runner).buildOnHostDaemon("qits-bootstrap:abc",
                Path.of("/src/cli/docker/Dockerfile"), Path.of("/src/cli"), null);

        assertThat(runner.argv).containsExactly(List.of("docker", "build",
                "-f", "/src/cli/docker/Dockerfile", "-t", "qits-bootstrap:abc", "/src/cli"));
    }
}
