package eu.wohlben.qits.cli.bootstrap.phases;

import eu.wohlben.qits.cli.bootstrap.api.Http;
import eu.wohlben.qits.cli.bootstrap.api.Json;
import eu.wohlben.qits.cli.bootstrap.config.DomainName;
import eu.wohlben.qits.cli.bootstrap.config.WrapperDir;
import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseContext;
import eu.wohlben.qits.cli.bootstrap.platform.BootstrapState;
import eu.wohlben.qits.cli.bootstrap.platform.ComposeTemplate;
import eu.wohlben.qits.cli.bootstrap.platform.Docker;
import eu.wohlben.qits.cli.bootstrap.platform.DomainTokens;
import eu.wohlben.qits.cli.bootstrap.platform.PgAdmin;
import eu.wohlben.qits.cli.bootstrap.platform.PlatformModel;
import eu.wohlben.qits.cli.bootstrap.platform.SeedDockerfile;
import eu.wohlben.qits.cli.bootstrap.proc.Cmd;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessResult;
import org.eclipse.microprofile.config.ConfigProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The hand-built part: everything the pipeline cannot make for itself on the first boot, plus the
 * files the seed stack is started from.
 */
public class SeedPhases {

    /**
     * The temporary maven registry's container name — known here, not only in the run state. On
     * qits-net it is also the name this CLI dials it by, because docker's embedded DNS answers a
     * container's own name.
     */
    static final String AUTH_SEED_HTTP = "qits-maven-seed-http";

    /**
     * The temporary registry as THIS CLI reaches it. The mount puts the repository under
     * {@code /artifacts/maven/maven}, so it answers the same paths the real store does — which is
     * what lets the seed builds resolve against either one without knowing which is up.
     */
    private static final String AUTH_SEED_URL = "http://" + AUTH_SEED_HTTP + "/artifacts";

    /**
     * Where the metadata of the seed's LAST published artifact lives under an artifacts base url —
     * the sentinel the skip probe asks for. See {@link #mavenSeed()} for why it is this one.
     */
    private static final String SEED_SENTINEL_METADATA =
            "/maven/maven/eu/wohlben/qits/qits-containers-client/maven-metadata.xml";

    /**
     * The qits libraries a seed image build resolves, in DEPENDENCY ORDER, and the repository each
     * one is built from. Every one of them is published into the temporary registry below before
     * the first seed image is built, and into the real store once it answers.
     * <p>
     * Every pair here is forced by a pom:
     * <ul>
     *   <li>qits-registries is written against qits-blobstore's entities, so the blob store is
     *       first.
     *   <li>qits-eventstream is written against qits-db-core, a module of qits-integrations-quarkus,
     *       so the integrations come BEFORE it. This is the edge that broke the 2026-08-11 proving
     *       run: the integrations used to be last, every earlier green run had an eventstream with
     *       no qits dependency at all, and the day it grew one the seed resolved qits-db-core
     *       against a registry that does not exist yet — "Connection refused" on localhost:8081,
     *       minutes into the phase.
     *   <li>qits-githost-events is written against qits-eventstream and follows it.
     *   <li>qits-containers' two libraries are written against qits-db-core and qits-eventstream,
     *       so the orchestrator is last.
     * </ul>
     * <b>githost and containers name SERVICE repositories and publish modules of them</b> — the git
     * host's event vocabulary, and the orchestrator's core and client. {@link
     * PlatformModel#mavenModule} says which modules and why.
     */
    static final List<String> SEED_LIBRARIES = List.of(
            "blobstore", "registries", "integrations-quarkus", "eventstream", "githost",
            "containers");

    /**
     * <b>The third-party download cache every maven container this program starts shares.</b>
     * <p>
     * These containers are stock maven images with an empty local repository, so each one used to
     * pull the whole dependency world from Maven Central again — the seed phase, then one publish
     * per library, then the ci-daemon build. Four cold bootstraps in one evening on 2026-08-11 and
     * Central's CDN throttled this host: the maven phases crawled for hours and attempt 4 died at
     * the qits-blobstore publish on a 502 from the mirror's central proxy. A named volume outlives
     * both the phase and the run, so the second bootstrap of an evening downloads nothing.
     * <p>
     * It is NOT {@code qits-maven-seed}, which holds what this platform publishes. This one holds
     * what the internet publishes, and {@code unwrap --with-data-volumes} keeps it for that reason.
     */
    static final String MAVEN_CACHE_VOLUME = "qits-maven-cache";

    /** The mount, and the {@code -D} that points maven at it. Together, once, everywhere. */
    static final String MAVEN_CACHE_MOUNT = MAVEN_CACHE_VOLUME + ":/cache";

    /**
     * The local repository is named on the command line rather than left to {@code HOME}, so the
     * mount point is the same fact in every container whatever user it runs as.
     */
    static final String MAVEN_REPO_LOCAL = " -Dmaven.repo.local=/cache/repository";

    /**
     * <b>This run's qits bytes are always built, never remembered.</b> A cache that outlives the run
     * is right for third-party jars, which are immutable at their version, and wrong for ours: a
     * seed build stamps a calver that a later checkout reuses, so yesterday's
     * {@code qits-db-core:2026.811.165001} can satisfy a resolution that today's source would
     * answer differently — and the version says nothing about it. The restamp recovery made that
     * real. Only a released artifact is unique per bytes, and these are not released.
     * <p>
     * The purge takes only {@code eu/wohlben/qits}, so every third-party jar in the cache stays.
     */
    static final String MAVEN_PURGE_QITS = "rm -rf /cache/repository/eu/wohlben/qits\n";

    private final Boot boot;

    public SeedPhases(Boot boot) {
        this.boot = boot;
    }

    // --- preflight and sources -------------------------------------------------------------------

    public Phase preflight() {
        return new Phase("preflight", "preflight: docker, git and the wrapper checkouts", ctx -> {
            if (!boot.docker.daemonReachable()) {
                throw new IllegalStateException("cannot reach a docker daemon — is it running?");
            }
            ctx.log("  docker daemon: reachable");
            if (!boot.docker.composePluginPresent()) {
                throw new IllegalStateException("docker compose plugin missing");
            }
            ctx.log("  docker compose: present");
            // Loudly, because the failure it prevents is silent: without buildx the client falls
            // back to the legacy builder and every image this run builds is built by something
            // else, with no line anywhere saying so.
            if (!boot.docker.buildxPresent()) {
                throw new IllegalStateException("docker buildx missing — every seed image would be "
                        + "built by the legacy builder instead, silently. The payload image ships "
                        + "the plugin, so this run is not the payload image this program builds");
            }
            ctx.log("  docker buildx: present");
            boot.state.swarm = ensureSwarm(boot.docker, ctx::log);
            ctx.log("  swarm: " + boot.state.swarm);
            if (!boot.git.available()) {
                throw new IllegalStateException("no git on PATH");
            }
            boot.state.dockerGid = boot.docker.socketGroupId();
            ctx.log("  docker socket group: " + boot.state.dockerGid);

            // The CLI lives at cli/qits-cli-bootstrap inside the wrapper, so an unset
            // QITS_WRAPPER_DIR is answered by walking up from here rather than by assuming the
            // working directory IS the wrapper. Which of the two happened is printed: a run
            // against the wrong checkout is otherwise indistinguishable from a run against the
            // right one until the sources phase clones something surprising.
            WrapperDir.Resolved resolved =
                    WrapperDir.resolveOrClone(boot.config.wrapperDir(), Path.of("").toAbsolutePath());
            Path wrapper = resolved.path();
            // ABSENT is a cold start, and the `wrapper` phase clones it — a bare machine has no
            // checkout and no platform git host to get one from. An EMPTY directory is the same
            // thing: a script that made the path, or a mount point, holds no answer either. A path
            // that exists with ANYTHING in it and is not a checkout still stops the boot: this run
            // decides which sha the whole platform is built from, and a directory nobody cloned
            // answers that question with whatever is standing there.
            if (Files.exists(wrapper) && !boot.git.isCheckout(wrapper)
                    && !isEmptyDirectory(wrapper)) {
                throw new IllegalStateException(wrapper + " exists, is not empty and is not a git "
                        + "checkout, so it is not the wrapper repository. Move it aside, or point "
                        + "QITS_WRAPPER_DIR (--wrapper-dir) at a " + WrapperDir.REPO + " checkout");
            }
            boot.state.wrapperDir = wrapper;
            boot.state.srcDir = Path.of(boot.config.src()).toAbsolutePath().normalize();
            boot.state.composeFile = wrapper.resolve("docker-compose.qits.yml");
            Files.createDirectories(boot.state.srcDir);
            ctx.log("  wrapper: " + wrapper + "  (" + resolved.how() + ")");
            if (!boot.git.isCheckout(wrapper)) {
                ctx.log("  cold start: the wrapper phase clones it from " + boot.config.orgUrl());
            }
            ctx.log("  sources: " + boot.state.srcDir);
            // Printed rather than assumed: everything the domain switches on — the dns zone, the
            // nameserver's SOA identity, the edge's TLS ports and its certificate — is invisible in
            // a log that never says which of the two runs this is. The value was checked before the
            // payload image was built, so this line cannot be the first place a typo shows.
            DomainName.of(boot.config).ifPresentOrElse(
                    domain -> ctx.log("  domain: " + domain + "  (ns " + DomainName.nsName(domain)
                            + ", hostmaster " + DomainName.hostmaster(domain) + ")"),
                    () -> ctx.log("  domain: none — dns serves no zones and the edge stays on "
                            + "plain HTTP"));

            long local = PlatformModel.platformRepos().stream()
                    .filter(name -> boot.git.isCheckout(boot.state.wrapperCheckout(name)))
                    .count();
            int total = PlatformModel.platformRepos().size();
            ctx.log("  " + local + " of " + total + " repositories have a local checkout; the rest "
                    + "come from " + boot.config.orgUrl());
            ctx.note(local + "/" + total + " local checkouts");
        });
    }

