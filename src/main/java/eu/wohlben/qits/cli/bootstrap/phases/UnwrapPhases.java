package eu.wohlben.qits.cli.bootstrap.phases;

import eu.wohlben.qits.cli.bootstrap.config.WrapperDir;
import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseContext;
import eu.wohlben.qits.cli.bootstrap.proc.Cmd;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Unwrap: take the platform off this machine again.
 * <p>
 * What is "qits-marked" is what the platform actually marks — the deployer's own container labels,
 * the compose project of the seed stack, the {@code qits-} name prefixes and the images published
 * under {@code qits/} or the local registry host. There is no single unified label to sweep by, and
 * inventing one here would only be true of the containers this program removed.
 * <p>
 * <b>Two label namespaces, both forever.</b> {@code qits.platform.deployments.*} is what
 * qits-deployments writes; {@code qits.cd.*} is what the retired qits-cd wrote. Unwrap is how a
 * pre-v3 platform is taken off a machine, and a machine that has not been bootstrapped since the
 * merge-back carries only the old labels — so the old patterns stay, and dropping them would leave
 * containers running that this command reported as removed.
 * <p>
 * <b>Two container-name shapes too, for the same reason.</b> Every container the platform ever
 * started used to begin {@code qits-}. Since 2026-08-08 an environment service is named after its
 * wire alias — {@code prod-qits-ci}, {@code prod-qits-gateway} — which begins with the environment
 * name instead. So the sweep asks for {@code qits-} at the START or {@code -qits-} ANYWHERE, which
 * covers both shapes and every environment name, not only the one this run is configured with. A
 * name is added here; none is ever taken away.
 * <p>
 * Volumes are the one guarded decision: they hold the platform's databases, the registry's blobs
 * and the git host's repositories. They stay unless {@code --with-volumes} says otherwise.
 * <p>
 * <b>{@code --with-data-volumes} is the middle answer, and it exists for the re-bootstrap.</b>
 * Moving a service onto another database is a cold boot of that service's data and nothing else,
 * so the data volumes go and the CONFIG volumes stay: {@code qits-deployments-config} holds the
 * push token, the client secrets and every run-arg the deployer boots from, and losing it turns a
 * migration into a re-issue of every credential on the machine. Keep-patterns are therefore
 * checked FIRST and win, and a volume matching neither set is kept — a sweep that guesses about a
 * volume it has never heard of is how someone's data goes.
 * <p>
 * <b>A CACHE is not data either.</b> {@code qits-maven-cache} holds third-party jars pulled from
 * Maven Central, which belong to nobody here and are the same bytes after any reset. It is kept by
 * {@code --with-data-volumes} and removed by {@code --with-volumes}: the middle answer is about
 * this platform's state, the full one is about this machine.
 */
public class UnwrapPhases {

    /**
     * Kept whatever else matches, and checked before anything is deleted.
     * <p>
     * <b>{@code qits-maven-cache} is here because it is not this platform's data.</b> It holds
     * third-party jars the bootstrap's own maven containers downloaded from Maven Central, and a
     * database reset is no reason to fetch the dependency world again — four cold runs in one
     * evening is what got this host throttled and killed a bootstrap on a 502. Naming it rather
     * than letting it fall between the two patterns: it is a volume this program creates, so what
     * happens to it is a decision, and it stays one if someone widens {@link #DATA}.
     */
    private static final List<String> KEEP = List.of("qits-*-config", "qits-maven-cache");

    /**
     * Deleted by {@code --with-data-volumes}. {@code qits-maven-seed} is the temporary Maven
     * repository the first-boot dependency cycle is broken with — rebuilt by the next bootstrap,
     * so it is data in every sense that matters here. The cache above is its opposite: qits bytes
     * that this platform published against third-party bytes that it did not.
     */
    private static final List<String> DATA = List.of("qits-*-data", "qits-maven-seed");

    private final Boot boot;
    private final boolean withVolumes;
    private final boolean withDataVolumes;
    private final boolean dryRun;

    public UnwrapPhases(Boot boot, boolean withVolumes, boolean withDataVolumes, boolean dryRun) {
        this.boot = boot;
        this.withVolumes = withVolumes;
        this.withDataVolumes = withDataVolumes;
        this.dryRun = dryRun;
    }

    public List<Phase> build() {
        List<Phase> phases = new ArrayList<>();
        phases.add(composeDown());
        phases.add(containers());
        if (withVolumes) {
            phases.add(volumes());
        } else if (withDataVolumes) {
            phases.add(dataVolumes());
        } else {
            phases.add(volumesKept());
        }
        phases.add(selfDetach());
        phases.add(networks());
        phases.add(images());
        return List.copyOf(phases);
    }

