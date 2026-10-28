package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The formulas, at the sizes that made them: a 16 GB VPS runs ONE build, because two native ones
 * livelocked exactly that host, and its target holds one workspace and one desk beside it — the
 * smallest answer is never zero and never under the reservations.
 */
class TargetSlotsTest {

    private static long gib(double amount) {
        return (long) (amount * 1024 * 1024 * 1024);
    }

    @Test
    void aSixteenGibHostRunsOneBuild() {
        assertThat(TargetSlots.concurrentBuildsFor(gib(16))).isOne();
    }

    /** What `free` reports on a 16 GB VPS — the firmware and the kernel take the rest. */
    @Test
    void theFigureAsixteenGigabyteVpsActuallyReportsRunsOneBuild() {
        assertThat(TargetSlots.concurrentBuildsFor(gib(15.2))).isOne();
    }

    @Test
    void twoBuildsFromTwentyTwoGib() {
        assertThat(TargetSlots.concurrentBuildsFor(gib(22))).isEqualTo(2);
    }

    @Test
    void threeBuildsFromTwentyEightGib() {
        assertThat(TargetSlots.concurrentBuildsFor(gib(28))).isEqualTo(3);
    }

    /** Under the reserve, and still ONE: a platform that builds nothing is a stopped one. */
    @Test
    void aHostSmallerThanTheReserveStillRunsOneBuild() {
        assertThat(TargetSlots.concurrentBuildsFor(gib(8))).isOne();
    }

    /** An unreadable host memory is the smallest host, never zero builds. */
    @Test
    void anUnreadableHostRunsOneBuild() {
        assertThat(TargetSlots.concurrentBuildsFor(0)).isOne();
    }

    /** This machine has memory, and whatever it is the answer is a sane build count. */
    @Test
    void readsThisHostsMemory() {
        assertThat(TargetSlots.hostMemoryBytes()).isGreaterThan(gib(0.5));
        assertThat(TargetSlots.concurrentBuildsFor(TargetSlots.hostMemoryBytes()))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void describesTheHostForTheBootLog() {
        assertThat(TargetSlots.describe(gib(15.2))).isEqualTo("host 15.2 GiB");
        assertThat(TargetSlots.describe(0)).isEqualTo("host memory unreadable");
    }

    /** 16 - 10 - 6 leaves nothing, and nothing is still one slot: a drained target is no target. */
    @Test
    void aSixteenGibHostRunningOneBuildHasOneAgentSlot() {
        assertThat(TargetSlots.agentSlotsFor(gib(16), 1)).isOne();
    }

    /** 32 - 10 - 12 = 10, two agent slots of 4 GiB. */
    @Test
    void aThirtyTwoGibHostRunningTwoBuildsHasTwoAgentSlots() {
        assertThat(TargetSlots.agentSlotsFor(gib(32), 2)).isEqualTo(2);
    }

    /** An unreadable host memory is the smallest host, never zero slots. */
    @Test
    void anUnreadableHostHasOneAgentSlot() {
        assertThat(TargetSlots.agentSlotsFor(0, 1)).isOne();
        assertThat(TargetSlots.agentSlotsFor(0, 0)).isOne();
    }

    /** The CI share is what is taken first: the same host with fewer builds runs more agents. */
    @Test
    void everyBuildTakesItsShareBeforeTheAgents() {
        assertThat(TargetSlots.agentSlotsFor(gib(32), 1)).isEqualTo(4);
        assertThat(TargetSlots.agentSlotsFor(gib(32), 3)).isOne();
    }

    /**
     * The total is the CI share plus the agent share, and the agent share never falls under the
     * agent reservations: one build, one workspace and one desk on the smallest host.
     */
    @Test
    void theTargetTotalIsTheCiShareAndNeverUnderItsReservations() {
        assertThat(TargetSlots.targetSlotsFor(gib(16), 1, 2)).isEqualTo(3);
        assertThat(TargetSlots.targetSlotsFor(0, 1, 2)).isEqualTo(3);
        assertThat(TargetSlots.targetSlotsFor(gib(32), 1, 2)).isEqualTo(5);
        assertThat(TargetSlots.targetSlotsFor(gib(32), 2, 2)).isEqualTo(4);
        assertThat(TargetSlots.targetSlotsFor(gib(16), 0, 0)).isOne();
    }
}
