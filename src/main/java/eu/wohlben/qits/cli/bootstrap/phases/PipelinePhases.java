package eu.wohlben.qits.cli.bootstrap.phases;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.bootstrap.api.Http;
import eu.wohlben.qits.cli.bootstrap.api.Json;
import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseContext;
import eu.wohlben.qits.cli.bootstrap.engine.Waiter;
import eu.wohlben.qits.cli.bootstrap.platform.PlatformModel;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/** From "the seed is up" to "the platform deployed itself". */
public class PipelinePhases {

    /**
     * What the auth-plane probe presents. Deliberately not a real token: a valid one would prove
     * the idp can mint, which is a different question. This asks only whether the service can reach
     * its issuer well enough to REFUSE something — which is the step that initializes the tenant.
     */
    private static final Map<String, String> PROBE_BEARER =
            Map.of("Authorization", "Bearer qits-bootstrap-auth-plane-probe");

    private final Boot boot;

    public PipelinePhases(Boot boot) {
        this.boot = boot;
    }

    // --- the seed stack ---------------------------------------------------------------------------

    public Phase seedStackUp() {
        return new Phase("seed-stack", "start the seed stack", ctx -> {
            boot.docker.ensureNetwork(Boot.NETWORK, ctx::log);
            // The artifacts instance used while building seed images has no machine credentials:
            // the idp did not exist yet. Replace it so compose starts the real service with the
            // generated client secret; otherwise post-receive notifications reach CI bare.
            String artifacts = PlatformModel.wireAlias("platform-artifacts", boot.config.envName());
            if (boot.docker.allNames().contains(artifacts)) {
                ctx.log("  replacing the bootstrap artifact registry with the authenticated seed service");
                boot.docker.removeContainer(artifacts, ctx::log);
            }
            // Only what the deployer does not already manage: a compose service whose application
            // has a live deployed container must NOT be resurrected next to it — the deployer's own
            // container included, once a self-update handoff has made it one of its own
            // deployments.
            //
            // A compose service is keyed by its wire alias, so that is what is named on the command
            // line — the same string the deployed container answers to.
            List<String> running = boot.docker.runningNames();
            List<String> up = new ArrayList<>();
            for (String name : PlatformModel.CORE) {
                String prefix = PlatformModel.pdNamePrefix(name, boot.config.envName());
                String alias = PlatformModel.wireAlias(name, boot.config.envName());
                if (running.stream().anyMatch(container -> container.startsWith(prefix))) {
                    ctx.log("  " + alias + " is deployer-managed — compose leaves it alone");
                } else {
                    up.add(alias);
                }
            }
            if (up.isEmpty()) {
                ctx.log("  the whole seed is deployer-managed — compose has nothing to start");
                ctx.note("nothing to start");
                return;
            }
            List<String> command = new ArrayList<>(List.of("compose", "-p", "qits", "-f",
                    boot.state.composeFile.toString(), "up", "-d"));
            command.addAll(up);
            Boot.must(boot.docker.exec(Duration.ofMinutes(30), ctx::log,
                    command.toArray(String[]::new)), "compose up failed");
            ctx.note(String.join(" ", up));
        });
    }