    /** The seed stack, through compose, so its own bookkeeping goes with it. */
    private Phase composeDown() {
        return new Phase("compose-down", "stop the compose seed stack", ctx -> {
            WrapperDir.Resolved wrapper =
                    WrapperDir.resolve(boot.config.wrapperDir(), Path.of("").toAbsolutePath());
            ctx.log("  wrapper: " + wrapper.path() + "  (" + wrapper.how() + ")");
            Path compose = wrapper.path().resolve("docker-compose.qits.yml");
            if (!Files.isRegularFile(compose)) {
                ctx.skip("no generated compose file at " + compose);
            }
            if (dryRun) {
                ctx.log("  would run: docker compose -p qits -f " + compose + " down");
                return;
            }
            boot.docker.exec(Duration.ofMinutes(10), ctx::log,
                    "compose", "-p", "qits", "-f", compose.toString(), "down");
        });
    }

    /** Everything either deployer deployed, plus anything left of the compose project. */
    private Phase containers() {
        return new Phase("containers", "remove the platform's containers", ctx -> {
            Set<String> ids = new LinkedHashSet<>();
            for (String namespace : List.of("qits.platform.deployments", "qits.cd")) {
                ids.addAll(byFilter("label=" + namespace + ".environment"));
                ids.addAll(byFilter("label=" + namespace + ".app-name"));
                ids.addAll(byFilter("label=" + namespace + ".target"));
            }
            ids.addAll(byFilter("label=com.docker.compose.project=qits"));
            ids.addAll(namedQits());
            // NOT THIS ONE. The CLI runs as a container called qits-bootstrap-cli, which the sweep
            // above matches like any other — and removing it is `docker rm -f` on the process doing
            // the removing. unwrap would die here, leaving the networks, the images and every phase
            // after this one undone, and the failure would read as the daemon going away.
            //
            // Excluded rather than named out of the pattern: the pattern is what a machine's qits
            // containers look like and must keep matching, and the exclusion holds for whatever
            // name this container was started under, including one a person chose.
            String self = boot.docker.selfContainerName();
            if (self != null && ids.remove(self)) {
                ctx.log("  keeping " + self + ", which is this run");
            }
            if (ids.isEmpty()) {
                ctx.skip("no qits containers");
            }
            ctx.log("  " + ids.size() + " container(s)");
            remove(ctx, new ArrayList<>(ids), "rm", "-f");
            ctx.note(done(ids.size()));
        });
    }

    private Phase volumes() {
        return new Phase("volumes", "remove the qits-* volumes (ALL local state)", ctx -> {
            List<String> names = volumeNames();
            if (names.isEmpty()) {
                ctx.skip("no qits-* volumes");
            }
            ctx.log("  " + String.join(", ", names));
            remove(ctx, names, "volume", "rm");
            ctx.note(done(names.size()));
        });
    }

    /**
     * The re-bootstrap's sweep: a service's data goes, its configuration stays.
     * <p>
     * Both name lists are printed in full. This is the toggle a person reaches for when they are
     * about to lose data on purpose, and the only way to check the sweep agreed with them is to
     * read what it kept beside what it removed.
     */
    private Phase dataVolumes() {
        return new Phase("volumes-data",
                "remove the qits-*-data volumes, keep the config ones", ctx -> {
            List<String> all = volumeNames();
            List<String> kept = all.stream().filter(name -> !isData(name)).toList();
            List<String> going = all.stream().filter(UnwrapPhases::isData).toList();
            ctx.log("  keeping " + kept.size() + ": "
                    + (kept.isEmpty() ? "nothing" : String.join(", ", kept)));
            if (going.isEmpty()) {
                ctx.skip("no data volumes; " + kept.size() + " kept");
            }
            ctx.log("  removing " + going.size() + ": " + String.join(", ", going));
            remove(ctx, going, "volume", "rm");
            ctx.note(done(going.size()) + ", " + kept.size() + " kept");
        });
    }

    /**
     * Data, and therefore removable — but only after the keep list has had its say. A volume that
     * matches neither list is kept: this program did not create it and does not know what it is.
     */
    static boolean isData(String name) {
        if (KEEP.stream().anyMatch(pattern -> matches(pattern, name))) {
            return false;
        }
        return DATA.stream().anyMatch(pattern -> matches(pattern, name));
    }

