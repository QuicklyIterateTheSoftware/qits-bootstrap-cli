package eu.wohlben.qits.cli.bootstrap.phases;

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

/**
 * Unwrap: take the platform off this machine again.
 * <p>
 * What is "qits-marked" is what the platform actually marks — cd's own container labels, the
 * compose project of the seed stack, the {@code qits-} name prefixes and the images published under
 * {@code qits/} or the local registry host. There is no single unified label to sweep by, and
 * inventing one here would only be true of the containers this program removed.
 * <p>
 * Volumes are the one guarded decision: they hold the platform's databases, the registry's blobs
 * and the git host's repositories. They stay unless {@code --with-volumes} says otherwise.
 */
public class UnwrapPhases {

    private final Boot boot;
    private final boolean withVolumes;
    private final boolean dryRun;

    public UnwrapPhases(Boot boot, boolean withVolumes, boolean dryRun) {
        this.boot = boot;
        this.withVolumes = withVolumes;
        this.dryRun = dryRun;
    }

    public List<Phase> build() {
        List<Phase> phases = new ArrayList<>();
        phases.add(composeDown());
        phases.add(containers());
        if (withVolumes) {
            phases.add(volumes());
        } else {
            phases.add(volumesKept());
        }
        phases.add(networks());
        phases.add(images());
        return List.copyOf(phases);
    }

    /** The seed stack, through compose, so its own bookkeeping goes with it. */
    private Phase composeDown() {
        return new Phase("compose-down", "stop the compose seed stack", ctx -> {
            Path compose = Path.of(boot.config.wrapperDir()).toAbsolutePath().normalize()
                    .resolve("docker-compose.qits.yml");
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

    /** Everything qits-cd deployed, plus anything left of the compose project. */
    private Phase containers() {
        return new Phase("containers", "remove the platform's containers", ctx -> {
            Set<String> ids = new LinkedHashSet<>();
            ids.addAll(byFilter("label=qits.cd.environment"));
            ids.addAll(byFilter("label=qits.cd.app-name"));
            ids.addAll(byFilter("label=qits.cd.target"));
            ids.addAll(byFilter("label=com.docker.compose.project=qits"));
            ids.addAll(namedQits());
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
            List<String> names = boot.docker
                    .run(Cmd.of("docker", "volume", "ls", "-q"), null)
                    .captured().stream().map(String::trim)
                    .filter(name -> name.startsWith("qits-")).toList();
            if (names.isEmpty()) {
                ctx.skip("no qits-* volumes");
            }
            ctx.log("  " + String.join(", ", names));
            remove(ctx, names, "volume", "rm");
            ctx.note(done(names.size()));
        });
    }

    private Phase volumesKept() {
        return new Phase("volumes-kept", "keep the qits-* volumes", ctx -> {
            List<String> names = boot.docker
                    .run(Cmd.of("docker", "volume", "ls", "-q"), null)
                    .captured().stream().map(String::trim)
                    .filter(name -> name.startsWith("qits-")).toList();
            ctx.log("  " + names.size() + " volume(s) kept: databases, registry blobs and the git host");
            ctx.log("  add --with-volumes for the full clean slate");
            ctx.note(names.size() + " kept");
        });
    }

    private Phase networks() {
        return new Phase("networks", "remove the platform's networks", ctx -> {
            Set<String> names = new LinkedHashSet<>();
            for (String line : boot.docker.run(Cmd.of(
                    "docker", "network", "ls", "--format", "{{.Name}}"), null).captured()) {
                String name = line.trim();
                if (name.equals("qits-net") || name.equals("qits-platform")
                        || name.startsWith("qits-env-") || name.startsWith("qits_")) {
                    names.add(name);
                }
            }
            for (String line : boot.docker.run(Cmd.of(
                    "docker", "network", "ls", "--filter", "label=qits.cd.network",
                    "--format", "{{.Name}}"), null).captured()) {
                names.add(line.trim());
            }
            names.removeIf(String::isBlank);
            if (names.isEmpty()) {
                ctx.skip("no qits networks");
            }
            ctx.log("  " + String.join(", ", names));
            remove(ctx, new ArrayList<>(names), "network", "rm");
            ctx.note(done(names.size()));
        });
    }

    /** The platform's own images: qits/* and everything published to the local registry host. */
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

    private List<String> namedQits() {
        return boot.docker.allNames().stream().filter(name -> name.startsWith("qits-")).toList();
    }

    private String done(int count) {
        return dryRun ? count + " would go" : count + " removed";
    }

    /** Removal is best effort per item: one stubborn object must not hide the rest. */
    private void remove(PhaseContext ctx, List<String> items, String... command) {
        int failed = 0;
        for (String item : items) {
            if (dryRun) {
                ctx.log("  would remove " + item);
                continue;
            }
            List<String> args = new ArrayList<>(List.of(command));
            args.add(item);
            ProcessResult result = boot.docker.exec(Duration.ofMinutes(5), null,
                    args.toArray(String[]::new));
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
}
