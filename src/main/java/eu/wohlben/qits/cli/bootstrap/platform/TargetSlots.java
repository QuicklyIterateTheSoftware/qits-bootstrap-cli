package eu.wohlben.qits.cli.bootstrap.platform;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * <b>How many slots this host's own target may run at once, computed from the memory of the
 * host it shares with the platform.</b> Two shares make it up: the CI share
 * ({@link #concurrentBuildsFor}), which is the target's {@code min_ci}; and the agent share
 * ({@link #agentSlotsFor}), what is left for workspaces and desks. Their sum, the agent share never
 * under its minimums, is the target's total ({@link #targetSlotsFor}) — the
 * {@code QITS_SYSTEM_RUNNER_SLOTS} the {@code target-localhost} phase starts the qits-system-runner
 * with. The class was {@code CiConcurrency} until the target replaced the workspaces runner
 * (qits-1535), whose own formula ({@code WorkspaceSlots}) moved in here as the agent share; the CI
 * share sized this host's CI runner until that runner was replaced too (qits-1547).
 * <p>
 * The CI share sized qits-ci's own retired in-process executor (`QITS_CI_CONCURRENT_BUILDS`)
 * until qits-443 removed that executor; the formula and the reason for computing it carried over
 * unchanged.
 * <p>
 * <b>It is computed rather than written down because a literal killed a machine.</b> On 2026-08-22
 * two concurrent GraalVM-native builds on a 16 GB host with no swap livelocked it and it needed a
 * hard reset. The step container's {@code QITS_CI_MEMORY_LIMIT=4g} does not bound them: a step's
 * {@code docker build} is served by the HOST daemon, so the builder runs OUTSIDE that cgroup and
 * a native image compilation takes several gigabytes of its own. Two of them plus the platform's
 * own containers is more than a 16 GB host has. The {@code 1} an operator set by hand on
 * 2026-08-17 survived until the next re-bootstrap wrote the literal {@code 2} back over it, which
 * is the second half of the lesson: a number that has to be right on this host cannot live in a
 * template.
 * <p>
 * The formula reserves memory for the platform itself and then gives each concurrent build its
 * own share:
 * <pre>
 *     concurrentBuilds = max(1, floor((totalMemoryGiB - 10) / 6))
 * </pre>
 * — 1 on a 16 GB host (wohlben.eu), 2 from 22 GB, 3 from 28 GB. Never 0: a platform that builds
 * nothing is not a smaller platform, it is a stopped one, so the smallest host runs one build and
 * takes its time.
 * <p>
 * An operator who knows better still wins: {@code QITS_CI_CONCURRENT_BUILDS} in {@code .env} or
 * the environment is used as it stands (see {@code BootstrapConfig.ciConcurrentBuilds}).
 * <p>
 * <b>The agent share is that rule carried one step further.</b> The platform keeps its reserve,
 * each CI build keeps its share, and what is left is shared out per agent slot:
 * <pre>
 *     agentSlots = max(1, floor((totalMemoryGiB - 10 - 6 * ciSlots) / 4))
 * </pre>
 * — 1 on a 16 GB host running one build (wohlben.eu), 2 on 32 GB running two. The target's total
 * is the CI share plus that, the agent share never under the agent minimums, so the smallest host
 * still holds one build, one workspace and one desk.
 */
public final class TargetSlots {

    private static final long GIB = 1024L * 1024L * 1024L;

    /** Kept for the platform's own containers, out of reach of the builds. */
    private static final long RESERVED = 10 * GIB;

    /** What one concurrent GraalVM-native build is allowed to cost. */
    private static final long PER_BUILD = 6 * GIB;

    /** What one agent slot — a workspace or a desk — is given. */
    private static final long PER_AGENT = 4 * GIB;

    private TargetSlots() {
    }

    /**
     * The formula, and nothing else — no host is read here, which is what makes it testable.
     *
     * @param totalBytes the host's total physical memory, or 0 when it could not be read
     */
    public static int concurrentBuildsFor(long totalBytes) {
        long forBuilds = totalBytes - RESERVED;
        if (forBuilds < PER_BUILD) {
            return 1;
        }
        return (int) (forBuilds / PER_BUILD);
    }

    /**
     * The agent share: what is left for workspaces and desks once the platform and this host's CI
     * builds have theirs. No host is read here either.
     *
     * @param totalBytes the host's total physical memory, or 0 when it could not be read
     * @param ciSlots    how many builds this host's target runs at once
     */
    public static int agentSlotsFor(long totalBytes, int ciSlots) {
        long forAgents = totalBytes - RESERVED - PER_BUILD * Math.max(0, ciSlots);
        if (forAgents < PER_AGENT) {
            return 1;
        }
        return (int) (forAgents / PER_AGENT);
    }

    /**
     * The target's total: the CI share plus the agent share, the agent share never less than the
     * agent minimums the target is declared with — a total under the sum of its minimums is one
     * qits-system ignores the minimums of and fails the {@code slots} health check over.
     *
     * @param ciSlots       the CI share, which is the target's {@code min_ci}
     * @param agentMinimums the target's workspace and desk minimums, summed
     */
    public static int targetSlotsFor(long totalBytes, int ciSlots, int agentMinimums) {
        return Math.max(0, ciSlots)
                + Math.max(Math.max(1, agentMinimums), agentSlotsFor(totalBytes, ciSlots));
    }

    /**
     * <b>The HOST's total memory, even when this runs in the payload container.</b>
     * {@code /proc/meminfo} is not namespaced, so a container reads the machine's own figure —
     * which is the one that matters, because the builds this sizes are served by the host daemon.
     * A container memory limit would show in cgroup files and deliberately is not consulted.
     * <p>
     * {@code MemTotal} is in kB. The management bean is the fallback for a host with no procfs;
     * both failing answers 0, and {@link #concurrentBuildsFor} reads that as the smallest host.
     */
    public static long hostMemoryBytes() {
        try {
            List<String> lines = Files.readAllLines(Path.of("/proc/meminfo"));
            for (String line : lines) {
                if (line.startsWith("MemTotal:")) {
                    String[] parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]) * 1024L;
                }
            }
        } catch (Exception ignored) {
            // No procfs, or a shape it has never had. The bean below is the answer.
        }
        try {
            return ((com.sun.management.OperatingSystemMXBean)
                    ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
        } catch (Exception | LinkageError ignored) {
            return 0;
        }
    }

    /** "15.2 GiB" — what the boot prints beside the number it computed. */
    public static String describe(long totalBytes) {
        if (totalBytes <= 0) {
            return "host memory unreadable";
        }
        return "host " + String.format(Locale.ROOT, "%.1f", (double) totalBytes / GIB) + " GiB";
    }
}
