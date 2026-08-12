package eu.wohlben.qits.cli.bootstrap.phases;

import eu.wohlben.qits.cli.bootstrap.config.TestConfig;
import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseContext;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseSkipped;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessResult;
import eu.wohlben.qits.cli.bootstrap.proc.RunLog;
import eu.wohlben.qits.cli.bootstrap.proc.ScriptedRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The re-bootstrap's sweep, which is the one place unwrap deletes data on purpose. What it must
 * never take with it is a config volume: those hold the push token, the client secrets and the
 * deployer's extras, and losing them turns a migration into a re-issue of every credential on
 * the machine.
 */
class UnwrapPhasesTest {

    @Test
    void theDataVolumesGo() {
        assertThat(UnwrapPhases.isData("qits-deployments-data")).isTrue();
        assertThat(UnwrapPhases.isData("qits-platform-artifacts-data")).isTrue();
        assertThat(UnwrapPhases.isData("qits-platform-idp-data")).isTrue();
        // The new one, covered by the same pattern rather than by a line of its own.
        assertThat(UnwrapPhases.isData("qits-oci-postgresql-data")).isTrue();
        // The first-boot Maven repository: rebuilt by the next bootstrap.
        assertThat(UnwrapPhases.isData("qits-maven-seed")).isTrue();
    }

    @Test
    void theConfigVolumesStay() {
        assertThat(UnwrapPhases.isData("qits-deployments-config")).isFalse();
        // And so does the edge's certificate. A re-bootstrap is a cold boot of the DATABASES; a
        // Let's Encrypt certificate is neither data nor config but it is rate-limited to re-issue,
        // and the "matches neither list" rule is what keeps it. --with-volumes still takes it.
        assertThat(UnwrapPhases.isData("qits-edge-letsencrypt")).isFalse();
    }

    /**
     * The download cache is not this platform's data: it holds third-party jars from Maven Central,
     * and re-fetching them on every re-bootstrap is what got this host throttled. The full teardown
     * still takes it — that sweep is about the machine, not about the platform's state.
     */
    @Test
    void theMavenDownloadCacheSurvivesADataReset() {
        assertThat(UnwrapPhases.isData("qits-maven-cache")).isFalse();
        assertThat(UnwrapPhases.isPlatformVolume("qits-maven-cache")).isTrue();
        // Its neighbour is classified the other way: the seed repository holds what THIS platform
        // published, which the next bootstrap republishes.
        assertThat(UnwrapPhases.isData("qits-maven-seed")).isTrue();
        // And the full teardown asks no questions of the keep list at all.
        assertThat(UnwrapPhases.isPlatformVolume("qits-deployments-config")).isTrue();
        assertThat(UnwrapPhases.isPlatformVolume("postgres-data")).isFalse();
    }

    @Test
    void aVolumeMatchingNeitherListIsKept() {
        // This program did not create it and does not know what it is.
        assertThat(UnwrapPhases.isData("qits-something-else")).isFalse();
        assertThat(UnwrapPhases.isData("qits-maven-seed-extra")).isFalse();
        assertThat(UnwrapPhases.isData("postgres-data")).isFalse();
    }

    /**
     * An overlay releases its endpoints a moment after the containers go, so the first
     * {@code network rm} answers "has active endpoints" and the second or third takes it.
     */
    @Test
    void aNetworkThatIsStillReleasingItsEndpointsIsAskedAgain() {
        AtomicInteger tries = new AtomicInteger();
        AtomicInteger pauses = new AtomicInteger();

        ProcessResult result = UnwrapPhases.retrying(
                () -> tries.incrementAndGet() < 3
                        ? failed("Error response from daemon: network qits-net has active endpoints")
                        : removed(),
                UnwrapPhases.NETWORK_ATTEMPTS, pauses::incrementAndGet);

        assertThat(result.ok()).isTrue();
        assertThat(tries.get()).isEqualTo(3);
        assertThat(pauses.get()).isEqualTo(2);
    }

    /** Bounded: an endpoint that never goes costs the window and is then reported, not waited on. */
    @Test
    void aRemovalThatKeepsFailingGivesUpAfterTheWindow() {
        AtomicInteger tries = new AtomicInteger();

        ProcessResult result = UnwrapPhases.retrying(
                () -> {
                    tries.incrementAndGet();
                    return failed("has active endpoints");
                },
                UnwrapPhases.NETWORK_ATTEMPTS, () -> {
                });

        assertThat(result.ok()).isFalse();
        assertThat(tries.get()).isEqualTo(UnwrapPhases.NETWORK_ATTEMPTS);
    }