    /**
     * idp first: with the gate on, the first push's post-receive needs a token, and the replayed
     * build-succeeded needs one too. qits-deployments before anything asks it for an environment
     * operation — it owns the topology and the socket both, so nothing else can answer for it.
     * <p>
     * The EDGE is what the host port answers now, so it is the second thing asked and the first
     * thing asked over the wire this program uses for the rest of the run. A gateway that is up
     * behind an edge that is not is unreachable, so the two are probed separately: the edge on
     * 127.0.0.1, the gateway by its alias on qits-net.
     */
    public Phase seedHealth() {
        return new Phase("seed-health", "wait for the seed services", ctx -> {
            String env = boot.config.envName();
            boot.awaitHealth(ctx, "qits-platform-idp (on qits-net, no host port)",
                    () -> boot.inNetwork.get("http://qits-platform-idp:8080/idp/q/health/ready"));
            // /q is the edge's own prefix, the one thing it never proxies. An answer here is this
            // process, not something behind it.
            boot.awaitHealth(ctx, "qits-platform-edge on port " + boot.config.port(),
                    () -> boot.http.get("http://127.0.0.1:" + boot.config.port() + "/q/health/ready",
                            Map.of()));
            boot.awaitHealth(ctx, env + "-qits-gateway (on qits-net, behind the edge)",
                    () -> boot.inNetwork.get(
                            "http://" + env + "-qits-gateway:8080/q/health/ready"));
            boot.awaitHealth(ctx, "qits-platform-artifacts on port " + boot.config.registryPort(),
                    boot.artifacts::health);
            // Through the edge and the gateway's route table, and on qits-net if that route is not
            // up yet. Either answer means the service is ready; the first also proves the whole
            // path the rest of this run calls ci and the deployer through.
            boot.awaitHealth(ctx, env + "-qits-ci (host port, else qits-net)", () -> {
                Http.Response viaGateway = boot.ci.health();
                return viaGateway.ok() ? viaGateway
                        : boot.inNetwork.get("http://" + env + "-qits-ci:8080/ci/q/health/ready");
            });
            boot.awaitHealth(ctx, env + "-qits-deployments (host port, else qits-net)", () -> {
                Http.Response viaGateway = boot.pd.health();
                return viaGateway.ok() ? viaGateway : boot.inNetwork.get("http://" + env
                        + "-qits-deployments:8080/platform-deployments/q/health/ready");
            });
            // Health is not enough with the machine gate on: a freshly (re)created service
            // initializes its OIDC tenant lazily on the FIRST request, and if that races idp's own
            // restart, every request 500s for a few seconds — reads included. Poll one read per
            // guarded service until it answers non-5xx, so every phase after this one inherits a
            // warm auth plane.
            // WRITE-shaped probes, deliberately: a read does not initialize the OIDC tenant on
            // every service, so a read-probe can pass while the first real write still hits the
            // idp race and 500s.
            //
            // The probe CARRIES A BEARER, and that is the whole point of it. Nothing initializes
            // the tenant until a request actually presents a credential to validate — so an
            // unauthenticated body is rejected at validation (400) by a service whose auth plane is
            // still stone cold, and the probe passes having proved nothing. A junk bearer answers
            // 401 once the tenant is warm and 5xx before it. Measured, not assumed: `{}` with no
            // header answers 400 on both services, `{}` with one answers 401.
            boot.awaitHealth(ctx, env + "-qits-ci auth plane (junk bearer -> 401)",
                    () -> warmWhenGuardRefused(boot.http.postJson(
                            boot.config.ciUrl() + "/api/events/trigger", "{}", PROBE_BEARER)));
            boot.awaitHealth(ctx, env + "-qits-deployments auth plane (junk bearer -> 401)",
                    () -> warmWhenGuardRefused(boot.http.postJson(
                            boot.config.platformDeploymentsUrl() + "/api/events/build-succeeded",
                            "{}", PROBE_BEARER)));
        });
    }

    // --- the cold-start publishes -----------------------------------------------------------------

    public Phase daemonPublish() {
        return new Phase("daemon-publish", "publish the ci-daemon binary to the registry", ctx -> {
            String sha = boot.state.daemonSha;
            if (sha == null || sha.isBlank()) {
                throw new IllegalStateException("no ci-daemon digest to publish");
            }
            if (boot.artifacts.daemonPublished("qits-ci-daemon", sha)) {
                ctx.skip("qits-ci-daemon " + SeedPhases.shortSha(sha) + " already published");
            }
            Path binary = boot.state.daemonBinary;
            if (binary == null || !Files.isRegularFile(binary)) {
                throw new IllegalStateException("the daemon binary is neither on disk nor published"
                        + " — rerun without QITS_SKIP_BUILD");
            }
            ctx.status("uploading " + (Files.size(binary) / (1024 * 1024)) + " MB to "
                    + boot.artifacts.base() + "/daemons/qits-ci-daemon/" + SeedPhases.shortSha(sha));
            Http.Response response = boot.artifacts.publishDaemon("qits-ci-daemon", sha, binary);
            if (response.status() != 201) {
                throw new IllegalStateException("daemon publish answered " + response.describe());
            }
            ctx.log("  published qits-ci-daemon " + sha);
            ctx.note(SeedPhases.shortSha(sha));
        });
    }

    /**
     * Creating a repository is a wire call: the git host keeps every repository as blobs in
     * qits-platform-artifacts' own store, so there is no volume to seed and nothing on disk to
     * initialise.
     * The PUT is idempotent — 201 when this call created it, 200 when one was already there.
     */
    public Phase gitRepositories() {
        return new Phase("git-repos", "create the platform's repositories on the git host", ctx -> {
            int created = 0;
            for (String name : PlatformModel.platformRepos()) {
                String repo = PlatformModel.repo(name);
                ctx.status("PUT " + boot.artifacts.gitUrl(repo));
                Http.Response response = boot.artifacts.createRepository(repo);
                if (response.status() != 200 && response.status() != 201) {
                    throw new IllegalStateException("create of " + repo + " answered "
                            + response.describe());
                }
                created += response.status() == 201 ? 1 : 0;
                ctx.log("  " + repo + " -> /artifacts/git/" + repo
                        + (response.status() == 201 ? "  (created)" : ""));
            }
            ctx.note(created + " created, "
                    + (PlatformModel.platformRepos().size() - created) + " already there");
        });
    }

