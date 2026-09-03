package eu.wohlben.qits.cli.bootstrap.phases;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.bootstrap.api.CiApi;
import eu.wohlben.qits.cli.bootstrap.api.Http;
import eu.wohlben.qits.cli.bootstrap.api.Json;
import eu.wohlben.qits.cli.bootstrap.config.Acme;
import eu.wohlben.qits.cli.bootstrap.config.DomainName;
import eu.wohlben.qits.cli.bootstrap.config.ExtraSans;
import eu.wohlben.qits.cli.bootstrap.config.PublicIp;
import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseContext;
import eu.wohlben.qits.cli.bootstrap.engine.Waiter;
import eu.wohlben.qits.cli.bootstrap.platform.BootstrapState;
import eu.wohlben.qits.cli.bootstrap.platform.ComposeTemplate;
import eu.wohlben.qits.cli.bootstrap.platform.Docker;
import eu.wohlben.qits.cli.bootstrap.platform.PlatformModel;
import eu.wohlben.qits.cli.bootstrap.proc.Cmd;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
     * THE DEPLOYER'S TERMINAL VOCABULARY, minus the one good word. A row in any of these states is
     * finished: no later row for this sha is coming, so the wait ends and warns.
     * <p>
     * The list comes from qits-deployments' status refinement, and this CLI has to know a word
     * BEFORE the deployer starts writing it: an unknown status reads as "still working", so the
     * wait sits on a finished row for its whole timeout — measured cost, one hour per repository.
     * <ul>
     *   <li>{@code FAILED} — the deploy did not come up.
     *   <li>{@code IMAGE_MISSING} — there was nothing to deploy at that sha.
     *   <li>{@code ROLLED_BACK} — the swarm manager restored the predecessor. The SERVICE kept
     *       serving, which is why this is not a catastrophe, but it is not serving THIS commit and
     *       the boot must say so.
     *   <li>{@code SUPERSEDED} — an in-flight row overtaken by a newer deployment. Nothing further
     *       will happen to it.
     *   <li>{@code GONE} — an observer demoting a row that was active and is not any more.
     * </ul>
     * ACTIVE is deliberately absent: it is the wait's success and is answered on its own.
     */
    static final Set<String> TERMINAL_DEPLOYMENT_STATUSES =
            Set.of("FAILED", "IMAGE_MISSING", "ROLLED_BACK", "SUPERSEDED", "GONE");

    private final Boot boot;

    public PipelinePhases(Boot boot) {
        this.boot = boot;
    }

    // --- the seed stack ---------------------------------------------------------------------------

    public Phase seedStackUp() {
        return new Phase("seed-stack", "deploy the seed stack", ctx -> {
            boot.docker.ensureNetwork(Boot.NETWORK, ctx::log);
            // The artifacts instance used while building seed images has no machine credentials:
            // the idp did not exist yet. Replace it so the stack starts the real service with the
            // audience it validates against; otherwise every admin write reaches it unguarded.
            String artifacts = PlatformModel.wireAlias("artifacts", boot.config.envName());
            if (boot.docker.allNames().contains(artifacts)) {
                ctx.log("  replacing the bootstrap artifact registry with the authenticated seed service");
                boot.docker.removeContainer(artifacts, ctx::log);
            }
            // The same hand-off for the mirror, which seed-mirror started by hand before any stack
            // file existed. Its volume is the cache and survives the recreate; what it gains is the
            // observability address and the deployer's adoption at the next cutover.
            String mirror = PlatformModel.wireAlias("platform-mirror", boot.config.envName());
            if (boot.docker.allNames().contains(mirror)) {
                ctx.log("  handing the seed mirror over to the stack, from the same volume");
                boot.docker.removeContainer(mirror, ctx::log);
            }
            // The same hand-off for postgres: seed-postgres started it by hand, and the stack has
            // to own the server it is about to declare or two of them write one cluster. The
            // recreate keeps the data — the volume is the cluster — and it is env-safe, because
            // POSTGRES_PASSWORD applies at initdb only and this cluster is already initialised.
            //
            // Asking for the ALIAS is what makes this leave a deployed postgres alone: the deployer
            // names its own containers qits-pd-…, and a stack names its own <stack>_<service>.<n>.…
            // — so a container answering to the bare alias was started by hand and by this run.
            String postgres = PlatformModel.wireAlias("oci-postgresql", boot.config.envName());
            if (boot.docker.allNames().contains(postgres)) {
                ctx.log("  handing the seed postgres over to the stack, from the same volume");
                boot.docker.removeContainer(postgres, ctx::log);
            }
            SeedPlan plan = seedPlan(boot.docker.runningNames(), boot.docker.serviceNames(),
                    boot.config.envName());
            plan.managed().forEach(alias ->
                    ctx.log("  " + alias + " is deployer-managed — the stack leaves it alone"));
            if (!plan.stale().isEmpty()) {
                // AND REMOVING THEM IS THE SWARM-SHAPED HALF OF THAT RULE. A compose sibling stayed
                // down once the deployer's cutover removed its container; a SERVICE's task is
                // restarted by swarm within seconds, so a seed service left standing beside a
                // deployed container is not idle — it is a second holder of the alias, for good.
                ctx.log("  removing " + String.join(", ", plan.stale())
                        + ": the deployer manages those applications now");
                Boot.must(boot.docker.serviceRm(plan.stale(), ctx::log),
                        "removing the superseded seed services failed");
            }
            if (plan.deploy().isEmpty()) {
                ctx.log("  the whole seed is deployer-managed — nothing to deploy");
                ctx.note("nothing to start");
                return;
            }
            Boot.must(boot.docker.stackDeploy(stackFile(ctx, plan.deploy()), Docker.STACK,
                    Duration.ofMinutes(30), ctx::log), "docker stack deploy failed");
            ctx.note(String.join(" ", plan.deploy()));
        });
    }

    /**
     * What this run deploys, what it leaves alone, and what it takes away — decided from the
     * container list and the service list together, because the platform has both shapes on it.
     *
     * @param deploy  the wire aliases to deploy, which are the stack file's own service keys
     * @param managed the applications a qits-pd-… container is already running
     * @param stale   seed SERVICES of those same applications, which must go
     */
    record SeedPlan(List<String> deploy, List<String> managed, List<String> stale) {
    }

    /**
     * <b>Never a seed service beside a deployed container.</b> That is the rule every
     * {@code depends_on} was left out of the stack file for, and it is why this program asks what
     * is running before it deploys anything.
     * <p>
     * The question is asked of the CONTAINER list, because a deployment is still a
     * {@code docker run} container named {@code qits-pd-<env>-<app>-<id8>}, and answered into the
     * SERVICE list, because the seed is a stack now and a service outlives the container it was
     * asked about.
     */
    static SeedPlan seedPlan(List<String> running, List<String> services, String envName) {
        List<String> deploy = new ArrayList<>();
        List<String> managed = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        for (String name : PlatformModel.CORE) {
            String prefix = PlatformModel.pdNamePrefix(name, envName);
            String alias = PlatformModel.wireAlias(name, envName);
            // Deployer-managed shows up two ways: a SERVICE under the bare wire alias (the swarm
            // driver's shape — its task containers carry swarm's own names, so the qits-pd-
            // container test would miss every one of them and this run would stack a seed twin
            // over each), or a qits-pd- container (the docker driver's shape, and what a platform
            // from before the flip still runs).
            if (!services.contains(alias)
                    && running.stream().noneMatch(container -> container.startsWith(prefix))) {
                deploy.add(alias);
                continue;
            }
            managed.add(alias);
            // Only the STACK-qualified twin is stale. The bare-alias service is the deployer's
            // own — sweeping it would take down the deployed application this run just decided
            // not to touch.
            String qualified = Docker.stackService(Docker.STACK, alias);
            services.stream().filter(qualified::equals).forEach(stale::add);
        }
        return new SeedPlan(List.copyOf(deploy), List.copyOf(managed), List.copyOf(stale));
    }

    /**
     * The file this run deploys: the generated one when the whole seed is this run's to start, and
     * a subset of it when some of the seed is the deployer's.
     * <p>
     * {@code docker stack deploy} takes no service list, so the choice is made in the file — see
     * {@link eu.wohlben.qits.cli.bootstrap.platform.ComposeTemplate#only}. The whole file stays
     * where it was written, because it is what the platform's seed IS; the subset is a working
     * file of this phase and says so in its name.
     */
    private Path stackFile(PhaseContext ctx, List<String> deploy) throws IOException {
        Path whole = boot.state.composeFile;
        if (deploy.size() == PlatformModel.CORE.size()) {
            return whole;
        }
        Path subset = whole.resolveSibling("docker-stack.qits.partial.yml");
        Files.deleteIfExists(subset);
        Files.writeString(subset, ComposeTemplate.only(
                Files.readString(whole, StandardCharsets.UTF_8), deploy), StandardCharsets.UTF_8);
        ctx.log("  " + subset.getFileName() + ": " + deploy.size() + " of "
                + PlatformModel.CORE.size() + " services, the rest being deployer-managed");
        return subset;
    }

    /**
     * idp first: with the gate on, every call this run makes by hand needs a token — the release
     * announcements, the releases handed to the deployer, and the environment reconcile.
     * qits-deployments before anything asks it for an environment operation — it owns the topology
     * and the socket both, so nothing else can answer for it.
     * <p>
     * The edge comes up before any projected deployment route exists. Seed services are therefore
     * checked through their fixed qits-net aliases; public routing is only authoritative after
     * deployment events have been projected.
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
            // THE BYTE PLANE'S THREE, each at its own alias. They are polled together because they
            // fail together in the same way — a store, a cache and a git host are three services
            // now, and "the registry answers" stopped being one question at the split.
            boot.awaitHealth(ctx, env + "-qits-artifacts (the store, on qits-net)",
                    boot.artifacts::health);
            boot.awaitHealth(ctx, "qits-platform-mirror (the caches, on qits-net)",
                    () -> boot.http.get(boot.config.mirrorUrl() + "/mirror/q/health/ready",
                            Map.of()));
            // BEFORE git-repos, which is two phases away and PUTs a repository per platform
            // repository against it. Its readiness includes the database it refuses to boot
            // without, which is exactly what those calls need.
            boot.awaitHealth(ctx, env + "-qits-githost (the git host, on qits-net)",
                    boot.githost::health);
            // THE ALIAS TABLE, waited for beside the store it names repositories in. Its readiness
            // is its three databases, which is exactly what the two phases below need: one asks it
            // for the qits project, the other registers every (storage id, name) pair against it
            // before a single push. A name resolves through this service or nowhere.
            String projectsToken = projectsToken();
            boot.awaitHealth(ctx, env + "-qits-projects (the alias table, on qits-net)",
                    () -> boot.projects.health(projectsToken));
            boot.awaitHealth(ctx, env + "-qits-ci (on qits-net)", boot.ci::health);
            boot.awaitHealth(ctx, PlatformModel.wireAlias("deployments", env) + " (on qits-net)",
                    boot.pd::health);
            // THE BUS, and it is waited for here rather than trusted to arrive: every green build
            // of this run travels ci -> outbox -> this service -> the deployer's subscriber, and
            // the first push is two phases away. Its fixed alias is available before the edge has
            // projected any deployment endpoint.
            //
            // No auth-plane probe below for it: it enforces no machine gate, so there is no tenant
            // to warm.
            boot.awaitHealth(ctx, PlatformModel.wireAlias("events", env) + " (the bus, on qits-net)",
                    () -> boot.http.get(boot.config.eventsUrl() + "/q/health/ready", Map.of()));
            // THE CONTAINER ORCHESTRATOR, and it is waited for BEFORE the first pipeline of this
            // boot rather than trusted to arrive: qits-ci runs every step as a container it asks
            // this service for, so a pipeline that starts first has nowhere to run a step.
            //
            // At its OWN alias, the one exception to "everything through the edge", and for one
            // reason: there is no public route to this service and there must not be one. Every caller is a machine on qits-net, and a route would put a
            // socket-holding orchestrator behind the platform's public door. Health lives under
            // /containers/q because that is the service's own non-application root path, and
            // readiness includes the two databases it refuses to boot without.
            //
            // Its auth plane IS warmed below, and that note used to say the opposite: "nothing in
            // THIS run calls the orchestrator, so there is no first request to warm the tenant for
            // — the probe belongs with the change that makes ci a caller." This run is that change.
            // ci asks this service for every pipeline step now, and the first pipeline of this boot
            // is two phases away, so the first request to touch its OIDC tenant would be a build's.
            boot.awaitHealth(ctx, PlatformModel.wireAlias("containers", env)
                            + " (on qits-net, no public route)",
                    () -> boot.http.get("http://" + PlatformModel.wireAlias("containers", env)
                            + ":8080/containers/q/health/ready", Map.of()));
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
            // THE DOOR THIS RUN ACTUALLY USES, and that is why it is the probe: /events/
            // build-succeeded is gone from the deployer and answers 404, which reads as "not warm"
            // and would spin here for the phase's whole timeout.
            boot.awaitHealth(ctx, PlatformModel.wireAlias("deployments", env)
                            + " auth plane (junk bearer -> 401)",
                    () -> warmWhenGuardRefused(boot.http.postJson(
                            boot.config.platformDeploymentsUrl() + "/api/events/software-released",
                            "{}", PROBE_BEARER)));
            // THE ORCHESTRATOR'S, and it is the one probe here that is READ-shaped. The two above
            // are writes because a read on those services is unguarded and therefore never touches
            // the tenant; every route of qits-containers is guarded, reads included — a row says
            // which containers another module has running — so a listing presents the bearer and
            // initializes the tenant exactly as a write would. It is also the safer shape by a
            // wide margin: a write-shaped probe at a service whose writes START CONTAINERS is one
            // malformed body away from being a workload, and with the gate OFF it would answer 400
            // and spin here until the phase timed out. A read answers 401 warm, 200 with the gate
            // off, and creates nothing either way.
            //
            // At its own alias, like its health poll above: there is no gateway route to this
            // service and there must not be one. The owner in the path is ci's, because that is
            // whose inventory this stands in for.
            boot.awaitHealth(ctx, PlatformModel.wireAlias("containers", env)
                            + " auth plane (junk bearer -> 401)",
                    () -> warmWhenGuardRefused(boot.http.get(
                            "http://" + PlatformModel.wireAlias("containers", env)
                                    + ":8080/containers/api/containers/"
                                    + PlatformModel.wireAlias("ci", env),
                            PROBE_BEARER)));
        });
    }

    // --- the first account ------------------------------------------------------------------------

    /**
     * <b>THE KEY TO THE FIRST ACCOUNT, minted once per installation.</b> A person registers at
     * {@code /idp/register} with it, gets an admin, and the token is spent — so it is minted here,
     * printed by the closing report and recorded beside the client secrets.
     * <p>
     * <b>Once, and the recorded value is what makes it once.</b> The idp mints a fresh token on
     * every call and every one of them creates an admin, so a boot that minted on each rerun would
     * leave a pile of live keys to this platform behind it. A rerun that finds
     * {@code IDP_REGISTER_TOKEN} in {@code .qits-bootstrap.env} mints nothing — whether the first
     * account exists yet or not, which is the same answer for both and needs no question asked of
     * the idp. Deleting that line is how a lost token is replaced.
     * <p>
     * <b>Here because this is the first point the idp answers</b>, and a row it writes outlives
     * every redeploy after it — the store is postgres, not the container. It is dialled with the
     * EDGE's static client: minting is a static client's right, and the edge is the one whose whole
     * business is user sessions.
     * <p>
     * <b>A refusal warns and the boot goes on.</b> Nothing this platform runs waits on a person
     * registering, and while the sessions flip is off nothing even reads a session — so the cost of
     * an idp that answered badly is one line in the report and a rerun, never a failed bootstrap.
     */
    public Phase registerToken() {
        return new Phase("register-token", "mint the first account's register token", ctx -> {
            if (boot.state.registerTokenRecorded) {
                ctx.skip("an earlier run minted one — it is in " + BootstrapState.FILE_NAME);
            }
            String client = PlatformModel.wireAlias("edge", boot.config.envName());
            String secret = boot.state.secrets.getOrDefault(client, "");
            String url = boot.config.idpIssuer() + "/api/register-tokens";
            ctx.status("POST " + url + " as " + client);
            Http.Response response = boot.idp.mintRegisterToken(client, secret);
            if (!response.ok()) {
                ctx.warn("no register token: " + url + " answered " + response.describe()
                        + ". The platform is up; nobody can register the first account until a "
                        + "token is minted, and a rerun asks again.");
                return;
            }
            String token = Json.text(Json.parse(response.body()), "token");
            if (token.isBlank()) {
                ctx.warn("no register token: " + url + " answered " + response.status()
                        + " with no 'token' field. A rerun asks again.");
                return;
            }
            // Recorded before it is printed: a token this run holds and never wrote down is a token
            // lost to a closed terminal, and the next run would mint a second one.
            BootstrapState state = new BootstrapState(
                    boot.state.wrapperDir.resolve(BootstrapState.FILE_NAME));
            state.put(BootstrapState.REGISTER_TOKEN_KEY, token);
            state.write();
            boot.state.registerToken = token;
            // Not logged here. The closing report prints it once, where a person is reading.
            ctx.log("  minted and recorded in " + state.file());
            ctx.note("minted");
        });
    }

    /**
     * The register token's place in the closing report, in three states.
     * <p>
     * <b>A value is printed on the run that minted it and on no other.</b> A credential reprinted
     * by every boot is a credential on every screen and in every run log for the life of the
     * platform, and after the first run there is a better answer: it is in the state file, where
     * the pointer sends the reader. A run that minted nothing and found nothing recorded says
     * nothing here — its phase warned, which is where that belongs.
     */
    static List<String> registerLines(String registerUrl, String minted, boolean recorded,
            String stateFile) {
        if (minted != null && !minted.isBlank()) {
            return List.of(
                    "register:  THE FIRST ACCOUNT is made at " + registerUrl + ", with this token:",
                    "             " + minted,
                    "           ONE-TIME: it makes one user, an admin, and is spent. Printed here "
                            + "once and kept",
                    "           in " + stateFile + ".",
                    "           Sessions are OFF (qits.edge.sessions.enabled=false), so nothing "
                            + "asks for a login yet;",
                    "           the account and its passkey outlive the flip.");
        }
        if (recorded) {
            return List.of(
                    "register:  THE FIRST ACCOUNT is made at " + registerUrl + ". Its ONE-TIME "
                            + "token was minted by an",
                    "           earlier run and is IDP_REGISTER_TOKEN in " + stateFile + " — spent "
                            + "once an account exists.",
                    "           Delete that line and rerun to mint a fresh one.");
        }
        return List.of();
    }

    /**
     * The domain's place in the closing report: the records it needs, and which certificate the edge
     * is serving.
     * <p>
     * <b>THIS PLATFORM SERVES NO DNS.</b> The records are held wherever the domain is, and this run
     * writes none of them — so they are printed as a step to CHECK, and they belong BEFORE the run:
     * the certificate order is answered over the public name.
     * <p>
     * <b>A failed order is a line here, not a failed boot.</b> The commonest reason is a record the
     * world has not seen yet, which fixes itself in minutes to hours and which this program can
     * neither hurry nor detect. So the phase warns and this block prints the retry with the mode and
     * the address already filled in, exactly as the register token's lines do for its own one call.
     */
    static List<String> domainLines(String domain, String publicIp, Acme.Mode mode, String email,
            String certificate, List<String> projectSlugs, List<String> extraSans) {
        List<String> lines = new ArrayList<>();
        lines.add("domain:    " + domain + " — DNS IS NOT THIS PLATFORM'S. Check your provider "
                + "holds these A records:");
        for (SeedPhases.ZoneRecord record : SeedPhases.zoneRecords(domain, publicIp)) {
            lines.add("             " + pad(record.name()) + " A  " + record.value() + "   "
                    + record.why());
        }
        lines.add("           A wildcard per DEPTH, not a record per name: the edge reads at most "
                + "the first two");
        lines.add("           labels of a Host header, so a new environment or a new app vhost "
                + "needs no dns step.");
        lines.add("           Names are relative to the apex — @ is the apex, and no wildcard "
                + "matches it.");
        lines.add("           Every one carries " + publicIp + ", the address this run was given.");
        lines.addAll(editorLines(domain, projectSlugs, extraSans));
        lines.addAll(tlsLines(domain, mode, email, certificate));
        return lines;
    }

    /**
     * <b>The editor hosts, beside the records, and what the certificate does about them.</b>
     * <p>
     * The two halves of a public name come apart here, which is why this block sits between them.
     * DNS is fine by construction: {@code editor.<project>.<domain>} is the same depth as
     * {@code <app>.<env>.<domain>}, so the {@code *.*} record above answers it for every project
     * there will ever be. The CERTIFICATE is not: a wildcard covers one label, so
     * {@code *.<domain>} stops above these names and {@code *.<env>.<domain>} only holds where the
     * middle label is an environment. Each editor host is therefore a SAN of its own.
     * <p>
     * <b>Printed per project rather than as a shape</b>, because the interesting fact is which
     * projects are covered and which are not — a project created after the certificate was ordered
     * has a working name serving a certificate it is not in, and a browser refuses it with an
     * error about the site's identity rather than about a missing name.
     */
    private static List<String> editorLines(String domain, List<String> projectSlugs,
            List<String> extraSans) {
        List<String> lines = new ArrayList<>();
        lines.add("editor:    the web editor is one origin per project, editor.<project>."
                + domain + ". The records");
        lines.add("           above already cover them — that is the *.* depth — but the "
                + "CERTIFICATE does not:");
        lines.add("           a wildcard covers ONE label, so each name is its own SAN, named in "
                + "QITS_ACME_EXTRA_SANS.");
        if (projectSlugs.isEmpty()) {
            lines.add("           No project list was read, so add one name per project: "
                    + "QITS_ACME_EXTRA_SANS=editor.<project>");
            return lines;
        }
        List<String> missing = new ArrayList<>();
        for (String slug : projectSlugs) {
            String host = ExtraSans.editorHost(slug, domain);
            boolean covered = extraSans.contains(host);
            lines.add("             " + host + (covered ? "   on the certificate"
                    : "   NOT on the certificate"));
            if (!covered) {
                missing.add("editor." + slug);
            }
        }
        if (!missing.isEmpty()) {
            lines.add("           Add QITS_ACME_EXTRA_SANS=" + String.join(",", missing)
                    + " and rerun, or restart the edge");
            lines.add("           once the value is in its extras — a name reaches the "
                    + "certificate at the next ORDER,");
            lines.add("           which is a renewal or a restart, not a deploy. Until then those "
                    + "hosts serve TLS a");
            lines.add("           browser refuses.");
        }
        return lines;
    }

    /** The record names, in a column, so short names read as a table rather than as prose. */
    private static String pad(String name) {
        return name.length() >= 6 ? name : name + " ".repeat(6 - name.length());
    }

    /**
     * The certificate half, in the four states a run can end in: a staging certificate, a production
     * one, issuance switched off, and an order that did not go through.
     */
    private static List<String> tlsLines(String domain, Acme.Mode mode, String email,
            String certificate) {
        List<String> lines = new ArrayList<>();
        if (Acme.Mode.STAGING.word().equals(certificate)) {
            lines.add("tls:       ISSUED — the edge serves a Let's Encrypt STAGING certificate for "
                    + domain + ".");
            lines.add("           A browser still refuses it: staging issues from an untrusted "
                    + "root. It proves the");
            lines.add("           records, the challenge and the reload, which is what it is "
                    + "for. When you are");
            lines.add("           satisfied, rerun with QITS_ACME_MODE=production — one rerun, and "
                    + "NO redeploy:");
            lines.add("           the PEMs land on the qits-edge-letsencrypt volume under the same "
                    + "two names and");
            lines.add("           the TLS registry reloads them within the hour.");
            return lines;
        }
        if (Acme.Mode.PRODUCTION.word().equals(certificate)) {
            lines.add("tls:       ISSUED — the edge serves a real Let's Encrypt certificate for "
                    + domain + ".");
            lines.add("           https://" + domain + " is the front door and browsers accept it. "
                    + "Renewal is the same");
            lines.add("           command below, and needs no redeploy: the PEMs are replaced on "
                    + "the volume and the");
            lines.add("           TLS registry reloads them within the hour.");
            return lines;
        }
        if (mode == Acme.Mode.OFF) {
            lines.add("tls:       ISSUANCE OFF (QITS_ACME_MODE=off) — the edge holds the "
                    + "PLACEHOLDER certificate, which");
            lines.add("           browsers reject. Rerun with QITS_ACME_MODE=staging to have this "
                    + "run order one, or");
            lines.add("           do it by hand:");
        } else {
            lines.add("tls:       NOT ISSUED — the order did not go through, so the edge still "
                    + "holds the PLACEHOLDER");
            lines.add("           certificate and browsers reject it. The usual reason is the "
                    + "records above: a");
            lines.add("           name the internet cannot resolve yet answers nothing, and the "
                    + "HTTP-01");
            lines.add("           challenge is fetched over exactly that name. Port 80 has to "
                    + "reach this host too.");
            lines.add("           Nothing is lost — rerun the boot, or issue it by hand from a "
                    + "container on qits-net:");
        }
        lines.add("             quarkus tls lets-encrypt issue-certificate"
                + (mode == Acme.Mode.PRODUCTION ? "" : " --staging") + " \\");
        lines.add("               --domain=" + domain + " --email=" + email + " \\");
        lines.add("               --management-url=http://qits-platform-edge:9000");
        lines.add("           The management port is NOT published: it is unauthenticated and a "
                + "swarm publish cannot");
        lines.add("           be loopback-only, so it is reachable on qits-net and nowhere else.");
        return lines;
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
     * <b>EVERY REPOSITORY THIS PLATFORM HAS, created and given its public address in one pass.</b>
     * Creating one is a wire call: qits-githost keeps a repository as blobs in its own store, so
     * there is no volume to seed and nothing on disk to initialise. The PUT is idempotent — 201 when
     * this call created it, 200 when one was already there.
     * <p>
     * <b>Three acts per repository, and the order inside them is the whole design.</b> MINT a
     * storage id — an opaque UUID, {@link PlatformModel#seedStorageId} — then {@code PUT /git/<id>},
     * then hand qits-projects the pair through the adopt route, before the next repository is
     * touched. Adoption last of the three because that service checks the bare exists before it
     * writes a row; adoption BEFORE ANY PUSH because a name resolves only through the row, and a
     * push to a name that resolves nowhere is a 404 rather than a repository.
     * <p>
     * <b>It is the whole of what {@code register-repos} used to be, and it happens forty phases
     * earlier.</b> That phase hung off qits-projects' own deployment, sixth of seventeen, because
     * nothing before it could resolve a name — so every push of the boot's first half was
     * id-addressed and carried no name onto its event. qits-projects is a seed service since
     * 2026-08-21, so the alias table answers before the first push and {@code Boot.gitUrl} flips
     * here, once, for the rest of the run.
     * <p>
     * <b>THE ID IS RECORDED AS IT IS MINTED, NOT RE-DERIVED.</b> A UUID has nothing to derive it
     * from, so the pairing is written to {@code .qits-bootstrap.env} per repository — before the
     * next one is created — and read back at {@code recorded-state}. A run that lost it would
     * address bares it never made and leave the platform's history in the ones it had.
     * <p>
     * <b>A NAME THAT ALREADY RESOLVES IS NOT CREATED AGAIN, and that is what makes a rerun work at
     * all.</b> The deployed git host closes {@code /git/<id>} to everything but qits-projects'
     * client ({@code qits.githost.storage-client}), so this phase's own PUT is 403 there — it runs
     * before that deployment on a cold boot and against a guarded host on every rerun. Asking
     * qits-projects first costs nothing when the answer is yes, and the id it answers with is the
     * one this run then uses. A repository that is neither registered nor creatable fails here with
     * both facts named, which is the honest end of a rerun that added a repository to the model
     * after the cutover.
     */
    public Phase gitRepositories() {
        return new Phase("git-repos",
                "create the platform's repositories and register their names", ctx -> {
            // The seed stack can replace a same-tag githost task after seed-health observed the
            // previous task. Close that rollout race at the point that needs the service: every
            // PUT below is idempotent, but the first one must not be sacrificed to a brief socket
            // gap between Swarm tasks.
            boot.awaitHealth(ctx, boot.config.envName() + "-qits-githost before repository PUTs",
                    boot.githost::health);
            String token = projectsToken();
            String projectId = boot.state.projectId;
            if (projectId == null || projectId.isBlank()) {
                throw new IllegalStateException("the " + PlatformModel.PROJECT + " project has no "
                        + "id on this run — qits-project is the phase that reads it, and nothing "
                        + "can be registered without one");
            }
            BootstrapState recorded = new BootstrapState(
                    boot.state.wrapperDir.resolve(BootstrapState.FILE_NAME));
            recorded.read();
            int created = 0;
            int adopted = 0;
            for (String name : PlatformModel.platformRepos()) {
                String repo = PlatformModel.repo(name);
                String known = boot.state.repositoryIds.get(repo);
                String resolved = resolvedStorageId(projectId, repo, token);
                if (resolved != null) {
                    // qits-projects already answers for this name, so the bare it points at exists
                    // and this run must not ask the storage scheme about it. The id it names wins
                    // over anything recorded: the row is what every clone url resolves through.
                    if (known != null && !known.equals(resolved)) {
                        ctx.warn(repo + " resolves to /git/" + resolved + " and this machine had "
                                + "recorded /git/" + known + ". The row wins — every push of this "
                                + "run goes to the bare qits-projects names, and the recorded one "
                                + "is left behind holding whatever an earlier run put in it");
                    }
                    boot.state.repositoryIds.put(repo, resolved);
                    recorded.putRepositoryId(repo, resolved);
                    recorded.write();
                    adopted++;
                    continue;
                }
                String id = boot.storageId(name);
                recorded.putRepositoryId(repo, id);
                // WRITTEN BEFORE THE BARE IS MADE, not after the loop: an id this run minted and
                // did not record is a bare nothing can address again.
                recorded.write();
                ctx.status("PUT " + boot.githost.gitUrl(id));
                Http.Response response = boot.githost.createRepository(id, boot.githostToken());
                if (response.status() == 403) {
                    throw new IllegalStateException("create of " + repo + " at /git/" + id
                            + " was refused: the git host serves its storage scheme only to "
                            + "qits-projects' client now, and qits-projects holds no repository "
                            + "named " + repo + " under project " + PlatformModel.PROJECT
                            + ". Add it through qits-projects, or unwrap and bootstrap again.");
                }
                if (response.status() != 200 && response.status() != 201) {
                    throw new IllegalStateException("create of " + repo + " answered "
                            + response.describe());
                }
                created += response.status() == 201 ? 1 : 0;
                register(ctx, projectId, name, repo, id, token);
                ctx.log("  " + repo + " -> /git/" + id
                        + (response.status() == 201 ? "  (created)" : "") + ", registered");
            }
            boot.state.repositoriesRegistered = true;
            ctx.log("  every push from here on is " + boot.config.gitHostUrl() + "/" + projectId
                    + "/<repo>");
            ctx.note(created + " created, "
                    + (PlatformModel.platformRepos().size() - created - adopted)
                    + " already there, " + adopted + " already registered");
        });
    }

    /**
     * <b>Where the public clone url of one repository comes into existence.</b> The pair is what it
     * actually is — the storage id the bare lives under, and the name the platform addresses it by —
     * and qits-projects' alias table is the only place either fact is authoritative.
     * <p>
     * <b>The url is the ORG's, not the platform's.</b> What qits-projects stores on a row is the
     * forge the repository is BACKED UP to, and the wrapper's own reconcile derives exactly this
     * value by folding {@code ../<name>.git} against the wrapper's origin. Writing the same thing
     * means the two never disagree; writing a platform address would make every row name the host it
     * already lives on.
     * <p>
     * <b>A REFUSAL IS RE-ASKED AS THE QUESTION THAT MATTERS.</b> What this run owes the rest of the
     * train is that the name RESOLVES, so that is what is asked, and only an answer of no is a
     * failure. The adopt route is idempotent on the storage id, so a rerun costs one request.
     */
    private void register(PhaseContext ctx, String projectId, String name, String repo, String id,
                          String token) {
        ctx.status("registering " + repo + " (/git/" + id + ")");
        Http.Response answer = boot.projects.adoptRepository(projectId, id, repo,
                boot.config.orgUrl() + "/" + repo + ".git",
                // From where the WRAPPER puts it, so this run and qits-projects' own reconcile read
                // one fact rather than two derivations of it.
                PlatformModel.archetype(name, boot.state.wrapperPath(name)), token);
        if (!answer.ok() && resolvedStorageId(projectId, repo, token) == null) {
            throw new IllegalStateException("registering " + repo + " (/git/" + id
                    + ") under project " + projectId + " answered " + answer.describe()
                    + ". Without it /git/" + projectId + "/" + repo + " resolves nowhere, and "
                    + "every push this run has left to make is to that address.");
        }
    }

    /**
     * <b>What this repository's name resolves to, or null.</b> Asked of the same route qits-githost
     * asks on every name-addressed clone, so a value here is proof that the public url serves rather
     * than an inference from a row this run wrote.
     * <p>
     * Every failure answers null — including a service that is not there. A name that cannot be
     * resolved is a name this run is about to register anyway.
     */
    private String resolvedStorageId(String projectId, String repo, String token) {
        try {
            return storageIdIn(boot.projects.repositoryByName(projectId, repo, token));
        } catch (RuntimeException unreachable) {
            return null;
        }
    }

    /**
     * The storage id in a by-name answer, or null when the name resolves to nothing.
     * <p>
     * Kept static and pure so the shape of that answer is provable without a platform: it is the
     * one read this run makes before deciding not to create a repository, so an answer read wrongly
     * is either a bare made twice or a rerun that re-PUTs everything.
     */
    static String storageIdIn(Http.Response answer) {
        if (!answer.ok()) {
            return null;
        }
        String id = Json.text(Json.parse(answer.body()), "repositoryId");
        return id.isBlank() ? null : id;
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
                boot.push(ctx, repo + " main", src, boot.gitUrl(name),
                        List.of("qits.no-ci", "qits.token=" + boot.config.pushToken()), "main");
                ctx.log("  " + repo + " history at " + boot.git.shortRef(src, "main"));
            }
            ctx.note(PlatformModel.SEEDED_REPOS.size() + " histories");
        });
    }

    /**
     * The RELEASED artifacts, restored by re-establishing ONE SCM fact: the release tag. The
     * wrapper builds install RELEASED versions, and a clean version only ever comes from a repo's
     * ci-event-release.yml; a post-receive publish on main produces a prerelease nothing pins. That
     * file declares {@code event: SCMPublishTag}, so pushing the tag is what starts the publish run
     * — this phase pushes it and waits, and asks the platform for nothing else.
     * <p>
     * <b>It used to fabricate an SCMRelease</b> through qits-ci's manual trigger door, and that was
     * harmful rather than merely indirect. SCMRelease means "a version is NEW": qits-ci announced
     * SoftwareRelease per artifact and the release train woke up — a bump run in every consumer,
     * each ending in a release call against qits-workspaces, which does not exist yet. Only the
     * seed stack serves here; qits-workspaces is deployed by the phases below, minutes later. So
     * every bootstrap left the same red maintenance-branch runs behind. A replay has no novelty to
     * announce: the release happened once, long ago, and what a restore owes the platform is the
     * SCM state it derives its artifacts from. Announcing novelty is qits-workspaces' job, on a
     * real release.
     * <p>
     * The recipe a tag selects is read from MAIN on the git host, which is why {@code preseed}
     * pushes main before any of these phases run: a repository with no main there matches no
     * trigger file, and the tag would start nothing.
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
        return new Phase("release-" + name, "replay " + repo + "'s release tag", ctx -> {
            Path src = boot.state.repoDir(name);
            String version = boot.git.describeTag(src, "main");
            if (version.isBlank()) {
                throw new IllegalStateException(repo + " has no release tag reachable from main "
                        + "— nothing to replay");
            }
            ctx.log("  release tag " + version);
            // A replay whose run already went green has nothing left to publish, and re-running it
            // is not free: an image publisher rebuilds for half an hour. The published state is
            // what this phase exists to restore; already restored is a skip, not a rerun. The
            // question is asked by SHA, not version: the version lives on the trigger event and
            // the run row does not carry it. The sha is MAIN'S HEAD, not the tag's commit — an
            // event-triggered run is cloned at the head of main and recorded there (its script
            // checks the tag out itself, invisibly to the run row); matched against the tag's
            // commit, a repository whose main had moved past its release waited a full budget on a
            // run that had already succeeded — the second bus-only proving run, phase 36. The
            // CONFIG PATH is what keeps a follow-up bump run (also at main's head) from answering
            // for a release.
            // MAIN AS A BRANCH, never as HEAD: a restoring boot leaves this checkout detached at
            // its release tag, and what ci records on an event run is the head of MAIN as the git
            // host has it — which is the branch this run pushed, not the commit checked out here.
            String mainSha = boot.git.commitOf(src, "main");
            // BY STORAGE ID, not by name: a ci run row is keyed by the id the announcing push
            // addressed, which is the git host's and not the platform's public one.
            String storageId = boot.storageId(name);
            if (!mainSha.isBlank() && boot.ci.greenReleaseRunAt(storageId, mainSha)) {
                ctx.skip("release " + version + " already ran green — the registry holds what "
                        + "this replay publishes");
            }
            // The newest finished run BEFORE the tag lands is a previous attempt's, and must not be
            // read as this one's outcome. Null when the repo never ran.
            String baselineRun = boot.ci.finishedEventRun(storageId).map(r -> r[0]).orElse(null);
            // THE PUSH IS THE WHOLE TRIGGER. qits-githost turns every ACCEPTED ref of a push into
            // an event, so this one tag ref becomes one SCMPublishTag, which is the event the
            // release recipe declares. `qits.no-ci` stays and suppresses nothing here: it is a
            // fact on the COMMIT event and this push moves no branch — one refspec, one tag.
            ProcessResult push = boot.push(ctx, repo + " " + version, src,
                    boot.gitUrl(name),
                    List.of("qits.no-ci", "qits.token=" + boot.config.pushToken()),
                    "refs/tags/" + version);
            if (upToDate(push)) {
                // The tag is already here, so no ref moved, so nothing was announced and no run is
                // coming — and there is no second door to knock on, which is the design rather
                // than a gap: a restore re-establishes SCM state, and this state stands. The
                // registry holds this version from the boot whose push first announced it.
                //
                // If THAT run went red the pin is missing, and the deployable that pins it says so
                // a few phases later. Asking for the publish again is a person's move then, and
                // both doors are theirs: qits-ci's manual trigger, or deleting the tag on the git
                // host and pushing it again.
                ctx.skip(version + " is already on the git host — no ref moved, nothing announced");
            }
            ctx.log("  " + version + " pushed — the tag is what starts the release run");

            // The same relay the deploy wait uses. A release run is a build too, and it was as
            // silent as the other one.
            CiLogStream ciLog = new CiLogStream(boot.ci, ctx);
            // The run is waited for by ROW, not by anything this phase was handed: the push
            // returns as soon as the git host has the tag, and the event, the trigger evaluation
            // and the run all happen behind it.
            //
            // "An EVENT run of this repository" is not "the release run": an upstream's
            // SoftwareRelease fires this repository's own follow-up bump, also an EVENT run — a
            // 1-second quiet-exit that landed NEWEST during the first bus-only bootstrap and hid
            // the wait's real target. The release run is the one that EXECUTED the release
            // pipeline file, at main's head — where an event-triggered run is cloned and recorded;
            // the tag checkout is its script's own business. The config path is the fact that
            // identifies it, and it stays the one to ask by: every event run records main's head,
            // so the sha collides, and the trigger name is no protection either — it collided
            // outright while releases were SCMRelease-fired, and a recipe is free to move to
            // another event again.
            String status = Waiter.await(ctx, repo + "'s " + version + " release run",
                    boot.config.releaseTimeout(),
                    boot.config.pollInterval(), () -> {
                        boot.ci.newestRun(storageId)
                                .map(run -> Json.text(run, "id"))
                                .filter(id -> !id.equals(baselineRun))
                                .ifPresent(ciLog::follow);
                        for (String[] run : boot.ci.finishedEventRuns(storageId)) {
                            if (!run[0].equals(baselineRun)
                                    && eu.wohlben.qits.cli.bootstrap.api.CiApi.RELEASE_CONFIG
                                            .equals(run[2])
                                    && mainSha.equals(run[3])) {
                                return Waiter.Poll.done(run[1], run[1]);
                            }
                        }
                        return Waiter.Poll.pending("no finished release run for " + version
                                + " yet");
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
     * One standing environment — the one {@code --platform-env} names: the shared network and the
     * PLATFORM flag. NO applications array; registration is derived from the repository's
     * deployments.yml when a release enters.
     * <p>
     * <b>And no branch, because there is no such column any more.</b> An environment used to listen
     * to {@code environment/<name>} and a green build of that ref deployed into it. The entry is a
     * released VERSION now, so the deployer's entry tier is simply the row carrying the flag — the
     * branch is gone from the table, from the create body and from the update body, and an older
     * sender's is ignored rather than refused. Sending one would therefore be silent and mean
     * nothing.
     * <p>
     * The flag is not decoration; it is the whole of what makes this environment deploy anything.
     * There is exactly one, every release enters at it, and the platform plane is deployed into it
     * too — a platform service has a tier now, and only its wire alias stays bare.
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
            Optional<String> existing = boot.pd.environmentId(name);
            if (existing.isPresent()) {
                // Re-asserts the platform flag, which is what carries a platform bootstrapped
                // before the flag existed onto it. Designating is a move on the deployer's side, so
                // asserting it on the row that already holds it changes nothing.
                patch(existing.get(), Json.object("platform", Json.verbatim("true")));
                boot.state.environmentId = existing.get();
                ctx.log("  reconciled the existing '" + name + "' environment");
            } else {
                refuseIfAnotherEnvironmentIsThePlatformOne(name);
                refuseIfAProjectAlreadyHasThisName(ctx, name);
                Http.Response created = boot.pd.createEnvironment(name, Boot.NETWORK,
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
                    patch(id, Json.object("platform", Json.verbatim("true")));
                    boot.state.environmentId = id;
                } else {
                    throw new IllegalStateException("environment create answered "
                            + created.describe());
                }
            }
            ctx.log("  environment " + name + " (" + boot.state.environmentId + ") — network "
                    + Boot.NETWORK + ", the platform environment: every release enters here");
            ctx.note(name);
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
                    "The platform environment here is '" + standing
                            + "', and this boot asks for '"
                            + wanted + "'. Renaming it would leave every running container on "
                            + standing + "-qits-* aliases and orphan the recorded IDP_SECRET_"
                            + PlatformModel.clientKey(standing)
                            + "_* entries in .qits-bootstrap.env. Run `qits unwrap` first, or "
                            + "bootstrap with --platform-env " + standing + ".");
        });
    }

    /**
     * <b>A project already answers to this name: STOP.</b>
     * <p>
     * An environment name and a project slug are read at the same place. The edge takes at most the
     * first two labels of a Host header, and position 1 is an ENVIRONMENT if it is one — so
     * {@code editor.<project>.<domain>}, the web editor's origin, is the same shape as
     * {@code <app>.<env>.<domain>}. Bootstrapping an environment called after an existing project
     * makes every one of that project's own names read as that tier's: its editor is served out of
     * the wrong environment, and the tie-break says so before any app label is even considered.
     * <p>
     * <b>The other direction is closed at the source.</b> qits-projects is handed the environment
     * names as {@code QITS_PROJECTS_RESERVED_SLUGS} and refuses them, so a project cannot be
     * created into this collision. This is what closes the direction that service cannot see: an
     * environment named after a project that is already there.
     * <p>
     * <b>It refuses rather than renames</b>, for the same reason the platform-environment check
     * beside it does — the name is inside every wire alias, every container name and every recorded
     * idp secret key, and none of that is repaired here.
     * <p>
     * <b>On the CREATE arm only, and that is the same line rerun-safety is always drawn on.</b> An
     * environment row that already stands is a platform that is already running under that name;
     * refusing its rerun would strand it rather than repair anything. What this stops is a NEW
     * environment being made into the collision, which is the only moment the choice is still open.
     * <p>
     * <b>A listing that does not answer blocks nothing.</b> The read is one line of evidence, not a
     * gate the boot has to pass through: qits-projects has answered several phases earlier for this
     * run to have got here at all, and refusing a boot because a report-grade read timed out would
     * be a worse failure than the one being prevented.
     */
    private void refuseIfAProjectAlreadyHasThisName(PhaseContext ctx, String wanted) {
        List<String> slugs = projectSlugs();
        if (slugs.isEmpty()) {
            ctx.log("  no project list was read, so the slug collision check is skipped");
            return;
        }
        environmentNameRefusal(wanted, slugs).ifPresent(refusal -> {
            throw new IllegalArgumentException(refusal);
        });
    }

    /**
     * The refusal an environment name earns from the project slugs, or empty. Pure so the rule is
     * provable without a platform.
     */
    static Optional<String> environmentNameRefusal(String wanted, List<String> projectSlugs) {
        String name = wanted.strip().toLowerCase(Locale.ROOT);
        return projectSlugs.stream()
                .filter(slug -> slug.strip().toLowerCase(Locale.ROOT).equals(name))
                .findFirst()
                .map(slug -> "A project on this platform is already called '" + slug + "', and "
                        + "this boot asks for an environment of the same name. They are read at the "
                        + "same place: the edge takes the first two labels of a host, and the label "
                        + "in front is an APPLICATION when the one behind it is an environment — so "
                        + "editor." + slug + ".<domain>, that project's own web editor, would be "
                        + "read as the editor of the '" + name + "' tier instead. Bootstrap with "
                        + "--platform-env under another name, or rename the project first.");
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
     * Sequential on purpose: each release build is a cold native build on the host daemon, and a
     * workstation rarely wants eight at once.
     * <p>
     * <b>TWO PUSHES AND ONE WORD, and the word is what deploys.</b> The third push is gone with the
     * ref it moved: {@code environment/<name>} is not a branch anything listens to any more, so
     * there is nothing to point at a commit. What a deployment comes from now is a RELEASE —
     * qits-deployments enters a deployment request from a {@code SoftwareRelease} and pulls
     * {@code qits/<app>:<version>} — so this phase re-establishes the two SCM facts a release is
     * made of and then asks for the release build:
     * <ol>
     *   <li><b>main, quietly.</b> The trunk, and the tree qits-ci discovers a trigger file in. It
     *       goes up with {@code -o qits.no-ci} because the option is a FACT on the push event and
     *       qits-ci's listener honours it; without it every boot would queue a build of a commit
     *       nothing deploys.
     *   <li><b>the newest release tag, quietly.</b> The stamp that says this commit is that
     *       release. It starts nothing by itself: a deployable's release recipe selects
     *       {@code SCMRelease}, not {@code SCMPublishTag}.
     *   <li><b>the {@code SCMRelease} itself</b>, through qits-ci's manual door, which runs that
     *       recipe: it checks the tag out, builds and pushes {@code qits/<app>:<version>}, and its
     *       green run announces the {@code SoftwareRelease} the deployer is waiting for.
     * </ol>
     * <b>A boot still RESTORES, and the restore is now the version rather than a ref.</b> The
     * platform comes back as its last released self because the version this phase names is the
     * newest release tag reachable from main — the same tag the {@code sources} phase already stood
     * the checkout at, by construction, so the seed container this deployment replaces was built
     * from the tree its successor is built from. A seed that applied a migration its successor has
     * never heard of leaves that successor crash-looping on Flyway validation.
     * <p>
     * <b>An unreleased main cannot deploy at all now, and {@code --ship-mains} is ignored.</b>
     * There is no sha-addressed image and no ref to point at one; the only coordinate the deployer
     * takes is a version, and versions are minted by the release flow in qits-projects. Local mains
     * are still pushed — that is what the flag always did first, and it is now what every boot
     * does — while the {@code sources} phase warns that the flag itself changes nothing. The
     * 2026-08-08 accident it was invented against cannot happen for a stronger reason than a flag:
     * unreleased code has no coordinate.
     */
    public Phase deploy(String name) {
        String repo = PlatformModel.repo(name);
        // THE DEPLOYER'S OWN KEY, and it is not the repository's name. qits-ci is built from
        // qits-ci-service, the row is written under the application, and the image is
        // qits/qits-ci — so a lookup by repository name matches no row and waits out its timeout.
        String application = PlatformModel.application(name);
        return new Phase("deploy-" + name, repo + ": push -> release build -> deploy", ctx -> {
            Path src = boot.state.repoDir(name);

            // BEFORE ANYTHING IS ANNOUNCED, and that is the whole point of where this line sits. A
            // terminal row carrying the baseline id belongs to an earlier run, not this one — but
            // the announcement below is what creates this one, and the deployer can write its row
            // before the read returns. Captured after, the baseline is this phase's OWN deployment:
            // every later poll calls it stale and the wait never ends on it. Found by the
            // 2026-08-07 teardown run, where qits-projects did exactly that.
            String baselineRowId = boot.pd.newestDeployment(boot.state.environmentId, application)
                    .map(r -> Json.text(r, "id")).orElse(null);
            // The run rows of this repository are keyed by its STORAGE id — what a push announced —
            // never by the name the deployer keys its own rows by.
            String storageId = boot.storageId(name);

            ctx.status("pushing " + repo + " to main (quietly)");
            // THE BRANCH, NOT HEAD. A restoring boot leaves the checkout detached at the release
            // tag, so HEAD:refs/heads/main would push the RELEASE onto main — a rewind of the trunk
            // on the git host, refused as a non-fast-forward and wrong even where it was accepted.
            // The trunk is the local main branch and goes up as itself.
            boot.push(ctx, repo + " to main", src, boot.gitUrl(name),
                    List.of("qits.no-ci", "qits.token=" + boot.config.pushToken()),
                    "main:refs/heads/main");

            // WHICH VERSION DEPLOYS, read after main is up so the commit it names is already in the
            // store. A fact about the checkout, which an earlier phase of this same run refreshed.
            //
            // THE NEWEST RELEASE, NOT THE NEAREST TAG, and the two are different questions: a
            // release cut on an older commit and tagged later is nearer in history while being
            // older. It is asked exactly as the `sources` phase asks it — one function,
            // PlatformModel.newestRelease over the version-sorted merged tags — because that phase
            // stood this checkout at a tag and built the seed image from it. A deployment of
            // another version is a successor whose Flyway lineage its own seed has already run
            // past.
            String version = PlatformModel.newestRelease(boot.git.tagsNewestFirst(src, "main"));
            if (version.isBlank()) {
                // Not a failed boot: the applications behind this one still deserve their turn, and
                // this is a fact about the repository rather than a contradiction in the platform.
                ctx.warn(repo + " has no release tag reachable from main, so there is no version to"
                        + " deploy — a deployment is qits/" + application + ":<version> and nobody"
                        + " has minted one. Cut a release through qits-projects and rerun");
                ctx.note("no release to deploy");
                return;
            }
            ctx.log("  " + repo + " restores " + version);
            // MAIN'S HEAD, because that is where an event-triggered run is cloned and recorded —
            // the tag checkout is the release recipe's own business, invisible to the run row. Read
            // as the BRANCH, never as HEAD: this checkout stands at the release tag.
            String mainSha = boot.git.commitOf(src, "main");

            // ONE TAG, THE NEWEST, and that is a trade: pushing every tag would restore the whole
            // release history in one go, at one bus event and one full candidate sweep per tag
            // across every repository qits-ci knows. The older tags restore nothing this boot needs.
            ctx.status("pushing " + repo + " " + version);
            boot.push(ctx, repo + " " + version, src, boot.gitUrl(name),
                    List.of("qits.no-ci", "qits.token=" + boot.config.pushToken()),
                    "refs/tags/" + version);

            if ("platform-edge".equals(name)) {
                // Both edges need the same host ports. Release them only after the real edge's
                // source is safely in githost, but before its service is created. The cutover is
                // intentionally a short closed interval, never two authorities.
                boot.ingress.stop(ctx::log);
            }

            if (alreadyLive(ctx, name, application, version)) {
                ctx.note("already live at " + version);
                return;
            }

            // ASK FOR THE BUILD, OR ASK FOR THE DEPLOYMENT — never both, and the question that
            // decides is whether the release pipeline has already run green at this commit. Green
            // means the image is published and the announcement was made to a platform that has
            // since been rebuilt; what is missing then is the deployment, not the build.
            String runId = null;
            if (!mainSha.isBlank() && boot.ci.greenReleaseRunAt(storageId, mainSha)) {
                ctx.log("  " + repo + "'s release run at " + SeedPhases.shortSha(mainSha)
                        + " is already green — qits/" + application + ":" + version
                        + " is published, so this asks the deployer for it directly");
            } else {
                runId = announceRelease(ctx, repo, application, version);
            }
            if (runId == null) {
                postSoftwareReleased(ctx, name, application, version);
            }
            awaitDeployment(ctx, name, application, version, runId, mainSha, baselineRowId);
        });
    }

    /**
     * <b>The one word that starts a release build: an {@code SCMRelease} for this repository at the
     * version it is standing at, through qits-ci's manual door.</b> Answers the run it started, or
     * null when no recipe selected it.
     * <p>
     * <b>Why the bring-up says it at all.</b> On a live platform qits-projects publishes this event
     * when it creates a release tag, and everything downstream follows. A bootstrap has no release
     * to cut — it restores tags that were minted long ago — but the artifact the restore needs is
     * an IMAGE at the released version, and the release recipe is the only pipeline that publishes
     * one. So this hands qits-ci the event the recipe selects and nothing else: no new tag, no new
     * version, no release request. The RELEASE REPLAYS beside this phase still say nothing —
     * their recipes select {@code SCMPublishTag}, so their push is the whole trigger.
     * <p>
     * <b>Both spellings of the name, because a real one carries both.</b> {@code repository} is the
     * row id and {@code repositoryName} the stable name, and a trigger file may match either; a
     * condition on the id path is evaluated against the name field whenever the payload has one.
     * The replay that omitted the second evaluated clean against nineteen repositories and matched
     * none — 200 with an empty run list, measured, third bus-only proving run.
     * <p>
     * <b>A refusal stops the boot.</b> The trigger evaluates on the request thread, so 200 means
     * the runs exist as the call returns and 503 means nothing was accepted — qits-ci's own
     * contract, written so a retry loses nothing. Three tries for the one measured refusal cause, a
     * candidate read meeting a service mid-cutover. Past that it is a misconfiguration, and
     * discovering it eighteen times over eighteen timeouts is an hour each; the boot stops with
     * qits-ci's own words instead.
     */
    private String announceRelease(PhaseContext ctx, String repo, String application,
            String version) {
        String event = releaseEvent(repo, version);
        // The door demands the one project=* client — the same identity the git host announces
        // pushes with, because this stands in for an announcement the platform makes itself.
        String token = boot.tokenOrNull(
                PlatformModel.wireAlias("artifacts", boot.config.envName()),
                PlatformModel.wireAlias("ci", boot.config.envName()));
        ctx.status("announcing " + repo + " " + version + " to qits-ci");
        Http.Response answer = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            answer = boot.ci.trigger(event, token);
            if (answer.ok()) {
                break;
            }
            ctx.log("  release announcement attempt " + attempt + " refused: " + answer.describe());
            sleep(5000);
        }
        if (answer == null || !answer.ok()) {
            throw new IllegalStateException("the SCMRelease for " + repo + " " + version
                    + " was refused by qits-ci: "
                    + (answer == null ? "no answer" : answer.describe()));
        }
        List<String> runs = CiApi.triggeredRunIds(answer);
        if (runs.isEmpty()) {
            // No release recipe selected it, so nothing is going to publish the image. Said once
            // and loudly: the deployer is still asked below, because a version this platform
            // already holds deploys perfectly well, and an IMAGE_MISSING row minutes from now is a
            // better answer than an hour of waiting for a build nobody started.
            ctx.warn(repo + " has no release pipeline that selects its own SCMRelease, so nothing"
                    + " will publish qits/" + application + ":" + version
                    + ". Asking the deployer for the version the registry may already hold");
            return null;
        }
        ctx.log("  release run " + String.join(", ", runs) + " started — a cold native build, be "
                + "patient");
        return runs.get(0);
    }

    /**
     * The event itself, built where it can be read without a platform: this is the one announcement
     * this program makes that another service's committed trigger file has to select, so the shape
     * is worth pinning.
     * <p>
     * <b>No branch.</b> A real one carries none either — a release is a tag, and the request's
     * backing branch is deleted in the same operation that creates that tag, so a branch here would
     * name a ref that no longer exists.
     */
    static String releaseEvent(String repo, String version) {
        return "{\"name\":\"SCMRelease\",\"payload\":{"
                + "\"repository\":" + Json.quote(repo) + ","
                + "\"repositoryName\":" + Json.quote(repo) + ","
                + "\"version\":" + Json.quote(version) + "}}";
    }

    /**
     * "Is it live at this version?" has two answers, because the two planes are read in two places.
     * An environment service has a deployment row under this tier. A platform service has one too
     * since the plane was given a real environment — but its container is the stronger evidence and
     * the cheaper one: the deployer names it qits-pd-&lt;app&gt;-&lt;id8&gt;, with the tier segment
     * dropped rather than filled, and runs the image tagged with the version it deployed.
     */
    private boolean alreadyLive(PhaseContext ctx, String name, String application, String version) {
        if (PlatformModel.isPlatformService(name)) {
            Optional<String> live = platformContainer(name, version);
            if (live.isPresent()) {
                ctx.log("  " + application + " already live at " + version
                        + " (" + live.get() + ")");
                return true;
            }
            return false;
        }
        Optional<JsonNode> row = boot.pd.newestDeployment(boot.state.environmentId, application);
        if (row.isPresent() && "ACTIVE".equals(Json.text(row.get(), "status"))
                && version.equals(Json.text(row.get(), "version"))) {
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
                ctx.log("  " + application + " already ACTIVE at " + version);
                return true;
            }
            ctx.log("  " + application + " has an ACTIVE row at " + version
                    + " but its container is gone — redeploying");
        }
        return false;
    }

    /**
     * The container name of a platform service running this version, once docker says it is
     * serving. The health check is this port's one addition to the script: a running/unhealthy
     * container counted as live once, and the rollback that followed looked like a success.
     */
    private Optional<String> platformContainer(String name, String version) {
        return platformContainerAt(name, version).filter(container -> serving(container[1]))
                .map(container -> container[0]);
    }

    /**
     * The container of a platform service at this version as {name, docker's status text} — serving
     * or not, and preferring one that is.
     * <p>
     * The status travels with the name because whether the wait is over is one question and what to
     * SAY while it is not is another. "no healthy container at this version" never mentioned that
     * the container was there and restarting, so the operator could not tell a build that had not
     * finished from one that had crash-looped.
     */
    private Optional<String[]> platformContainerAt(String name, String version) {
        String prefix = PlatformModel.pdNamePrefix(name, boot.config.envName());
        // The swarm driver deploys a SERVICE under the wire alias, and swarm names its task
        // containers <service>.<slot>.<taskid> — no qits-pd- prefix anywhere. Matching only the
        // docker driver's names left this wait blind while the deployer's log said "Deployed …
        // into the platform": measured on the third flip boot, 44 minutes staring at a healthy
        // healthy task.
        String alias = PlatformModel.wireAlias(name, boot.config.envName());
        String[] notServing = null;
        // A pipe separator, deliberately: the process pipeline strips control characters — tabs
        // become spaces before a line reaches this loop (Ansi.clean), so a tab-separated format
        // parses as one field and no container ever matches. Found by the v3 proving run.
        for (String line : boot.docker.ps("{{.Names}}|{{.Image}}|{{.Status}}")) {
            String[] parts = line.split("\\|");
            // The seed stack owns the very first deployer service, so its swarm tasks carry the
            // stack prefix: qits_<alias>.<slot>.<taskid>. A self-update keeps that name — the
            // driver replaces the service in place — so the platform shape has THREE spellings.
            if (parts.length < 3
                    || !(parts[0].startsWith(prefix)
                            || parts[0].startsWith(alias + ".")
                            || parts[0].startsWith("qits_" + alias + "."))) {
                continue;
            }
            // A swarm task's image carries the manifest digest after the tag; the version this wait
            // matches is the TAG.
            String image = parts[1];
            int digest = image.indexOf('@');
            if (digest >= 0) {
                image = image.substring(0, digest);
            }
            if (!image.endsWith(":" + version)) {
                continue;
            }
            String[] container = {parts[0], parts[2]};
            if (serving(container[1])) {
                return Optional.of(container);
            }
            // A cutover has two containers at one version for a moment; keep looking past the one
            // that is not serving, and remember it in case none of them is.
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

    /**
     * Did this push move NOTHING? Two phases ask it and both act on the answer, so it is one
     * function: a deploy push that moved nothing announced no build, and a tag push that moved
     * nothing announced no release.
     * <p>
     * git spells it "Everything up-to-date" — hyphens. Matching only the spaced form meant no event
     * was ever posted for an unchanged repo: environment applications were rescued by the stale-row
     * replay, and a platform service hung for its whole timeout on an event nobody sent. Found by
     * the tenth proving run. Match both spellings, and read the TAIL as well as the captured head:
     * git says it last, and a long push fills the capture.
     */
    static boolean upToDate(ProcessResult push) {
        String text = (push.tailText(50) + "\n" + push.out()).toLowerCase(Locale.ROOT);
        return text.contains("up to date") || text.contains("up-to-date");
    }

    /**
     * WHAT A DEPLOYMENT ROW AT THE PUSHED SHA SAYS, or null while it says nothing yet and the wait
     * goes on. Kept pure so the deployer's status vocabulary can be read without a deployer.
     * <p>
     * The shape of the answer carries its meaning: the wait notes an outcome that starts with
     * ACTIVE and WARNS about every other one, so a terminal row reads
     * {@code DEPLOY <status>: <detail>} and ends the boot's wait as a warning. That is this
     * program's posture — a deployment that did not land does not abandon the applications behind
     * it — and it is the same answer for all five terminal words.
     */
    static String deploymentVerdict(String status, String containerName, String detail) {
        if ("ACTIVE".equals(status)) {
            return "ACTIVE " + containerName;
        }
        if (TERMINAL_DEPLOYMENT_STATUSES.contains(status)) {
            return "DEPLOY " + status + ": " + (detail.isBlank() ? "no detail" : detail);
        }
        return null;
    }

    /**
     * The wait: the release build, then the deployment it announces.
     * <p>
     * <b>{@code runId} is the run this phase started, or null when it started none</b> — the
     * already-green arm and the no-recipe arm both hand the deployer the release directly and have
     * no build to watch. A known id is a better question than the old baseline dance: it is read
     * whole ({@code GET /ci/api/runs/{id}} carries the status beside the steps), so a repository
     * holding several runs at one commit cannot answer for another.
     * <p>
     * {@code runSha} is main's head, and it is only for the operator's line: an event-triggered run
     * is cloned and recorded there while the release recipe checks the tag out itself.
     */
    private void awaitDeployment(PhaseContext ctx, String name, String application, String version,
            String runId, String runSha, String baselineRowId) {
        boolean platformService = PlatformModel.isPlatformService(name);
        CiLogStream ciLog = new CiLogStream(boot.ci, ctx);
        DeployLogStream pdLog = new DeployLogStream(boot.docker, ctx, application,
                PlatformModel.wireAlias("deployments", boot.config.envName()),
                PlatformModel.pdNamePrefix("deployments", boot.config.envName()));
        long[] greenForMillis = {0};
        boolean[] replayed = {false};
        long interval = boot.config.pollInterval().toMillis();
        String target = platformService
                ? "a container named " + PlatformModel.pdNamePrefix(name, boot.config.envName())
                + "* running :" + version + " and serving"
                : "a deployment row for " + application + " at " + version + " in "
                + boot.config.platformDeploymentsUrl() + "/api/deployments";
        try {
            String outcome = Waiter.await(ctx, target, boot.config.deployTimeout(),
                    boot.config.pollInterval(), () -> {
                        // The deployer's account of this application, before the verdict is read, so
                        // "what is it doing" is answered in the same breath as "is it done".
                        pdLog.follow();
                        String deploymentState = "no row yet";
                        if (platformService) {
                            Optional<String[]> container = platformContainerAt(name, version);
                            if (container.isPresent() && serving(container.get()[1])) {
                                return Waiter.Poll.done("ACTIVE " + container.get()[0], "live");
                            }
                            deploymentState = container
                                    .map(found -> found[0] + " is " + found[1])
                                    .orElse("no container at this version");
                        } else {
                            Optional<JsonNode> row = boot.pd.newestDeployment(
                                    boot.state.environmentId, application);
                            if (row.isPresent()) {
                                String status = Json.text(row.get(), "status");
                                deploymentState = status.isBlank() ? "PENDING" : status;
                                boolean stale = Json.text(row.get(), "id").equals(baselineRowId);
                                if (version.equals(Json.text(row.get(), "version")) && !stale) {
                                    String verdict = deploymentVerdict(status,
                                            Json.text(row.get(), "containerName"),
                                            Json.text(row.get(), "detail"));
                                    if (verdict != null) {
                                        return Waiter.Poll.done(verdict, status);
                                    }
                                }
                                if (stale) {
                                    // Whatever the word is. A stale row is tolerated by its ID, not
                                    // by its status, so every terminal status gets the same
                                    // "an earlier run left this" reading and none of them ends this
                                    // phase's wait.
                                    deploymentState = "stale " + deploymentState
                                            + " row from an earlier run";
                                }
                            }
                        }
                        // Read the build out loud while it runs, and read its status from the same
                        // row. The relay reads the run on this same interval and turns itself off
                        // rather than failing; a run this phase did not start is not followed at
                        // all, because an earlier one's log says nothing about what is waited for.
                        ciLog.follow(runId);
                        String runStatus = runId == null ? ""
                                : boot.ci.run(runId).map(r -> Json.text(r, "status")).orElse("");
                        if (isRedRunStatus(runStatus)) {
                            boot.ci.failedStepOutput(runId).ifPresent(output -> {
                                ctx.log("  the step that stopped the run said:");
                                output.lines().forEach(line -> ctx.log("    " + line));
                            });
                            return Waiter.Poll.done("RELEASE BUILD " + runStatus, runStatus);
                        }
                        // <b>The green run's own announcement can still be lost.</b> qits-ci
                        // publishes SoftwareRelease when the run closes, and the deployer's
                        // subscriber is durable — but the deployer is an application THIS BOOT
                        // redeploys, and a subscriber that is mid-cutover when the frame is offered
                        // catches up on its own sweep rather than at once. So a green run with no
                        // row a minute later gets the release handed over by hand, ONCE. One more
                        // attempt, never a retry loop. Environment services only: a platform
                        // service is read through its container, which only turns true once the
                        // cutover has finished, and that alone can outlast a minute.
                        if ("SUCCESS".equals(runStatus) && !platformService) {
                            greenForMillis[0] += interval;
                            // A stale terminal row means the deployer consumed this version's
                            // announcement long ago and will never act unprompted — hand it over at
                            // once, not after a minute.
                            boolean staleTerminal = boot.pd.newestDeployment(
                                            boot.state.environmentId, application)
                                    .map(r -> Json.text(r, "id").equals(baselineRowId))
                                    .orElse(false);
                            if ((greenForMillis[0] >= 60_000 || staleTerminal) && !replayed[0]) {
                                ctx.warn(application + "'s release build is green but no deployment"
                                        + " appeared — handing the deployer the release");
                                postSoftwareReleased(ctx, name, application, version);
                                replayed[0] = true;
                            }
                        }
                        return Waiter.Poll.pending("release build "
                                + (runId == null ? "not this phase's"
                                        : runStatus.isBlank() ? "starting" : runStatus)
                                + (runSha.isBlank() ? "" : " at " + SeedPhases.shortSha(runSha))
                                + ", deployment " + deploymentState);
                    });
            if (outcome.startsWith("ACTIVE")) {
                ctx.log("  " + application + " " + outcome);
                ctx.note(outcome);
            } else {
                ctx.warn(application + " " + outcome);
            }
        } catch (TimeoutException e) {
            // The script's posture: a deployment that never lands is a warning on an otherwise
            // finished boot, not a reason to abandon the applications behind it.
            ctx.warn(application + ": no terminal deployment after "
                    + boot.config.deployTimeout().toSeconds() + "s (the release build may still be "
                    + "running — watch docker ps and docker logs qits-ci)");
        } catch (Exception e) {
            throw new IllegalStateException("waiting for " + application + " failed: " + e, e);
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
     * <b>Hands the deployer the release, by hand.</b> Two callers and one meaning: the version is
     * published and there is no deployment of it — either because nothing ran (the image is
     * already in the registry from an earlier boot) or because the announcement of a run that did
     * go green never reached the deployer's subscriber.
     * <p>
     * Retried: the edge this public call travels through is an application this run deploys, so a
     * call can meet it mid-cutover.
     * <p>
     * <b>The public pair goes with it whenever this run holds it.</b> The deployer reads the
     * repository's {@code deployments.yml} to deploy at all, and with {@code (projectId, repoName)}
     * it reads it name-addressed; without, it falls back to the storage scheme, which the deployed
     * git host serves to qits-projects' client alone. This run has minted and recorded the pair for
     * every repository, so it sends it.
     */
    private void postSoftwareReleased(PhaseContext ctx, String name, String application,
            String version) {
        String repo = PlatformModel.repo(name);
        String projectId = boot.state.projectId;
        Http.Response last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                // The ci client, addressed to the deployer: this stands in for the announcement
                // qits-ci makes on a green release run, and a token that says qits-ci is exactly
                // that. Both are wire aliases — the audience is the deployer's own id, which is why
                // the idp's ci client is granted it in the seed.
                String token = boot.tokenOrNull(PlatformModel.wireAlias("ci", boot.config.envName()),
                        PlatformModel.wireAlias("deployments", boot.config.envName()));
                last = boot.pd.softwareReleased(boot.storageId(name), projectId, repo, application,
                        version, token);
                if (last.ok()) {
                    ctx.log("  software-released posted for " + application + " " + version);
                    return;
                }
            } catch (RuntimeException e) {
                ctx.log("  release hand-over attempt " + attempt + " failed: " + e.getMessage());
            }
            sleep(5000);
        }
        ctx.warn("could not hand the deployer " + application + " " + version
                + (last == null ? "" : ": " + last.describe()));
    }

    /**
     * A ci run that ended red. Three words and qits-ci writes all three: {@code FAILED} is a step
     * that returned non-zero, {@code CONFIG_ERROR} a recipe that could not be read, and
     * {@code TIMED_OUT} a step aborted at its deadline. A word missing from here reads as "still
     * running", so the wait sits on a finished run for its whole timeout.
     */
    static boolean isRedRunStatus(String status) {
        return "FAILED".equals(status)
                || "CONFIG_ERROR".equals(status)
                || "TIMED_OUT".equals(status);
    }

    // THE PUSH RE-ANNOUNCEMENT IS GONE, and the rule it served is not.
    //
    // "An announcement the platform makes once, this program re-makes once" had two halves. The
    // RELEASE half survives above, as postSoftwareReleased: qits-ci's green-run notice travels the
    // bus and the deployer keeps an HTTP intake for a caller to hand it the release by hand. What
    // is re-made there is a SoftwareRelease and never a build: /events/build-succeeded is gone
    // from the deployer, and a call to it would be a call to nothing.
    //
    // The PUSH half is retired, because there is no longer an announcement to lose: qits-githost
    // writes SCMPublishCommit to its outbox inside the push's own transaction, and qits-ci's
    // durable listener reads it back after any outage of its own. POST /ci/api/events/post-receive
    // is gone from that service too. A push starts nothing this boot waits for in any case — every
    // push this program makes carries -o qits.no-ci or moves a tag.

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
                ProcessResult result = boot.push(ctx, repo + " main", src,
                        boot.gitUrl(name),
                        List.of("qits.token=" + boot.config.pushToken()), "main");
                // The BRANCH's sha, like the refspec above: this checkout may stand at a release
                // tag, and what went up is main.
                ctx.log("  " + repo + (upToDate(result) ? " already at main"
                        : " pushed " + boot.git.shortRef(src, "main")));
            }
            ctx.note(PlatformModel.SEEDED_REPOS.size() + " repositories");
        });
    }

    // --- deployment configuration as platform state -------------------------------------------------

    /**
     * The application the two phases below hang off, and the reason its position in
     * {@link PlatformModel#DEPLOYABLES} is not a preference: everything deployed after it is
     * deployed from what it serves.
     */
    public static final String CONFIGURATION = "configuration";

    /**
     * The application that owns the alias table — a SEED service since 2026-08-21, which is what
     * lets {@code git-repos} give every repository its public address before the first push.
     * Spelled here because two phases name the same service for two different reasons: the seed
     * one's self-seed is released by {@code qits-project}, and its own deployment is still where
     * the successor takes the alias table over.
     */
    public static final String PROJECTS = "projects";

    /**
     * <b>The same bytes, in the service.</b> This imports the extras this boot rendered — the whole
     * properties file, comments and all — into qits-configuration, so the deployer can be told to
     * read its configuration from there instead of from the file on its config volume.
     * <p>
     * <b>The file stays exactly as it is.</b> It is the cold-boot source — the seed deployer runs
     * from it for every deployment before this phase — and it remains the fallback for a platform
     * that never flips. Nothing here rewrites, filters or re-spells it: what is imported is what
     * {@code pd-extras} wrote, rendered from the same tokens, so the two cannot disagree.
     * <p>
     * <b>Idempotent, so a rerun costs one request.</b> A line whose value is already stored writes
     * no revision, which is what keeps the history a record of changes rather than of boots.
     * <p>
     * <b>The identity is asserted on a private hop</b>, the way this program talks to the deployer's
     * read API: {@code X-Qits-User} and {@code X-Qits-Roles} on qits-net, at the service's own alias.
     * Deliberately not through the edge — it strips client-supplied identity headers from what it
     * proxies, which is the property that makes it safe as a public door.
     */
    public Phase configurationImport() {
        return new Phase("configuration-import",
                "import the deployment extras into qits-configuration", ctx -> {
            // The rendered extras, PLUS the two deployment values the release train does not restore
            // on its own: the image versions each daemon starts sandboxes from. They are the calvers
            // qits-projects-daemon and qits-workspace-daemon were released at this boot — read the
            // same way releaseReplay reads them, so the seeds and the pins cannot disagree. Seeded
            // here, before qits-projects and qits-workspaces deploy a few phases below, they give
            // each service the right version on its FIRST boot instead of leaving the first sandbox
            // to wait on the durable SoftwareRelease event to be consumed.
            String properties = withImageVersions(
                    ComposeTemplate.extras(new SeedPhases(boot).tokens()),
                    boot.git.describeTag(boot.state.repoDir("projects-daemon"), "main"),
                    boot.git.describeTag(boot.state.repoDir("workspace-daemon"), "main"));
            boot.awaitHealth(ctx, "qits-configuration at " + boot.config.configurationUrl(),
                    () -> boot.configuration.health());
            Http.Response answer = boot.configuration.importProperties(properties);
            if (!answer.ok()) {
                // A failure here STOPS the boot rather than warning, and the flip below is why: a
                // deployer pointed at a service that does not hold this platform's configuration
                // refuses every deployment left in the train.
                throw new IllegalStateException("importing the extras into qits-configuration "
                        + "failed: " + answer.describe());
            }
            ctx.log("  " + answer.body());
            ctx.note("imported");
        });
    }

    /**
     * The imported extras with every daemon's image version seeded onto the service that deploys
     * from it: the project-agent version on qits-projects, the workspace version on qits-workspaces.
     * Each is guarded independently — a blank version appends nothing — so a boot that released one
     * daemon but not the other still seeds the one it can.
     */
    static String withImageVersions(String extras, String projectsAgentVersion,
            String workspaceVersion) {
        return withWorkspaceImageVersion(
                withProjectsAgentVersion(extras, projectsAgentVersion),
                workspaceVersion);
    }

    /**
     * The imported extras with the project-agent image version appended as an env entry on
     * qits-projects. A blank version — a projects-daemon that has never released — leaves the
     * extras untouched: releaseReplay stops the boot before this phase in that case, and an empty
     * value would be a worse seed than none, since qits-projects carries its own fallback default.
     * <p>
     * One source for the version: {@code releaseReplay} restores the tag, this line seeds it, and a
     * new SoftwareRelease listener in qits-configuration keeps it in sync afterwards — so the value
     * the deployer injects tracks the pin the release train moves.
     */
    static String withProjectsAgentVersion(String extras, String version) {
        if (version.isBlank()) {
            return extras;
        }
        return extras.stripTrailing() + "\n"
                + "# The project-agent image version, seeded so qits-projects' first deploy pins\n"
                + "# the release this boot cut rather than waiting on the SoftwareRelease event.\n"
                + projectsAgentImageVersionSeed(version) + "\n";
    }

    /** The seed line: qits-projects' {@code QITS_PROJECTS_AGENT_IMAGE_VERSION} deployment env. */
    static String projectsAgentImageVersionSeed(String version) {
        return "qits.platform.deployments.extras." + PlatformModel.application("projects")
                + ".env.QITS_PROJECTS_AGENT_IMAGE_VERSION=" + version;
    }

    /**
     * The imported extras with the workspace image version appended as an env entry on
     * qits-workspaces. The {@code qits/workspace} image is published by the workspace-daemon release
     * pipeline, so the version is the calver qits-workspace-daemon was released at this boot — read
     * the same way {@code releaseReplay} reads it, so the seed and the pin cannot disagree. A blank
     * version — a workspace-daemon that has never released — leaves the extras untouched, since an
     * empty value would be a worse seed than the fallback default qits-workspaces carries.
     */
    static String withWorkspaceImageVersion(String extras, String version) {
        if (version.isBlank()) {
            return extras;
        }
        return extras.stripTrailing() + "\n"
                + "# The workspace image version, seeded so qits-workspaces' first deploy pins\n"
                + "# the release this boot cut rather than waiting on the SoftwareRelease event.\n"
                + workspaceImageVersionSeed(version) + "\n";
    }

    /** The seed line: qits-workspaces' {@code QITS_WORKSPACE_IMAGE_VERSION} deployment env. */
    static String workspaceImageVersionSeed(String version) {
        return "qits.platform.deployments.extras." + PlatformModel.application("workspaces")
                + ".env.QITS_WORKSPACE_IMAGE_VERSION=" + version;
    }

    /**
     * <b>THE FLIP, and it is deliberately its own phase after the import.</b> The deployer's
     * {@code QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL} makes qits-configuration AUTHORITATIVE: a
     * resolved read per deployment, and an unreachable or unpopulated service refuses the
     * deployment rather than shipping a stale value. So the order is load-bearing in both
     * directions — a deployer holding this url before qits-configuration is deployed and imported
     * refuses every deployment including qits-configuration's own, and a deployer that never gets
     * it goes on deploying from the file.
     * <p>
     * <b>Two halves, and only this one is live.</b> The other is in the generated extras, on the
     * deployer's own application, so every later self-update inherits the keys — a live
     * {@code service update} alone is exactly the fix the old model kept reverting at the next
     * deploy. This half exists because the deployer running RIGHT NOW was started before the
     * service existed, and the rest of this run's deploy train is what proves the read.
     * <p>
     * The values come out of the rendered extras rather than being spelled again here: one source,
     * so the running deployer and its successor cannot be configured differently.
     */
    public Phase configurationFlip() {
        return new Phase("configuration-flip",
                "point the running deployer at qits-configuration", ctx -> {
            String extras = ComposeTemplate.extras(new SeedPhases(boot).tokens());
            List<String> env = flipEnv(extras, PlatformModel.application("deployments"));
            String alias = PlatformModel.wireAlias("deployments", boot.config.envName());
            List<String> services = boot.docker.serviceNames();
            // The seed's stack-qualified name first, then the bare alias — which is what the
            // deployer names its own service when it has already taken this application over.
            String service = List.of(Docker.stackService(Docker.STACK, alias), alias).stream()
                    .filter(services::contains).findFirst().orElse(null);
            if (service == null) {
                ctx.warn("no running qits-deployments service to point at qits-configuration. The "
                        + "extras carry the keys, so the next deployer to start reads them — but "
                        + "nothing in THIS run deploys from the service");
                return;
            }
            String secret = boot.state.secrets.getOrDefault(alias, "");
            if (alreadyFlipped(service, env, secret)) {
                ctx.skip(service + " already reads " + boot.config.configurationUrl());
            }
            ctx.log("  " + service + " is older than the flip — updating it so the rest of this "
                    + "train deploys from qits-configuration");
            List<String> command = new ArrayList<>(List.of("docker", "service", "update"));
            env.forEach(pair -> {
                command.add("--env-add");
                command.add(pair);
            });
            command.add(service);
            Boot.must(boot.docker.run(Cmd.of(command)
                    .timeout(Duration.ofMinutes(10))
                    .mask(secret), ctx::log),
                    "pointing " + service + " at qits-configuration failed");
            ctx.note(env.size() + " values on " + service);
        });
    }

    /**
     * The env pairs of the flip, read back out of the extras this boot rendered. Filtered by what
     * they MEAN rather than listed: the url that moves the authority, and the named oidc client that
     * is the credential the moved read presents. Nothing else on the deployer's block is this
     * phase's to touch — the rest is already in the file the running deployer read at its boot.
     */
    static List<String> flipEnv(String extras, String application) {
        String prefix = "qits.platform.deployments.extras." + application + ".env.";
        return extras.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()))
                .filter(pair -> pair.startsWith("QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL=")
                        || pair.startsWith("QUARKUS_OIDC_CLIENT_CONFIGURATION_"))
                .toList();
    }

    /**
     * Whether the service already carries every pair, so a rerun costs a read rather than a task
     * restart — the same courtesy {@code pd-extras} pays with its digest. The secret is masked
     * because this reads the whole environment back, and every line a command prints reaches the
     * run log.
     */
    private boolean alreadyFlipped(String service, List<String> env, String secret) {
        List<String> current = boot.docker.run(Cmd.of(List.of("docker", "service", "inspect",
                        "--format",
                        "{{range .Spec.TaskTemplate.ContainerSpec.Env}}{{println .}}{{end}}",
                        service))
                .mask(secret), null).captured();
        return Set.copyOf(current.stream().map(String::trim).toList()).containsAll(env);
    }

    // --- the public identity ------------------------------------------------------------------------

    /**
     * <b>THE PROJECT EVERY PLATFORM REPOSITORY BELONGS TO, before the first one is created.</b>
     * Nothing this run seeds has a public address until there is a project to hold it: the clone url
     * is {@code /git/<projectId>/<repoName>} and the id in it is this project's.
     * <p>
     * <b>The project is qits-projects' OWN to create, and this phase releases it rather than making
     * one.</b> Two seeds of one project are two projects with one name, so the service's startup
     * self-seed is the single writer — it mints the id, names the project after the wrapper and
     * creates the wrapper's own origin on the git host.
     * <p>
     * <b>The seed service starts with that self-seed HELD, and the reason is a race with no other
     * cure.</b> Creating the wrapper origin needs a bearer for the git host, and the idp is a seed
     * service starting in the same second. A self-seed that fired first would fail, roll its own
     * transaction back — project row included — and not try again until the container restarted. So
     * the stack file spells {@code QITS_STARTUP_SEED_ENABLED=false}, this phase turns it on once
     * {@code seed-health} has watched the idp answer, and the wait below is then a wait on work
     * that can succeed.
     * <p>
     * <b>Only the seed service is ever released.</b> On a platform whose qits-projects is deployed,
     * there is no seed service to update and the project already exists — the phase is then the wait
     * alone, which is what a rerun costs.
     * <p>
     * <b>It creates the project and NOT the catalogue.</b> The seed's other switch
     * ({@code QITS_STARTUP_SEED_RECONCILE_REPOSITORIES=false}, see the stack file) keeps the wrapper
     * reconcile out of the seed window: under the 2026-08-21 ruling no storage id is a name, so a
     * reconcile against a platform whose repositories do not exist yet would mirror all of them in
     * from the org. This run creates them, and the reconcile first runs on the DEPLOYED container,
     * where every entry matches a row by alias.
     */
    public Phase qitsProject() {
        return new Phase("qits-project", "the '" + PlatformModel.PROJECT
                + "' project qits-projects seeds itself", ctx -> {
            String token = projectsToken();
            boot.awaitHealth(ctx, "qits-projects at " + boot.projects.base(),
                    () -> boot.projects.health(token));
            releaseSelfSeed(ctx);
            String projectId = Waiter.await(ctx, "the '" + PlatformModel.PROJECT + "' project in "
                            + boot.projects.base(), boot.config.healthTimeout(),
                    boot.config.pollInterval(),
                    () -> qitsProjectId(boot.projects.projects(token))
                            .map(id -> Waiter.Poll.done(id, id))
                            .orElseGet(() -> Waiter.Poll.pending(
                                    "the self-seed has not created it yet")));
            boot.state.projectId = projectId;
            ctx.log("  project " + PlatformModel.PROJECT + " is " + projectId);
            ctx.note(projectId);
        });
    }

    /** The key the seed stack holds qits-projects' startup self-seed with. */
    static final String SELF_SEED_ENABLED = "QITS_STARTUP_SEED_ENABLED";

    /**
     * Turns the seed qits-projects' self-seed on, or says why there was nothing to turn on.
     * <p>
     * The seed SERVICE only: a deployed qits-projects was started by the deployer from extras that
     * spell no such key, so its self-seed has been on since it booted and the project is already
     * there. Asking for the stack-qualified name first and the bare alias second is the same
     * two-step {@code configuration-flip} makes, and for the same reason — the deployer names its
     * own service after the bare alias once it has taken the application over.
     */
    private void releaseSelfSeed(PhaseContext ctx) {
        String alias = PlatformModel.wireAlias(PROJECTS, boot.config.envName());
        List<String> services = boot.docker.serviceNames();
        String service = Docker.stackService(Docker.STACK, alias);
        if (!services.contains(service)) {
            ctx.log("  no seed " + service + " — qits-projects is the deployer's, and its self-seed "
                    + "has been on since it booted");
            return;
        }
        ctx.log("  releasing the self-seed on " + service + ": the idp answers now, so the wrapper "
                + "origin it creates can be authorised");
        Boot.must(boot.docker.run(Cmd.of(List.of("docker", "service", "update",
                                "--env-add", SELF_SEED_ENABLED + "=true", service))
                        .timeout(Duration.ofMinutes(10)), ctx::log),
                "releasing the self-seed on " + service + " failed");
    }

    /**
     * The {@code qits} project's id in a listing answer, if that listing names one.
     * <p>
     * Matched on the SLUG as well as the name, because the two are different fields with the same
     * value here and only one of them is what other things are named after. Kept static and pure so
     * the shape of qits-projects' listing is provable without one.
     */
    static Optional<String> qitsProjectId(Http.Response listing) {
        if (!listing.ok()) {
            return Optional.empty();
        }
        for (JsonNode entry : Json.parse(listing.body()).path("entries")) {
            JsonNode project = entry.path("project");
            if (PlatformModel.PROJECT.equals(Json.text(project, "name"))
                    || PlatformModel.PROJECT.equals(Json.text(project, "slug"))) {
                return Optional.of(Json.text(project, "id")).filter(id -> !id.isBlank());
            }
        }
        return Optional.empty();
    }

    /**
     * Every project slug the platform holds, or none.
     * <p>
     * <b>A courtesy read, and it must never end the run.</b> It exists so the closing report can
     * name each project's editor host and say whether the certificate covers it; a report is not
     * worth failing a boot for, so a listing that does not answer prints the shape instead of the
     * table.
     */
    private List<String> projectSlugs() {
        try {
            return projectSlugs(boot.projects.projects(projectsToken()));
        } catch (RuntimeException unavailable) {
            return List.of();
        }
    }

    /**
     * The slugs in a listing answer, in the order it gave them.
     * <p>
     * The SLUG and not the name: the slug is the public spelling of a project everywhere a name
     * reaches DNS, and {@code editor.<slug>.<domain>} is one of those places. Kept static and pure
     * so the shape of qits-projects' listing is provable without one.
     */
    static List<String> projectSlugs(Http.Response listing) {
        if (!listing.ok()) {
            return List.of();
        }
        List<String> slugs = new ArrayList<>();
        for (JsonNode entry : Json.parse(listing.body()).path("entries")) {
            String slug = Json.text(entry.path("project"), "slug");
            if (slug != null && !slug.isBlank() && !slugs.contains(slug)) {
                slugs.add(slug);
            }
        }
        return List.copyOf(slugs);
    }

    /**
     * The bootstrap's own bearer, addressed to qits-projects — or null with the gate off, where the
     * forwarded identity headers are the whole credential.
     */
    private String projectsToken() {
        return boot.tokenOrNull(PlatformModel.wireAlias("bootstrap", boot.config.envName()),
                PlatformModel.wireAlias("projects", boot.config.envName()));
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
            // THE BROWSER DOOR CARRIES THE ENVIRONMENT'S NAME NOW, on both kinds of platform,
            // because every service has a host of its own under it and one session has to cover
            // them all. A cookie is shared with a name's CHILDREN only, and bare localhost can
            // parent nothing — it is a public suffix, so browsers drop a cookie scoped to it.
            String door = boot.config.publicOrigin();
            // THE LOGIN IS A SERVICE HOST NOW, like every other. The door serves no /idp path.
            String idp = boot.config.idpOrigin();
            String authority = boot.config.envAuthority();
            // THE ENVIRONMENT LABEL IS OPTIONAL FOR THE DEFAULT TIER on a domain platform:
            // <app>.<domain> and <app>.<env>.<domain> are the same host, and the short one is what
            // a person types. Locally there is no apex to shorten to — the door carries the
            // environment's name, so an app host is <app>.<env>.localhost:<port> and nothing else.
            String apex = DomainName.of(boot.config).orElse(null);
            String appHost = apex == null ? "http://<app>." + authority : "https://<app>." + apex;
            String returns = apex == null ? "*." + authority : "*." + apex + " and *." + authority;
            report.add("edge:      " + door + "/  — the host's one HTTP port, in front of every "
                    + "environment. The");
            report.add("           DOOR ITSELF SERVES ONE ROUTE: GET / redirects to the projects "
                    + "host, and every");
            report.add("           other path on it is a 404 — each service answers on a name of "
                    + "its own below.");
            report.add("           It is the byte plane's port too: the registry, the mirror and "
                    + "the git host publish");
            report.add("           none of their own. EVERY method on those three names needs a "
                    + "bearer, reads included.");
            report.add("apps:      EVERY SERVICE HAS A HOST OF ITS OWN: " + appHost + "/ serves");
            report.add("           that service's UI at / and its wire routes beside it — idp, "
                    + "ci, projects,");
            report.add("           workspaces, docs, configuration, artifacts, events, "
                    + "deployments, system, and the");
            report.add("           three above.");
            if (apex != null) {
                report.add("           The environment label is optional for " + env
                        + ", the default tier: <app>." + apex);
                report.add("           and <app>." + authority + " are the same host. Another "
                        + "tier spells its own label.");
            }
            report.add("           ONE LOGIN COVERS THEM ALL: the session cookie is scoped to "
                    + boot.config.browserSsoCookieDomain() + " and the");
            report.add("           idp accepts a return to " + returns + " — exactly one extra "
                    + "label, same port.");
            report.add("sign in:   " + idp + "/idp/login — the login page, on the idp's own host.");
            report.add("           It is where a passkey ceremony happens and the only origin the "
                    + "idp accepts one");
            report.add("           from. Any service host sends an anonymous browser here and "
                    + "takes it back after.");
            if (apex == null) {
                report.add("           Every *.localhost name resolves to loopback on its own "
                        + "(Chromium, Firefox and");
                report.add("           nss-myhostname), so there is nothing to add. Ask this "
                        + "host's resolver:");
                report.add("             getent hosts ci." + env + ".localhost");
                report.add("           An empty answer is the one case that needs /etc/hosts, one "
                        + "line per name:");
                report.add("             127.0.0.1  <app>." + env + ".localhost");
            }
            report.add("registry:  " + boot.config.registryVhost()
                    + " — the platform's OWN images and packages (" + env + "-qits-artifacts)");
            report.add("mirror:    " + boot.config.mirrorVhost()
                    + " — everything third-party, cached (qits-platform-mirror).");
            report.add("           Point dockerd's registry-mirrors at it: "
                    + "\"registry-mirrors\": [\"http://" + boot.config.mirrorVhost() + "\"]");
            report.add("daemon:    BOTH names are plain HTTP, so /etc/docker/daemon.json needs "
                    + "them listed or every");
            report.add("           push and pull fails on TLS. Add, then restart the daemon:");
            report.add("             \"insecure-registries\": [\"" + boot.config.registryVhost()
                    + "\", \"" + boot.config.mirrorVhost() + "\"]");
            report.add("           Both names resolve by themselves, as every *.localhost name "
                    + "does — the apps: note");
            report.add("           above has the check for the resolver that does not.");
            report.add("login:     a PULL needs a credential now as much as a PUSH, and a person's "
                    + "is COMMISSIONED —");
            report.add("           the idp issues one per context, so no static pair is handed "
                    + "out. Ask for yours");
            report.add("           from a container, because the idp answers on qits-net and has "
                    + "no public route:");
            report.add("             docker run --rm --network qits-net curlimages/curl -s "
                    + "-u <client>:<secret> \\");
            report.add("               -H 'Content-Type: application/json' \\");
            report.add("               -d '{\"contextKind\":\"workstation\",\"contextId\":\""
                    + "<hostname>\"}' \\");
            report.add("               " + boot.config.idpIssuer() + "/api/clients");
            report.add("           <client>:<secret> is a service client out of "
                    + ".qits-bootstrap.env, and a commissioned");
            report.add("           credential inherits ITS access — so name "
                    + PlatformModel.wireAlias("artifacts", env)
                    + ", the one that may write.");
            report.add("           The answer's clientId and secret are the credential:");
            report.add("             docker login " + boot.config.registryVhost()
                    + " -u <clientId> -p <secret>");
            report.add("           Hand it back when the workstation is done with it: DELETE "
                    + "/idp/api/clients/<clientId>.");
            report.addAll(registerLines(idp + "/idp/register",
                    boot.state.registerToken, boot.state.registerTokenRecorded,
                    boot.state.wrapperDir.resolve(BootstrapState.FILE_NAME).toString()));
            if (apex == null) {
                // A passkey is bound to the rp id it was made under and asserts on that host and
                // its children. The local rp id was bare localhost until the per-service hosts
                // landed, and dev.localhost is not a child of it — those credentials assert
                // nowhere now, and nothing about them can be migrated.
                report.add("passkeys:  the local door moved from localhost to " + authority
                        + ", and the passkey binding");
                report.add("           moved with it. A passkey made on an older local platform, "
                        + "under the rp id");
                report.add("           'localhost', asserts here for nobody. Register it again "
                        + "at");
                report.add("             " + idp + "/idp/register");
            }
            report.add("ipv6:      ONE HOST RULE, and without it every vhost client HANGS rather "
                    + "than fails. The");
            report.add("           launcher installs it on each run, so it is already in place "
                    + "unless the run said");
            report.add("           otherwise — it needs root, and a warning there means it is "
                    + "yours to run:");
            report.add("             sudo ip6tables -I INPUT -i lo -p tcp --dport "
                    + boot.config.port() + " -j REJECT \\");
            report.add("               --reject-with tcp-reset");
            report.add("           The resolver answers ::1 first for a *.localhost name and "
                    + "swarm's routing mesh is");
            report.add("           IPv4-only: the ingress listener ACCEPTS the v6 connection and "
                    + "never serves it, so");
            report.add("           curl, docker, git, maven and npm all sit there. The reset "
                    + "makes each one fall back");
            report.add("           to IPv4 at once. A host-mode publish never had this — "
                    + "docker-proxy bound both");
            report.add("           families — so it arrives with ingress. The rule does NOT "
                    + "survive a reboot, which");
            report.add("           is why the launcher installs it every time rather than once.");
            report.add("workloads: " + PlatformModel.wireAlias("containers", env)
                    + " on qits-net — the orchestrator that holds the");
            report.add("           docker socket. No host port and no public route: every caller "
                    + "is a machine");
            report.add("           on this network, and every route of it is behind the machine "
                    + "gate.");
            // THE PUBLIC FORM AND NOTHING ELSE. /git/<repoId> is the storage scheme — qits-projects'
            // internal address for the bare — and the deployed git host serves it to that service's
            // client alone. Printing it here would be printing an address a person is refused.
            //
            // THE SLUG IS THE PUBLIC SPELLING OF THE FIRST SEGMENT. qits-projects matches the
            // project segment by id OR slug and the git host passes it through verbatim, so
            // /git/qits/<repo>.git is the address a person is given — a name they can read and
            // type, rather than the uuid a machine holds. The machine form is printed beside it,
            // below, because that is what every service on qits-net dials.
            String project = boot.state.projectId == null || boot.state.projectId.isBlank()
                    ? "<projectId>" : boot.state.projectId;
            report.add("git host:  http://" + boot.config.gitHostVhost() + "/git/"
                    + PlatformModel.PROJECT + "/<repo>.git — qits-githost, through the edge.");
            report.add("           The first segment is the PROJECT (" + PlatformModel.PROJECT
                    + " holds every repository this run seeded)");
            report.add("           and the second is the repository's name. That pair is the only "
                    + "clone url there is:");
            report.add("           /git/<repoId> is the storage scheme, and the git host serves it "
                    + "to qits-projects alone.");
            report.add("           EVERY method needs a bearer here, reads included — as on the "
                    + "two registry names");
            report.add("           since the flip. Mint one and clone with it:");
            report.add("             curl -u <client>:<secret> http://"
                    + boot.config.gitHostVhost() + "/token        (the access_token is in the "
                    + "answer)");
            report.add("             git -c http.extraHeader=\"Authorization: Bearer <token>\" "
                    + "clone \\");
            report.add("               http://" + boot.config.gitHostVhost() + "/git/"
                    + PlatformModel.PROJECT + "/qits-qits.git");
            report.add("           The clients and where their secrets are recorded are on the "
                    + "machines: line below.");
            report.add("           The first segment is the SLUG above or the project's id, and "
                    + "both always resolve.");
            report.add("           On qits-net it is " + boot.config.gitHostUrl() + "/" + project
                    + "/<repo>, which is what every service dials.");
            report.add("mode:      restore — every checkout was stood at its newest release tag, "
                    + "and that is what the seed");
            report.add("           images and the deployments were both built from. Local mains "
                    + "were pushed and deploy");
            report.add("           nothing.");
            if (boot.config.shipMains()) {
                report.add("           --ship-mains WAS SET AND IGNORED: a deployment is "
                        + "qits/<app>:<version> and an unreleased");
                report.add("           main has no version, so shipping one is not something this "
                        + "platform can be asked for.");
            }
            report.add("dev loop:  there is no push that deploys. Cut a release request in "
                    + "qits-projects and the platform");
            report.add("           deploys it: the tag fires the release build, the build "
                    + "announces the version, and the");
            report.add("           deployer pulls qits/<app>:<version>. A rerun of this boot "
                    + "restores the last release.");
            report.add("deploy:    a RELEASE deploys — qits-projects tags, qits-ci publishes "
                    + "qits/<app>:<version> and");
            report.add("           announces it, qits-deployments enters a deployment request "
                    + "and pulls that image.");
            report.add("           Pushing a branch deploys nothing, and environment/<name> is "
                    + "retired: there is no");
            report.add("           deploy ref on this platform. The platform services ("
                    + String.join(", ", PlatformModel.PLATFORM_SERVICES) + ")");
            report.add("           take the same road into this same environment: one instance "
                    + "each, bare aliases,");
            report.add("           joined to every environment's networks.");
            report.add("swarm:     " + boot.state.swarm + ". qits-net is an attachable overlay: "
                    + "swarm services and");
            report.add("           plain containers share it and resolve each other by name.");
            report.add("topology:  qits-deployments owns the environments, the services,");
            report.add("           the links AND the deployments — one component, at "
                    + boot.config.platformDeploymentsUrl());
            report.add("names:     an environment service answers to " + env
                    + "-qits-<app>, a platform service to its bare");
            report.add("           repository name. Deployed containers are qits-pd-" + env
                    + "-qits-<app>-<id8>");
            report.add("           and qits-pd-qits-<app>-<id8>.");
            report.add("main:      finalized by the release AFTER its deployment lands — "
                    + "qits-projects merges the release");
            report.add("           tag back. A direct push needs -o qits.token="
                    + boot.config.pushToken());
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
            // Only with a domain. This platform serves no dns, so the records are printed as a
            // step to CHECK at whatever provider holds the domain; the certificate is the one thing
            // under it the run does itself.
            // NOTE: this could be a hook to read those records back from an external dns provider,
            // and say which of them are actually in place.
            DomainName.of(boot.config).ifPresent(domain -> report.addAll(domainLines(domain,
                    PublicIp.of(boot.config).orElse(""), Acme.mode(boot.config),
                    Acme.email(boot.config, domain), boot.state.certificate,
                    // The editor hosts are per PROJECT, so the list comes from the platform. A
                    // read that does not answer prints the shape instead of a table — the same
                    // courtesy every optional read in this program keeps.
                    projectSlugs(),
                    ExtraSans.of(boot.config, Optional.of(domain)))));
            report.add("images:    the release replays published qits/workspace-base, qits/workspace,");
            report.add("           qits/projects-daemon and qits/project-agent at their released "
                    + "versions —");
            report.add("           the coordinates qits-workspaces and qits-projects pin. Nothing "
                    + "is supplied by hand.");
            report.forEach(ctx::log);
        });
    }

    /**
     * <b>The seed-only volumes this run created, removed with the builder and for the same
     * reason.</b> {@code qits-maven-seed} is the temporary Maven repository the first-boot
     * dependency cycle is broken with, and the next bootstrap builds another one.
     * {@code qits-maven-cache} is the third-party download cache every seed build shares.
     * <p>
     * <b>The cache is the contested one, and both sides are real.</b>
     * {@code UnwrapPhases.KEEP} holds it through a data reset on purpose: re-fetching the
     * dependency world is what got this host throttled by Maven Central and killed a boot on a 502.
     * That argument is about a machine that reruns the boot, which is the dev loop — and the dev
     * loop is exactly what {@code QITS_KEEP_BUILDER=1} is for. A server boots once and then wants
     * the disk back, so the default takes it.
     */
    static final List<String> SEED_ONLY_VOLUMES = List.of("qits-maven-seed", "qits-maven-cache");

    /**
     * <b>The builder is bootstrap-time only, and this is where the machine gets its disk back.</b>
     * After this run every build on this host goes through qits-containers to the default builder;
     * nothing asks for {@link Docker#BUILDER} again until the next bootstrap, whose
     * {@code ensureBuilder} creates it anew. What it leaves behind is a container and the state
     * volume {@code buildx_buildkit_<builder>0_state} — 13.7 GB measured on wohlben.eu, held by a
     * builder no component of the platform will ever use.
     * <p>
     * {@code buildx rm} is what removes the volume: it is the only command that knows the name
     * buildx gave it. A {@code volume rm} by hand needs that spelling to be guessed, and guessing
     * at volume names is how someone's data goes.
     * <p>
     * <b>THE COST IS A COLD RE-BOOTSTRAP</b>, ten to twenty minutes more: the seed images are then
     * rebuilt with no layer cache and the seed's Maven containers re-fetch from Maven Central.
     * {@code QITS_KEEP_BUILDER=1} is the dev loop's answer, and this phase is skipped whole.
     * <p>
     * <b>It removes no images, and that absence is deliberate.</b> The bootstrap does rebuild
     * images under fixed tags — {@code qits/graalvmce-musl-builder:jdk-25} and the
     * {@code qits/build-images/*:latest} step images — so each rebuild leaves its predecessor
     * dangling. But a dangling image carries no record of the tag it used to hold, so nothing here
     * can tell one of ours from one of somebody else's on a shared host, and a blanket prune is a
     * sweep that guesses. Attributed image deletion is qits-containers' gc endpoint, driven by
     * qits-platform-orchestrator with the platform's pin set in hand; it is that component's job
     * and not this one's.
     */
    public Phase teardownBootstrapBuilder() {
        return new Phase("teardown-bootstrap-builder",
                "reclaim the bootstrap's builder and its seed caches", ctx -> {
            if (boot.config.keepBuilder()) {
                ctx.skip("QITS_KEEP_BUILDER=1 — the builder and its warm cache stay");
            }
            List<String> reclaimed = new ArrayList<>();
            ctx.log("  removing " + Docker.BUILDER + " — bootstrap-time only, and the next boot "
                    + "creates it again");
            ProcessResult removed = boot.docker.removeBuilder(Docker.BUILDER, ctx::log);
            if (removed.ok()) {
                reclaimed.add(Docker.BUILDER);
            } else if (Docker.alreadyGone(removed)) {
                ctx.log("  " + Docker.BUILDER + " was not there — a run that built nothing, or a "
                        + "second run of this phase");
            } else {
                ctx.warn("could not remove the builder " + Docker.BUILDER + ": "
                        + removed.tailText(3) + ". Its state volume stays on this host — "
                        + "`docker buildx rm " + Docker.BUILDER + "` by hand is the whole fix");
            }
            // DANGLING IS THE WHOLE PERMISSION. A volume some container still holds is one this
            // run does not understand, whoever named it — the seed's own containers are gone by
            // now, so anything of ours that is still attached is attached to something else.
            List<String> dangling = boot.docker.danglingVolumes();
            for (String volume : SEED_ONLY_VOLUMES) {
                if (!dangling.contains(volume)) {
                    ctx.log("  " + volume + " is in use or already gone — left alone");
                    continue;
                }
                ProcessResult gone = boot.docker.removeVolume(volume, ctx::log);
                if (gone.ok()) {
                    reclaimed.add(volume);
                } else {
                    ctx.warn("could not remove " + volume + ": " + gone.tailText(3));
                }
            }
            ctx.note(reclaimed.isEmpty() ? "nothing to reclaim"
                    : "removed " + String.join(", ", reclaimed));
        });
    }
}
