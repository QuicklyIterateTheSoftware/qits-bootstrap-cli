package eu.wohlben.qits.cli.bootstrap.platform;

import eu.wohlben.qits.cli.bootstrap.proc.Cmd;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessResult;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * The docker CLI, driving the HOST's daemon through the socket mounted into this container.
 * <p>
 * Every path this class puts on a command line is read by the CLIENT — a build context and a
 * {@code -f -} Dockerfile are packed and sent, {@code docker cp} reads the source here, and
 * {@code docker compose -f} parses the file here. So the run's own working directory is the only
 * place paths have to be right, and the script's {@code /out} mount and gitdir contortions stay
 * retired even though the socket came back. The paths that ARE resolved by the daemon are the ones
 * this program hands it deliberately: named volumes, and {@code /var/run/docker.sock} for the
 * services that need it.
 */
public class Docker {

    /** Long enough for a cold GraalVM native build, which is what most of these are. */
    public static final Duration BUILD_TIMEOUT = Duration.ofHours(4);

    /**
     * Where a container learns its own id. Docker sets the hostname to the container's short id
     * unless it is given another one, and both are names the daemon resolves.
     */
    private static final Path SELF = Path.of("/etc/hostname");

    private final ProcessRunner runner;

    public Docker(ProcessRunner runner) {
        this.runner = runner;
    }

    public ProcessResult run(Cmd cmd, Consumer<String> out) {
        return runner.run(cmd, out);
    }

    public boolean daemonReachable() {
        return runner.run(Cmd.of("docker", "version"), null).ok();
    }

    public boolean composePluginPresent() {
        return runner.run(Cmd.of("docker", "compose", "version"), null).ok();
    }

    /**
     * Whether this client has buildx. Asked because a client without it does not fail: it falls
     * back to the legacy builder, which reads build flags differently and reports nothing.
     * qits-deployments carries a scar from exactly that. The payload image ships the plugin, so a
     * false here means the image was built wrong or the run is not the payload — both worth
     * stopping for, because the alternative is images that differ from the ones the platform
     * expects and no message saying so.
     */
    public boolean buildxPresent() {
        return runner.run(Cmd.of("docker", "buildx", "version"), null).ok();
    }

    /**
     * The daemon's swarm state — {@code active}, {@code inactive}, {@code pending}. The host half
     * reports it and changes nothing: {@code swarm init} rewrites the networking of every container
     * on the machine, which is a migration rather than a preflight repair.
     */
    public String swarmState() {
        ProcessResult state = runner.run(Cmd.of(
                "docker", "info", "--format", "{{.Swarm.LocalNodeState}}"), null);
        return state.ok() && !state.trimmed().isEmpty() ? state.trimmed() : "unknown";
    }

    /** Whether an image reference is already on this daemon. */
    public boolean imageExists(String reference) {
        return runner.run(Cmd.of("docker", "image", "inspect", "-f", "{{.Id}}", reference),
                null).ok();
    }

    /** The docker socket's group. The deployer and ci join it rather than running as root. */
    public String socketGroupId() {
        Path socket = Path.of("/var/run/docker.sock");
        try {
            Object gid = Files.getAttribute(socket, "unix:gid");
            if (gid != null) {
                return gid.toString();
            }
        } catch (IOException | UnsupportedOperationException e) {
            // Fall through to stat, then to 0 — a group that does not exist is better than a crash.
        }
        ProcessResult stat = runner.run(Cmd.of("stat", "-c", "%g", socket.toString()), null);
        return stat.ok() && !stat.trimmed().isEmpty() ? stat.trimmed() : "0";
    }

