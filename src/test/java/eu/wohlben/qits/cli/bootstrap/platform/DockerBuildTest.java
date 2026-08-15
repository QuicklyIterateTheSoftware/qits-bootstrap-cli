package eu.wohlben.qits.cli.bootstrap.platform;

import eu.wohlben.qits.cli.bootstrap.config.BootstrapConfig;
import eu.wohlben.qits.cli.bootstrap.config.TestConfig;
import eu.wohlben.qits.cli.bootstrap.phases.Boot;
import eu.wohlben.qits.cli.bootstrap.proc.ScriptedRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Every image this bootstrap builds resolves the platform's own maven artifacts from the SEED
 * registry, and the build argument that says so is asserted here because nothing else can say
 * it.</b>
 * <p>
 * The repositories declare {@code ARG QITS_MAVEN_REPOSITORY_URL} and their maven settings read it,
 * and the committed default now names the edge vhost — the address of a platform that is RUNNING.
 * A seed build runs minutes before any edge exists, so a build that rode that default died at
 * qits-platform-mirror with a connection refused. It used to work only because the default happened
 * to be this same loopback url.
 */
class DockerBuildTest {

    private static final String ARG =
            "QITS_MAVEN_REPOSITORY_URL=http://localhost:8081/artifacts/maven/maven";

    private static BootstrapConfig config(Map<String, String> env) {
        return TestConfig.from(env);
    }

    private static Docker docker(ScriptedRunner runner, BootstrapConfig config) {
        return new Docker(runner).withBuildArgs(Boot.imageBuildArgs(config));
    }

    @Test
    void aFreshBuilderGetsTheEnforcedContainerLimits() {
        ScriptedRunner runner = new ScriptedRunner(command ->
                command.equals(List.of("docker", "buildx", "inspect", "qits-bootstrap-builder"))
                        ? ScriptedRunner.failed("absent") : ScriptedRunner.ok("ready"));

        new Docker(runner).build(List.of("/src"), null);

        assertThat(runner.argv).containsSubsequence(
                List.of("docker", "buildx", "create", "--name", "qits-bootstrap-builder",
                        "--driver", "docker-container", "--driver-opt",
                        "memory=4g,cpu-quota=200000"),
                List.of("docker", "buildx", "inspect", "--bootstrap",
                        "qits-bootstrap-builder"));
    }

    /** The seed images: a Dockerfile on stdin, the host's network, and the build argument. */
    @Test
    void aSeedBuildCarriesTheSeedMavenRepository() {
        ScriptedRunner runner = new ScriptedRunner(command -> ScriptedRunner.ok("done"));

        docker(runner, config(Map.of())).buildFromStdin("qits/platform-mirror:latest",
                "FROM quay.io/x\n", Path.of("/src/qits-platform-mirror"), List.of(), null);

        assertThat(runner.argv.getLast()).containsExactly("docker", "buildx", "build", "--builder",
                "qits-bootstrap-builder", "--load", "--network", "host",
                "-t", "qits/platform-mirror:latest", "-f", "-",
                "--build-arg", ARG, "/src/qits-platform-mirror");
    }

    /** And it is carried BESIDE a build's own arguments, never instead of them. */
    @Test
    void anImagesOwnBuildArgsRideAlongside() {
        ScriptedRunner runner = new ScriptedRunner(command -> ScriptedRunner.ok("done"));

        docker(runner, config(Map.of())).buildFromStdin("qits/platform-edge:latest", "FROM quay.io/x\n",
                Path.of("/src/qits-platform-edge"), List.of("--build-arg", "QITS_VARIANT=local"), null);

        assertThat(runner.argv.getLast()).containsSubsequence("--build-arg", ARG,
                "--build-arg", "QITS_VARIANT=local", "/src/qits-platform-edge");
    }

    /** The step images take the other build method, and the argument is at the seam they share. */
    @Test
    void aStepImageBuildCarriesItToo() {
        ScriptedRunner runner = new ScriptedRunner(command -> ScriptedRunner.ok("done"));

        docker(runner, config(Map.of())).build(List.of("-t", "qits/build-images/ci-base:latest",
                "-f", "/src/qits-oci/ci-base/Dockerfile", "/src/qits-oci"), null);

        assertThat(runner.argv.getLast()).containsExactly("docker", "buildx", "build", "--builder",
                "qits-bootstrap-builder", "--load", "--build-arg", ARG,
                "-t", "qits/build-images/ci-base:latest",
                "-f", "/src/qits-oci/ci-base/Dockerfile", "/src/qits-oci");
    }

    /** The knob moves it, because the seed containers publish whatever the knob says. */
    @Test
    void theSeedRegistryPortKnobMovesTheUrl() {
        assertThat(Boot.imageBuildArgs(
                config(Map.of("QITS_REGISTRY_PORT", "9091"))))
                .containsExactly("--build-arg",
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
     * The HOST half builds the payload image with a Docker of its own and gets none of this: the
     * payload is this CLI, which resolves nothing of the platform.
     */
    @Test
    void aDockerWithNoBuildArgsAddsNothing() {
        ScriptedRunner runner = new ScriptedRunner(command -> ScriptedRunner.ok("done"));

        new Docker(runner).build(List.of("-t", "qits-bootstrap:abc", "/src/cli"), null);

        assertThat(runner.argv.getLast())
                .containsExactly("docker", "buildx", "build", "--builder",
                        "qits-bootstrap-builder", "--load",
                        "-t", "qits-bootstrap:abc", "/src/cli");
    }
}