    /**
     * <b>The one swarm state this program repairs is {@code inactive}, and it repairs it here.</b>
     * Every network it makes is an overlay and every service the platform will run is a swarm
     * service, so a daemon that is not a swarm manager cannot carry this platform at all.
     * <p>
     * {@code inactive} is a machine that is in nobody's swarm: {@code docker swarm init} makes it a
     * single-node one, which is what this host is. Every other state is somebody else's answer —
     * {@code pending} is a join in flight, {@code locked} is a swarm that needs its unlock key, and
     * {@code active} without the control plane is a WORKER, which takes its orders from a manager
     * elsewhere. This phase names the state and stops. Initialising over any of them would tear a
     * machine out of a cluster it belongs to.
     *
     * @return what the run summary prints
     */
    static String ensureSwarm(Docker docker, Consumer<String> out) {
        Docker.Swarm swarm = docker.swarm();
        if (swarm.ready()) {
            return "active";
        }
        if (!swarm.inactive()) {
            throw new IllegalStateException(swarmRefusal(swarm));
        }
        ProcessResult init = docker.initSwarm(out);
        if (!init.ok()) {
            throw new IllegalStateException(initRefusal(init));
        }
        // Read back rather than trusted: the exit code says the command ran, and what this phase
        // owes every phase after it is a daemon that answers `active` to the next question.
        Docker.Swarm after = docker.swarm();
        if (!after.ready()) {
            throw new IllegalStateException("docker swarm init succeeded and the daemon still says "
                    + describe(after) + " — nothing here can make a swarm of that");
        }
        return "active (initialised by this run)";
    }

    private static String swarmRefusal(Docker.Swarm swarm) {
        String fix = switch (swarm.state()) {
            case "active" -> "This node is a WORKER: it runs tasks a manager gives it and creates "
                    + "nothing of its own. Bootstrap on a manager of this swarm, or promote it";
            case "pending" -> "A join is in flight. Let it finish, or `docker swarm leave --force`, "
                    + "then rerun";
            case "locked" -> "The swarm is locked. `docker swarm unlock` with its key, then rerun";
            default -> "Either put this daemon in a swarm, or take it out of the one it half is: "
                    + "`docker swarm leave --force`, then rerun";
        };
        return "this docker daemon is " + describe(swarm) + ", and the platform needs a swarm "
                + "MANAGER: every network it creates is an overlay and every service it runs is a "
                + "swarm service. " + fix + ". Nothing was changed — a machine in somebody else's "
                + "swarm is not a bootstrap's to re-initialise";
    }

    /**
     * <b>A host with several interfaces is told to choose, and it is told by a person.</b> Docker
     * answers "could not choose an IP address to advertise" and stops. This program cannot answer it
     * either — it runs as a container, so the routes it can read are docker's own and the address it
     * would derive is the bridge's, not the one another node reaches this machine at. So the failure
     * carries the exact command instead of a guess.
     */
    private static String initRefusal(ProcessResult init) {
        if (init.out().contains("could not choose an IP address to advertise")) {
            return "docker swarm init could not choose an address to advertise, because this host "
                    + "has more than one. Run it once by hand with the address this machine is "
                    + "reached at — `docker swarm init --advertise-addr <ip>` — and rerun the "
                    + "bootstrap. This run cannot pick it: it is a container, and the routes it "
                    + "sees are docker's rather than the host's";
        }
        return "docker swarm init failed (exit " + init.exitCode() + "): " + init.tailText(3);
    }

    private static String describe(Docker.Swarm swarm) {
        return swarm.state() + (swarm.ready() || !"active".equals(swarm.state())
                ? "" : " but not a manager");
    }

    /**
     * <b>The run joins qits-net, and every address after this line depends on it.</b> This CLI is a
     * container now, and every address it dials — the artifacts store, the edge, the idp, postgres
     * — is a wire alias that resolves for members of that network and for nobody else. A run that
     * skipped this would not fail here; it would fail in the first phase that polls something, as a
     * timeout with a name that does not resolve.
     * <p>
     * FIRST, so nothing is dialled before it, and it ensures the network exists rather than
     * assuming a platform stands: on a cold boot this is what creates qits-net, as the attachable
     * overlay every member of the platform joins — see {@link Docker#ensureNetwork}. Attaching is
     * measured to take effect at once — the embedded DNS answers on the next lookup, with no
     * restart of this process.
     * <p>
     * Already attached is success, the same way an existing network is adopted rather than
     * refused: the launcher may have started this container with {@code --network qits-net}, and a
     * second connect would be an error about a state this phase wanted anyway.
     */
    public Phase joinNetwork() {
        return new Phase("network", "join " + Boot.NETWORK, ctx -> {
            boot.docker.ensureNetwork(Boot.NETWORK, ctx::log);
            String self = boot.docker.selfName();
            if (self == null || !boot.docker.containerExists(self)) {
                throw new IllegalStateException("this run cannot find its own container: "
                        + "/etc/hostname says '" + self + "' and the daemon knows no container by "
                        + "that name, so it cannot join " + Boot.NETWORK + " and cannot reach one "
                        + "single address of the platform. It runs as a container — the image is "
                        + "docker/Dockerfile.bootstrap — with the host's docker socket mounted. A "
                        + "custom --hostname breaks this lookup; let docker set it");
            }
            if (boot.docker.networksOf(self).contains(Boot.NETWORK)) {
                ctx.log("  " + self + " is already on " + Boot.NETWORK);
            } else {
                Boot.must(boot.docker.exec(ctx::log, "network", "connect", Boot.NETWORK, self),
                        "joining " + Boot.NETWORK + " failed");
                ctx.log("  " + self + " joined " + Boot.NETWORK);
            }
            ctx.note("on " + Boot.NETWORK);
        });
    }

    /**
     * <b>The wrapper repository itself, when this machine has none.</b>
     * <p>
     * {@code curl … | bash} on a bare box is the case this exists for: there is no checkout to run
     * from, and no platform git host to clone one from either, because this run is what creates
     * that host. So the wrapper comes from the org anonymously — the same place {@link #sources}
     * gets a component whose checkout is missing — into the working directory, which leaves the
     * operator holding a real checkout they can rerun from.
     * <p>
     * <b>The submodules are deliberately NOT initialised, and adding {@code --recurse-submodules}
     * here would be a mistake.</b> {@link #sources} clones every platform repository from the org
     * whenever the wrapper has no checkout of it, so a BARE wrapper is already a complete answer;
     * initialising the submodules would clone the whole platform a second time, into a tree no
     * phase reads, and a cold start pays for it in the tens of minutes. What the run needs from the
     * wrapper is the DIRECTORY: the generated compose file, {@code .qits-bootstrap.env} and the
     * {@code .qits-bootstrap} workspace all live in it.
     * <p>
     * A wrapper that IS a checkout is left exactly as it is — not refreshed, not fast-forwarded. In
     * the ordinary case it is the operator's own working copy, and the sha it stands on is their
     * decision rather than this program's.
     */
    public Phase wrapper() {
        return new Phase("wrapper", "the wrapper repository", ctx -> {
            Path wrapper = boot.state.wrapperDir;
            if (boot.git.isCheckout(wrapper)) {
                ctx.skip("already checked out at " + wrapper);
            }
            // Preflight refused anything standing in this path, so what is left is an absent
            // directory or an empty one — and `git clone` takes both.
            String from = boot.config.orgUrl() + "/" + WrapperDir.REPO + ".git";
            ctx.status("cloning " + WrapperDir.REPO + " from " + from);
            Boot.must(boot.git.clone(from, wrapper, ctx::log),
                    "clone of " + WrapperDir.REPO + " from " + from + " failed — a cold start has "
                            + "nowhere else to get the wrapper from. Clone it by hand and rerun, or "
                            + "point QITS_WRAPPER_DIR (--wrapper-dir) at a checkout");
            ctx.log(String.format("  %-26s %s  (%s)", WrapperDir.REPO, boot.git.shortHead(wrapper),
                    from));
            ctx.log("  submodules left uninitialised: the sources phase clones each repository "
                    + "from " + boot.config.orgUrl() + " anyway");
            ctx.note("cloned " + boot.git.shortHead(wrapper));
        });
    }

    /**
     * <b>Both silences here are gone.</b> This phase decides which sha the whole platform is built
     * from, and both of its old fallbacks answered a broken input with a working-looking run:
     * <ul>
     *   <li>A wrapper path that was not a checkout fell through to GitHub. A rename that outran
     *       {@link PlatformModel#repoPath} then deployed the org's last push instead of the work in
     *       the checkout — and said so in one line among thousands. Now: an ABSENT directory is
     *       still answered by the org URL (not every model repository has to be a submodule of this
     *       wrapper), but a directory that exists and is not a checkout stops the boot.
     *       <p>
     *       <b>An EMPTY directory counts as absent</b>, and that is not a softening of the rule:
     *       git puts one at every gitlink whose submodule is not checked out, so the wrapper the
     *       cold start clones has one per repository. It holds no commits, no local work and no
     *       answer about which sha to build — there is nothing there for the org URL to ignore.
     *       A directory with anything at all in it is the case the rule was written for and still
     *       stops the boot.
     *   <li>A refresh that failed logged "using what is checked out" and built the stale copy. A
     *       non-fast-forward is the ordinary cause and the ordinary cause is a rebase, so the stale
     *       copy is a commit that no longer exists anywhere.
     * </ul>
     */
    public Phase sources() {
        return new Phase("sources", "clone or refresh the platform's sources", ctx -> {
            for (String name : PlatformModel.platformRepos()) {
                String repo = PlatformModel.repo(name);
                Path localSrc = boot.state.wrapperCheckout(name);
                if (Files.exists(localSrc) && !boot.git.isCheckout(localSrc)
                        && !isEmptyDirectory(localSrc)) {
                    throw new IllegalStateException(localSrc + " is not a git checkout and is not "
                            + "empty, so " + repo + " has no source and something else is standing "
                            + "in its place. Either the submodule is half-initialised or "
                            + "PlatformModel.repoPath names the wrong directory for '" + name
                            + "'.");
                }
                String from = boot.git.isCheckout(localSrc)
                        ? localSrc.toString()
                        : boot.config.orgUrl() + "/" + repo + ".git";
                Path target = boot.state.repoDir(name);
                if (boot.git.isCheckout(target)) {
                    ctx.status("refreshing " + repo);
                    Boot.must(boot.git.pullFastForward(target, ctx::log),
                            "refresh of " + repo + " failed — " + target + " cannot fast-forward "
                                    + "to " + from + ", so this run would build a commit that is "
                                    + "no longer anywhere. Delete that directory and rerun");
                } else {
                    ctx.status("cloning " + repo + " from " + from);
                    Boot.must(boot.git.clone(from, target, ctx::log), "clone of " + repo + " failed");
                }
                ctx.log(String.format("  %-26s %s  (%s)", repo, boot.git.shortHead(target), from));
            }
            ctx.note(PlatformModel.platformRepos().size() + " repositories");
        });
    }