    /** Everything else is removed once: only a network detaches after the thing on it is gone. */
    @Test
    void oneAttemptIsOneCommand() {
        AtomicInteger tries = new AtomicInteger();

        UnwrapPhases.retrying(() -> {
            tries.incrementAndGet();
            return failed("no such volume");
        }, 1, () -> {
        });

        assertThat(tries.get()).isEqualTo(1);
    }

    private static ProcessResult removed() {
        return new ProcessResult(0, List.of("qits-net"), List.of("qits-net"), false, false);
    }

    private static ProcessResult failed(String... output) {
        return new ProcessResult(1, List.of(output), List.of(output), false, false);
    }

    // --- the swarm sweep ---------------------------------------------------------------------------

    @TempDir
    Path temp;

    /** What a phase said, and nothing more: the display is not what these tests are about. */
    private static final class Ctx implements PhaseContext {
        final List<String> lines = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        @Override
        public void log(String line) {
            lines.add(line);
        }

        @Override
        public void status(String status) {
        }

        @Override
        public void note(String note) {
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }
    }

    private Boot boot(ScriptedRunner runner) {
        return new Boot(TestConfig.from(Map.of()),
                new RunLog(temp.resolve("run.log")), runner);
    }

    private static void run(Boot boot, String id, PhaseContext ctx) throws Exception {
        Phase phase = new UnwrapPhases(boot, false, false, false).build().stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst().orElseThrow();
        phase.action().run(ctx);
    }

    /** The seed is a stack, and this is how it goes. */
    @Test
    void theSeedStackIsRemovedByName() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command -> ScriptedRunner.ok("Removing service"));

        run(boot(runner), "stack-rm", new Ctx());

        assertThat(runner.lines()).contains("docker stack rm qits");
    }

    /**
     * A machine that never ran one answers "Nothing found in stack", and that is not a failure: the
     * compose-down phase after it is exactly what such a machine needs.
     */
    @Test
    void aMachineWithNoStackIsWarnedAboutAndSweptOn() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(
                command -> ScriptedRunner.failed("Nothing found in stack: qits"));
        Ctx ctx = new Ctx();

        run(boot(runner), "stack-rm", ctx);

        assertThat(ctx.warnings).hasSize(1);
        assertThat(ctx.warnings.getFirst()).contains("Nothing found in stack");
    }

    /**
     * Services before containers, and by their own vocabulary: removing a task's container removes
     * nothing, because swarm starts another one.
     */
    @Test
    void everyQitsServiceIsSweptByLabelAndByName() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(command -> {
            String line = String.join(" ", command);
            if (line.contains("label=qits.platform.deployments.app-name")) {
                return ScriptedRunner.ok("qits-pd-prod-qits-ci-a1b2c3d4");
            }
            if (line.contains("label=com.docker.stack.namespace=qits")) {
                return ScriptedRunner.ok("qits_prod-qits-events");
            }
            if (line.contains("service ls --format")) {
                // The name sweep sees the label ones again, plus one no label caught.
                return ScriptedRunner.ok("qits-pd-prod-qits-ci-a1b2c3d4", "qits_prod-qits-events",
                        "qits_qits-platform-idp", "somebody-elses-service");
            }
            return ScriptedRunner.ok();
        });

        run(boot(runner), "services", new Ctx());

        assertThat(runner.lines()).contains(
                "docker service rm qits-pd-prod-qits-ci-a1b2c3d4",
                "docker service rm qits_prod-qits-events",
                "docker service rm qits_qits-platform-idp");
        // Found by two sweeps, removed once — and somebody else's service is not this platform's.
        assertThat(runner.lines().stream().filter(line -> line.startsWith("docker service rm")))
                .hasSize(3);
        assertThat(runner.lines()).noneMatch(line -> line.contains("somebody-elses-service"));
    }

    /** A daemon in no swarm answers nothing, which is a machine with no services to remove. */
    @Test
    void aDaemonWithNoServicesSkipsTheSweep() {
        ScriptedRunner runner = new ScriptedRunner(
                command -> ScriptedRunner.failed("This node is not a swarm manager"));

        assertThatThrownBy(() -> run(boot(runner), "services", new Ctx()))
                .isInstanceOf(PhaseSkipped.class);
        assertThat(runner.lines()).noneMatch(line -> line.startsWith("docker service rm"));
    }

    /** The three name shapes a machine can be carrying, and the stack's own prefix among them. */
    @Test
    void everyShapeThisPlatformNamesThingsWithIsSwept() {
        assertThat(UnwrapPhases.isPlatformName("qits-pd-qits-platform-idp-f325ef80")).isTrue();
        assertThat(UnwrapPhases.isPlatformName("prod-qits-ci")).isTrue();
        assertThat(UnwrapPhases.isPlatformName("qits_prod-qits-events")).isTrue();
        assertThat(UnwrapPhases.isPlatformName("postgres")).isFalse();
    }
}
