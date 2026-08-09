package eu.wohlben.qits.cli.bootstrap.phases;

import org.junit.jupiter.api.Test;

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
    }

    @Test
    void aVolumeMatchingNeitherListIsKept() {
        // This program did not create it and does not know what it is.
        assertThat(UnwrapPhases.isData("qits-something-else")).isFalse();
        assertThat(UnwrapPhases.isData("qits-maven-seed-extra")).isFalse();
        assertThat(UnwrapPhases.isData("postgres-data")).isFalse();
    }
}