    /** A pattern is a literal with one optional {@code *}, which is all these names need. */
    private static boolean matches(String pattern, String name) {
        int star = pattern.indexOf('*');
        if (star < 0) {
            return name.equals(pattern);
        }
        String head = pattern.substring(0, star);
        String tail = pattern.substring(star + 1);
        return name.length() >= head.length() + tail.length()
                && name.startsWith(head) && name.endsWith(tail);
    }

    private List<String> volumeNames() {
        return boot.docker
                .run(Cmd.of("docker", "volume", "ls", "-q"), null)
                .captured().stream().map(String::trim)
                .filter(UnwrapPhases::isPlatformVolume).toList();
    }

    /**
     * What {@code --with-volumes} takes: every volume the platform named, the keep list included.
     * The full teardown asks no questions — {@link #isData} is the middle answer's test and this
     * one is the total one, so a volume kept by {@code --with-data-volumes} still goes here.
     */
    static boolean isPlatformVolume(String name) {
        return name.startsWith("qits-");
    }

    private Phase volumesKept() {
        return new Phase("volumes-kept", "keep the qits-* volumes", ctx -> {
            List<String> names = volumeNames();
            ctx.log("  " + names.size() + " volume(s) kept: databases, registry blobs, the git host"
                    + " and the maven download cache");
            ctx.log("  add --with-data-volumes to reset the databases and keep the config volumes"
                    + " and the maven cache");
            ctx.log("  add --with-volumes for the full clean slate");
            ctx.note(names.size() + " kept");
        });
    }

    /**
     * <b>An attached container is an endpoint, and docker refuses to remove a network that has
     * one.</b> This CLI runs as a container and joins qits-net to reach the platform's addresses at
     * all, so without this the phase below would fail on the single endpoint this program put
     * there itself. It is qits-deployments' own order when it deletes an environment: disconnect
     * the services, then remove the networks.
     * <p>
     * Every platform network this container is on, by the same name test the removal uses — the
     * two cannot drift apart. On none of them it skips, which is what a run started with no
     * {@code --network} looks like.
     */
    private Phase selfDetach() {
        return new Phase("self-detach", "leave the platform's networks", ctx -> {
            String self = boot.docker.selfName();
            List<String> attached = self == null || !boot.docker.containerExists(self)
                    ? List.of()
                    : boot.docker.networksOf(self).stream()
                            .filter(UnwrapPhases::isPlatformNetwork).toList();
            if (attached.isEmpty()) {
                ctx.skip("this run is on none of the platform's networks");
            }
            ctx.log("  " + self + " is on " + String.join(", ", attached));
            int left = 0;
            for (String network : attached) {
                if (dryRun) {
                    ctx.log("  would disconnect from " + network);
                    continue;
                }
                ProcessResult result = boot.docker.exec(Duration.ofMinutes(1), null,
                        "network", "disconnect", network, self);
                if (result.ok()) {
                    left++;
                    ctx.log("  left " + network);
                } else {
                    ctx.warn("still on " + network + ", which cannot be removed while this run is "
                            + "an endpoint on it: " + result.tailText(1));
                }
            }
            ctx.note(dryRun ? attached.size() + " would go" : left + " left");
        });
    }

    /**
     * qits-net is the shared legacy network; qits-platform is where platform services run;
     * qits-env-&lt;env&gt; is an environment's bundle and qits-env-&lt;env&gt;-&lt;app&gt; one
     * service's own network; qits_* is what a compose project would have named.
     */
    private static boolean isPlatformNetwork(String name) {
        return name.equals("qits-net") || name.equals("qits-platform")
                || name.startsWith("qits-env-") || name.startsWith("qits_");
    }

    private Phase networks() {
        return new Phase("networks", "remove the platform's networks", ctx -> {
            Set<String> names = new LinkedHashSet<>();
            for (String line : boot.docker.run(Cmd.of(
                    "docker", "network", "ls", "--format", "{{.Name}}"), null).captured()) {
                String name = line.trim();
                if (isPlatformNetwork(name)) {
                    names.add(name);
                }
            }
            for (String label : List.of("qits.platform.deployments.network", "qits.cd.network")) {
                for (String line : boot.docker.run(Cmd.of(
                        "docker", "network", "ls", "--filter", "label=" + label,
                        "--format", "{{.Name}}"), null).captured()) {
                    names.add(line.trim());
                }
            }
            names.removeIf(String::isBlank);
            if (names.isEmpty()) {
                ctx.skip("no qits networks");
            }
            ctx.log("  " + String.join(", ", names));
            remove(ctx, new ArrayList<>(names), NETWORK_ATTEMPTS, "network", "rm");
            ctx.note(done(names.size()));
        });
    }

