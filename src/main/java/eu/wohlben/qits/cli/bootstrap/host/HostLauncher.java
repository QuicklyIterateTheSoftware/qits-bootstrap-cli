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
import java.util.Optional;

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

    /** This CLI's own repository name, which is what a clone of it is called. */
    static final String CLI_CHECKOUT = "qits-cli-bootstrap";

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
            // Reported here, repaired inside: the payload's preflight initialises an INACTIVE
            // daemon and refuses every other state that is not an active manager. This line stays
            // because it is the state the run started from, printed before anything changes it.
            out.println("swarm: " + docker.swarmState());

            // A wrapper that is not here yet is a COLD START, not a failure: the run clones it,
            // inside the container, into the working directory. This half only has to agree with
            // the payload about where that is, and to keep the mount set honest about it.
            WrapperDir.Resolved wrapper = WrapperDir.resolveOrClone(config.wrapperDir(), workDir);
            boolean wrapperOnHost = Files.isDirectory(wrapper.path());
            out.println("wrapper: " + wrapper.path() + "  (" + wrapper.how() + ")");
            if (!wrapperOnHost && !wrapper.path().startsWith(workDir)) {
                // Only the working directory and the paths below it are mounted, so a clone
                // anywhere else would live in the container and die with it — a bootstrap that
                // "worked" and left nothing to rerun from.
                out.println("the wrapper " + wrapper.path() + " does not exist and is outside "
                        + workDir + ", which is the only place a run can create it. Clone "
                        + WrapperDir.REPO + " there by hand, or unset QITS_WRAPPER_DIR "
                        + "(--wrapper-dir) and let the run clone it into the working directory");
                return 2;
            }

            Path context = imageContext(wrapper.path(), workDir).orElse(null);
            if (context == null) {
                out.println("no qits-cli-bootstrap checkout to build the payload image from — "
                        + "looked in " + wrapper.path().resolve(CLI_PATH) + ", at and above "
                        + workDir + ", and in " + workDir.resolve(CLI_CHECKOUT) + ". Run this from "
                        + "inside a checkout of this CLI, or from a wrapper whose submodules are "
                        + "initialised (git submodule update --init)");
                return 2;
            }
            out.println("payload built from: " + context);

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
                    image, wrapper.path(), wrapperOnHost, workDir, sources, logFile,
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
     * The build context for the payload image, which is <b>a checkout of this CLI</b> — the one
     * thing the image is made of.
     * <p>
     * Three places, in order, and each is a machine the bootstrap is actually started on:
     * <ol>
     *   <li>the wrapper's own {@code cli/qits-cli-bootstrap} submodule — the ordinary run, from
     *       inside the checkout;
     *   <li>at or above the working directory — a clone of this CLI alone, which is all a COLD
     *       machine has: {@code curl … | bash} clones this repository, builds it and runs it, and
     *       there is no wrapper until the run itself clones one;
     *   <li>{@code <working directory>/qits-cli-bootstrap} — the same cold clone, from a script
     *       that stepped back out of it so the wrapper lands beside it rather than inside it.
     * </ol>
     * The marker is the Dockerfile, not the directory name: a checkout is what has one, whatever
     * anyone called the directory.
     */
    static Optional<Path> imageContext(Path wrapper, Path from) {
        Path start = from.toAbsolutePath().normalize();
        Path inWrapper = wrapper.resolve(CLI_PATH);
        if (isCliCheckout(inWrapper)) {
            return Optional.of(inWrapper);
        }
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (isCliCheckout(candidate)) {
                return Optional.of(candidate);
            }
        }
        Path beside = start.resolve(CLI_CHECKOUT);
        return isCliCheckout(beside) ? Optional.of(beside) : Optional.empty();
    }

    private static boolean isCliCheckout(Path dir) {
        return Files.isRegularFile(dir.resolve(PayloadImage.DOCKERFILE));
    }

    /**
     * The image is addressed by its content, so "already built" is the ordinary answer and the
     * whole point: the image's first stage is a cold GraalVM native build, and a bootstrap that
     * ran one every time would spend many minutes before its first phase.
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
