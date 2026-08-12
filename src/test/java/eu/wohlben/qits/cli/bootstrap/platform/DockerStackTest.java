package eu.wohlben.qits.cli.bootstrap.platform;

import eu.wohlben.qits.cli.bootstrap.proc.ScriptedRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stack and service commands, at the only level that can be proven without a daemon: the exact
 * argv. A flag dropped here is a bootstrap that gets further than it should before it breaks.
 */
class DockerStackTest {

    private static ScriptedRunner answering(String... output) {
        return new ScriptedRunner(command -> ScriptedRunner.ok(output));
    }

    /**
     * {@code --resolve-image never}, and the spelling matters twice over: the flag is
     * {@code --no-resolve-image} on {@code service create} and neither works for the other command,
     * and without it docker asks a registry to resolve {@code qits/ci:latest} — a local tag no
     * registry has ever heard of.
     */
    @Test
    void theStackIsDeployedWithoutResolvingLocalTags() {
        ScriptedRunner runner = answering("Creating service qits_prod-qits-ci");

        new Docker(runner).stackDeploy(Path.of("/w/docker-compose.qits.yml"), Docker.STACK,
                Duration.ofMinutes(30), null);

        assertThat(runner.argv.getLast()).containsExactly("docker", "stack", "deploy",
                "--resolve-image", "never", "-c", "/w/docker-compose.qits.yml", "qits");
    }

    /** No --prune: what the file leaves out is the deployer's, not this program's to remove. */
    @Test
    void deployingASubsetPrunesNothing() {
        ScriptedRunner runner = answering("Updating service qits_prod-qits-ci");

        new Docker(runner).stackDeploy(Path.of("/w/docker-stack.qits.partial.yml"), Docker.STACK,
                Duration.ofMinutes(30), null);

        assertThat(runner.lines().getLast()).doesNotContain("--prune");
    }

    @Test
    void theStackIsRemovedByName() {
        ScriptedRunner runner = answering("Removing service qits_prod-qits-ci");

        new Docker(runner).stackRm(Docker.STACK, null);

        assertThat(runner.argv.getLast()).containsExactly("docker", "stack", "rm", "qits");
    }

    /** What {@code docker ps} used to answer, now that a task's container carries a made-up name. */
    @Test
    void theServiceNamesAreListedByName() {
        ScriptedRunner runner = answering("qits_prod-qits-ci", "qits_qits-platform-idp");

        assertThat(new Docker(runner).serviceNames())
                .containsExactly("qits_prod-qits-ci", "qits_qits-platform-idp");
        assertThat(runner.argv.getLast()).containsExactly(
                "docker", "service", "ls", "--format", "{{.Name}}");
    }

    @Test
    void aLabelFilterIsPassedThrough() {
        ScriptedRunner runner = answering("qits-pd-prod-qits-ci");

        new Docker(runner).serviceNames("label=qits.platform.deployments.app-name");

        assertThat(runner.argv.getLast()).containsExactly("docker", "service", "ls", "--filter",
                "label=qits.platform.deployments.app-name", "--format", "{{.Name}}");
    }

    /** One call, however many names: service rm takes them all. */
    @Test
    void servicesAreRemovedInOneCall() {
        ScriptedRunner runner = answering("qits_prod-qits-ci", "qits_prod-qits-events");

        new Docker(runner).serviceRm(List.of("qits_prod-qits-ci", "qits_prod-qits-events"), null);

        assertThat(runner.argv.getLast()).containsExactly("docker", "service", "rm",
                "qits_prod-qits-ci", "qits_prod-qits-events");
    }

    /** The qualified name a stack gives a service; the bare alias resolves too, and is the address. */
    @Test
    void aStackQualifiesItsServiceNames() {
        assertThat(Docker.stackService(Docker.STACK, "prod-qits-ci")).isEqualTo("qits_prod-qits-ci");
    }
}
