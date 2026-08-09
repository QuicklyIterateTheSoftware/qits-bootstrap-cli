package eu.wohlben.qits.cli.bootstrap.host;

import eu.wohlben.qits.cli.bootstrap.config.BootstrapConfig;
import eu.wohlben.qits.cli.bootstrap.config.WrapperDir;
import eu.wohlben.qits.cli.bootstrap.platform.Docker;
import eu.wohlben.qits.cli.bootstrap.proc.Cmd;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessResult;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessRunner;
import eu.wohlben.qits.cli.bootstrap.proc.RunLog;
import eu.wohlben.qits.cli.bootstrap.ui.UiFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The host half: the same program, started outside a container, putting itself inside one.
 * <p>
 * <b>Every phase runs in a container, and this is how it gets there.</b> The phases dial the
 * platform at its wire aliases on qits-net, which resolve for members of that network and for
 * nobody else, so there is no host-addressed mode to fall back to — a run that stayed on the host
 * would fail on its first poll. This half therefore has exactly three jobs: check what only the
 * host can check, build the payload image, and run it.
 * <p>
 * <b>It grows no configuration of its own.</b> It reads the same {@link BootstrapConfig} the phases
 * read, and it hands the environment through rather than interpreting it: the working directory
 * inside the container is the one the launcher stood in, so {@code .env} is the same file, and the
 * paths a {@code QITS_*} value names are mounted where they already are. One binary, one contract.
 * <p>
 * The exit code is the payload's, unchanged: 2 a phase failed, 1 a deployment never landed, 0
 * clean. {@code docker run} propagates it, so all this half does is not swallow it.
 */
public final class HostLauncher {

    /** Where this CLI sits inside the wrapper, and therefore where the image is built from. */
    static final String CLI_PATH = "cli/qits-cli-bootstrap";

    private HostLauncher() {
    }

    /**
     * Preflight, build, run, relay.
     *
     * @param args this program's own arguments, passed on unchanged
     */
    public static int run(BootstrapConfig config, List<String> args, PrintStream out)
            throws Exception {
        Path workDir = Path.of("").toAbsolutePath().normalize();
        List<String> argv;

        // The log is opened and closed here so the payload, which appends to the same file, has it
        // to itself while it runs. It is created by the user who started the launcher, which is
        // also who the container runs as, so the two writers are one owner.
        try (RunLog log = new RunLog(Path.of(config.logFile()))) {
            log.section("launch " + String.join(" ", args));
            ProcessRunner runner = new ProcessRunner(log);
            Docker docker = new Docker(runner);

            // What only the host can answer. The compose plugin, git and stty are not checked here:
            // they are in the image, and the payload's own preflight checks the ones it will use.
            if (!docker.daemonReachable()) {
                out.println("cannot reach a docker daemon — is it running?");
                return 2;
            }
            out.println("docker daemon: reachable");
            // Reported, never changed. `docker swarm init` would rewrite this machine's networking
            // for every container on it, which is not a side effect a bootstrap gets to have; the
            // move onto swarm is its own change. Saying the state out loud is what makes the next
            // one's starting point a fact.
            out.println("swarm: " + docker.swarmState());

            WrapperDir.Resolved wrapper = WrapperDir.resolve(config.wrapperDir(), workDir);
            out.println("wrapper: " + wrapper.path() + "  (" + wrapper.how() + ")");
            if (!Files.isDirectory(wrapper.path())) {
                out.println(WrapperDir.notFound(workDir));
                return 2;
            }

            Path context = wrapper.path().resolve(CLI_PATH);
            if (!Files.isDirectory(context)) {
                out.println("no " + CLI_PATH + " in " + wrapper.path() + " — the payload image is "
                        + "built from this CLI's own checkout, so it has to be one of the wrapper's "
                        + "submodules. Run: git submodule update --init");
                return 2;
            }

            String image = PayloadImage.reference(context);
            if (!buildIfMissing(docker, image, context, out)) {
                return 2;
            }

            // Resolved against the working directory here exactly as the payload will resolve them
            // inside, because the working directory is the same one. Created before the run so
            // docker does not create the mount point itself, which it would do as root.
            Path sources = workDir.resolve(config.src()).normalize();
            Files.createDirectories(sources);
            Path logFile = workDir.resolve(config.logFile()).normalize();

            argv = ContainerRun.command(new ContainerRun.Plan(
                    image, wrapper.path(), workDir, sources, logFile,
                    user(runner), docker.socketGroupId(),
                    // The same test UiFactory uses to pick a display, asked on this side: the two
                    // must not disagree about whether there is a terminal.
                    UiFactory.isTerminal(),
                    config, System.getenv(), args));
            log.line("$ " + String.join(" ", argv));
        }

        out.println("$ " + String.join(" ", argv));
        // NOT through ProcessRunner: the payload owns the terminal. Its streams are inherited so
        // JLine has a real tty to repaint, and so the exit code arrives unaltered.
        Process payload = new ProcessBuilder(argv).inheritIO().start();
        return payload.waitFor();
    }

    /**
     * The image is addressed by its content, so "already built" is the ordinary answer and the
     * whole point: a bootstrap that rebuilt a JRE, three docker CLIs and a Quarkus jar every run
     * would spend minutes before its first phase.
     */
    private static boolean buildIfMissing(Docker docker, String image, Path context, PrintStream out)
            throws IOException {
        if (docker.imageExists(image)) {
            out.println("payload image: " + image + " (built already)");
            return true;
        }
        out.println("payload image: " + image + " — building it from " + context);
        ProcessResult built = docker.build(List.of(
                "-f", context.resolve(PayloadImage.DOCKERFILE).toString(),
                "-t", image,
                context.toString()), out::println);
        if (!built.ok()) {
            out.println("the payload image did not build (exit " + built.exitCode() + ")");
            return false;
        }
        return true;
    }

    /**
     * Who started this. Shelled rather than read from a Java API because there is no Java API for
     * it, and this program shells for a living; the alternative — the owner of a file it creates —
     * is the directory's group on a setgid directory rather than the process's.
     */
    private static String user(ProcessRunner runner) {
        ProcessResult uid = runner.run(Cmd.of("id", "-u"), null);
        ProcessResult gid = runner.run(Cmd.of("id", "-g"), null);
        if (!uid.ok() || !gid.ok()) {
            throw new IllegalStateException("cannot read this user's uid and gid, so the container "
                    + "cannot be run as them: " + uid.tailText(1) + " " + gid.tailText(1));
        }
        return uid.trimmed() + ":" + gid.trimmed();
    }
}
