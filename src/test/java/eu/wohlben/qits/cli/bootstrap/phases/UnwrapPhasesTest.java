package eu.wohlben.qits.cli.bootstrap.phases;

import eu.wohlben.qits.cli.bootstrap.proc.ProcessResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The re-bootstrap's sweep, which is the one place unwrap deletes data on purpose. What it must
 * never take with it is a config volume: those hold the push token, the client secrets and the
 * deployer's run-args, and losing them turns a migration into a re-issue of every credential on
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
}
