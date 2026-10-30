package eu.wohlben.qits.cli.bootstrap.platform;

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
 * No build argument and no secret rides along that the caller did not name: a seed build resolves
 * the platform's jars out of a docker volume handed in as a named context, which is asserted here
 * too.
 */
class DockerBuildTest {

    private static final String IMAGE = "moby/buildkit:v0.33.0";

    /**
     * Where the scratch directories are made. It is a real directory rather than a name because the
     * facade writes the Dockerfile into it for real.
     */
    @TempDir
    Path temp;

    private Docker docker(ScriptedRunner runner) {
        return new Docker(runner).withBuildScratch(temp);
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
                        .replaceAll(root + "/image-[^:/]+", "<images>"))
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
     * Host network: the buildctl client dials the daemon on the host's loopback, and a build's
     * RUN reaches the internet through the host's own network.
     */
    @Test
    void theFirstBuildOfARunCreatesTheBuildkitd() {
        ScriptedRunner runner = coldHost();

        docker(runner).buildFromStdin("qits/platform-mirror:latest",
                "FROM quay.io/x\n", Path.of("/src/qits-mirror"), List.of(), null);

        assertThat(runner.argv).containsSubsequence(
                List.of("docker", "volume", "create", "qits-buildkitd-state"),
                List.of("docker", "run", "-d", "--name", "qits-buildkitd",
                        "--privileged", "--network", "host",
                        "--restart", "unless-stopped",
                        "--oom-score-adj", "500",
                        "--memory", "9g", "--cpu-quota", "400000", "--cpuset-cpus", "0-3",
                        "-v", "qits-buildkitd-state:/var/lib/buildkit",
                        IMAGE,
                        "--addr", "unix:///run/buildkit/buildkitd.sock",
                        "--addr", "tcp://0.0.0.0:1234", "--oci-worker-net=host"));
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

        docker(runner).buildFromStdin("qits/platform-mirror:latest",
                "FROM quay.io/x\n", Path.of("/src/qits-mirror"), List.of(), null);

        assertThat(runner.lines()).noneMatch(line -> line.startsWith("docker run -d"))
                .noneMatch(line -> line.startsWith("docker start"));
    }