    /**
     * Deployable repositories contain gitlinks to the frontend repositories, and those commits must
     * exist on the platform git host BEFORE the first wrapper pipeline clones its submodules. A
     * push is the only door into the store, and {@code -o qits.no-ci} is what keeps it quiet:
     * firing all release-train pipelines here would race them against the deliberately serial
     * deployment train.
     */
    public Phase releaseTrainPreseed() {
        return new Phase("preseed", "pre-seed release-train histories for deployable submodules", ctx -> {
            for (String name : PlatformModel.SEEDED_REPOS) {
                String repo = PlatformModel.repo(name);
                Path src = boot.state.repoDir(name);
                ctx.status("pushing " + repo + " main, quietly");
                ProcessResult result = boot.git.push(src, boot.artifacts.gitUrl(repo),
                        List.of("qits.no-ci", "qits.token=" + boot.config.pushToken()), "main",
                        boot.config.pushToken(), ctx::log);
                Boot.must(result, "pre-seed push of " + repo + " failed");
                ctx.log("  " + repo + " history at " + boot.git.shortRef(src, "main"));
            }
            ctx.note(PlatformModel.SEEDED_REPOS.size() + " histories");
        });
    }

    /**
     * The RELEASED artifacts, published by replaying each publisher's release pipeline. The wrapper
     * builds install RELEASED versions, and a clean version only ever comes from a repo's
     * ci-event-release.yml fired by an SCMRelease event; a post-receive publish on main produces a
     * prerelease nothing pins. A fresh platform has had no releases, so the release pipeline is
     * fired by hand — and waited for, because the deployables cannot build until the artifacts
     * exist.
     */
    public Phase releaseReplay(String name) {
        String repo = PlatformModel.repo(name);
        return new Phase("release-" + name, "replay the release pipeline of " + repo, ctx -> {
            Path src = boot.state.repoDir(name);
            String version = boot.git.describeTag(src, "main");
            if (version.isBlank()) {
                throw new IllegalStateException(repo + " has no release tag reachable from main "
                        + "— nothing to replay");
            }
            ctx.log("  release tag " + version);
            ProcessResult push = boot.git.push(src, boot.artifacts.gitUrl(repo),
                    List.of("qits.no-ci", "qits.token=" + boot.config.pushToken()),
                    "refs/tags/" + version, boot.config.pushToken(), ctx::log);
            Boot.must(push, "tag push of " + repo + " " + version + " failed");

            // The manual trigger demands the one project=* client — the same identity the git host
            // uses to announce pushes, because this stands in for the announcement a real release
            // would have made.
            String token = boot.tokenOrNull(
                    PlatformModel.wireAlias("platform-artifacts", boot.config.envName()),
                    PlatformModel.wireAlias("ci", boot.config.envName()));
            String event = "{\"name\":\"SCMRelease\",\"payload\":{"
                    + "\"repository\":" + Json.quote(repo) + ","
                    + "\"branch\":\"main\","
                    + "\"version\":" + Json.quote(version) + "}}";
            Http.Response triggered = boot.ci.trigger(event, token);
            if (!triggered.ok()) {
                throw new IllegalStateException("SCMRelease trigger for " + repo + " refused: "
                        + triggered.describe());
            }
            ctx.log("  SCMRelease triggered, waiting for the release run");

            String status = Waiter.await(ctx, repo + "'s release run at " + boot.config.ciUrl()
                            + "/api/runs/finished", boot.config.releaseTimeout(),
                    boot.config.pollInterval(), () -> {
                        Optional<String> finished = boot.ci.finishedEventRunStatus(repo);
                        return finished.<Waiter.Poll<String>>map(value -> Waiter.Poll.done(value, value))
                                .orElseGet(() -> Waiter.Poll.pending("still running"));
                    });
            if (!"SUCCESS".equals(status)) {
                throw new IllegalStateException(repo + " release run ended " + status
                        + " — the registry never got its package");
            }
            ctx.log("  " + repo + " released " + version);
            ctx.note(version);
        });
    }

    // --- the environment --------------------------------------------------------------------------

