package eu.wohlben.qits.cli.bootstrap.phases;

import eu.wohlben.qits.cli.bootstrap.api.ArtifactsApi;
import eu.wohlben.qits.cli.bootstrap.api.PdApi;
import eu.wohlben.qits.cli.bootstrap.api.CiApi;
import eu.wohlben.qits.cli.bootstrap.api.ConfigurationApi;
import eu.wohlben.qits.cli.bootstrap.api.GitHostApi;
import eu.wohlben.qits.cli.bootstrap.api.Http;
import eu.wohlben.qits.cli.bootstrap.api.IdpApi;
import eu.wohlben.qits.cli.bootstrap.api.ProjectsApi;
import eu.wohlben.qits.cli.bootstrap.config.BootstrapConfig;
import eu.wohlben.qits.cli.bootstrap.ingress.BootstrapIngressLifecycle;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseContext;
import eu.wohlben.qits.cli.bootstrap.engine.Waiter;
import eu.wohlben.qits.cli.bootstrap.platform.Docker;
import eu.wohlben.qits.cli.bootstrap.platform.Git;
import eu.wohlben.qits.cli.bootstrap.platform.RunState;
import eu.wohlben.qits.cli.bootstrap.platform.PinnedVersions;
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
    /** The bootstrap-only sidecar; it never enters platform deployment extras. */
    public final BootstrapIngressLifecycle ingress;
    public final RunState state = new RunState();

    public final Http http = new Http();
    public final ArtifactsApi artifacts;
    /** The git host, a service of its own since the byte-plane split. */
    public final GitHostApi githost;
    public final CiApi ci;
    public final PdApi pd;
    public final IdpApi idp;
    /** Deployment configuration as platform state, seeded by this run and read by the deployer. */
    public final ConfigurationApi configuration;
    /** The alias table's owner: what this run hands its (storage id, name) pairs to. */
    public final ProjectsApi projects;
    /** The pin closure, read once — see {@link #pinnedVersions}. */
    private PinnedVersions pinned;

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
        this.ingress = new BootstrapIngressLifecycle(this);
        this.artifacts = new ArtifactsApi(http, config.artifactsUrl());
        this.githost = new GitHostApi(http, config.gitHostUrl(), config.gitHostHealthUrl());
        this.ci = new CiApi(http, config.ciUrl());
        this.pd = new PdApi(http, config.platformDeploymentsUrl());
        this.idp = new IdpApi(http, config.idpIssuer());
        this.configuration = new ConfigurationApi(http, config.configurationUrl());
        this.projects = new ProjectsApi(http, config.projectsUrl());
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

    /** The seed maven repository as the ingress serves it, and the capability that opens it. */
    private String seedMavenUrl;
    private String seedMavenCapability;

    /** The edge replaces the seed port before the first seed image is built. */
    public void useBootstrapMavenRepository(String url, String capability) {
        this.seedMavenUrl = url;
        this.seedMavenCapability = capability;
        docker.withBootstrapMavenRepository(url, "bootstrap", capability);
    }

    /**
     * <b>One file in the seed maven repository, as a url a Dockerfile's {@code ADD} can fetch —
     * credentials and all.</b>
     * <p>
     * Every other build of this run reaches the repository from a {@code RUN}, which is handed the
     * capability as a BuildKit secret. An {@code ADD <url>} cannot be: BuildKit resolves it itself,
     * outside any step, so a secret mount never reaches it and the credential has to be in the url.
     * The ingress's maven route demands HTTP Basic, so an anonymous url is a 401 rather than a
     * download.
     * <p>
     * <b>That is not the leak it looks like.</b> The capability is masked out of the log by
     * {@code Cmd.mask}, which every build of this run already carries, and it does not reach the
     * image: the fetch happens in a {@code scratch} stage the final image discards, and BuildKit
     * records no build argument in an image's config — measured, {@code docker history} and
     * {@code docker inspect} both show nothing. It expires with the run either way.
     */
    public String seedMavenFile(String path) {
        String base = seedMavenUrl == null ? config.seedMavenRepositoryUrl() : seedMavenUrl;
        return withCredentials(base, seedMavenCapability) + "/" + path;
    }

    /** The same url without the credential, which is the one that may be printed. */
    public String seedMavenFilePublic(String path) {
        return (seedMavenUrl == null ? config.seedMavenRepositoryUrl() : seedMavenUrl) + "/" + path;
    }

    /**
     * {@code http://host/x} plus {@code bootstrap:<capability>}, or the url unchanged when this run
     * has no capability. The capability is base64url, so it carries no character a userinfo field
     * would have to escape.
     */
    static String withCredentials(String url, String capability) {
        int scheme = url.indexOf("://");
        if (capability == null || capability.isBlank() || scheme < 0) {
            return url;
        }
        return url.substring(0, scheme + 3) + "bootstrap:" + capability
                + "@" + url.substring(scheme + 3);
    }

    /**
     * <b>Every library version this boot has to publish, not just the checked-out one.</b> Read
     * once per run out of the checkouts the {@code sources} phase stood, and the warnings are said
     * once here rather than by each of the phases that ask.
     * <p>
     * Lazy because the answer needs every checkout, and the first phase that asks is the first one
     * after {@code sources}. See {@link PinnedVersions} for why one version per library is not
     * enough on a platform with an empty store.
     */
    public PinnedVersions pinnedVersions(PhaseContext ctx) {
        if (pinned == null) {
            pinned = PinnedVersions.read(PlatformModel.platformRepos(),
                    (name, ref, path) -> git.fileAt(state.repoDir(name), ref, path));
            pinned.all().forEach((producer, versions) ->
                    ctx.log("  " + PlatformModel.repo(producer) + " is still pinned at " + versions));
            pinned.warnings().forEach(ctx::warn);
        }
        return pinned;
    }

    /**
     * <b>The storage id this run seeded a repository's bare under</b> — what an earlier phase of
     * this run or of an earlier one recorded, and a fresh mint
     * ({@link PlatformModel#seedStorageId}) for one nothing has recorded yet.
     * <p>
     * <b>The mint happens HERE, once per repository per run, and the answer is kept.</b> A storage
     * id is a UUID with nothing to derive it from, so a second call that minted again would address
     * a bare this run never created — and {@code git-repos} writes what this answers into
     * {@code .qits-bootstrap.env} as each bare is made, which is what carries the pairing across a
     * resumed or repeated run. Never re-derived at a call site.
     */
    public String storageId(String name) {
        return state.repositoryIds.computeIfAbsent(PlatformModel.repo(name),
                repo -> PlatformModel.seedStorageId());
    }

    /**
     * <b>WHERE THIS RUN PUSHES A REPOSITORY, and it changes exactly once per boot.</b>
     * <p>
     * Before {@code git-repos} there is no alias table entry to resolve a name through, so the
     * address is the internal {@code /git/<storage id>} — which is what that phase's own lifecycle
     * PUTs use and the only thing they can use. From that phase onward it is the public
     * {@code /git/<projectId>/<repoName>}, and every PUSH of the run is on that side of the line:
     * qits-projects is a seed service, so the pairing is registered before the first push rather
     * than forty phases into the deploy train.
     * <p>
     * The switch is not a tidiness. The git host's own deployment closes the id-addressed scheme to
     * everything but qits-projects' client, so a run still pushing the old address would be 403 for
     * the rest of the train. It is also what puts the two name fields on the push's event, which is
     * what gives every later build a {@code QITS_CI_PROJECT_ID} and a sibling url that resolves.
     * <p>
     * One method, so no phase decides this for itself.
     */
    public String gitUrl(String name) {
        if (state.repositoriesRegistered && state.projectId != null
                && !state.projectId.isBlank()) {
            return githost.gitUrl(state.projectId, PlatformModel.repo(name));
        }
        return githost.gitUrl(storageId(name));
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
