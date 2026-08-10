package eu.wohlben.qits.cli.bootstrap.phases;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.bootstrap.api.Http;
import eu.wohlben.qits.cli.bootstrap.api.Json;
import eu.wohlben.qits.cli.bootstrap.config.DomainName;
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

    /**
     * git's "this ref did not exist" sha, which is what a re-announced push carries as its oldSha.
     * qits-ci ignores the field — the run is read out of newSha — but the wire contract has the
     * shape of a real hook call and a replay that lies about it would be harder to recognise in a
     * log than one that says plainly it knows no predecessor.
     */
    private static final String NO_PREDECESSOR_SHA = "0".repeat(40);

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
            // The same hand-off for postgres: seed-postgres started it by hand, and compose has to
            // own the container it is about to declare or the two fight over the host port. The
            // recreate keeps the data — the volume is the cluster — and it is env-safe, because
            // POSTGRES_PASSWORD applies at initdb only and this cluster is already initialised.
            //
            // Asking for the ALIAS is what makes this leave a deployed postgres alone: the
            // deployer names its own containers qits-pd-…, so a name this test matches was started
            // by hand and by this run.
            String postgres = PlatformModel.wireAlias("oci-postgresql", boot.config.envName());
            if (boot.docker.allNames().contains(postgres)) {
                ctx.log("  handing the seed postgres over to compose, from the same volume");
                boot.docker.removeContainer(postgres, ctx::log);
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
     * The EDGE is the first hop of every call this program makes to ci and the deployer, so it is
     * the second thing asked and the first thing asked over the path the rest of the run uses. A
     * gateway that is up behind an edge that is not is unreachable, so the two are probed
     * separately, each by its own alias.
     */
    public Phase seedHealth() {
        return new Phase("seed-health", "wait for the seed services", ctx -> {
            String env = boot.config.envName();
            boot.awaitHealth(ctx, "qits-platform-idp (no host port, dialled on qits-net)",
                    () -> boot.http.get("http://qits-platform-idp:8080/idp/q/health/ready",
                            Map.of()));
            // /q is the edge's own prefix, the one thing it never proxies. An answer here is this
            // process, not something behind it. Its alias rather than the port it publishes: this
            // run is on qits-net, and the published port is for a person's browser.
            boot.awaitHealth(ctx, "qits-platform-edge (the door, on qits-net)",
                    () -> boot.http.get("http://qits-platform-edge:8080/q/health/ready", Map.of()));
            boot.awaitHealth(ctx, env + "-qits-gateway (on qits-net, behind the edge)",
                    () -> boot.http.get("http://" + env + "-qits-gateway:8080/q/health/ready",
                            Map.of()));
            boot.awaitHealth(ctx, "qits-platform-artifacts (on qits-net)", boot.artifacts::health);
            // At its OWN alias, which is the one exception to "everything through the edge": there
            // is no gateway route to this service and there must not be one, so its record API is
            // addressed directly. Health lives under /dns/q because that is the service's own
            // non-application root path, and readiness includes the database it refuses to boot
            // without — which is exactly what the zone write two phases from now needs.
            boot.awaitHealth(ctx, "qits-platform-dns (on qits-net, no gateway route)",
                    () -> boot.http.get(boot.config.dnsUrl() + "/q/health/ready", Map.of()));
            // Through the edge and the gateway's route table, and at the service's own alias if
            // that route is not up yet. Either answer means the service is ready; the first also
            // proves the whole path the rest of this run calls ci and the deployer through, which
            // is why the direct alias is the FALLBACK and never the address.
            boot.awaitHealth(ctx, env + "-qits-ci (through the edge, else direct)", () -> {
                Http.Response viaGateway = boot.ci.health();
                return viaGateway.ok() ? viaGateway
                        : boot.http.get("http://" + env + "-qits-ci:8080/ci/q/health/ready",
                                Map.of());
            });
            boot.awaitHealth(ctx, env + "-qits-deployments (through the edge, else direct)", () -> {
                Http.Response viaGateway = boot.pd.health();
                return viaGateway.ok() ? viaGateway : boot.http.get("http://" + env
                        + "-qits-deployments:8080/platform-deployments/q/health/ready", Map.of());
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
     * <p>
     * <b>The version replayed is the last release tag reachable from main</b>, and a repository
     * carrying none STOPS THE BOOT. That is right rather than harsh, and it is right for the image
     * publishers too: what this phase exists to restore is a PIN, and a version nobody has minted
     * is a pin nothing holds — there is nothing to dangle, so a replay would be a guess about which
     * version to publish. The failure names the repository, and the fix is to cut a release
     * through qits-workspaces rather than to soften this phase.
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
            // The newest finished run BEFORE this attempt triggers is a previous attempt's, and
            // must not be read as this one's outcome. Null when the repo never ran.
            String baselineRun = boot.ci.finishedEventRun(repo).map(r -> r[0]).orElse(null);
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

            // The same relay the deploy wait uses. A release run is a build too, and it was as
            // silent as the other one.
            CiLogStream ciLog = new CiLogStream(boot.ci, ctx);
            String status = Waiter.await(ctx, repo + "'s release run at " + boot.config.ciUrl()
                            + "/api/runs/finished", boot.config.releaseTimeout(),
                    boot.config.pollInterval(), () -> {
                        boot.ci.newestRun(repo)
                                .map(run -> Json.text(run, "id"))
                                .filter(id -> !id.equals(baselineRun))
                                .ifPresent(ciLog::follow);
                        Optional<String[]> finished = boot.ci.finishedEventRun(repo);
                        return finished
                                .filter(r -> !r[0].equals(baselineRun))
                                .<Waiter.Poll<String>>map(r -> Waiter.Poll.done(r[1], r[1]))
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
     * One standing environment — the one {@code --platform-env} names: its branch
     * {@code environment/<name>}, the shared network, and the PLATFORM flag. NO applications array;
     * registration is derived from the repo's deployments.yml on the first green build of the
     * branch that deploys it.
     * <p>
     * The flag is not decoration. The deployer ships a platform service only from the branch the
     * platform environment listens to, so an environment created without it leaves
     * qits-platform-idp, qits-platform-artifacts, qits-platform-docs and the edge registering
     * nothing and deploying nowhere — silently, because "no tier is the platform one" and "this
     * branch is not the platform tier's" are one answer.
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
                // Also re-asserts the platform flag, which is what carries a platform bootstrapped
                // before the flag existed onto it. Designating is a move on the deployer's side, so
                // asserting it on the row that already holds it changes nothing.
                patch(existing.get(),
                        Json.object("branch", branch, "platform", Json.verbatim("true")));
                boot.state.environmentId = existing.get();
                ctx.log("  reconciled the existing '" + name + "' environment onto " + branch);
            } else {
                refuseIfAnotherEnvironmentIsThePlatformOne(name);
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
                    patch(id, Json.object("branch", branch, "platform", Json.verbatim("true")));
                    boot.state.environmentId = id;
                } else {
                    throw new IllegalStateException("environment create answered "
                            + created.describe());
                }
            }
            ctx.log("  environment " + name + " (" + boot.state.environmentId + ") — branch "
                    + branch + ", network " + Boot.NETWORK + ", the platform environment");
            ctx.note(name + " on " + branch);
    }

    /**
     * A platform already stands here under another name: STOP.
     * <p>
     * This used to rename. A row called {@code qits} or {@code dev} — the names environments had
     * before they were named after tiers — was PATCHed onto the wanted name, which kept its
     * applications, its deployments and its containers rather than stranding them behind a second
     * row nothing deploys. That was right for a migration with two known names and one destination.
     * It is wrong for a knob: {@code --platform-env} makes any name reachable, so the rename would
     * fire on a re-bootstrap that simply mistyped, or on one deliberately naming a second tier.
     * <p>
     * And a rename is not what it looks like. The name is inside every wire alias, every deployed
     * container's name and every idp client id, so PATCHing the row leaves the platform running as
     * {@code <old>-qits-*} while every generated file addresses {@code <new>-qits-*}; the recorded
     * secrets are keyed by the old name too, so the idp would be handed clients nothing holds
     * credentials for. Not one of those is repaired by this phase, and half of them are only
     * repaired by redeploying everything.
     * <p>
     * So the boot stops with the two facts a person needs: what is standing here, and that
     * {@code unwrap} is the way out. Moving the plane on a LIVE platform is a different operation —
     * a PATCH on the deployer designating another tier, with the undeploy and redeploy that implies
     * — and it is deliberately not this.
     */
    private void refuseIfAnotherEnvironmentIsThePlatformOne(String wanted) {
        boot.pd.platformEnvironment().ifPresent(environment -> {
            String standing = Json.text(environment, "name");
            throw new IllegalArgumentException(
                    "The platform environment here is '" + standing + "' (branch "
                            + Json.text(environment, "branch") + "), and this boot asks for '"
                            + wanted + "'. Renaming it would leave every running container on "
                            + standing + "-qits-* aliases and orphan the recorded IDP_SECRET_"
                            + PlatformModel.clientKey(standing)
                            + "_* entries in .qits-bootstrap.env. Run `qits unwrap` first, or "
                            + "bootstrap with --platform-env " + standing + ".");
        });
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
            if (upToDate && staleRedRun(repo, sha, baselineRunId)) {
                // No push, no event, and the run this sha already has is RED. There is no image to
                // deploy, so the build event below would only buy an IMAGE_MISSING row — which is
                // what a human then had to unpick by hand on the first prod bootstrap. Ask for the
                // BUILD instead, by making the announcement the git host makes on a real push.
                //
                // The baseline captured above is the red run's own id, so the wait below already
                // ignores it and reads only a run NEWER than it. If that one is red too, the phase
                // warns exactly as it did before: this replaces a dead end with one more attempt,
                // not with a retry loop.
                ctx.log("  " + repo + " unchanged and its newest run at " + sha.substring(0, 7)
                        + " is red — re-announcing the push so ci builds it again");
                reannouncePush(ctx, repo, sha, ref);
            } else if (upToDate) {
                // No push, no event — and not live at HEAD either. The image exists from an
                // earlier run; hand the deployer the event it never got, naming the ref that
                // deploys it.
                ctx.log("  " + repo + " unchanged but not deployed at HEAD — posting the build event");
                postBuildEvent(ctx, repo, sha, ref);
            } else {
                ctx.log("  pushed " + sha.substring(0, 7)
                        + ", waiting for the deployment (a cold native build — be patient)");
            }
            awaitDeployment(ctx, name, repo, sha, ref, baselineRowId, baselineRunId, !upToDate);
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
     * The container name of a platform service running this sha, once docker says it is serving.
     * The health check is this port's one addition to the script: a running/unhealthy container
     * counted as live once, and the rollback that followed looked like a success.
     */
    private Optional<String> platformContainer(String name, String sha) {
        return platformContainerAtSha(name, sha).filter(container -> serving(container[1]))
                .map(container -> container[0]);
    }

    /**
     * The container of a platform service at this sha as {name, docker's status text} — serving or
     * not, and preferring one that is.
     * <p>
     * The status travels with the name because whether the wait is over is one question and what to
     * SAY while it is not is another. "no healthy container at this sha" never mentioned that the
     * container was there and restarting, so the operator could not tell a build that had not
     * finished from one that had crash-looped.
     */
    private Optional<String[]> platformContainerAtSha(String name, String sha) {
        String prefix = PlatformModel.pdNamePrefix(name, boot.config.envName());
        String[] notServing = null;
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
            String[] container = {parts[0], parts[2]};
            if (serving(container[1])) {
                return Optional.of(container);
            }
            // A cutover has two containers at one sha for a moment; keep looking past the one that
            // is not serving, and remember it in case none of them is.
            notServing = notServing == null ? container : notServing;
        }
        return Optional.ofNullable(notServing);
    }

    /**
     * Does docker's status text say this container is SERVING?
     * <p>
     * Where there is a healthcheck its verdict is the only one worth reading: {@code (healthy)} ends
     * the wait, {@code (health: starting)} and {@code (unhealthy)} are both "not yet". Neither is a
     * failure decided here — the phase's own timeout bounds this wait, and a container still
     * starting is precisely what a wait is for.
     * <p>
     * Where there is no healthcheck there is no verdict, so being Up is the whole test. That also
     * settles {@code Restarting (1) 4 seconds ago}, which carries no health suffix and is not Up.
     * A crash-looping container used to pass this test: on the first PG-era bootstrap, phase 39
     * reported qits-platform-idp "ACTIVE qits-pd-qits-platform-idp-f325ef80" while the deployer's
     * own gate was marking that same deployment FAILED. The boot marched on and stalled two phases
     * later.
     */
    static boolean serving(String status) {
        String text = status.toLowerCase(Locale.ROOT);
        if (text.contains("(healthy)")) {
            return true;
        }
        if (text.contains("health: starting") || text.contains("unhealthy")) {
            return false;
        }
        return text.startsWith("up");
    }

    private void awaitDeployment(PhaseContext ctx, String name, String repo, String sha,
            String ref, String baselineRowId, String baselineRunId, boolean pushed) {
        boolean platformService = PlatformModel.isPlatformService(name);
        CiLogStream ciLog = new CiLogStream(boot.ci, ctx);
        DeployLogStream pdLog = new DeployLogStream(boot.docker, ctx, repo,
                PlatformModel.wireAlias("deployments", boot.config.envName()),
                PlatformModel.pdNamePrefix("deployments", boot.config.envName()));
        long[] greenForMillis = {0};
        long[] noRunForMillis = {0};
        boolean[] replayed = {false};
        boolean[] reannounced = {false};
        long interval = boot.config.pollInterval().toMillis();
        String target = platformService
                ? "a container named " + PlatformModel.pdNamePrefix(name, boot.config.envName())
                + "* running :" + sha.substring(0, 7) + " and serving"
                : "a deployment row for " + repo + " at " + sha.substring(0, 7) + " in "
                + boot.config.platformDeploymentsUrl() + "/api/deployments";
        try {
            String outcome = Waiter.await(ctx, target, boot.config.deployTimeout(),
                    boot.config.pollInterval(), () -> {
                        // The deployer's account of this repository, before the verdict is read, so
                        // "what is it doing" is answered in the same breath as "is it done".
                        pdLog.follow();
                        String deploymentState = "no row yet";
                        if (platformService) {
                            Optional<String[]> container = platformContainerAtSha(name, sha);
                            if (container.isPresent() && serving(container.get()[1])) {
                                return Waiter.Poll.done("ACTIVE " + container.get()[0], "live");
                            }
                            deploymentState = container
                                    .map(found -> found[0] + " is " + found[1])
                                    .orElse("no container at this sha");
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
                        // Read the build out loud while it runs. Only THIS phase's run: an earlier
                        // one's log says nothing about what is being waited for. The relay reads
                        // the run on this same interval and turns itself off rather than failing.
                        if (!staleRun) {
                            ciLog.follow(newestRun
                                    .filter(r -> sha.equals(Json.text(r, "commitSha")))
                                    .map(r -> Json.text(r, "id")).orElse(null));
                        }
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
                        // The git host's push announcement is fire-and-forget too, and it dies for
                        // real: the first proving run lost qits-platform-idp's to the database
                        // cutover the previous phase's qits-oci-postgresql deploy had just caused —
                        // ci's pool was severed the second the announcement arrived, no run was
                        // ever enqueued, and the wait read "ci run not started" for its entire
                        // hour. A pushed sha with no run a minute later IS that loss: nothing
                        // upstream retries, so make the announcement again, once. Only after a real
                        // push — the up-to-date branches already sent whatever event they needed.
                        if (pushed && runStatus.isBlank()) {
                            noRunForMillis[0] += interval;
                            if (noRunForMillis[0] >= 60_000 && !reannounced[0]) {
                                ctx.warn(repo + " was pushed but ci never started a run at "
                                        + sha.substring(0, 7)
                                        + " — the announcement is lost, making it again");
                                reannouncePush(ctx, repo, sha, ref);
                                reannounced[0] = true;
                            }
                        } else {
                            noRunForMillis[0] = 0;
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

    /**
     * Is the newest run of this repository the one that was there BEFORE this phase pushed, at this
     * very sha, and red? Only then is there nothing left to wait for: no new run is coming, and the
     * image the deployer would be sent after does not exist.
     * <p>
     * All three conditions are needed. A run at another sha says nothing about this one; a run
     * newer than the baseline is this phase's own and belongs to the wait; a green stale run means
     * the image IS there and the lost-event replay is the right answer instead.
     */
    private boolean staleRedRun(String repo, String sha, String baselineRunId) {
        return boot.ci.newestRun(repo)
                .filter(run -> Json.text(run, "id").equals(baselineRunId))
                .filter(run -> sha.equals(Json.text(run, "commitSha")))
                .map(run -> Json.text(run, "status"))
                .filter(status -> "FAILED".equals(status) || "CONFIG_ERROR".equals(status))
                .isPresent();
    }

    /**
     * Announces the push to ci the way the git host does, so a sha whose only run is red gets
     * built again. This is a POST-RECEIVE, not a build-succeeded: what is missing is the IMAGE,
     * and only a run makes one.
     * <p>
     * The token is minted as the platform store client, exactly as the release replay's is, because
     * that is the identity the git host announces with — ci's intake checks the token's project
     * claim against the repo the event names, and the store speaks for every repository, so it is
     * the one client granted the wildcard. The branch is the ref that DEPLOYS the application,
     * spelled the way the hook spells it: the branch name, with no refs/heads/ in front.
     * <p>
     * Retried like the build event, and for the same reason: this call travels through the edge and
     * the gateway, both of which are applications this run deploys.
     */
    private void reannouncePush(PhaseContext ctx, String repo, String sha, String ref) {
        Http.Response last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String token = boot.tokenOrNull(
                        PlatformModel.wireAlias("platform-artifacts", boot.config.envName()),
                        PlatformModel.wireAlias("ci", boot.config.envName()));
                last = boot.ci.postReceive(Json.object("repoId", repo, "branch", ref,
                        "oldSha", NO_PREDECESSOR_SHA, "newSha", sha), token);
                if (last.ok()) {
                    ctx.log("  post-receive re-announced for " + repo + " on " + ref
                            + " — waiting for the run it queues");
                    return;
                }
            } catch (RuntimeException e) {
                ctx.log("  re-announce attempt " + attempt + " failed: " + e.getMessage());
            }
            sleep(5000);
        }
        ctx.warn("could not re-announce the push for " + repo
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
            report.add("dns:       qits-platform-dns, published on " + boot.config.dnsPort()
                    + " udp AND tcp — a sibling of the edge, never a route behind it.");
            report.add("           Zones and records are ROWS: " + boot.config.dnsUrl()
                    + "/api/zones");
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
            // Only with a domain, and every line of it is a step this program cannot take itself:
            // the ACME order is made from the host by a CLI, and the delegation and the records need
            // this machine's public address, which a container behind a NAT cannot learn.
            DomainName.of(boot.config).ifPresent(domain -> {
                report.add("domain:    " + domain + " — the zone row exists and answers; it has no "
                        + "records yet.");
                report.add("           1. At the registrar: NS " + DomainName.nsName(domain)
                        + " for " + domain + ", plus a GLUE A record");
                report.add("              " + DomainName.nsName(domain)
                        + " -> this host's public IP. An NS holds a hostname, so");
                report.add("              without the glue nothing can find the server it names.");
                report.add("           2. Write the records: POST " + boot.config.dnsUrl()
                        + "/api/zones/{id}/records");
                report.add("              (their values are that same public IP, which this run "
                        + "cannot know).");
                report.add("tls:       the edge holds a PLACEHOLDER certificate — browsers reject "
                        + "it. Issue the real one");
                report.add("           from this host, staging while we trial it:");
                report.add("             quarkus tls lets-encrypt issue-certificate --staging "
                        + "--domain=" + domain + " \\");
                report.add("               --email=<operator> "
                        + "--management-url=http://localhost:9000");
                report.add("           Renewal is renew-certificate with the same management URL. "
                        + "The PEMs land in the");
                report.add("           qits-edge-letsencrypt volume under the same two filenames "
                        + "and the TLS registry");
                report.add("           reloads within the hour, so neither is a redeploy. HTTP-01 "
                        + "needs port 80 reachable");
                report.add("           from the internet and the delegation above in place.");
            });
            report.add("images:    the release replays published qits/workspace-base, qits/workspace,");
            report.add("           qits/projects-daemon and qits/project-agent at their released "
                    + "versions —");
            report.add("           the coordinates qits-workspaces and qits-projects pin. Nothing "
                    + "is supplied by hand.");
            report.forEach(ctx::log);
        });
    }
}
