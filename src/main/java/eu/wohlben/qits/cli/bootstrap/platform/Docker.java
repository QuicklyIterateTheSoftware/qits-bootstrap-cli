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
 * The host's docker CLI. This is the architecture's one real simplification over the script: the
 * bootstrap runs on the host, so it talks to the daemon the way a person does — no socket mount, no
 * throwaway wrapper container, no paths that mean one thing inside and another outside.
 */
public class Docker {

    /** Long enough for a cold GraalVM native build, which is what most of these are. */
    public static final Duration BUILD_TIMEOUT = Duration.ofHours(4);

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

    /** The docker socket's group. cd and ci join it rather than running as root. */
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

    public boolean networkExists(String name) {
        return runner.run(Cmd.of("docker", "network", "inspect", name), null).ok();
    }

    /** Creates the network unless it is there. cd and compose both adopt an existing one. */
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

    /** "name image" per running container — how a singleton's live sha is read. */
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