    /**
     * One standing environment: name dev, branch environment/dev, the shared network. NO
     * applications array — registration is derived from the repo's deployments.yml on the first
     * green build of the branch that deploys it.
     * <p>
     * RECONCILE, NEVER RECREATE. A DELETE tears down every container of the environment, which here
     * is the whole platform, the deployer included.
     */
    public Phase environment() {
        return new Phase("environment", "reconcile the '" + boot.config.envName()
                + "' environment in qits-deployments", ctx -> {
            // The seed restart can recreate the deployer moments before this call, and its OIDC
            // tenant needs a not-yet-recovered idp — a seconds-wide window where even reads 500.
            // Cutovers blip; callers retry. Bounded and visible, like every other wait here.
            IllegalStateException last = null;
            for (int attempt = 1; attempt <= 12; attempt++) {
                try {
                    reconcileEnvironmentOnce(ctx);
                    return;
                } catch (IllegalStateException e) {
                    last = e;
                    ctx.status("the deployer is not answering yet (attempt " + attempt + "/12) — "
                            + e.getMessage());
                    Thread.sleep(5_000);
                }
            }
            throw last;
        });
    }

    private void reconcileEnvironmentOnce(PhaseContext ctx) throws Exception {
            String name = boot.config.envName();
            String branch = boot.config.envBranch();
            Optional<String> existing = boot.pd.environmentId(name);
            if (existing.isPresent()) {
                patch(existing.get(), Json.object("branch", branch));
                boot.state.environmentId = existing.get();
                ctx.log("  reconciled the existing '" + name + "' environment onto " + branch);
            } else {
                // A row this platform's own earlier lives created under a name that has since
                // moved: 'qits' before environments were named after tiers, 'dev' before the one
                // environment became the one it serves from. Renaming in place keeps its
                // applications, its deployments and its containers; creating a second row beside
                // it would leave every one of them owned by an environment nothing deploys.
                //
                // Only the FIRST of these that exists is taken, and only when the wanted name has
                // no row of its own. A clean bootstrap finds neither and simply creates.
                Optional<String> preRename = List.of("qits", "dev").stream()
                        .filter(old -> !old.equals(name))
                        .map(boot.pd::environmentId)
                        .flatMap(Optional::stream)
                        .findFirst();
                if (preRename.isPresent()) {
                    patch(preRename.get(), Json.object("name", name, "branch", branch));
                    boot.state.environmentId = preRename.get();
                    ctx.log("  renamed a pre-rename environment row to '" + name + "' on " + branch);
                } else {
                    Http.Response created = boot.pd.createEnvironment(name, branch, Boot.NETWORK,
                            boot.tokenOrNull(PlatformModel.wireAlias("ci", boot.config.envName()),
                        PlatformModel.wireAlias("deployments", boot.config.envName())));
                    if (created.status() == 201) {
                        boot.state.environmentId = Json.text(
                                Json.parse(created.body()).path("environment"), "id");
                    } else if (created.status() == 409) {
                        // Created between the listing above and this POST. Take the row and
                        // reconcile it.
                        String id = boot.pd.environmentId(name).orElseThrow(() ->
                                new IllegalStateException("environment create answered 409 but '"
                                        + name + "' is not listed"));
                        patch(id, Json.object("branch", branch));
                        boot.state.environmentId = id;
                    } else {
                        throw new IllegalStateException("environment create answered "
                                + created.describe());
                    }
                }
            }
            ctx.log("  environment " + name + " (" + boot.state.environmentId + ") — branch "
                    + branch + ", network " + Boot.NETWORK);
            ctx.note(name + " on " + branch);
    }

    private void patch(String id, String json) {
        Http.Response response = boot.pd.patchEnvironment(id, json,
                boot.tokenOrNull(PlatformModel.wireAlias("ci", boot.config.envName()),
                        PlatformModel.wireAlias("deployments", boot.config.envName())));
        if (!response.ok()) {
            throw new IllegalStateException("environment " + id + " reconcile failed: "
                    + response.describe());
        }
    }

    // --- push, build, deploy — one application at a time -------------------------------------------

