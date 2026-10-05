package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The formula, at the sizes that matter: the 16 GB host the live platform runs on gets ONE
 * workspace beside its one build, and the smallest answer is never zero.
 */
class WorkspaceSlotsTest {

    private static long gib(double amount) {
        return (long) (amount * 1024 * 1024 * 1024);
    }

    /** 16 - 10 - 6 leaves nothing, and nothing is still one slot: a drained runner is no runner. */
    @Test
    void aSixteenGibHostRunningOneBuildRunsOneWorkspace() {
        assertThat(WorkspaceSlots.localSlotsFor(gib(16), 1)).isOne();
    }

    /** 32 - 10 - 12 = 10, two workspaces of 4 GiB. */
    @Test
    void aThirtyTwoGibHostRunningTwoBuildsRunsTwoWorkspaces() {
        assertThat(WorkspaceSlots.localSlotsFor(gib(32), 2)).isEqualTo(2);
    }

    /** An unreadable host memory is the smallest host, never zero slots. */
    @Test
    void anUnreadableHostRunsOneWorkspace() {
        assertThat(WorkspaceSlots.localSlotsFor(0, 1)).isOne();
        assertThat(WorkspaceSlots.localSlotsFor(0, 0)).isOne();
    }

    /** The CI share is what is taken first: the same host with fewer builds runs more workspaces. */
    @Test
    void everyBuildTakesItsShareBeforeTheWorkspaces() {
        assertThat(WorkspaceSlots.localSlotsFor(gib(32), 1)).isEqualTo(4);
        assertThat(WorkspaceSlots.localSlotsFor(gib(32), 3)).isOne();
    }
}
