package eu.wohlben.qits.cli.bootstrap.platform;

import eu.wohlben.qits.cli.bootstrap.proc.ProcessResult;
import eu.wohlben.qits.cli.bootstrap.proc.ScriptedRunner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one shared network, and the driver it is made with. A swarm service cannot attach a bridge —
 * measured — so every network this program creates is an attachable overlay, and a bridge of the
 * same name is a platform that would fail at its first service instead of here.
 */
class DockerNetworkTest {

    private static ScriptedRunner answering(String driver, ProcessResult create) {
        return new ScriptedRunner(command -> command.contains("inspect")
                ? (driver.isEmpty() ? ScriptedRunner.failed("no such network") : ScriptedRunner.ok(driver))
                : create);
    }

    @Test
    void anAbsentNetworkIsCreatedAsAnAttachableOverlay() {
        ScriptedRunner runner = answering("", ScriptedRunner.ok("f00d"));

        new Docker(runner).ensureNetwork("qits-net", null);

        assertThat(runner.argv.getLast()).containsExactly(
                "docker", "network", "create", "-d", "overlay", "--attachable", "qits-net");
    }

    @Test
    void anOverlayThatIsAlreadyThereIsAdopted() {
        ScriptedRunner runner = answering("overlay", ScriptedRunner.ok());

        new Docker(runner).ensureNetwork("qits-net", null);

        assertThat(runner.lines()).noneMatch(line -> line.contains("network create"));
    }

    /** The platform is re-bootstrapped rather than migrated, so the way out is a removal. */
    @Test
    void aBridgeOfTheSameNameStopsTheRun() {
        ScriptedRunner runner = answering("bridge", ScriptedRunner.ok());

        assertThatThrownBy(() -> new Docker(runner).ensureNetwork("qits-net", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists as a bridge network")
                .hasMessageContaining("docker network rm qits-net");
        assertThat(runner.lines()).noneMatch(line -> line.contains("network create"));
    }

    /** ci and the deployer ensure this network too, and whoever won the race made the same one. */
    @Test
    void aSecondCreatorInTheSameMomentIsNotAFailure() {
        ScriptedRunner runner = answering("", ScriptedRunner.failed(
                "Error response from daemon: network with name qits-net already exists"));

        new Docker(runner).ensureNetwork("qits-net", null);
    }

    @Test
    void aCreateThatFailedForAnyOtherReasonStopsTheRun() {
        ScriptedRunner runner = answering("", ScriptedRunner.failed(
                "Error response from daemon: This node is not a swarm manager"));

        assertThatThrownBy(() -> new Docker(runner).ensureNetwork("qits-net", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a swarm manager");
    }

    /** The daemon's two answers, from one docker info. */
    @Test
    void theSwarmStateIsReadWithItsControlPlane() {
        ScriptedRunner runner = new ScriptedRunner(command -> ScriptedRunner.ok("active true"));

        assertThat(new Docker(runner).swarm()).isEqualTo(new Docker.Swarm("active", true));
        assertThat(runner.argv.getFirst()).isEqualTo(List.of("docker", "info", "--format",
                "{{.Swarm.LocalNodeState}} {{.Swarm.ControlAvailable}}"));
    }
}