    /**
     * Sequential on purpose: each push triggers a cold native build on the host daemon, and a
     * workstation rarely wants eight at once. Every deployable takes the same path — push, and if
     * the push was a no-op but the application is not live at HEAD, the build-succeeded event is
     * posted by hand.
     */
    public Phase deploy(String name) {
        String repo = PlatformModel.repo(name);
        return new Phase("deploy-" + name, repo + ": push -> ci build -> deploy", ctx -> {
            Path src = boot.state.repoDir(name);
            overlayPipelineConfig(ctx, name, src);

            // ONE deploy ref, on both planes. A platform service used to have its own
            // branch; both planes now ask a green build the same question — does an environment
            // listen to this ref — so environment/<name> is the whole set and platform/main is
            // retired. main stays the trunk for every repository and deploys nothing, so the quiet
            // ref is main.
            String ref = boot.config.envBranch();
            //
            // BOTH refs, one sha, and only one of them deploys. The ref that does not deploy still
            // has to exist and point here, so it goes up with -o qits.no-ci: a second post-receive
            // for the same sha would queue a second cold native build of an image the first one
            // already published.
            // BEFORE the pushes, and that is the whole point of where these two lines sit. A
            // terminal row carrying the baseline id belongs to an earlier run, not this one — but
            // the push is what creates this one, and post-receive can register it before the read
            // below returns. Captured after the push, the baseline is this phase's OWN run: every
            // later poll calls it stale, the FAILED short-circuit never fires, and a build that
            // died in seven seconds is waited on for the full hour. Found by the 2026-08-07
            // teardown run, where qits-projects did exactly that.
            String baselineRowId = PlatformModel.isPlatformService(name) ? null
                    : boot.pd.newestDeployment(boot.state.environmentId, repo)
                            .map(r -> Json.text(r, "id")).orElse(null);
            // The CI run needs the same baseline, for the same reason. A rerun pushes nothing, so
            // the newest run at this sha is whatever the last boot left — and a repository can hold
            // several runs at one commit, red and green side by side, from a release train's twin
            // builds. Reading the newest as this phase's outcome failed qits-workspaces in zero
            // seconds against a red row from the evening before, while the deployment it had just
            // asked for went on to land. Found by the 2026-08-07 proving run.
            String baselineRunId = boot.ci.newestRun(repo).map(r -> Json.text(r, "id")).orElse(null);

            String quietRef = "main";
            ctx.status("pushing " + repo + " to " + quietRef + " (quietly)");
            Boot.must(boot.git.push(src, boot.artifacts.gitUrl(repo),
                            List.of("qits.no-ci", "qits.token=" + boot.config.pushToken()),
                            "HEAD:refs/heads/" + quietRef, boot.config.pushToken(), ctx::log),
                    "push of " + repo + " to " + quietRef + " failed");

            ctx.status("pushing " + repo + " to " + ref + " (the ref that deploys it)");
            ProcessResult push = boot.git.push(src, boot.artifacts.gitUrl(repo),
                    List.of("qits.token=" + boot.config.pushToken()),
                    "HEAD:refs/heads/" + ref, boot.config.pushToken(), ctx::log);
            Boot.must(push, "push of " + repo + " to " + ref + " failed");
            String sha = boot.git.head(src);
            // git spells it "Everything up-to-date" — hyphens. Matching only the spaced form
            // meant no event was ever posted for an unchanged repo: environment applications were
            // rescued by the stale-row replay, and a platform service hung for its whole timeout on
            // an event nobody sent. Found by the tenth proving run. Match both spellings.
            String pushText = (push.tailText(50) + "\n" + push.out()).toLowerCase(Locale.ROOT);
            boolean upToDate = pushText.contains("up to date") || pushText.contains("up-to-date");

            if (alreadyLive(ctx, name, repo, sha)) {
                ctx.note("already live at " + sha.substring(0, 7));
                return;
            }
            if (upToDate) {
                // No push, no event — and not live at HEAD either. The image exists from an
                // earlier run; hand the deployer the event it never got, naming the ref that
                // deploys it.
                ctx.log("  " + repo + " unchanged but not deployed at HEAD — posting the build event");
                postBuildEvent(ctx, repo, sha, ref);
            } else {
                ctx.log("  pushed " + sha.substring(0, 7)
                        + ", waiting for the deployment (a cold native build — be patient)");
            }
            awaitDeployment(ctx, name, repo, sha, ref, baselineRowId, baselineRunId);
        });
    }