    /**
     * The name this process's own container answers to, or null when the file cannot be read. It
     * is not proof of a container — a host's {@code /etc/hostname} is a hostname too — so the
     * caller asks the daemon whether it knows the name before trusting it.
     */
    public String selfName() {
        try {
            String name = Files.readString(SELF).trim();
            return name.isBlank() ? null : name;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The NAME the daemon knows this process's own container by, or null when this is not one.
     * <p>
     * {@link #selfName()} answers with the id, and every sweep in this program lists names — so a
     * phase that must not touch the container it is running in has to compare like with like. That
     * is {@code unwrap}'s {@code containers} phase, which would otherwise {@code docker rm -f}
     * itself.
     */
    public String selfContainerName() {
        String self = selfName();
        if (self == null) {
            return null;
        }
        ProcessResult name = runner.run(Cmd.of("docker", "inspect", "--type", "container",
                "-f", "{{.Name}}", self), null);
        if (!name.ok()) {
            return null;
        }
        // docker reports it with a leading slash; `docker ps --format {{.Names}}` does not.
        String text = name.trimmed();
        return text.startsWith("/") ? text.substring(1) : text.isEmpty() ? null : text;
    }

    public boolean containerExists(String nameOrId) {
        return runner.run(Cmd.of("docker", "inspect", "--type", "container", "-f", "{{.Id}}",
                nameOrId), null).ok();
    }

    /** The networks a container is an endpoint on. */
    public List<String> networksOf(String nameOrId) {
        ProcessResult result = runner.run(Cmd.of("docker", "inspect", "-f",
                "{{range $net, $_ := .NetworkSettings.Networks}}{{$net}} {{end}}", nameOrId), null);
        List<String> networks = new ArrayList<>();
        for (String line : lines(result)) {
            for (String name : line.split("\\s+")) {
                if (!name.isBlank()) {
                    networks.add(name);
                }
            }
        }
        return networks;
    }

    public boolean networkExists(String name) {
        return runner.run(Cmd.of("docker", "network", "inspect", name), null).ok();
    }

    /** Creates the network unless it is there. The deployer and compose both adopt an existing one. */
    public void ensureNetwork(String name, Consumer<String> out) {
        if (!networkExists(name)) {
            runner.run(Cmd.of("docker", "network", "create", name), out);
        }
    }

    public void ensureVolume(String name, Consumer<String> out) {
        runner.run(Cmd.of("docker", "volume", "create", name), out);
    }

    /** Names of the running containers. */
    public List<String> runningNames() {
        return lines(runner.run(Cmd.of("docker", "ps", "--format", "{{.Names}}"), null));
    }

    /** Names of every container, running or not. */
    public List<String> allNames() {
        return lines(runner.run(Cmd.of("docker", "ps", "-a", "--format", "{{.Names}}"), null));
    }

    /** "name image" per running container — how a platform service's live sha is read. */
    public List<String> runningNamesAndImages() {
        return lines(runner.run(Cmd.of("docker", "ps", "--format", "{{.Names}} {{.Image}}"), null));
    }

    public List<String> ps(String format) {
        return lines(runner.run(Cmd.of("docker", "ps", "--format", format), null));
    }

    public void removeContainer(String name, Consumer<String> out) {
        runner.run(Cmd.of("docker", "rm", "-f", name), out);
    }

    /**
     * A container's log since an RFC3339 moment, each line led by docker's own timestamp — which is
     * what lets the caller ask only for what is new. stderr rides along: the runner merges the
     * streams, and a container's log arrives on both.
     */
    public List<String> logsSince(String container, String since) {
        return lines(runner.run(
                Cmd.of("docker", "logs", "--timestamps", "--since", since, container), null));
    }

    /**
     * Builds an image from a Dockerfile fed on stdin, which is how the seed builds get the
     * mirror-free FROM lines without touching the checkout.
     */
    public ProcessResult buildFromStdin(String tag, String dockerfile, Path context,
                                        List<String> extraArgs, Consumer<String> out) {
        List<String> command = new ArrayList<>(List.of(
                "docker", "build", "--network", "host", "-t", tag, "-f", "-"));
        command.addAll(extraArgs);
        command.add(context.toString());
        return runner.run(Cmd.of(command)
                .stdin(dockerfile)
                .timeout(BUILD_TIMEOUT)
                // Line-oriented build output. Honoured by buildkit, ignored by the legacy builder,
                // which is what makes it safe to set unconditionally.
                .env("BUILDKIT_PROGRESS", "plain"), out);
    }

    public ProcessResult build(List<String> args, Consumer<String> out) {
        List<String> command = new ArrayList<>(List.of("docker", "build"));
        command.addAll(args);
        return runner.run(Cmd.of(command).timeout(BUILD_TIMEOUT)
                .env("BUILDKIT_PROGRESS", "plain"), out);
    }

    public ProcessResult exec(Consumer<String> out, String... args) {
        return runner.run(Cmd.of(prepend("docker", args)), out);
    }

    public ProcessResult exec(Duration timeout, Consumer<String> out, String... args) {
        return runner.run(Cmd.of(prepend("docker", args)).timeout(timeout), out);
    }

    private static List<String> prepend(String first, String[] rest) {
        List<String> all = new ArrayList<>(rest.length + 1);
        all.add(first);
        all.addAll(Arrays.asList(rest));
        return all;
    }

    private static List<String> lines(ProcessResult result) {
        List<String> out = new ArrayList<>();
        for (String line : result.captured()) {
            if (!line.isBlank() && !line.startsWith("$ ")) {
                out.add(line.trim());
            }
        }
        return out;
    }
}
