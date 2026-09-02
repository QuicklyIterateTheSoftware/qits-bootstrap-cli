package eu.wohlben.qits.cli.bootstrap.host;

import eu.wohlben.qits.cli.bootstrap.config.BootstrapConfig;
import eu.wohlben.qits.cli.bootstrap.config.WrapperDir;
import eu.wohlben.qits.cli.bootstrap.platform.Docker;
import eu.wohlben.qits.cli.bootstrap.proc.Cmd;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessResult;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessRunner;
import eu.wohlben.qits.cli.bootstrap.proc.RunLog;
import eu.wohlben.qits.cli.bootstrap.ui.UiFactory;
import eu.wohlben.qits.cli.bootstrap.ui.BootState;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

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
    public static final String SUPERVISOR = "qits-bootstrap-progress";

    /**
     * <b>Where this CLI sits inside the wrapper, and therefore where the image is built from —
     * EVERY layout and name it has had, newest first.</b> The first one that holds a checkout wins,
     * so this binary works on a wrapper from either side of the layout flip and either side of the
     * rename. The list is what the failure message names, too.
     * <ul>
     *   <li>{@code components/qits-bootstrap/qits-bootstrap-cli} — the component layout with the
     *       repository renamed into the {@code <component>-<role>} grammar;
     *   <li>{@code components/qits-bootstrap/qits-cli-bootstrap} — the component layout before the
     *       rename;
     *   <li>{@code cli/qits-cli-bootstrap} — the archetype layout.
     * </ul>
     * <b>Newest first is what makes a half-renamed machine build the right tree.</b> A rename is a
     * {@code git mv} in the wrapper, and a checkout that has not pulled it still has the old
     * directory beside the new one; taking the oldest hit would build the stale copy without saying
     * so.
     */
    static final List<String> CLI_PATHS = List.of(
            "components/qits-bootstrap/qits-bootstrap-cli",
            "components/qits-bootstrap/qits-cli-bootstrap",
            "cli/qits-cli-bootstrap");

    /**
     * This CLI's own repository name, which is what a clone of it is called — both spellings,
     * newest first, for the same reason the paths carry both. A cold {@code curl … | bash} clones
     * the repository beside the working directory, so what the directory is called is whatever
     * GitHub served the clone under.
     */
    static final List<String> CLI_CHECKOUTS = List.of("qits-bootstrap-cli", "qits-cli-bootstrap");

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
            fixIpv6Loopback(runner, config, out);

            // A wrapper that is not here yet is a COLD START, not a failure: the run clones it,
            // inside the container, into the working directory. This half only has to agree with
            // the payload about where that is, and to keep the mount set honest about it.
            WrapperDir.Resolved wrapper = WrapperDir.resolveOrClone(config.wrapperDir(), workDir);
            boolean wrapperOnHost = Files.isDirectory(wrapper.path());
            out.println("wrapper: " + wrapper.path() + "  (" + wrapper.how() + ")");
            List<Path> gitDirs = wrapperOnHost ? linkedGitDirs(wrapper.path()) : List.of();
            if (!gitDirs.isEmpty()) {
                out.println("wrapper is a linked worktree — mounting the git directories it "
                        + "points into (" + gitDirs.size() + ")");
            }
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
                out.println("no bootstrap CLI checkout to build the payload image from — "
                        + "looked in " + CLI_PATHS.stream().map(wrapper.path()::resolve)
                                .map(Path::toString).collect(Collectors.joining(" and "))
                        + ", at and above "
                        + workDir + ", and in " + CLI_CHECKOUTS.stream().map(workDir::resolve)
                                .map(Path::toString).collect(Collectors.joining(" and "))
                        + ". Run this from "
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
            Path progressFile = workDir.resolve(".qits-bootstrap-progress.json").normalize();
            Files.writeString(progressFile, new BootState(config.tailLines()).snapshotJson());

            if (config.web() && !startSupervisor(docker, image, workDir, progressFile,
                    user(runner), config, out)) {
                return 2;
            }

            argv = ContainerRun.command(new ContainerRun.Plan(
                    image, wrapper.path(), wrapperOnHost, gitDirs, workDir, sources, logFile,
                    progressFile, user(runner), docker.socketGroupId(),
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

    private static boolean startSupervisor(Docker docker, String image, Path workDir,
                                           Path state, String user, BootstrapConfig config,
                                           PrintStream out) {
        if (docker.allNames().contains(SUPERVISOR)) {
            docker.removeContainer(SUPERVISOR, out::println);
        }
        ProcessResult result = docker.exec(out::println, "run", "-d", "--name", SUPERVISOR,
                "--restart", "unless-stopped", "--user", user, "-v", workDir + ":" + workDir,
                "-w", workDir.toString(), "-e", "QITS_WEB_BIND=true", "-e",
                "QITS_WEB_HOST=0.0.0.0",
                image, "progress-supervisor", "--state", state.toString());
        if (!result.ok()) {
            out.println("the bootstrap progress supervisor did not start (exit "
                    + result.exitCode() + ")");
            return false;
        }
        out.println("browser view: served by the bootstrap edge (durable supervisor)");
        return true;
    }

    /**
     * <b>The one host repair the payload cannot make: the edge's port must REFUSE an IPv6
     * connection on this host's loopback, and this makes it refuse.</b>
     * <p>
     * The byte plane is reached at {@code <app>.<env>.localhost}, and a resolver answers
     * {@code ::1} for such a name FIRST. The edge publishes its port in swarm INGRESS mode now, and
     * the routing mesh is IPv4-only: the v6 listener accepts the connection and then serves
     * nothing, so curl, docker, git, maven and npm all hang instead of failing over to IPv4. A
     * host-mode publish never showed this — docker-proxy bound both families — which is why it
     * arrives with the mode.
     * <p>
     * <b>It hangs with no error, and that is what makes it expensive.</b> On 2026-09-02 a release
     * run's SBOM upload — one {@code curl -X PUT} to {@code registry.<env>.localhost} from a CI
     * step on the host network — sat on {@code [::1]:<port>} for over forty minutes and blocked the
     * whole CI queue behind it. Nothing timed out and nothing said anything.
     * <p>
     * The fix is one host rule:
     * <pre>ip6tables -I INPUT -i lo -p tcp --dport &lt;edge port&gt; -j REJECT --reject-with tcp-reset</pre>
     * The reset is what makes every client fall back at once; a DROP would leave them hanging just
     * as the accept does.
     * <p>
     * <b>The rule does not survive a reboot, which is why this runs on EVERY boot rather than
     * once.</b> A past run's rule is not evidence: the landmine re-arms itself at the next restart,
     * and the only honest question is what the loopback answers right now. So the effect is probed
     * rather than the rule table read — a connect to {@code [::1]:<port>} that SUCCEEDS is the
     * broken state, whatever produced it — the rule is installed, and the probe is repeated to
     * prove it took. Refused is the good answer and so is nothing listening, which is every cold
     * boot, so this stays quiet unless there was something to do.
     * <p>
     * <b>Done here rather than in the preflight PHASE, because only this half is on the host.</b>
     * The payload is a container: {@code ::1} inside it is its own loopback, the host's is not
     * reachable from there, and its own network namespace has no rule table worth writing.
     * <p>
     * Installing needs root and an {@code ip6tables} binary, and a launcher started as a plain user
     * has neither. That is not a failure of the boot: it falls back to the warning the operator
     * used to get, with the command to run by hand.
     */
    static void fixIpv6Loopback(ProcessRunner runner, BootstrapConfig config, PrintStream out) {
        if (!ipv6LoopbackAccepts(config.port())) {
            return;
        }
        // Quiet on success: the log keeps the command and its output, the screen gets one line.
        ProcessResult result = runner.run(
                Cmd.of(ip6tablesReject(config.port())).timeout(Duration.ofSeconds(30)), null);
        if (result.ok() && !ipv6LoopbackAccepts(config.port())) {
            out.println("ipv6 loopback: [::1]:" + config.port() + " resets now — ip6tables rule "
                    + "installed (it does not survive a reboot, so every run installs it)");
            return;
        }
        out.println("WARNING: [::1]:" + config.port() + " accepts connections and this run could "
                + "not stop it (ip6tables exited " + result.exitCode() + " — installing needs "
                + "root). Every *.localhost name resolves to ::1 first, and swarm's ingress mesh "
                + "serves IPv4 only — so a client reaching the edge by name will HANG rather than "
                + "fail over. Run this by hand, after every reboot:");
        out.println("  sudo " + String.join(" ", ip6tablesReject(config.port())));
    }

    /** The rule, in one place: what is run, and what the fall-back warning prints. */
    static List<String> ip6tablesReject(int port) {
        return List.of("ip6tables", "-I", "INPUT", "-i", "lo", "-p", "tcp",
                "--dport", String.valueOf(port), "-j", "REJECT", "--reject-with", "tcp-reset");
    }

    private static boolean ipv6LoopbackAccepts(int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("::1", port), 2000);
            return true;
        } catch (IOException e) {
            // Refused, unreachable or timed out: nothing is accepting v6 on that port, which is
            // what the rule produces and what an empty host looks like.
            return false;
        }
    }

    /**
     * The build context for the payload image, which is <b>a checkout of this CLI</b> — the one
     * thing the image is made of.
     * <p>
     * Three places, in order, and each is a machine the bootstrap is actually started on:
     * <ol>
     *   <li>the wrapper's own submodule of this CLI, at any of {@link #CLI_PATHS} — the
     *       ordinary run, from inside the checkout;
     *   <li>at or above the working directory — a clone of this CLI alone, which is all a COLD
     *       machine has: {@code curl … | bash} clones this repository, builds it and runs it, and
     *       there is no wrapper until the run itself clones one;
     *   <li>{@code <working directory>/<one of }{@link #CLI_CHECKOUTS}{@code >} — the same cold
     *       clone, from a script that stepped back out of it so the wrapper lands beside it rather
     *       than inside it.
     * </ol>
     * The marker is the Dockerfile, not the directory name: a checkout is what has one, whatever
     * anyone called the directory.
     */
    /**
     * The real git directories of a wrapper checked out as a LINKED WORKTREE, which live outside
     * it. An ordinary clone keeps {@code .git} inside the wrapper and the wrapper's own mount
     * covers everything; a worktree keeps pointer FILES instead — one for the wrapper, one per
     * submodule — and every git command in the container, the source clones above all, dies with
     * "not a git repository" unless the directories they point into are mounted too.
     * <p>
     * Most submodules point under the primary checkout's own {@code .git} (at
     * {@code modules/<name>/worktrees/…}), so the wrapper's answer covers them and the mount
     * set's dedup collapses the rest. Not all of them: a submodule that carries an EMBEDDED
     * {@code .git} directory keeps its worktree slice under that, beside the primary's — which is
     * why every submodule is asked rather than only the wrapper.
     * <p>
     * <b>The walk is DEPTH-BOUNDED rather than depth-fixed, and that is the layout flip's doing.</b>
     * It used to look exactly two levels down, which was every submodule of the archetype layout
     * ({@code <archetype>/<repo>}) and none of the component one
     * ({@code components/<component>/<repo>}) — a worktree of a reorganised wrapper would have
     * mounted the wrapper's own git directory and no submodule's, and every source clone inside the
     * container would have died with "not a git repository". Both depths are walked now, and a
     * directory with no pointer file answers nothing and costs nothing.
     */
    private static final int SUBMODULE_DEPTH = 3;

    static List<Path> linkedGitDirs(Path wrapper) {
        List<Path> dirs = new ArrayList<>();
        collectLinkedGitDirs(wrapper, SUBMODULE_DEPTH, dirs);
        return List.copyOf(dirs);
    }

    private static void collectLinkedGitDirs(Path dir, int depth, List<Path> into) {
        linkedGitDir(dir).ifPresent(into::add);
        if (depth <= 0) {
            return;
        }
        try (var children = Files.list(dir)) {
            for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                // The git directory itself is bookkeeping, never a checkout, and walking into it
                // would find every submodule's slice a second time.
                if (!child.getFileName().toString().equals(".git")) {
                    collectLinkedGitDirs(child, depth - 1, into);
                }
            }
        } catch (IOException unreadable) {
            // preflight's own message is the better one when the wrapper cannot be read
        }
    }

    /**
     * Where a checkout's {@code .git} pointer file leads: the COMMON git directory when the
     * bookkeeping names one, else the pointer's target itself. Empty for an ordinary checkout,
     * whose {@code .git} is a directory in place.
     */
    static Optional<Path> linkedGitDir(Path checkout) {
        Path pointer = checkout.resolve(".git");
        if (!Files.isRegularFile(pointer)) {
            return Optional.empty();
        }
        String content;
        try {
            content = Files.readString(pointer).trim();
        } catch (IOException unreadable) {
            return Optional.empty();
        }
        if (!content.startsWith("gitdir:")) {
            return Optional.empty();
        }
        Path gitDir = checkout.resolve(content.substring("gitdir:".length()).trim()).normalize();
        // A linked worktree's slice is <common>/worktrees/<name>, and `commondir` inside it points
        // back to the shared .git. Without the file, the pointer's target is all there is to mount.
        Path commonDir = gitDir.resolve("commondir");
        if (Files.isRegularFile(commonDir)) {
            try {
                gitDir = gitDir.resolve(Files.readString(commonDir).trim()).normalize();
            } catch (IOException unreadable) {
                // the slice still covers the wrapper's own refs
            }
        }
        return Optional.of(gitDir);
    }

    static Optional<Path> imageContext(Path wrapper, Path from) {
        Path start = from.toAbsolutePath().normalize();
        for (String cliPath : CLI_PATHS) {
            Path inWrapper = wrapper.resolve(cliPath);
            if (isCliCheckout(inWrapper)) {
                return Optional.of(inWrapper);
            }
        }
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (isCliCheckout(candidate)) {
                return Optional.of(candidate);
            }
        }
        for (String checkout : CLI_CHECKOUTS) {
            Path beside = start.resolve(checkout);
            if (isCliCheckout(beside)) {
                return Optional.of(beside);
            }
        }
        return Optional.empty();
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