    /** A stopped one is started rather than made again: its state volume is the cache. */
    @Test
    void aStoppedBuildkitdIsStarted() {
        ScriptedRunner runner = new ScriptedRunner(command -> ScriptedRunner.ok("done"));

        docker(runner).buildFromStdin("qits/platform-mirror:latest",
                "FROM quay.io/x\n", Path.of("/src/qits-mirror"), List.of(), null);

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

        docker(runner).buildFromStdin("qits/platform-mirror:latest",
                "FROM quay.io/x\n", Path.of("/src/qits-mirror"), List.of(), null);

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
    void aSeedBuildDrivesBuildctlAndCarriesNothingItWasNotGiven() {
        ScriptedRunner runner = coldHost();

        docker(runner).buildFromStdin("qits/platform-mirror:latest",
                "FROM quay.io/x\n", Path.of("/src/qits-mirror"), List.of(), null);

        assertThat(buildArgv(runner)).containsExactly(
                "docker", "run", "--rm", "--network", "host",
                "-v", "/src/qits-mirror:/ctx:ro",
                "-v", "<dockerfiles>:/dfdir:ro",
                "-v", "<images>:/out",
                "--entrypoint", "buildctl", IMAGE,
                "--addr", "tcp://127.0.0.1:1234",
                "build", "--frontend", "dockerfile.v0",
                "--local", "context=/ctx",
                "--local", "dockerfile=/dfdir",
                "--opt", "filename=Dockerfile",
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

        docker(runner).buildFromStdin("qits/platform-edge:latest",
                "FROM quay.io/rewritten\n", Path.of("/src/qits-edge"), List.of(), null);

        assertThat(written).containsExactly("FROM quay.io/rewritten\n");
    }

    /** A build's own arguments are passed as they are given. */
    @Test
    void anImagesOwnBuildArgsArePassed() {
        ScriptedRunner runner = coldHost();

        docker(runner).buildFromStdin("qits/platform-edge:latest",
                "FROM quay.io/x\n", Path.of("/src/qits-edge"),
                List.of("QITS_VARIANT=local"), null);

        assertThat(buildArgv(runner)).containsSubsequence(
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

        docker(runner).buildWithFile("qits/build-images/ci-base:latest",
                Path.of("/src/qits-build-images-oci/ci-base/Dockerfile"),
                Path.of("/src/qits-build-images-oci"), List.of(), null);

        assertThat(buildArgv(runner)).containsExactly(
                "docker", "run", "--rm", "--network", "host",
                "-v", "/src/qits-build-images-oci:/ctx:ro",
                "-v", "/src/qits-build-images-oci/ci-base:/dfdir:ro",
                "-v", "<images>:/out",
                "--entrypoint", "buildctl", IMAGE,
                "--addr", "tcp://127.0.0.1:1234",
                "build", "--frontend", "dockerfile.v0",
                "--local", "context=/ctx",
                "--local", "dockerfile=/dfdir",
                "--opt", "filename=Dockerfile",
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

        ProcessResult result = docker(runner).buildFromStdin(
                "qits/platform-mirror:latest", "FROM quay.io/x\n",
                Path.of("/src/qits-mirror"), List.of(), null);

        assertThat(result.ok()).isFalse();
        assertThat(runner.lines()).noneMatch(line -> line.startsWith("docker load"));
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

    // --- the named contexts -------------------------------------------------------------------

    /**
     * <b>A volume becomes a named context</b>: mounted read-only into the client at
     * {@code /named/<name>}, handed to buildctl as a local of that name, and to the frontend as
     * {@code context:<name>=local:<name>} — which is what a Dockerfile's
     * {@code --mount=type=bind,from=<name>} and {@code COPY --from=<name>} read.
     */
    @Test
    void aVolumeContextIsMountedAndNamed() {
        ScriptedRunner runner = coldHost();

        docker(runner).buildFromStdin("qits/ci:latest", "FROM quay.io/x\n",
                Path.of("/src/qits-ci"), List.of(), Map.of("qits-m2", "qits-maven-seed"), null);

        assertThat(buildArgv(runner)).containsExactly(
                "docker", "run", "--rm", "--network", "host",
                "-v", "/src/qits-ci:/ctx:ro",
                "-v", "<dockerfiles>:/dfdir:ro",
                "-v", "<images>:/out",
                "-v", "qits-maven-seed:/named/qits-m2:ro",
                "--entrypoint", "buildctl", IMAGE,
                "--addr", "tcp://127.0.0.1:1234",
                "build", "--frontend", "dockerfile.v0",
                "--local", "context=/ctx",
                "--local", "dockerfile=/dfdir",
                "--opt", "filename=Dockerfile",
                "--local", "qits-m2=/named/qits-m2",
                "--opt", "context:qits-m2=local:qits-m2",
                "--output", "type=docker,name=qits/ci:latest,dest=/out/image.tar");
    }

    /** No secret of any kind reaches a build: there is no server left to present one to. */
    @Test
    void noBuildCarriesASecret() {
        ScriptedRunner runner = coldHost();

        docker(runner).buildFromStdin("qits/ci:latest", "FROM quay.io/x\n",
                Path.of("/src/qits-ci"), List.of(), Map.of("qits-m2", "qits-maven-seed"), null);

        assertThat(buildArgv(runner)).doesNotContain("--secret")
                .noneMatch(arg -> arg.contains("/secrets"))
                .noneMatch(arg -> arg.contains("QITS_MAVEN_REPOSITORY_URL"));
    }

    /**
     * <b>The HOST half builds the payload image on the host daemon, and it is the one build that
     * does.</b> It makes the container the phases run in, so it happens before any phase and
     * therefore before any buildkitd exists.
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
