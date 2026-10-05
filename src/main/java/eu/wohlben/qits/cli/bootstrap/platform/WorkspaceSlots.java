package eu.wohlben.qits.cli.bootstrap.platform;

/**
 * <b>How many workspaces this host's own workspaces runner may run at once, computed from the
 * memory of the host it shares with the platform and the CI runner.</b> The value is the
 * {@code slots} the {@code workspaces-runner-localhost} phase declares the runner with, and the
 * {@code QITS_WORKSPACES_RUNNER_SLOTS} it starts the container with.
 * <p>
 * It is {@link CiConcurrency}'s rule carried one step further, and computed for the same reason: a
 * number that has to be right on this host cannot live in a template. The platform keeps its
 * reserve, each CI build keeps its share, and what is left is shared out per workspace:
 * <pre>
 *     slots = max(1, floor((totalMemoryGiB - 10 - 6 * ciSlots) / 4))
 * </pre>
 * — 1 on a 16 GB host running one build (wohlben.eu), 2 on 32 GB running two. Never 0: a runner
 * declared with no slots is a drained one, and the runner refuses {@code SLOTS=0} outright.
 * <p>
 * There is no environment key to override it. The row's slots are an operator's to change on the
 * runners page afterwards, and the runner takes the row's number from its first ack whatever its
 * environment said.
 */
public final class WorkspaceSlots {

    private static final long GIB = 1024L * 1024L * 1024L;

    /** Kept for the platform's own containers — the same reserve {@link CiConcurrency} keeps. */
    private static final long RESERVED = 10 * GIB;

    /** What one CI build is allowed to cost — {@link CiConcurrency}'s share, per CI slot. */
    private static final long PER_BUILD = 6 * GIB;

    /** What one workspace is given. */
    private static final long PER_WORKSPACE = 4 * GIB;

    private WorkspaceSlots() {
    }

    /**
     * The formula, and nothing else — no host is read here, which is what makes it testable.
     *
     * @param totalBytes the host's total physical memory, or 0 when it could not be read
     * @param ciSlots    how many builds this host's CI runner runs at once
     */
    public static int localSlotsFor(long totalBytes, int ciSlots) {
        long forWorkspaces = totalBytes - RESERVED - PER_BUILD * Math.max(0, ciSlots);
        if (forWorkspaces < PER_WORKSPACE) {
            return 1;
        }
        return (int) (forWorkspaces / PER_WORKSPACE);
    }
}