    /**
     * What a previous bootstrap generated and this one must not change. Read before anything needs
     * it, so a full rerun keeps the secrets it issued last time.
     */
    public Phase recordedState() {
        return new Phase("recorded-state", "read the recorded run state", ctx -> {
            BootstrapState state = new BootstrapState(
                    boot.state.wrapperDir.resolve(BootstrapState.FILE_NAME));
            state.read();
            boot.state.daemonSha = state.daemonSha().orElse(null);
            for (String client : PlatformModel.idpClients(boot.config.envName())) {
                state.secret(client).ifPresent(secret -> boot.state.secrets.put(client, secret));
            }
            if (!state.exists()) {
                ctx.log("  no " + BootstrapState.FILE_NAME + " — this is a first boot");
                ctx.note("first boot");
                return;
            }
            ctx.log("  " + state.file());
            ctx.log("  recorded daemon digest: "
                    + (boot.state.daemonSha == null ? "none" : shortSha(boot.state.daemonSha)));
            ctx.log("  recorded client secrets: " + boot.state.secrets.size() + " of "
                    + PlatformModel.idpClients(boot.config.envName()).size());
            ctx.note("kept " + boot.state.secrets.size() + " secrets");
        });
    }

    /** The skip-build path's one duty: the digest is a run-pinned value the compose file needs. */
    public Phase skipBuildGate() {
        return new Phase("seed-skipped", "seed builds skipped (QITS_SKIP_BUILD)", ctx -> {
            if (boot.state.daemonSha == null || boot.state.daemonSha.isBlank()) {
                throw new IllegalStateException("QITS_SKIP_BUILD is set but no DAEMON_SHA was "
                        + "recorded in " + BootstrapState.FILE_NAME + " — rerun without it");
            }
            ctx.log("  reusing the recorded ci-daemon digest " + shortSha(boot.state.daemonSha));
            ctx.note("digest " + shortSha(boot.state.daemonSha));
        });
    }

    // --- the first-boot dependency cycle ----------------------------------------------------------

