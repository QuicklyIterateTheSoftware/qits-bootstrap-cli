package eu.wohlben.qits.cli.bootstrap.phases;

import eu.wohlben.qits.cli.bootstrap.api.ArtifactsApi;
import eu.wohlben.qits.cli.bootstrap.api.PdApi;
import eu.wohlben.qits.cli.bootstrap.api.CiApi;
import eu.wohlben.qits.cli.bootstrap.api.GitHostApi;
import eu.wohlben.qits.cli.bootstrap.api.Http;
import eu.wohlben.qits.cli.bootstrap.api.IdpApi;
import eu.wohlben.qits.cli.bootstrap.config.BootstrapConfig;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseContext;
import eu.wohlben.qits.cli.bootstrap.engine.Waiter;
import eu.wohlben.qits.cli.bootstrap.platform.Docker;
import eu.wohlben.qits.cli.bootstrap.platform.Git;
import eu.wohlben.qits.cli.bootstrap.platform.RunState;
import eu.wohlben.qits.cli.bootstrap.platform.PlatformModel;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessResult;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessRunner;
import eu.wohlben.qits.cli.bootstrap.proc.RunLog;
import eu.wohlben.qits.cli.bootstrap.ui.Format;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Everything the phases share: the tools, the addresses and the run's own state. */
public class Boot {

    public static final String NETWORK = "qits-net";

    public final BootstrapConfig config;
    public final RunLog log;
    public final ProcessRunner runner;
    public final Docker docker;
    public final Git git;
    public final RunState state = new RunState();

    public final Http http = new Http();
    public final ArtifactsApi artifacts;
    /** The git host, a service of its own since the byte-plane split. */
    public final GitHostApi githost;
    public final CiApi ci;
    public final PdApi pd;
    public final IdpApi idp;

    public Boot(BootstrapConfig config, RunLog log) {
        this(config, log, new ProcessRunner(log));
    }

    /**
     * The same run with the process runner given rather than made — the seam a test drives the
     * docker-shelling phases through, so what they put on a command line is provable without a
     * daemon. Everything else is built exactly as the running program builds it.
     */
    Boot(BootstrapConfig config, RunLog log, ProcessRunner runner) {
        this.config = config;
        this.log = log;
        this.runner = runner;
        this.docker = new Docker(runner).withBuildArgs(imageBuildArgs(config));
        this.git = new Git(runner);
        this.artifacts = new ArtifactsApi(http, config.artifactsUrl());
        this.githost = new GitHostApi(http, config.gitHostUrl(), config.gitHostHealthUrl());
        this.ci = new CiApi(http, config.ciUrl());
        this.pd = new PdApi(http, config.platformDeploymentsUrl());
        this.idp = new IdpApi(http, config.idpIssuer());
    }

    /**
     * <b>What every image this run builds is built with</b> — the seed images, the step images and
     * the ci-daemon's musl builder alike, because they are all built by the HOST's daemon while the
     * platform is still being made.
     * <p>
     * One argument today: where a build resolves the platform's own maven artifacts. It is passed
     * to every build rather than to the ones known to read it, because an image with no matching
     * {@code ARG} answers a build argument with a warning, and the alternative is a per-image list
     * that a new image is added without.
     * <p>
     * See {@link BootstrapConfig#seedMavenRepositoryUrl()} for why the value cannot be left to the
     * Dockerfile's own default any more.
     */
    public static List<String> imageBuildArgs(BootstrapConfig config) {
        return List.of("--build-arg", "QITS_MAVEN_REPOSITORY_URL=" + config.seedMavenRepositoryUrl());
    }

    /** Fails the phase, with the command's own last words attached. */
    public static void must(ProcessResult result, String what) {
        if (!result.ok()) {
            throw new IllegalStateException(what + " (exit " + result.exitCode() + ")\n"
                    + result.tailText(20));
        }
    }

    /** How long a push may keep failing before the boot gives up on it, and how often it retries. */
    static final Duration PUSH_WINDOW = Duration.ofSeconds(90);
    static final Duration PUSH_RETRY = Duration.ofSeconds(5);

