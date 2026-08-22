package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The formula, at the sizes that made it: a 16 GB VPS runs ONE build, because two native ones
 * livelocked exactly that host.
 */
class CiConcurrencyTest {

    private static long gib(double amount) {
        return (long) (amount * 1024 * 1024 * 1024);
    }

    @Test
    void aSixteenGibHostRunsOneBuild() {
        assertThat(CiConcurrency.concurrentBuildsFor(gib(16))).isOne();
    }

    /** What `free` reports on a 16 GB VPS — the firmware and the kernel take the rest. */
    @Test
    void theFigureAsixteenGigabyteVpsActuallyReportsRunsOneBuild() {
        assertThat(CiConcurrency.concurrentBuildsFor(gib(15.2))).isOne();
    }

    @Test
    void twoBuildsFromTwentyTwoGib() {
        assertThat(CiConcurrency.concurrentBuildsFor(gib(22))).isEqualTo(2);
    }

    @Test
    void threeBuildsFromTwentyEightGib() {
        assertThat(CiConcurrency.concurrentBuildsFor(gib(28))).isEqualTo(3);
    }

    /** Under the reserve, and still ONE: a platform that builds nothing is a stopped one. */
    @Test
    void aHostSmallerThanTheReserveStillRunsOneBuild() {
        assertThat(CiConcurrency.concurrentBuildsFor(gib(8))).isOne();
    }

    /** An unreadable host memory is the smallest host, never zero builds. */
    @Test
    void anUnreadableHostRunsOneBuild() {
        assertThat(CiConcurrency.concurrentBuildsFor(0)).isOne();
    }

    /** This machine has memory, and whatever it is the answer is a sane build count. */
    @Test
    void readsThisHostsMemory() {
        assertThat(CiConcurrency.hostMemoryBytes()).isGreaterThan(gib(0.5));
        assertThat(CiConcurrency.concurrentBuildsFor(CiConcurrency.hostMemoryBytes()))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void describesTheHostForTheBootLog() {
        assertThat(CiConcurrency.describe(gib(15.2))).isEqualTo("host 15.2 GiB");
        assertThat(CiConcurrency.describe(0)).isEqualTo("host memory unreadable");
    }
}