    /** Older checkouts get the standard publish step overlaid, so no push triggers nothing. */
    private void overlayPipelineConfig(PhaseContext ctx, String name, Path src) throws Exception {
        Path config = src.resolve(".config/qits/ci-post-receive.yml");
        if (Files.exists(config)) {
            return;
        }
        String repo = PlatformModel.repo(name);
        ctx.warn(repo + " has no pipeline config — overlaying the standard publish step");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                steps:
                  - image: qits/build-images/ci-base:latest
                    docker: true
                    timeout-seconds: 3600
                    script: |
                      ref="$QITS_REGISTRY/$QITS_IMAGE_REPOSITORY/%s:$QITS_CI_SHA"
                      docker build -t "$ref" -f docker/Dockerfile .
                      docker push "$ref"
                      docker rmi "$ref" || true
                """.formatted(repo), StandardCharsets.UTF_8);
        boot.git.add(src, ".config/qits/ci-post-receive.yml", ctx::log);
        boot.git.commitAsBootstrap(src,
                "Opt into CI: publish this repo's image from a green push", ctx::log);
    }

    /**
     * "Is it live at this sha?" has two answers, because the model has two shapes. An environment
     * service has a deployment row. A platform service belongs to no tier and so appears in no
     * per-environment listing, and docker is the record instead: the deployer names its container
     * qits-pd-&lt;app&gt;-&lt;id8&gt; — the tier segment is dropped rather than filled — and
     * runs the image tagged with the sha it deployed.
     */
    private boolean alreadyLive(PhaseContext ctx, String name, String repo, String sha) {
        if (PlatformModel.isPlatformService(name)) {
            Optional<String> live = platformContainer(name, sha);
            if (live.isPresent()) {
                ctx.log("  " + repo + " already live at " + sha.substring(0, 7)
                        + " (" + live.get() + ")");
                return true;
            }
            return false;
        }
        Optional<JsonNode> row = boot.pd.newestDeployment(boot.state.environmentId, repo);
        if (row.isPresent() && "ACTIVE".equals(Json.text(row.get(), "status"))
                && sha.equals(Json.text(row.get(), "commitSha"))) {
            // The row alone is not proof: an unwrap keeps the deployer's volume, so rows survive
            // containers. Trusting a row whose container is gone skipped the dev plane on a warm
            // boot. The container named on the row must actually be running and not unhealthy.
            String containerName = Json.text(row.get(), "containerName");
            boolean running = !containerName.isBlank()
                    && boot.docker.ps("{{.Names}}|{{.Status}}").stream()
                            .map(line -> line.split("\\|"))
                            .anyMatch(parts -> parts.length >= 2
                                    && parts[0].equals(containerName)
                                    && !parts[1].contains("unhealthy"));
            if (running) {
                ctx.log("  " + repo + " already ACTIVE at " + sha.substring(0, 7));
                return true;
            }
            ctx.log("  " + repo + " has an ACTIVE row at " + sha.substring(0, 7)
                    + " but its container is gone — redeploying");
        }
        return false;
    }

    /**
     * The container name of a platform service running this sha, if there is one and it is not
     * unhealthy. The health check is this port's one addition to the script: a running/unhealthy
     * container counted as live once, and the rollback that followed looked like a success.
     */
    private Optional<String> platformContainer(String name, String sha) {
        String prefix = PlatformModel.pdNamePrefix(name, boot.config.envName());
        // A pipe separator, deliberately: the process pipeline strips control characters — tabs
        // become spaces before a line reaches this loop (Ansi.clean), so a tab-separated format
        // parses as one field and no container ever matches. Found by the v3 proving run.
        for (String line : boot.docker.ps("{{.Names}}|{{.Image}}|{{.Status}}")) {
            String[] parts = line.split("\\|");
            if (parts.length < 3 || !parts[0].startsWith(prefix)) {
                continue;
            }
            if (!parts[1].endsWith(":" + sha)) {
                continue;
            }
            if (parts[2].contains("unhealthy")) {
                continue;
            }
            return Optional.of(parts[0]);
        }
        return Optional.empty();
    }

    private void awaitDeployment(PhaseContext ctx, String name, String repo, String sha,
            String ref, String baselineRowId, String baselineRunId) {
        boolean platformService = PlatformModel.isPlatformService(name);
        long[] greenForMillis = {0};
        boolean[] replayed = {false};
        long interval = boot.config.pollInterval().toMillis();
        String target = platformService
                ? "a container named " + PlatformModel.pdNamePrefix(name, boot.config.envName())
                + "* running :" + sha.substring(0, 7)
                : "a deployment row for " + repo + " at " + sha.substring(0, 7) + " in "
                + boot.config.platformDeploymentsUrl() + "/api/deployments";
        try {
            String outcome = Waiter.await(ctx, target, boot.config.deployTimeout(),
                    boot.config.pollInterval(), () -> {
                        String deploymentState = "no row yet";
                        if (platformService) {
                            Optional<String> live = platformContainer(name, sha);
                            if (live.isPresent()) {
                                return Waiter.Poll.done("ACTIVE " + live.get(), "live");
                            }
                            deploymentState = "no healthy container at this sha";
                        } else {
                            Optional<JsonNode> row = boot.pd.newestDeployment(
                                    boot.state.environmentId, repo);
                            if (row.isPresent()) {
                                String status = Json.text(row.get(), "status");
                                deploymentState = status.isBlank() ? "PENDING" : status;
                                boolean stale = Json.text(row.get(), "id").equals(baselineRowId);
                                if (sha.equals(Json.text(row.get(), "commitSha")) && !stale) {
                                    if ("ACTIVE".equals(status)) {
                                        return Waiter.Poll.done(
                                                "ACTIVE " + Json.text(row.get(), "containerName"),
                                                "ACTIVE");
                                    }
                                    if ("FAILED".equals(status) || "IMAGE_MISSING".equals(status)) {
                                        String detail = Json.text(row.get(), "detail");
                                        return Waiter.Poll.done("DEPLOY " + status + ": "
                                                + (detail.isBlank() ? "no detail" : detail), status);
                                    }
                                }
                                if (stale) {
                                    deploymentState = "stale " + deploymentState
                                            + " row from an earlier run";
                                }
                            }
                        }
                        Optional<JsonNode> newestRun = boot.ci.newestRun(repo);
                        String runStatus = newestRun
                                .filter(r -> sha.equals(Json.text(r, "commitSha")))
                                .map(r -> Json.text(r, "status")).orElse("");
                        boolean staleRun = newestRun
                                .map(r -> Json.text(r, "id").equals(baselineRunId)).orElse(false);
                        // A red row this phase did not cause is not this phase's outcome. A green
                        // one is still worth reading even when stale: it says no new run is coming,
                        // which is exactly what the replay below acts on.
                        if (!staleRun
                                && ("FAILED".equals(runStatus) || "CONFIG_ERROR".equals(runStatus))) {
                            newestRun.map(r -> Json.text(r, "id"))
                                    .flatMap(boot.ci::failedStepOutput)
                                    .ifPresent(output -> {
                                        ctx.log("  the step that failed said:");
                                        output.lines().forEach(line -> ctx.log("    " + line));
                                    });
                            return Waiter.Poll.done("CI " + runStatus, runStatus);
                        }
                        // The run's own announcement is fire-and-forget and can be lost.
                        // A green run with no deployment row a minute later is that loss — hand the
                        // deployer the event again, once. Environment services only: a platform
                        // service has no per-tier row, and its stand-in only turns true once the
                        // cutover has finished, which alone can outlast a minute.
                        if ("SUCCESS".equals(runStatus) && !platformService) {
                            greenForMillis[0] += interval;
                            // A stale terminal row means the deployer consumed this sha's event long
                            // ago and will never act unprompted — replay at once, not after a
                            // minute.
                            boolean staleTerminal = boot.pd.newestDeployment(
                                            boot.state.environmentId, repo)
                                    .map(r -> Json.text(r, "id").equals(baselineRowId))
                                    .orElse(false);
                            if ((greenForMillis[0] >= 60_000 || staleTerminal) && !replayed[0]) {
                                ctx.warn(repo + " run is green but no deployment appeared — "
                                        + "replaying the build event");
                                postBuildEvent(ctx, repo, sha, ref);
                                replayed[0] = true;
                            }
                        }
                        return Waiter.Poll.pending("ci run "
                                + (runStatus.isBlank() ? "not started"
                                        : runStatus + (staleRun ? " (an earlier run's)" : ""))
                                + ", deployment " + deploymentState);
                    });
            if (outcome.startsWith("ACTIVE")) {
                ctx.log("  " + repo + " " + outcome);
                ctx.note(outcome);
            } else {
                ctx.warn(repo + " " + outcome);
            }
        } catch (TimeoutException e) {
            // The script's posture: a deployment that never lands is a warning on an otherwise
            // finished boot, not a reason to abandon the applications behind it.
            ctx.warn(repo + ": no terminal deployment after "
                    + boot.config.deployTimeout().toSeconds() + "s (ci may still be building — "
                    + "watch docker ps and docker logs qits-ci)");
        } catch (Exception e) {
            throw new IllegalStateException("waiting for " + repo + " failed: " + e, e);
        }
    }

    /**
     * Only the GUARD's own answers count as warm. 401 and 403 are the token being read and refused,
     * which is the thing being waited for; a 400 or a 404 is the request never reaching the guard,
     * and treating those as warm is what let a cold auth plane through — the release replay's
     * authenticated call was then the first request to touch OIDC, and it 500'd.
     */
    private static Http.Response warmWhenGuardRefused(Http.Response response) {
        return response.status() == 401 || response.status() == 403
                ? new Http.Response(200, "auth plane warm (" + response.status() + ")")
                : response;
    }

    /**
     * Retried: the edge AND the gateway this call travels through are both applications this run
     * deploys, so a call can meet either one mid-cutover.
     */
    private void postBuildEvent(PhaseContext ctx, String repo, String sha, String ref) {
        Http.Response last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                // The ci client, addressed to the deployer: this event stands in for the
                // announcement qits-ci never sent, and a token that says qits-ci is exactly that.
                // Both are wire aliases — the audience is the deployer's own id, which is why the
                // idp's ci client is granted it in the seed.
                String token = boot.tokenOrNull(PlatformModel.wireAlias("ci", boot.config.envName()),
                        PlatformModel.wireAlias("deployments", boot.config.envName()));
                last = boot.pd.buildSucceeded(repo, ref, sha, token);
                if (last.ok()) {
                    ctx.log("  build-succeeded posted for " + repo + " on " + ref);
                    return;
                }
            } catch (RuntimeException e) {
                ctx.log("  build event attempt " + attempt + " failed: " + e.getMessage());
            }
            sleep(5000);
        }
        ctx.warn("could not post the build event for " + repo
                + (last == null ? "" : ": " + last.describe()));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The seeded repositories take the same protected-main bootstrap door, but stop at the git
     * host. Their own post-receive pipelines may publish packages; none of them is an application
     * of the deployer, so there is deliberately no deployment lookup, image replay or health wait.
     * qits-cd and qits-serviceregistry are here for their histories alone — both are superseded by
     * qits-deployments and neither is deployed any more.
     */
    public Phase releaseTrainPush() {
        return new Phase("release-train-push", "push the seeded repositories to the git host", ctx -> {
            for (String name : PlatformModel.SEEDED_REPOS) {
                String repo = PlatformModel.repo(name);
                Path src = boot.state.repoDir(name);
                ctx.status("pushing " + repo);
                ProcessResult result = boot.git.push(src, boot.artifacts.gitUrl(repo),
                        List.of("qits.token=" + boot.config.pushToken()), "main",
                        boot.config.pushToken(), ctx::log);
                Boot.must(result, "push of " + repo + " failed");
                boolean upToDate = result.out().toLowerCase(Locale.ROOT).contains("up to date");
                ctx.log("  " + repo + (upToDate ? " already at main"
                        : " pushed " + boot.git.shortHead(src)));
            }
            ctx.note(PlatformModel.SEEDED_REPOS.size() + " repositories");
        });
    }

    // --- the closing report ------------------------------------------------------------------------

    public Phase summary() {
        return new Phase("summary", "the platform", ctx -> {
            List<String> report = boot.state.summary;
            report.add("");
            for (String line : boot.docker.ps("table {{.Names}}\t{{.Status}}")) {
                if (line.contains("qits") || line.startsWith("NAMES")) {
                    report.add(line);
                }
            }
            String env = boot.config.envName();
            report.add("");
            report.add("edge:      http://localhost:" + boot.config.port()
                    + "/            the host's one port, in front of every environment");
            report.add("gateway:   " + env + "-qits-gateway on qits-net "
                    + "(variant: local, UNAUTHENTICATED) — no host port of its own");
            report.add("registry:  localhost:" + boot.config.registryPort() + " (host daemon only)");
            report.add("git host:  http://localhost:" + boot.config.port() + "/artifacts/git/<repoId>");
            report.add("dev loop:  commit in a repo, rerun with QITS_SKIP_BUILD=1 — the push redeploys it");
            report.add("deploy:    push " + boot.config.envBranch()
                    + " — the ONE deploy ref; pushing main builds but deploys nothing.");
            report.add("           The platform services (" + String.join(", ",
                    PlatformModel.PLATFORM_SERVICES) + ") deploy");
            report.add("           from that same ref: one instance each, joined to every "
                    + "environment's networks.");
            report.add("topology:  qits-deployments owns the environments, the services,");
            report.add("           the links AND the deployments — one component, at "
                    + boot.config.platformDeploymentsUrl());
            report.add("names:     an environment service answers to " + env
                    + "-qits-<app>, a platform service to its bare");
            report.add("           repository name. Deployed containers are qits-pd-" + env
                    + "-qits-<app>-<id8>");
            report.add("           and qits-pd-qits-<app>-<id8>.");
            report.add("main:      written by /workspaces/{id}/release, which then fast-forwards "
                    + boot.config.envBranch() + ";");
            report.add("           a direct push needs -o qits.token=" + boot.config.pushToken());
            if (boot.config.machineAuth()) {
                report.add("machines:  ENFORCED on ci, deployments, artifacts — issuer "
                        + boot.config.idpIssuer());
                report.add("           clients: " + String.join(", ",
                        PlatformModel.idpClients(env)));
                report.add("           secrets are in " + boot.state.wrapperDir
                        .resolve(".qits-bootstrap.env"));
            } else {
                report.add("machines:  gate OFF (QITS_MACHINE_AUTH=0) — ci, deployments "
                        + "and artifacts trust the network");
            }
            report.add("state:     seed compose + .qits-bootstrap.env in " + boot.state.wrapperDir);
            report.add("!! not part of either set (no image exists): qits-platform-dns");
            report.add("!! workspace containers need a qits/workspace:latest base image supplied separately");
            report.forEach(ctx::log);
        });
    }
}