    /**
     * The platform's own images: qits/* and everything published to the local registry host.
     * <p>
     * The payload image this run is executing from is {@code qits-bootstrap:<content sha>} and
     * matches neither pattern, which is why it is spelled that way rather than {@code qits/…}.
     * Docker refuses to remove an image a running container holds, so sweeping it in would end
     * every unwrap with a failure it could do nothing about — and keeping it is what makes the
     * unwrap-then-bootstrap cycle start in seconds.
     */
    private Phase images() {
        return new Phase("images", "remove the platform's images", ctx -> {
            String registryHost = "localhost:" + boot.config.registryPort() + "/";
            List<String> refs = new ArrayList<>();
            for (String line : boot.docker.run(Cmd.of(
                    "docker", "images", "--format", "{{.Repository}}:{{.Tag}}"), null).captured()) {
                String ref = line.trim();
                if (ref.startsWith("qits/") || ref.startsWith(registryHost)
                        || ref.startsWith("127.0.0.1:" + boot.config.registryPort() + "/")) {
                    refs.add(ref);
                }
            }
            if (refs.isEmpty()) {
                ctx.skip("no qits images");
            }
            ctx.log("  " + refs.size() + " image(s)");
            remove(ctx, refs, "rmi", "-f");
            ctx.note(done(refs.size()));
        });
    }

    /** Names rather than ids, so the same container found by two filters is one entry. */
    private List<String> byFilter(String filter) {
        return boot.docker.run(Cmd.of(
                        "docker", "ps", "-a", "--filter", filter, "--format", "{{.Names}}"), null)
                .captured().stream().map(String::trim).filter(name -> !name.isBlank()).toList();
    }

    /**
     * Containers the platform named, in both shapes: {@code qits-…} (the seed's platform services,
     * every {@code qits-pd-…} deployment, the retired {@code qits-cd-…} ones, the bootstrap's own
     * scratch containers) and {@code <env>-qits-…} (a seed environment service, whatever the
     * environment is called). The second test is deliberately not tied to the configured
     * environment name: unwrap cleans a machine, and the machine may carry tiers this run has
     * never heard of.
     */
    private List<String> namedQits() {
        return boot.docker.allNames().stream()
                .filter(name -> name.startsWith("qits-") || name.contains("-qits-"))
                .toList();
    }

    private String done(int count) {
        return dryRun ? count + " would go" : count + " removed";
    }

    /**
     * <b>An overlay lets its endpoints go a moment after the containers do.</b> Measured: a network
     * is removable about a second after the stack is down, and until then {@code network rm} answers
     * "has active endpoints". So the network sweep asks a few times over a short window rather than
     * reporting a failure that a person would only repeat by hand. Every attempt is a line.
     */
    static final int NETWORK_ATTEMPTS = 6;

    static final Duration NETWORK_PAUSE = Duration.ofMillis(500);

    /** Retries while the answer is a failure, up to {@code attempts} times, pausing between. */
    static ProcessResult retrying(Supplier<ProcessResult> attempt, int attempts, Runnable pause) {
        ProcessResult result = attempt.get();
        for (int left = attempts - 1; left > 0 && !result.ok(); left--) {
            pause.run();
            result = attempt.get();
        }
        return result;
    }

    /** Removal is best effort per item: one stubborn object must not hide the rest. */
    private void remove(PhaseContext ctx, List<String> items, String... command) {
        remove(ctx, items, 1, command);
    }

    private void remove(PhaseContext ctx, List<String> items, int attempts, String... command) {
        int failed = 0;
        for (String item : items) {
            if (dryRun) {
                ctx.log("  would remove " + item);
                continue;
            }
            List<String> args = new ArrayList<>(List.of(command));
            args.add(item);
            ProcessResult result = retrying(
                    () -> boot.docker.exec(Duration.ofMinutes(5), null, args.toArray(String[]::new)),
                    attempts,
                    () -> {
                        ctx.log("  " + item + " is not free yet; asking again");
                        pause();
                    });
            if (result.ok()) {
                ctx.log("  removed " + item);
            } else {
                failed++;
                ctx.log("  kept " + item + ": " + result.tailText(1));
            }
        }
        if (failed > 0) {
            ctx.warn(failed + " of " + items.size() + " could not be removed");
        }
    }

    private static void pause() {
        try {
            Thread.sleep(NETWORK_PAUSE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