    /**
     * qits-artifacts consumes qits libraries while also being the Maven registry that owns them in
     * steady state. The cycle is broken with a temporary, bootstrap-owned file repository, served
     * over HTTP on the registry port and removed before the real artifacts container claims it.
     * <p>
     * <b>The byte-plane split made this phase carry several libraries instead of one</b>, and that
     * is not a widening for its own sake: qits-artifacts, qits-platform-mirror and qits-githost are
     * built out of qits-blobstore and qits-registries, and all three of their seed images are built
     * before anything of this platform can publish a jar. A seed build resolving a library the
     * temporary registry does not hold fails minutes in, naming a version nobody ever pushed.
     * <p>
     * <b>The temporary registry holds every qits jar a seed image could ask for, whatever the plan's
     * order is.</b> qits-ci's image consumes qits-githost-events and qits-containers-client, and
     * today it is built after {@code seed-artifacts} has taken the registry port — so the copies it
     * really resolves are the store's, published by {@link #mavenPublish}. Both lists carry those
     * jars because the two are one port: whichever server is behind it when an image builds has to
     * answer, and a reordering that moves an image to the other side of {@code seed-artifacts} must
     * not be able to turn that into a failed build.
     * <p>
     * <b>One container, one deploy per entry, in the order {@link #SEED_LIBRARIES} spells.</b>
     * {@code mvn deploy} installs into the local repository on its way out, so qits-registries
     * resolves the qits-blobstore the line above it just built — which is the whole reason these
     * are not one container each. The other side of that: a library built before the qits jar it
     * depends on resolves nothing local, reaches for the registry port that no server holds yet,
     * and fails with "Connection refused". Read the poms before moving a line.
     * <p>
     * That local repository is now {@link #MAVEN_CACHE_VOLUME}, kept across runs so Central is not
     * re-read every bootstrap — and the script starts by deleting this platform's own group out of
     * it, so what one line hands the next is always this run's bytes. {@link #MAVEN_PURGE_QITS}
     * says why that is not optional.
     */
    public Phase mavenSeed() {
        return new Phase("maven-seed",
                "seed the qits libraries for the first byte-plane image builds",
                ctx -> {
                    // Version-agnostic on purpose: the checkouts publish their real calver, so a
                    // pinned-version probe never matches and a rerun collides with whoever holds
                    // the registry port. Metadata present = qits-containers-client is served,
                    // whatever version — and it is the LAST entry of SEED_LIBRARIES, deployed by
                    // the last line of the one script below, so its presence answers for the whole
                    // set. The sentinel has to be the last entry and nothing else: an earlier one
                    // is served halfway through a run that then died, and the probe would skip a
                    // phase that never finished.
                    //
                    // TWO ADDRESSES, because in-network there are two servers rather than one
                    // port. On the host both answered 127.0.0.1:REGISTRY_PORT, so one probe found
                    // whoever held it. Here the platform's own store answers under its wire alias
                    // and a previous run's temporary registry under its container name, and either
                    // one means this phase has nothing left to do.
                    if (boot.http.get(boot.config.artifactsUrl() + SEED_SENTINEL_METADATA,
                                    Map.of()).ok()
                            || boot.http.get(AUTH_SEED_URL + SEED_SENTINEL_METADATA, Map.of()).ok()) {
                        ctx.skip("qits-containers-client is already served");
                    }
                    // The platform's own store may already hold the registry port, and then the
                    // temporary one cannot have it — the bind fails with "port is already
                    // allocated" and the boot stops. It does not need it either: the store IS the
                    // Maven registry the seed builds resolve against, and every one of these
                    // libraries is published into it by every bootstrap that gets past phase 10. If
                    // a store this far along somehow has not got one, the seed build below fails by
                    // name rather than resolving nothing quietly.
                    storeAlreadyServing().ifPresent(who ->
                            ctx.skip(who + " serves port " + boot.config.registryPort()
                                    + " — it is the registry"));
                    boot.docker.ensureVolume("qits-maven-seed", ctx::log);
                    boot.docker.ensureVolume(MAVEN_CACHE_VOLUME, ctx::log);
                    String cid = create(ctx, List.of(
                            "docker", "create", "--user", "root", "--entrypoint", "sh",
                            "-v", "qits-maven-seed:/repo", "-v", MAVEN_CACHE_MOUNT,
                            "maven:3.9-eclipse-temurin-25",
                            "-c", seedScript()));
                    for (String library : SEED_LIBRARIES) {
                        ctx.log("  " + PlatformModel.repo(library) + " -> /src-" + library);
                        Boot.must(boot.docker.exec(Duration.ofMinutes(30), ctx::log, "cp",
                                        boot.state.repoDir(library) + "/.", cid + ":/src-" + library),
                                "copying " + PlatformModel.repo(library) + " in failed");
                    }
                    startAndReap(ctx, cid, "the qits library seed failed");

                    String container = AUTH_SEED_HTTP;
                    boot.docker.removeContainer(container, null);
                    // TWO CONSUMERS, TWO ADDRESSES, and both are needed:
                    //
                    //   --network qits-net  is for THIS CLI, which is on that network and can
                    //                       reach nothing that is not. The container name is the
                    //                       alias; AUTH_SEED_URL is what gets dialled.
                    //   -p 127.0.0.1:PORT   is for the HOST'S DOCKER DAEMON. The seed image builds
                    //                       run with --network host and resolve Maven through
                    //                       localhost:REGISTRY_PORT, and that consumer did not
                    //                       move onto the network with the CLI.
                    //
                    // Dropping either one hangs a phase rather than failing it: without the
                    // network the probe above never answers, without the publish the first seed
                    // build resolves nothing.
                    Boot.must(boot.docker.exec(ctx::log, "run", "-d", "--name", container,
                                    "--network", Boot.NETWORK,
                                    "-p", "127.0.0.1:" + boot.config.registryPort() + ":80",
                                    "-v", "qits-maven-seed:/usr/share/nginx/html/artifacts/maven/maven:ro",
                                    "nginx:alpine"),
                            "the temporary maven registry did not start");
                    boot.state.authSeedContainer = container;
                    // Even a failed run must not leave the registry port held by this container.
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        if (boot.state.authSeedContainer != null) {
                            boot.docker.removeContainer(boot.state.authSeedContainer, null);
                        }
                    }));
                    ctx.note("temporary maven registry up");
                });
    }

    // --- seed images ------------------------------------------------------------------------------

    public Phase seedImage(String name) {
        return new Phase("seed-image-" + name, "build the seed image qits/" + name + ":latest", ctx -> {
            Path repo = boot.state.repoDir(name);
            ctx.status("fetching submodules of qits-" + name);
            boot.git.submodulesShallow(repo, ctx::log);

            String seedUi = PlatformModel.seedUiPath(name);
            if (!seedUi.isEmpty()) {
                // Seed services only need their APIs. Their Dockerfiles consume an already-built
                // SPA, and a clean checkout has no dist directory while the npm registry does not
                // exist yet. The normal pipeline builds the real client from the same commit.
                Path ui = repo.resolve(seedUi);
                Files.createDirectories(ui);
                Files.writeString(ui.resolve("index.html"),
                        "<!doctype html><html><body>qits bootstrap</body></html>\n",
                        StandardCharsets.UTF_8);
                ctx.log("  placeholder client at " + seedUi);
            }

            List<String> extra = new ArrayList<>();
            if ("gateway".equals(name)) {
                // A shipped gateway must say whether it authenticates; `local` is the
                // unauthenticated workstation variant. Never publish that image or its port.
                extra.addAll(List.of("--build-arg", "QITS_VARIANT=local"));
            }
            String dockerfile =
                    SeedDockerfile.read(repo.resolve(PlatformModel.dockerfilePath(name)));
            // An image repository is its Dockerfile and nothing else, so the warning about the
            // four-gigabyte native build would be a lie about a thirty-second pull.
            ctx.status("Dockerfile".equals(PlatformModel.dockerfilePath(name))
                    ? "building qits/" + name + " from its Dockerfile"
                    : "cold GraalVM native build of qits/" + name + " — ~4 GB RAM, no maven cache");
            ProcessResult result = boot.docker.buildFromStdin("qits/" + name + ":latest",
                    dockerfile, repo, extra, ctx::log);
            Boot.must(result, "build of qits/" + name + " failed");
        });
    }

    /**
     * The step images pipeline configs name. qits-oci is their single source of truth, but the
     * first ci-base cannot be built by the pipeline that needs ci-base to run.
     */
    public Phase stepImage(String name) {
        return new Phase("step-image-" + name, "build qits/build-images/" + name + ":latest", ctx -> {
            Path oci = boot.state.repoDir("oci");
            ProcessResult result = boot.docker.build(List.of(
                    "-t", "qits/build-images/" + name + ":latest",
                    "-f", oci.resolve(name).resolve("Dockerfile").toString(),
                    oci.toString()), ctx::log);
            Boot.must(result, "build of qits/build-images/" + name + " failed");
        });
    }

    /**
     * <b>Brings up qits-platform-mirror alone, because everything after it resolves through it.</b>
     * The Maven publishes below fetch their plugins and their third-party dependencies through the
     * mirror's Maven Central cache, and the npm ones fetch every package that is not {@code @qits}
     * through its npmjs cache — so a mirror that is not answering is not a slow publish but a failed
     * one.
     * <p>
     * <b>It cannot pull through itself, and nothing here has to arrange that.</b> Its own seed image
     * was built minutes ago with the mirror prefixes rewritten to the direct upstreams, exactly like
     * every other seed image — see {@link SeedDockerfile}. What this phase starts is a service whose
     * layers came from quay.io.
     * <p>
     * Started by hand rather than by compose, under the wire alias the compose service will claim,
     * so the two never run side by side on the published port. Whoever already serves, serves: a
     * deployed mirror publishes the same port from the same volume and is strictly better than the
     * seed this phase would have started.
     */
    public Phase seedMirrorStart() {
        return new Phase("seed-mirror",
                "have qits-platform-mirror serving before anything resolves through it", ctx -> {
            boot.docker.ensureNetwork(Boot.NETWORK, ctx::log);
            boot.docker.ensureVolume("qits-platform-mirror-data", ctx::log);
            String mirror = PlatformModel.wireAlias("platform-mirror", boot.config.envName());
            String prefix = PlatformModel.pdNamePrefix("platform-mirror", boot.config.envName());
            Optional<String> serving = boot.docker.runningNames().stream()
                    .filter(name -> name.equals(mirror) || name.startsWith(prefix))
                    .findFirst();
            if (serving.isPresent()) {
                ctx.log("  " + serving.get() + " already serves port " + boot.config.mirrorPort()
                        + " from the same volume — no seed mirror to start");
            } else {
                boot.docker.removeContainer(mirror, null);
                Boot.must(boot.docker.run(Cmd.of(List.of(
                                "docker", "run", "-d", "--name", mirror,
                                "--network", Boot.NETWORK,
                                "-p", "127.0.0.1:" + boot.config.mirrorPort() + ":8080",
                                // The seed's own credential, on the same terms as the idp's and the
                                // bus's: this container starts before any deployer exists, so the
                                // role and the database were created by seed-postgres and handed
                                // over here. It refuses to boot without the triple.
                                "-e", "QITS_RESOURCE_DB_URL=jdbc:postgresql://"
                                        + PlatformModel.wireAlias("oci-postgresql",
                                                boot.config.envName())
                                        + ":5432/qits_platform_mirror",
                                "-e", "QITS_RESOURCE_DB_USERNAME=qits_platform_mirror",
                                "-e", "QITS_RESOURCE_DB_PASSWORD="
                                        + orEmpty(boot.state.pgPlatformMirrorPassword),
                                // Spelled because the jar's default sits under ${user.home}, which
                                // this image's passwd-less UID resolves to the literal "?".
                                "-e", "QITS_ARTIFACTS_BLOBS_DIR=/data/mirror/blobs",
                                "-v", "qits-platform-mirror-data:/data",
                                "qits/platform-mirror:latest"))
                        .mask(orEmpty(boot.state.pgPlatformMirrorPassword)), ctx::log),
                        "the seed " + mirror + " did not start");
            }
            // Always waited for, whoever is behind the alias: the four publishes after this phase
            // resolve through it, and a phase that skipped the start still owes them that.
            boot.awaitHealth(ctx, serving.orElse(mirror) + " on qits-net",
                    () -> boot.http.get(boot.config.mirrorUrl() + "/mirror/q/health/ready",
                            Map.of()));
        });
    }

    /**
     * Brings up qits-artifacts alone, so the Maven and npm publishes below have somewhere to land
     * before any pipeline exists. It STARTS one only when nothing is serving the registry port yet
     * — what this phase owes the ones after it is a store that answers, not a container it created.
     */
    public Phase seedArtifactsStart() {
        return new Phase("seed-artifacts",
                "have qits-artifacts serving for the Maven bootstrap", ctx -> {
            // By name and unconditionally: a crashed earlier run leaves the registry running, and
            // this run then skips the seed phase without ever learning the container's name.
            ctx.log("  removing the temporary maven registry, freeing port "
                    + boot.config.registryPort());
            boot.docker.removeContainer(AUTH_SEED_HTTP, ctx::log);
            boot.state.authSeedContainer = null;
            boot.docker.ensureNetwork(Boot.NETWORK, ctx::log);
            boot.docker.ensureVolume("qits-artifacts-data", ctx::log);
            // Named after its wire alias, like every other seed container: this one is started by
            // hand rather than by compose, and the name it takes has to be the same one the compose
            // service would have claimed, or the two run side by side on the registry port.
            String artifacts = PlatformModel.wireAlias("artifacts", boot.config.envName());
            // Whoever already holds the port, holds it. A seed beside a store that is up is
            // impossible — the bind answers "port is already allocated", exit 125, and the boot
            // stopped exactly there on the 2026-08-08 validation rerun — and pointless: a DEPLOYED
            // store publishes this very port from the same qits-platform-artifacts-data volume and
            // answers the same API, so it is strictly better than the seed this phase would have
            // started. This run's own seed container is asked for by name first, so a rerun still
            // reports it as itself rather than as the deployer's.
            Optional<String> serving = boot.docker.runningNames().contains(artifacts)
                    ? Optional.of(artifacts) : storeAlreadyServing();
            if (serving.isPresent()) {
                ctx.log("  " + serving.get() + " already serves port " + boot.config.registryPort()
                        + " from the same volume — no seed store to start");
                ctx.note(serving.get() + " serves :" + boot.config.registryPort());
            } else {
                boot.docker.removeContainer(artifacts, null);
                // No git env any more, and no ci intake: this service hosts no repositories and
                // announces nothing since the byte-plane split. What is left is the store.
                Boot.must(boot.docker.exec(ctx::log, "run", "-d", "--name", artifacts,
                                "--network", Boot.NETWORK,
                                "-p", "127.0.0.1:" + boot.config.registryPort() + ":8080",
                                "-e", "QUARKUS_DATASOURCE_ARTIFACTS_JDBC_URL=jdbc:h2:file:/data/artifacts/h2/artifacts",
                                "-e", "QITS_ARTIFACTS_BLOBS_DIR=/data/artifacts/blobs",
                                "-v", "qits-artifacts-data:/data",
                                "qits/artifacts:latest"),
                        "the seed " + artifacts + " did not start");
            }
            // Always waited for, whoever is behind the alias: every publish after this phase — the
            // two Maven ones, both npm ones, the daemon upload — needs the store answering, and a
            // phase that skipped the start still owes them that.
            boot.awaitHealth(ctx, serving.orElse(artifacts) + " on qits-net",
                    boot.artifacts::health);
        });
    }

    /**
     * Who already holds the registry port, when the platform's own store does — the deployed
     * container's name if one is running, otherwise the service's plain name.
     * <p>
     * <b>The answer comes from the API, not from the container list.</b> A deployed store is named
     * {@code qits-pd-<env>-qits-artifacts-<id8>}, and matching that shape alone would still miss
     * anything else the port could be behind. The artifacts API's own health is the honest
     * question: it is asked at the store's WIRE ALIAS, which the temporary Maven registry does not
     * answer to at all — it is an nginx under its own name — while qits-artifacts answers 200 there
     * whether it was started by this bootstrap, by compose or by the deployer. The container list is
     * then read only to name who it is, which is what makes the phase log readable.
     * <p>
     * Both phases that bind the registry port ask this before they bind it. Neither can win that
     * bind, and neither needs to: the store on the other end has the same volume.
     */
    private Optional<String> storeAlreadyServing() {
        if (!boot.artifacts.ready()) {
            return Optional.empty();
        }
        String prefix = PlatformModel.pdNamePrefix("artifacts", boot.config.envName());
        return Optional.of(boot.docker.runningNames().stream()
                .filter(name -> name.startsWith(prefix))
                .findFirst()
                .orElse(PlatformModel.wireAlias("artifacts", boot.config.envName())));
    }

    // --- the publishes the seed builds need -------------------------------------------------------

    /** The version the repo's root pom would publish: its first version element outside parent. */
    static String checkedOutVersion(Path repoDir) {
        try {
            String pom = Files.readString(repoDir.resolve("pom.xml"), StandardCharsets.UTF_8);
            String withoutParent = pom.replaceAll("(?s)<parent>.*?</parent>", "");
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("<version>([^<$]+)</version>").matcher(withoutParent);
            return m.find() ? m.group(1).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The maven arguments that build ONE module of a repository, or none when the whole repository
     * is published. Both halves of the seed use it, so a repository published by module is
     * published the same way into the temporary registry and into the store.
     */
    static String mavenModuleArgs(String repoName) {
        String module = PlatformModel.mavenModule(repoName);
        return module.isEmpty() ? "" : " -pl " + module + " -am";
    }

    /** One container, every seed library, into the temporary file repository at {@code /repo}. */
    static String seedScript() {
        StringBuilder script = new StringBuilder("set -eu\n").append(MAVEN_PURGE_QITS);
        for (String library : SEED_LIBRARIES) {
            script.append("cd /src-").append(library)
                    .append(" && mvn -B -ntp deploy -DskipTests")
                    .append(mavenModuleArgs(library))
                    .append(MAVEN_REPO_LOCAL)
                    .append(" -DaltDeploymentRepository=seed::default::file:///repo\n");
        }
        return script.toString();
    }

    /** One repository, into the store. The settings first, then the purge, then the build. */
    String publishScript(String repoName) {
        return mavenSettings() + MAVEN_PURGE_QITS
                + "cd /src && mvn -B -ntp -s /root/.m2/settings.xml deploy -DskipTests"
                + mavenModuleArgs(repoName) + MAVEN_REPO_LOCAL
                + " -DaltDeploymentRepository=qits::default::"
                + boot.config.artifactsUrl() + "/maven/maven";
    }

    public Phase mavenPublish(String repoName, String artifactId, String title) {
        return new Phase("publish-" + artifactId, title, ctx -> {
            // The checkouts publish their real calver, and the registry refuses to overwrite a
            // released version (403) — so probe the version this checkout would publish, parsed
            // from its root pom, before starting a container. Found by the third proving run.
            String version = checkedOutVersion(boot.state.repoDir(repoName));
            if (version != null
                    && boot.artifacts.mavenPublished("eu/wohlben/qits", artifactId, version, "jar")) {
                ctx.skip(artifactId + " " + version + " already published");
            }
            boot.docker.ensureVolume(MAVEN_CACHE_VOLUME, ctx::log);
            String cid = create(ctx, List.of(
                    "docker", "create", "--network", Boot.NETWORK, "--user", "root",
                    "--entrypoint", "sh", "-v", MAVEN_CACHE_MOUNT,
                    "maven:3.9-eclipse-temurin-25",
                    "-c", publishScript(repoName)));
            copyIn(ctx, boot.state.repoDir(repoName), cid);
            startAndReap(ctx, cid, artifactId + " publish failed");
        });
    }

    /**
     * The shared UI package, twice: the pinned version the checked-out lockfiles install, then
     * whatever the working tree is at now. Publish-if-absent makes both idempotent.
     */
    /**
     * Seed publishes are PINNED COMMITS ONLY. A working-tree publish under an already-released
     * version puts different bytes behind a version every lockfile pins by hash — EINTEGRITY in
     * every SPA build. The release replays own the released versions; unreleased work reaches the
     * registry through a release, never through the seed. Found by the v3 proving run.
     */
    public Phase uiComponentsPublish() {
        return new Phase("publish-ui-components", "publish the shared UI package into seed artifacts",
                ctx -> {
                    String script = """
                            set -eu
                            apk add --no-cache git >/dev/null
                            git config --global --add safe.directory '*'
                            git clone -q /src /src-004
                            cd /src-004
                            git checkout -q 9f9648482d6fe025cc7af9bd4496afab417f33f9
                            corepack enable
                            %s
                            pnpm install --frozen-lockfile
                            pnpm build
                            cd dist/qits-spa-ui-components
                            npm view @qits/ui-components@0.0.4 version >/dev/null 2>&1 || npm publish

                            """.formatted(npmrc());
                    nodePublish(ctx, "spa-ui-components", script, "UI package publish failed");
                });
    }

    public Phase angularPublish() {
        return new Phase("publish-angular", "publish the Angular integration package into seed artifacts",
                ctx -> {
                    String script = """
                            set -eu
                            apk add --no-cache git >/dev/null
                            git config --global --add safe.directory '*'
                            git clone -q /src /src-001
                            cd /src-001
                            git checkout -q 3f405717f14f0942399340d84db4ef0ca3769101
                            corepack enable
                            %s
                            pnpm install --frozen-lockfile
                            pnpm build
                            version=$(node -p "require(\\"./dist/qits-integrations-angular/package.json\\").version")
                            npm view "@qits/angular@$version" version >/dev/null 2>&1 || npm publish ./dist/qits-integrations-angular

                            """.formatted(npmrc());
                    nodePublish(ctx, "integrations-angular", script,
                            "Angular integration publish failed");
                });
    }

    /**
     * <b>The npm half of the two-endpoint topology, as a shell fragment.</b> Two registries, and
     * which package goes to which is decided by its SCOPE rather than by a path:
     * <ul>
     *   <li>{@code @qits} → this environment's own qits-artifacts, which is where these two phases
     *       publish and where every SPA build reads the platform's own packages from. The auth-token
     *       line beside it is what makes {@code npm publish} attempt the write at all — the registry
     *       takes any value, and the immutable version is what keeps it honest.
     *   <li>everything else → qits-platform-mirror's cache of npmjs. One cache for the machine, cold
     *       on a fresh platform and warm by the second build.
     * </ul>
     * <b>The default registry must not be the qits one.</b> A scoped entry is an override, so the
     * default is what every unscoped package resolves through — and the hosted registry serves only
     * what was published to it, which for npmjs' half of a lockfile is nothing.
     */
    private String npmrc() {
        return "cat > /root/.npmrc <<'NPMRC'\n"
                + "registry=" + boot.config.mirrorUrl() + "/artifacts/npm/npmjs/\n"
                + "@qits:registry=" + boot.config.artifactsUrl() + "/npm/npm/\n"
                + "//" + boot.config.artifactsUrl().replaceFirst("^https?://", "")
                + "/npm/npm/:_authToken=qits-bootstrap\n"
                + "NPMRC";
    }

    /**
     * <b>The third-party half of the two-endpoint topology, as a shell fragment.</b> A publish
     * container resolves its plugins and its dependencies from Maven Central, and since the
     * byte-plane split that means qits-platform-mirror's cache of it rather than the internet: one
     * cache for the machine, warmed by the first build that needs a jar and reused by every one
     * after it.
     * <p>
     * <b>A {@code <mirror>} with an exact {@code mirrorOf}, not a {@code <repository>}</b>, and that
     * is what gets it past Maven's own {@code external:http:*} blocker: a plain HTTP repository is
     * refused since 3.8.1, while an id-matched mirror of {@code central} wins over the blocking
     * entry. It is the same shape every service's committed {@code .qits-maven-settings.xml} uses
     * for the qits repository.
     * <p>
     * The deployment target is NOT here. It is {@code -DaltDeploymentRepository} on the command
     * line, because where a publish LANDS is the phase's decision and where it READS from is the
     * platform's.
     */
    private String mavenSettings() {
        return "mkdir -p /root/.m2 && cat > /root/.m2/settings.xml <<'SETTINGS'\n"
                + "<settings>\n"
                + "  <mirrors>\n"
                + "    <mirror>\n"
                + "      <id>qits-central-proxy</id>\n"
                + "      <mirrorOf>central</mirrorOf>\n"
                + "      <url>" + boot.config.mirrorUrl() + "/artifacts/maven/central</url>\n"
                + "    </mirror>\n"
                // The hosted half: a publish that consumes an earlier publish (registries needs
                // blobstore, githost-events needs eventstream) resolves it from the store it was
                // just deployed to. Same exact-id trick — the consumer poms declare a `qits-maven`
                // repository, and this mirror is what wins over the external:http:* blocker.
                + "    <mirror>\n"
                + "      <id>qits-maven</id>\n"
                + "      <mirrorOf>qits-maven</mirrorOf>\n"
                + "      <url>" + boot.config.artifactsUrl() + "/maven/maven</url>\n"
                + "    </mirror>\n"
                + "  </mirrors>\n"
                + "</settings>\n"
                + "SETTINGS\n";
    }

    private void nodePublish(PhaseContext ctx, String repoName, String script, String failure)
            throws Exception {
        String cid = create(ctx, List.of(
                "docker", "create", "--network", Boot.NETWORK, "--user", "root",
                "--entrypoint", "sh", "node:24-alpine", "-c", script));
        copyIn(ctx, boot.state.repoDir(repoName), cid);
        startAndReap(ctx, cid, failure);
    }

    // --- the ci-daemon binary ---------------------------------------------------------------------

    /**
     * A fully static musl native binary, built inside the builder image: docker cp carries the
     * source in and the binary out. container-build is off because we are already in the container
     * it would otherwise launch.
     */
    public Phase ciDaemon() {
        return new Phase("ci-daemon", "build the qits-ci-daemon binary (musl static native)", ctx -> {
            Path repo = boot.state.repoDir("ci-daemon");
            String dockerfile = SeedDockerfile.read(repo.resolve("docker/Dockerfile.musl-builder"));
            ctx.status("building the musl builder image");
            Boot.must(boot.docker.buildFromStdin("qits/graalvmce-musl-builder:jdk-25", dockerfile,
                            repo.resolve("docker"), List.of(), ctx::log),
                    "the musl builder image failed to build");

            // --entrypoint: the builder image entrypoints to native-image itself.
            //
            // The shared download cache, for the same reason the publishes have it — and this
            // build needs it most: it is on no network of ours, so it reads Maven Central direct
            // rather than through the mirror's proxy, with nothing in front of it to be warm.
            boot.docker.ensureVolume(MAVEN_CACHE_VOLUME, ctx::log);
            String cid = create(ctx, List.of(
                    "docker", "create", "--user", "root", "--entrypoint", "bash",
                    "-v", MAVEN_CACHE_MOUNT, "qits/graalvmce-musl-builder:jdk-25",
                    "-c", MAVEN_PURGE_QITS
                            + "cd /qits-build && ./mvnw -B -ntp -pl ci-daemon -am package -Dnative "
                            + "-DskipTests -Dquarkus.native.container-build=false"
                            + MAVEN_REPO_LOCAL));
            Boot.must(boot.docker.exec(Duration.ofMinutes(30), ctx::log,
                    "cp", repo.toString(), cid + ":/qits-build"), "copying the daemon source in failed");
            ctx.status("cold musl native build of the ci-daemon");
            ProcessResult run = boot.docker.exec(Docker.BUILD_TIMEOUT,
                    ctx::log, "start", "-a", cid);
            if (!run.ok()) {
                boot.docker.removeContainer(cid, null);
                throw new IllegalStateException("ci-daemon build failed\n" + run.tailText(20));
            }
            Path out = boot.state.wrapperDir.resolve(".qits-bootstrap").resolve("qits-ci-daemon");
            Files.createDirectories(out.getParent());
            Boot.must(boot.docker.exec(Duration.ofMinutes(30), ctx::log,
                            "cp", cid + ":/qits-build/ci-daemon/target/qits-ci-daemon", out.toString()),
                    "copying the daemon binary out failed");
            boot.docker.exec(null, "rm", cid);

            boot.state.daemonBinary = out;
            boot.state.daemonSha = sha256(out);
            ctx.log("  ci-daemon digest: sha256:" + boot.state.daemonSha);
            ctx.note("digest " + shortSha(boot.state.daemonSha));
        });
    }

    // --- postgres ---------------------------------------------------------------------------------

    /**
     * The platform's postgres, running with a password of this bootstrap's choosing from its very
     * first boot, and every database a seed container boots against.
     * <p>
     * <b>Before the first container that refuses to boot without a database, whichever one that
     * is.</b> It used to be enough to sit before the compose file, because everything with a store
     * was started by compose; qits-platform-mirror is started BY HAND several phases earlier, so
     * this phase moved up in front of it. On {@code QITS_SKIP_BUILD} there is no mirror start and
     * no image build, and it runs where it always did — the passwords it resolves fill both
     * generated files either way. That is why {@code BootstrapPlan} places it in each arm rather
     * than once after both: one run, one placement, and the right one for each.
     * <p>
     * <b>The image's own {@code POSTGRES_PASSWORD=qits-poc} never lives on this platform.</b> The
     * value below is generated on the first boot and recorded, and initdb takes it instead.
     * <p>
     * <b>Which databases are here, and which are deliberately not.</b> This phase provisions what
     * the SEED STACK needs: the deployer's own store AND ITS OUTBOX, and the stores of the core
     * services that come up beside it — qits-ci (its database and its outbox), qits-platform-idp,
     * qits-platform-dns, qits-events, qits-platform-mirror, qits-githost (its database and its
     * outbox) and qits-containers (the same pair). Four of the twelve are outboxes because the
     * eventstream library keeps its own Flyway lineage and cannot share a database with its host; ci
     * carried one from the start, the deployer joined the bus on 2026-08-10, and the git host and
     * the orchestrator are the newest publishers on it.
     * Every one of them runs Flyway at boot against a database that has to exist
     * already, and at that point in a cold boot no deployer exists to make one. The nameserver is
     * the loudest about it on purpose: it refuses to start rather than answer NXDOMAIN for every
     * hostname the platform hands out. Everything else — projects, workspaces, observability — is
     * pipeline-deployed only, so the deployer creates their roles and databases during their own
     * deployments from the {@code resources:} line in each repository's deployments.yml. Adding
     * them here would put a second authority on a credential that has exactly one.
     */
    public Phase seedPostgres() {
        return new Phase("seed-postgres", "start postgres and provision the seed's databases",
                ctx -> {
            BootstrapState state = new BootstrapState(
                    boot.state.wrapperDir.resolve(BootstrapState.FILE_NAME));
            state.read();
            String superuser = pgPassword(ctx, state, "PG_SUPERUSER_PASSWORD",
                    "qits.pg.superuser-password");
            String deployments = pgPassword(ctx, state, "PG_DEPLOYMENTS_PASSWORD",
                    "qits.pg.deployments-password");
            String deploymentsEventstream = pgPassword(ctx, state,
                    "PG_DEPLOYMENTS_EVENTSTREAM_PASSWORD",
                    "qits.pg.deployments-eventstream-password");
            String ci = pgPassword(ctx, state, "PG_CI_PASSWORD", "qits.pg.ci-password");
            String ciEventstream = pgPassword(ctx, state, "PG_CI_EVENTSTREAM_PASSWORD",
                    "qits.pg.ci-eventstream-password");
            String platformIdp = pgPassword(ctx, state, "PG_PLATFORM_IDP_PASSWORD",
                    "qits.pg.platform-idp-password");
            String platformDns = pgPassword(ctx, state, "PG_PLATFORM_DNS_PASSWORD",
                    "qits.pg.platform-dns-password");
            String events = pgPassword(ctx, state, "PG_EVENTS_PASSWORD", "qits.pg.events-password");
            String platformMirror = pgPassword(ctx, state, "PG_PLATFORM_MIRROR_PASSWORD",
                    "qits.pg.platform-mirror-password");
            String githost = pgPassword(ctx, state, "PG_GITHOST_PASSWORD",
                    "qits.pg.githost-password");
            String githostEventstream = pgPassword(ctx, state, "PG_GITHOST_EVENTSTREAM_PASSWORD",
                    "qits.pg.githost-eventstream-password");
            String containers = pgPassword(ctx, state, "PG_CONTAINERS_PASSWORD",
                    "qits.pg.containers-password");
            String containersEventstream = pgPassword(ctx, state,
                    "PG_CONTAINERS_EVENTSTREAM_PASSWORD",
                    "qits.pg.containers-eventstream-password");
            boot.state.pgSuperuserPassword = superuser;
            boot.state.pgDeploymentsPassword = deployments;
            boot.state.pgDeploymentsEventstreamPassword = deploymentsEventstream;
            boot.state.pgCiPassword = ci;
            boot.state.pgCiEventstreamPassword = ciEventstream;
            boot.state.pgPlatformIdpPassword = platformIdp;
            boot.state.pgPlatformDnsPassword = platformDns;
            boot.state.pgEventsPassword = events;
            boot.state.pgPlatformMirrorPassword = platformMirror;
            boot.state.pgGithostPassword = githost;
            boot.state.pgGithostEventstreamPassword = githostEventstream;
            boot.state.pgContainersPassword = containers;
            boot.state.pgContainersEventstreamPassword = containersEventstream;

            // RECORDED BEFORE THE SERVER IS STARTED, and the order is the whole point.
            // POSTGRES_PASSWORD applies at initdb only: once the data volume holds a cluster, the
            // value in the container's env is ignored and the password inside the cluster is the
            // only one that works. A run that started the server and then died before writing this
            // file would leave a database nothing on this machine can open.
            //
            // The application passwords are written here for a second reason: their roles are
            // created once and never altered, so a value that reached postgres and not this file
            // would be lost with the run that generated it.
            state.put("PG_SUPERUSER_PASSWORD", superuser);
            state.put("PG_DEPLOYMENTS_PASSWORD", deployments);
            state.put("PG_DEPLOYMENTS_EVENTSTREAM_PASSWORD", deploymentsEventstream);
            state.put("PG_CI_PASSWORD", ci);
            state.put("PG_CI_EVENTSTREAM_PASSWORD", ciEventstream);
            state.put("PG_PLATFORM_IDP_PASSWORD", platformIdp);
            state.put("PG_PLATFORM_DNS_PASSWORD", platformDns);
            state.put("PG_EVENTS_PASSWORD", events);
            state.put("PG_PLATFORM_MIRROR_PASSWORD", platformMirror);
            state.put("PG_GITHOST_PASSWORD", githost);
            state.put("PG_GITHOST_EVENTSTREAM_PASSWORD", githostEventstream);
            state.put("PG_CONTAINERS_PASSWORD", containers);
            state.put("PG_CONTAINERS_EVENTSTREAM_PASSWORD", containersEventstream);
            state.write();
            ctx.log("  recorded in " + state.file() + " before the server starts");

            // The `network` phase already created it and put this run on it; the call stays so the
            // phase says what it needs, and it is a no-op on an existing network.
            boot.docker.ensureNetwork(Boot.NETWORK, ctx::log);
            boot.docker.ensureVolume("qits-oci-postgresql-data", ctx::log);
            String pg = PlatformModel.wireAlias("oci-postgresql", boot.config.envName());
            // Whoever already serves, serves — the same posture as the seed artifacts store. A
            // deployed postgres answers the same alias and publishes the same host port from this
            // very volume, and a second bind is "port is already allocated" and the end of the run.
            String prefix = PlatformModel.pdNamePrefix("oci-postgresql", boot.config.envName());
            Optional<String> serving = boot.docker.runningNames().stream()
                    .filter(name -> name.equals(pg) || name.startsWith(prefix))
                    .findFirst();
            if (serving.isPresent()) {
                ctx.log("  " + serving.get() + " already serves " + pg
                        + " from the same volume — no seed server to start");
            } else {
                boot.docker.removeContainer(pg, null);
                Boot.must(boot.docker.run(Cmd.of(List.of(
                                "docker", "run", "-d", "--name", pg,
                                "--network", Boot.NETWORK,
                                "-p", "127.0.0.1:" + boot.config.pgPort() + ":5432",
                                // /var/lib/postgresql, NOT /var/lib/postgresql/data: postgres 18
                                // keeps PGDATA at /var/lib/postgresql/18/docker, so the pre-18 path
                                // mounts the volume BESIDE the cluster — every byte then goes into
                                // the container layer and is lost on the next recreate.
                                "-v", "qits-oci-postgresql-data:/var/lib/postgresql",
                                "-e", "POSTGRES_PASSWORD=" + superuser,
                                "qits/oci-postgresql:latest"))
                        .mask(superuser), ctx::log), "the seed " + pg + " did not start");
            }

            // Always waited for, whoever is behind it: the statements below need a server, and a
            // first boot spends its first seconds in initdb rather than listening.
            //
            // The WIRE ALIAS on 5432, like every other consumer of this database — the published
            // host port is for a person with a psql, not for this program. It is also the address
            // that survives the deployer's own cutover of qits-oci-postgresql, because the alias is
            // what the successor answers to.
            String url = "jdbc:postgresql://" + pg + ":5432/postgres";
            PgAdmin.awaitReady(url, "postgres", superuser, boot.config.healthTimeout(), ctx);
            ctx.log("  postgres answering on " + pg + ":5432");

            // Role, database and username are one name throughout, and every database is owned by
            // its own role and closed to public.
            try (Connection admin = PgAdmin.connect(url, "postgres", superuser)) {
                // The deployer's own identity, and the ONE role whose password converges on every
                // rerun: the deployer records the credential this CLI hands it, so a cluster that
                // survived while this file did not is repaired rather than left refusing
                // connections.
                provision(ctx, admin, "qits_deployments", deployments, true);
                // The deployer's OUTBOX, a second database with its own Flyway lineage. The
                // deployer joined the event bus on 2026-08-10 and its deployments.yml declares
                // `postgresql:eventstream:qits_deployments_eventstream`, so a RUNNING deployer
                // provisions this for its own successor — but the FIRST one comes up from the seed
                // compose file, before any deployer exists, and refuses to boot without it. Not
                // converged, like every other role below: the deployer's registry owns the
                // password from that first pipeline deployment on.
                provision(ctx, admin, "qits_deployments_eventstream", deploymentsEventstream, false);
                // The two core seed services. Created once and never altered again — from their
                // first pipeline deployment the deployer's resource registry owns these passwords,
                // and it rotates them when it has to. See PgAdmin.ensureRoleIfMissing.
                provision(ctx, admin, "qits_ci", ci, false);
                provision(ctx, admin, "qits_ci_eventstream", ciEventstream, false);
                provision(ctx, admin, "qits_platform_idp", platformIdp, false);
                // The nameserver, on the same terms: it is in the seed, so it boots from the compose
                // file before any deployer could have created it a database, and it refuses to boot
                // without one. The name is the deployer's own default for this application —
                // qits-platform-dns with the prefix dropped and dashes underscored — so the row the
                // deployer registers later is the row this creates.
                provision(ctx, admin, "qits_platform_dns", platformDns, false);
                // The BUS, on the same terms and for the same reason: it joined the seed on
                // 2026-08-10, so it boots from the compose file before any deployer could have
                // created it a database, and its datasource is an expression over the triple with
                // no fallback URL — unset, it dies at Flyway's first connect.
                //
                // WHY HANDING THIS CREDENTIAL OVER IS SAFE, checked in the deployer rather than
                // assumed (PgResourceProvisioner.ensureRole): on the first pipeline deployment of
                // an application the role EXISTS and the deployer's pd_resource registry has no
                // row for it, and that pair is its reconcile arm — it rotates the role to a fresh
                // password, records it, and starts the successor with what it recorded. The seed
                // container is stopped by the same cutover, so nothing is left holding the old
                // value. Every redeploy after that finds a row and touches nothing. This is
                // exactly what ci, the idp and the nameserver already go through; the CLI only
                // opens the door and never alters the role again.
                provision(ctx, admin, "qits_events", events, false);
                // THE BYTE PLANE'S THREE, on the same terms as everything above: all three
                // containers boot from the seed compose file before any deployer exists, and each
                // one dies at Flyway's first connect without its database.
                //
                // The mirror is the earliest consumer of any of them — it is started by hand,
                // before the compose file is even written, because every publish after it resolves
                // through it. The git host takes two, because the eventstream library keeps its
                // outbox in a lineage of its own and a push that publishes no event is a push no
                // consumer ever learns about.
                //
                // qits-artifacts is deliberately not here: it is the one service still on a file
                // H2, and its store is a path on its volume rather than a role on this server.
                provision(ctx, admin, "qits_platform_mirror", platformMirror, false);
                provision(ctx, admin, "qits_githost", githost, false);
                provision(ctx, admin, "qits_githost_eventstream", githostEventstream, false);
                // THE ORCHESTRATOR, a seed service since 2026-08-11 and a two-database one for the
                // same reason ci and the git host are: its registry of rows and the eventstream
                // outbox are two Flyway lineages and cannot share a database. Both names are the
                // deployer's own derivation from the application name, so the rows it registers on
                // the first pipeline deployment are the rows this creates.
                provision(ctx, admin, "qits_containers", containers, false);
                provision(ctx, admin, "qits_containers_eventstream", containersEventstream, false);
            }
            ctx.note("12 databases ready on " + pg);
        });
    }

    /** One role and its database, logged by name. Which arm the role gets is the caller's call. */
    private static void provision(PhaseContext ctx, Connection admin, String name, String password,
                                  boolean converge) throws Exception {
        ctx.log("  role " + name + ": " + (converge
                ? PgAdmin.ensureRole(admin, name, password)
                : PgAdmin.ensureRoleIfMissing(admin, name, password)));
        ctx.log("  database " + name + ": " + PgAdmin.ensureDatabase(admin, name, name));
    }

    /**
     * A postgres password, given &gt; kept &gt; generated, like every other credential this
     * bootstrap resolves. The ORIGIN reaches the screen; the value never does.
     * <p>
     * A given one has to be hex too. It is assembled into DDL, which cannot be parametrized, so
     * the charset is checked here — where the failure can name the key that was set — rather than
     * three statements later.
     */
    private static String pgPassword(PhaseContext ctx, BootstrapState state, String key,
                                     String configKey) {
        Optional<String> given = ConfigProvider.getConfig()
                .getOptionalValue(configKey, String.class).filter(value -> !value.isBlank());
        Optional<String> kept = state.value(key);
        String value;
        String origin;
        if (given.isPresent()) {
            value = given.get();
            origin = "given (" + configKey + ")";
        } else if (kept.isPresent()) {
            value = kept.get();
            origin = "kept";
        } else {
            value = randomSecret();
            origin = "generated";
        }
        if (!PgAdmin.isPassword(value)) {
            throw new IllegalStateException(key + " is not 16-64 hex characters. It is assembled "
                    + "into SQL that cannot be parametrized, so nothing else is accepted — change "
                    + configKey + " or the recorded value in " + BootstrapState.FILE_NAME);
        }
        ctx.log(String.format("  %-24s %s", key, origin));
        return value;
    }

    // --- secrets, compose, run-args ---------------------------------------------------------------

    /**
     * Every static client ships without a secret and is unusable until a deployment gives it one.
     * Precedence: an explicit override, else what a previous run recorded, else a fresh random.
     */
    public Phase idpSecrets() {
        return new Phase("idp-secrets", "resolve the idp's client secrets and record the run state",
                ctx -> {
                    for (String client : PlatformModel.idpClients(boot.config.envName())) {
                        Optional<String> given = ConfigProvider.getConfig()
                                .getOptionalValue("qits.idp.client." + client + ".secret", String.class)
                                .filter(value -> !value.isBlank());
                        String kept = boot.state.secrets.get(client);
                        String origin;
                        String value;
                        if (given.isPresent()) {
                            value = given.get();
                            origin = "given";
                        } else if (kept != null && !kept.isBlank()) {
                            value = kept;
                            origin = "kept";
                        } else {
                            value = randomSecret();
                            origin = "generated";
                        }
                        boot.state.secrets.put(client, value);
                        ctx.log(String.format("  %-24s %s", client, origin));
                    }
                    BootstrapState state = new BootstrapState(
                            boot.state.wrapperDir.resolve(BootstrapState.FILE_NAME));
                    state.write(boot.state.daemonSha, boot.state.secrets);
                    ctx.log("  recorded in " + state.file());
                });
    }

    public Phase composeFile() {
        return new Phase("compose-file", "generate the seed compose file", ctx -> {
            try {
                Files.writeString(boot.state.composeFile, ComposeTemplate.compose(tokens()),
                        StandardCharsets.UTF_8);
            } catch (java.nio.file.AccessDeniedException e) {
                // Same migration relic as the state file: a pre-CLI bootstrap wrote it as root.
                Files.deleteIfExists(boot.state.composeFile);
                Files.writeString(boot.state.composeFile, ComposeTemplate.compose(tokens()),
                        StandardCharsets.UTF_8);
            }
            ctx.log("  " + boot.state.composeFile);
            ctx.note(boot.state.composeFile.getFileName().toString());
        });
    }

    /**
     * The deployer's per-application run arguments, as a config file on a named volume: quarkus
     * reads config/application.properties next to the binary, and a self-update's successor mounts
     * the same volume — which is the whole reason this is a file and not compose env.
     */
    public Phase pdRunArgs() {
        return new Phase("pd-run-args", "write the deployer's run-args config volume", ctx -> {
            boot.docker.ensureVolume("qits-deployments-config", ctx::log);
            String properties = ComposeTemplate.runArgs(tokens());
            // What the volume held BEFORE this write, as a DIGEST rather than as text: the file
            // carries the push token and every client secret, and reading it back would put both
            // on the screen and in the log.
            String before = configDigest();
            ProcessResult result = boot.docker.run(Cmd.of(List.of(
                            "docker", "run", "--rm", "-i",
                            "-v", "qits-deployments-config:/cfg",
                            "--entrypoint", "sh", "alpine/git",
                            "-c", "cat > /cfg/application.properties "
                                    + "&& chown 1001:0 /cfg/application.properties"))
                    .stdin(properties)
                    .mask(boot.config.pushToken())
                    .mask(boot.state.secrets.getOrDefault(
                            PlatformModel.wireAlias("ci", boot.config.envName()), ""))
                    // Both postgres passwords are in this file too: the deployer's own credential
                    // and the admin one it provisions every other application's database with.
                    .mask(orEmpty(boot.state.pgSuperuserPassword))
                    .mask(orEmpty(boot.state.pgDeploymentsPassword))
                    // The seed services' credentials are NOT in this file — the deployer injects
                    // their triples from its own registry, its own outbox included. Masked anyway:
                    // a line each, against the day one of them is pinned here by hand.
                    .mask(orEmpty(boot.state.pgDeploymentsEventstreamPassword))
                    .mask(orEmpty(boot.state.pgCiPassword))
                    .mask(orEmpty(boot.state.pgCiEventstreamPassword))
                    .mask(orEmpty(boot.state.pgPlatformIdpPassword))
                    .mask(orEmpty(boot.state.pgPlatformDnsPassword))
                    .mask(orEmpty(boot.state.pgEventsPassword))
                    .mask(orEmpty(boot.state.pgPlatformMirrorPassword))
                    .mask(orEmpty(boot.state.pgGithostPassword))
                    .mask(orEmpty(boot.state.pgGithostEventstreamPassword)), ctx::log);
            Boot.must(result, "writing the deployer's run-args failed");
            ctx.log("  " + properties.lines().filter(l -> l.startsWith("qits.platform.deployments.run-args")).count()
                    + " applications configured on the qits-deployments-config volume");
            if (!sha256(properties).equals(before)) {
                restartSeedDeployer(ctx);
            }
        });
    }

    /**
     * The digest of the run-args file already on the volume, or empty when there is none. Computed
     * inside a container because the volume has no path on the host, and with the same image the
     * write above uses so nothing extra is pulled.
     */
    private String configDigest() {
        ProcessResult result = boot.docker.run(Cmd.of(List.of(
                "docker", "run", "--rm",
                "-v", "qits-deployments-config:/cfg",
                "--entrypoint", "sh", "alpine/git",
                // The redirect is the "no file yet" case, which is the ordinary cold boot.
                "-c", "sha256sum /cfg/application.properties 2>/dev/null | cut -d' ' -f1")), null);
        for (String line : result.captured()) {
            String value = line.trim();
            if (value.matches("[0-9a-f]{64}")) {
                return value;
            }
        }
        return "";
    }

    /**
     * <b>The deployer reads its run-args ONCE, at its own boot.</b> A rerun that changes the file
     * therefore changes nothing for a deployer that is already running: it goes on deploying from
     * the previous boot's arguments, and compose will not help — the volume is unchanged as far as
     * it is concerned, so {@code up -d} leaves the container alone. That is how a qits-ci was
     * deployed on the first prod bootstrap without the addresses its step containers dial, and the
     * recovery was a restart by hand.
     * <p>
     * Only the SEED deployer, by its wire alias. A deployed one ({@code qits-pd-…}) picked the file
     * up when its own cutover started it, and restarting it here would interrupt whatever it is
     * deploying. Restarting an idle seed deployer costs seconds and nothing else, which is what
     * makes this rerun-safe.
     */
    private void restartSeedDeployer(PhaseContext ctx) {
        String name = PlatformModel.wireAlias("deployments", boot.config.envName());
        if (!boot.docker.runningNames().contains(name)) {
            ctx.log("  the run-args changed; no seed deployer is running, so none is holding "
                    + "the old ones");
            return;
        }
        ctx.log("  the run-args changed and " + name + " is older than the change — restarting it "
                + "so it deploys from the new ones");
        Boot.must(boot.docker.exec(Duration.ofMinutes(5), ctx::log, "restart", name),
                "restarting " + name + " after its run-args changed failed");
        ctx.note("run-args changed, " + name + " restarted");
    }

    // --- what a domain adds -----------------------------------------------------------------------

    /**
     * <b>A self-signed certificate for the domain, so the edge can start at all.</b>
     * <p>
     * The edge's keystore names two files on the qits-edge-letsencrypt volume, and a keystore whose
     * files are missing fails startup — so on a cold boot the volume has to hold something before
     * compose starts the edge with that configuration. This writes it. It is a placeholder in the
     * only sense that matters: browsers reject it, and the real PEMs land in the same two filenames
     * when {@code quarkus tls lets-encrypt issue-certificate} runs, after which the TLS registry
     * reloads within the reload period.
     * <p>
     * <b>Skipped whenever a certificate is already there</b>, and that check is not an optimisation:
     * overwriting would replace a REAL certificate with a self-signed one, on a running public
     * platform, every time somebody reran the boot. The existence test and the write are one
     * container so nothing can happen between them.
     * <p>
     * <b>The image is alpine/git with openssl added</b> — the same image the run-args write uses one
     * phase earlier, so the boot pulls nothing new. It carries git and not openssl (measured, on
     * 2026-08-09), and the platform's other already-present images carry neither: nginx:alpine and
     * the qits service images have no openssl binary either. {@code apk add} is what the two npm
     * publish phases already do, so it adds no dependency the boot did not have.
     * <p>
     * {@code chown 1001:0} for the same reason the run-args write has it: the edge runs as that
     * unprivileged uid and cannot read a root-owned key.
     */
    public Phase placeholderCertificate(String domain) {
        return new Phase("edge-cert", "seed a placeholder certificate for " + domain
                + " on the edge's volume", ctx -> {
            boot.docker.ensureVolume("qits-edge-letsencrypt", ctx::log);
            String script = """
                    set -eu
                    if [ -f /cert/lets-encrypt.crt ]; then
                      echo "a certificate is already there — leaving it alone"
                      exit 0
                    fi
                    apk add --no-cache openssl >/dev/null
                    openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \\
                      -subj "/CN=%1$s" -addext "subjectAltName=DNS:%1$s,DNS:*.%1$s" \\
                      -keyout /cert/lets-encrypt.key -out /cert/lets-encrypt.crt 2>/dev/null
                    chown 1001:0 /cert/lets-encrypt.crt /cert/lets-encrypt.key
                    chmod 644 /cert/lets-encrypt.crt
                    chmod 640 /cert/lets-encrypt.key
                    echo "placeholder certificate written for %1$s"
                    """.formatted(domain);
            ProcessResult result = boot.docker.run(Cmd.of(List.of(
                    "docker", "run", "--rm",
                    "-v", "qits-edge-letsencrypt:/cert",
                    "--entrypoint", "sh", "alpine/git", "-c", script)), ctx::log);
            Boot.must(result, "writing the placeholder certificate for " + domain + " failed");
            boolean kept = result.captured().stream().anyMatch(line -> line.contains("already"));
            ctx.note(kept ? "certificate kept" : "placeholder written");
        });
    }

    /**
     * <b>The zone row, and nothing else.</b> A zone is what makes the nameserver answer for a name at
     * all: without one every query for the domain is REFUSED, whatever records exist.
     * <p>
     * <b>No records are created, and that is not an omission.</b> An A record's value is this host's
     * PUBLIC address, which this program has no way to learn — it runs in a container behind a NAT it
     * cannot see past, and guessing would publish a name that resolves to a private address. So the
     * records and the registrar's delegation are the operator's two steps, and the closing report
     * says so.
     * <p>
     * Idempotent: 201 is this call creating it, 409 is the service saying it or an overlapping zone is
     * already there — which is a rerun, and the message names the zone it overlapped. No token: the
     * write guard is {@code qits.dns.token}, blank on this platform, and the service is reachable only
     * from qits-net.
     */
    public Phase dnsZone(String domain) {
        return new Phase("dns-zone", "create the zone " + domain + " in qits-platform-dns", ctx -> {
            String url = boot.config.dnsUrl() + "/api/zones";
            ctx.status("POST " + url + " " + domain);
            Http.Response response =
                    boot.http.postJson(url, Json.object("fqdn", domain), Map.of());
            if (response.status() == 201) {
                ctx.log("  zone " + domain + " created");
            } else if (response.status() == 409) {
                ctx.log("  zone " + domain + " is already there: " + response.body());
            } else {
                throw new IllegalStateException("creating the zone " + domain + " answered "
                        + response.describe());
            }
            ctx.log("  no records seeded: their values are this host's PUBLIC address, which this "
                    + "run cannot know");
            ctx.note(domain);
        });
    }

    /** The values both generated files are filled with. */
    Map<String, String> tokens() {
        String env = boot.config.envName();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ENV_NAME", env);
        // The same name in the spelling an env-var key takes, because the idp's per-client keys
        // embed the client id and a client id starts with the environment name:
        // QITS_IDP_CLIENT_PROD_QITS_CI_SECRET.
        values.put("ENV_KEY", PlatformModel.clientKey(env));
        values.put("COMPOSE_FILE", boot.state.composeFile == null ? "docker-compose.qits.yml"
                : boot.state.composeFile.getFileName().toString());
        values.put("PORT", String.valueOf(boot.config.port()));
        values.put("REGISTRY_PORT", String.valueOf(boot.config.registryPort()));
        // The other two byte doors: the mirror's, for a docker daemon told to pull third-party
        // images through the cache, and the git host's, for a person pushing from the workstation.
        values.put("MIRROR_PORT", String.valueOf(boot.config.mirrorPort()));
        values.put("GIT_HOST_PORT", String.valueOf(boot.config.gitHostPort()));
        values.put("PG_PORT", String.valueOf(boot.config.pgPort()));
        values.put("DNS_PORT", String.valueOf(boot.config.dnsPort()));
        // Resolved by seed-postgres, which runs before both generated files are written.
        values.put("PG_SUPERUSER_PASSWORD", orEmpty(boot.state.pgSuperuserPassword));
        values.put("PG_DEPLOYMENTS_PASSWORD", orEmpty(boot.state.pgDeploymentsPassword));
        values.put("PG_DEPLOYMENTS_EVENTSTREAM_PASSWORD",
                orEmpty(boot.state.pgDeploymentsEventstreamPassword));
        values.put("PG_CI_PASSWORD", orEmpty(boot.state.pgCiPassword));
        values.put("PG_CI_EVENTSTREAM_PASSWORD", orEmpty(boot.state.pgCiEventstreamPassword));
        values.put("PG_PLATFORM_IDP_PASSWORD", orEmpty(boot.state.pgPlatformIdpPassword));
        values.put("PG_PLATFORM_DNS_PASSWORD", orEmpty(boot.state.pgPlatformDnsPassword));
        values.put("PG_EVENTS_PASSWORD", orEmpty(boot.state.pgEventsPassword));
        values.put("PG_PLATFORM_MIRROR_PASSWORD", orEmpty(boot.state.pgPlatformMirrorPassword));
        values.put("PG_GITHOST_PASSWORD", orEmpty(boot.state.pgGithostPassword));
        values.put("PG_GITHOST_EVENTSTREAM_PASSWORD",
                orEmpty(boot.state.pgGithostEventstreamPassword));
        values.put("PG_CONTAINERS_PASSWORD", orEmpty(boot.state.pgContainersPassword));
        values.put("PG_CONTAINERS_EVENTSTREAM_PASSWORD",
                orEmpty(boot.state.pgContainersEventstreamPassword));
        values.put("IDP", boot.config.idpIssuer());
        values.put("PUSH_TOKEN", boot.config.pushToken());
        values.put("MACHINE_REQUIRED", String.valueOf(boot.config.machineAuth()));
        // The OUTBOUND half, and a separate switch from the gate: quarkus-oidc-client ships
        // DISABLED, so a service given an issuer and a secret still posts BARE until this is set.
        values.put("MACHINE_CLIENT", String.valueOf(boot.config.machineAuth()));
        values.put("DOCKER_GID", boot.state.dockerGid);
        values.put("DAEMON_SHA", boot.state.daemonSha == null ? "" : boot.state.daemonSha);
        values.put("IDP_CLIENTS", String.join(",", PlatformModel.idpClients(env)));
        values.put("IDP_AUDIENCES", PlatformModel.idpAudiences(env));
        // Keyed by the APPLICATION, not by the client id: the id carries the environment name and
        // a placeholder cannot be spelled with a value the template does not know yet.
        for (String app : PlatformModel.IDP_CLIENT_APPS) {
            values.put("IDP_SECRET_" + PlatformModel.clientKey(app),
                    boot.state.secrets.getOrDefault(PlatformModel.wireAlias(app, env), ""));
        }
        // What QITS_DOMAIN adds, and nothing when there is none: every one of these is empty then,
        // so both files render exactly as a platform with no public names always rendered them.
        values.putAll(DomainTokens.of(DomainName.of(boot.config)));
        return values;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    // --- small helpers ----------------------------------------------------------------------------

    private String create(PhaseContext ctx, List<String> command) {
        ProcessResult result = boot.docker.run(Cmd.of(command).timeout(Duration.ofMinutes(30)), ctx::log);
        Boot.must(result, "docker create failed");
        List<String> lines = result.captured();
        if (lines.isEmpty()) {
            throw new IllegalStateException("docker create printed no container id");
        }
        return lines.getLast().trim();
    }

    private void copyIn(PhaseContext ctx, Path source, String cid) {
        Boot.must(boot.docker.exec(Duration.ofMinutes(30), ctx::log,
                "cp", source + "/.", cid + ":/src"), "copying " + source + " into the container failed");
    }

    private void startAndReap(PhaseContext ctx, String cid, String failure) {
        ProcessResult result = boot.docker.exec(Docker.BUILD_TIMEOUT, ctx::log, "start", "-a", cid);
        if (!result.ok()) {
            boot.docker.removeContainer(cid, null);
            throw new IllegalStateException(failure + "\n" + result.tailText(20));
        }
        boot.docker.exec(null, "rm", cid);
    }

    static String shortSha(String sha) {
        return sha == null ? "" : sha.substring(0, Math.min(12, sha.length()));
    }

    /**
     * What git leaves at a gitlink whose submodule is not checked out: the path, with nothing in
     * it. Every submodule of a freshly cloned wrapper looks like this, which is what a cold start
     * hands {@link #sources}.
     */
    static boolean isEmptyDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        }
    }

    private static String sha256(Path file) throws Exception {
        return sha256(Files.readAllBytes(file));
    }

    /** The same digest sha256sum prints for the bytes a container's {@code cat} would write. */
    private static String sha256(String text) throws Exception {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * Random rather than a fixed default, which is where this parts ways with the push token: that
     * value has to be nameable in the docs that teach the escape hatch, and these are never typed
     * by anyone.
     */
    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.substring(0, 32);
    }

    /** Used by the phase list to decide whether the seed is needed at all. */
    public boolean artifactsAnswering() {
        Http.Response response = boot.artifacts.health();
        return response.ok();
    }
}