    /**
     * Every push to the platform git host goes through here, retried inside a bounded window.
     * <p>
     * The boot deploys the platform's OWN postgres mid-run, and that cutover severs every
     * service's connection pool for a few seconds. Measured on the 2026-08-11 boot: phase 51
     * deployed qits-oci-postgresql, phase 52's push of qits-platform-idp landed in the flux, the
     * git host misanswered it, and git exited 128 — a 30-minute boot killed at 52/67 by a
     * transient the boot itself caused.
     * <p>
     * The retry is on ANY failure, not on a matched message: a push is idempotent (an up-to-date
     * one is a no-op), so a genuine misconfiguration costs only this window before it fails with
     * git's own last words. The token is masked here rather than at the call sites, so no retried
     * or printed command can carry it to the screen.
     */
    public ProcessResult push(PhaseContext ctx, String what, Path repo, String url,
                              List<String> options, String refspec) throws Exception {
        return pushRetrying(ctx, what, PUSH_WINDOW, PUSH_RETRY,
                () -> git.push(repo, url, options, refspec, config.pushToken(), githostToken(), ctx::log),
                System::currentTimeMillis, Thread::sleep);
    }

    /** The clock and the sleep are arguments so a test can spend the window in no time at all. */
    static ProcessResult pushRetrying(PhaseContext ctx, String what, Duration window,
                                      Duration interval, Supplier<ProcessResult> attempt,
                                      Waiter.Clock clock, Waiter.Sleeper sleeper) throws Exception {
        long start = clock.millis();
        List<ProcessResult> tries = new ArrayList<>();
        try {
            // Waiter, so a push that keeps failing looks like every other wait in this program:
            // what is being pushed, what the last attempt said, how long, and when it gives up.
            return Waiter.await(ctx, "the push of " + what, window, interval, () -> {
                ProcessResult result = attempt.get();
                tries.add(result);
                if (result.ok()) {
                    return Waiter.Poll.done(result, "pushed");
                }
                String words = lastWords(result);
                ctx.log("  push of " + what + " failed (exit " + result.exitCode() + "): " + words);
                return Waiter.Poll.pending("attempt " + tries.size() + " failed: " + words);
            }, clock, sleeper);
        } catch (TimeoutException giveUp) {
            ProcessResult last = tries.getLast();
            throw new IllegalStateException("push of " + what + " failed " + tries.size()
                    + (tries.size() == 1 ? " time over " : " times over ")
                    + Format.duration(Duration.ofMillis(clock.millis() - start))
                    + " (exit " + last.exitCode() + "). Last words:\n" + last.tailText(20));
        }
    }

    /** The last thing a failed command said. Already masked: the runner masks every line it reads. */
    static String lastWords(ProcessResult result) {
        List<String> tail = result.tail();
        for (int i = tail.size() - 1; i >= 0; i--) {
            if (!tail.get(i).isBlank()) {
                return tail.get(i);
            }
        }
        return "no output";
    }

    /**
     * A machine token for this bootstrap's own calls into the platform, or null when the gate is
     * off. It borrows a platform client rather than owning one: the calls it makes stand in for
     * announcements a service never sent, and a token that says so is exactly right.
     */
    public String tokenOrNull(String clientId, String audience) {
        if (!config.machineAuth()) {
            return null;
        }
        String secret = state.secrets.get(clientId);
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("no secret for the " + clientId
                    + " client — .qits-bootstrap.env and the running idp disagree");
        }
        return idp.token(clientId, secret, audience);
    }

    /**
     * The bootstrap is a machine client in its own right. qits-githost is always protected, so
     * this intentionally has no machineAuth-off or anonymous arm.
     */
    public String githostToken() {
        String clientId = PlatformModel.wireAlias("bootstrap", config.envName());
        String secret = state.secrets.get(clientId);
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("no secret for the " + clientId
                    + " client — .qits-bootstrap.env and the running idp disagree");
        }
        return idp.token(clientId, secret, PlatformModel.wireAlias("githost", config.envName()));
    }

    /** Waits for a health endpoint, saying which one and what it last answered. */
    public void awaitHealth(PhaseContext ctx, String what, Supplier<Http.Response> probe)
            throws Exception {
        Waiter.await(ctx, what, config.healthTimeout(), Duration.ofSeconds(5), () -> {
            Http.Response response = probe.get();
            return response.ok()
                    ? Waiter.Poll.done(response, "ready")
                    : Waiter.Poll.pending(response.describe());
        });
        ctx.log("  " + what + " ready");
    }
}
