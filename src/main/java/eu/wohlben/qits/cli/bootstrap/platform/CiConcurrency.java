package eu.wohlben.qits.cli.bootstrap.platform;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * <b>How many builds qits-ci may run at once, computed from the memory of the host it will run
 * on.</b> The value fills {@code QITS_CI_CONCURRENT_BUILDS} in both generated files.
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
 */
public final class CiConcurrency {

    private static final long GIB = 1024L * 1024L * 1024L;

    /** Kept for the platform's own containers, out of reach of the builds. */
    private static final long RESERVED = 10 * GIB;

    /** What one concurrent GraalVM-native build is allowed to cost. */
    private static final long PER_BUILD = 6 * GIB;

    private CiConcurrency() {
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
