package eu.wohlben.qits.cli.bootstrap.phases;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.bootstrap.api.Http;
import eu.wohlben.qits.cli.bootstrap.api.Json;
import eu.wohlben.qits.cli.bootstrap.config.Acme;
import eu.wohlben.qits.cli.bootstrap.config.DomainName;
import eu.wohlben.qits.cli.bootstrap.config.PublicIp;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The hand-built part: everything the pipeline cannot make for itself on the first boot, plus the
 * files the seed stack is started from.
 * <p>
 * <b>Every one of these builds reads a checkout the {@code sources} phase has already stood at this
 * boot's identity</b> — the newest release tag in a restore, main under {@code --ship-mains}. The
 * seed containers are scaffolding and are replaced within the hour, but scaffolding that migrates a
 * database or publishes a coordinate has to agree with the successor that replaces it, so there is
 * one commit per repository per boot and no phase here picks its own.
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
     *   <li>qits-blobstore is written against qits-db-core, a module of qits-integrations-quarkus,
     *       since its DbRetry release (2026.813.161828) — so the integrations come FIRST. The same
     *       class of break as the 2026-08-11 one below, bought a second time on 2026-08-13: every
     *       earlier green run had a blobstore with no qits dependency at all, and the day it grew
     *       one the seed resolved qits-db-core against a registry that does not exist yet —
     *       "Connection refused" on localhost:8081, seconds into the phase.
     *   <li>qits-registries is written against qits-blobstore's entities and follows it.
     *   <li>qits-eventstream is written against qits-db-core too, so the integrations come BEFORE
     *       it as well. This is the edge that broke the 2026-08-11 proving run: the integrations
     *       used to be last, and the day eventstream grew the dependency the seed died the same
     *       way, minutes into the phase.
     *   <li>qits-githost-events is written against qits-eventstream and follows it.
     *   <li>qits-containers' two libraries are written against qits-db-core and qits-eventstream,
     *       so the orchestrator is last.
     * </ul>
     * <b>githost and containers name SERVICE repositories and publish modules of them</b> — the git
     * host's event vocabulary, and the orchestrator's core and client. {@link
     * PlatformModel#mavenModule} says which modules and why.
     */
    static final List<String> SEED_LIBRARIES = List.of(
            "integrations-quarkus", "blobstore", "registries", "eventstream", "githost",
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
            ctx.log("  docker build: enforced 4g memory and 2 cpu limits");
            boot.state.swarm = ensureSwarm(boot.docker, ctx::log);
            ctx.log("  swarm: " + boot.state.swarm);
            warnAboutInsecureRegistries(ctx);
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
            // Printed rather than assumed: everything the domain switches on — the edge's TLS ports
            // and its certificate — is invisible in a log that never says which of the two runs this
            // is. The value was checked before the payload image was built, so this line cannot be
            // the first place a typo shows.
            DomainName.of(boot.config).ifPresentOrElse(
                    domain -> ctx.log("  domain: " + domain + "  (its dns records are held "
                            + "outside this platform)"),
                    () -> ctx.log("  domain: none — the edge stays on plain HTTP"));

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
     * <b>A WARNING, never a refusal: the docker daemon's own list of registries it will speak plain
     * HTTP to.</b>
     * <p>
     * The platform's registry and its mirror are reached from the host at
     * {@code <app>.<env>.localhost:<edge port>} now, and both speak HTTP. Docker's built-in
     * exemption is for the loopback ADDRESSES and not for names that resolve to them, so a daemon
     * without these two entries fails every push and every pull with "http: server gave HTTP
     * response to HTTPS client" — a message about TLS, several phases into a boot, for a
     * configuration line on the host.
     * <p>
     * It does not stop the boot, and that is deliberate twice over. The failure is repairable while
     * the run is going (edit {@code /etc/docker/daemon.json}, restart the daemon), and a bootstrap
     * that refused to start over a host setting it can neither read nor write would be refusing the
     * cold start it exists for. The closing report prints the same two entries as a host-side step.
     * <p>
     * The DAEMON is asked rather than the file, because the daemon is the thing that decides — and
     * a daemon that has not been restarted since the file was edited is exactly the case a file
     * read would call configured.
     */
    private void warnAboutInsecureRegistries(PhaseContext ctx) {
        List<String> insecure = boot.docker.insecureRegistries();
        List<String> missing = Stream.of(boot.config.registryVhost(), boot.config.mirrorVhost())
                .filter(name -> !insecure.contains(name))
                .toList();
        if (missing.isEmpty()) {
            ctx.log("  insecure registries: " + boot.config.registryVhost() + ", "
                    + boot.config.mirrorVhost());
            return;
        }
        ctx.warn("the docker daemon does not allow plain HTTP to " + String.join(" or ", missing)
                + " — every push and pull through the edge will fail with \"server gave HTTP "
                + "response to HTTPS client\". Add both names to insecure-registries in "
                + "/etc/docker/daemon.json and restart the daemon; the closing report spells the "
                + "line. The boot goes on: the fix is a host step and it can be made while this "
                + "runs");
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
     * <p>
     * <b>This phase also decides WHICH COMMIT of each source the boot means</b>, and that is the
     * second half of the same job: fetching the history and standing on the wrong part of it are
     * one mistake. In a restore — the default — a checkout whose output carries VERSION IDENTITY is
     * left detached at its newest release tag, which is the very commit the deploy phase will move
     * the deploy ref to. {@code --ship-mains} leaves everything on main, as this program always did.
     * <p>
     * <b>Version identity is the scope, and it is narrower than "seeded".</b> A deployable and a
     * release publisher each have a last release the platform can state and consumers pin;
     * everything else in {@code SEEDED_REPOS} — qits-oci's step-image sources, the SPA seed sources
     * — is rebuilt from source every boot and pinned by nobody, so its tags go stale unnoticed and
     * main is its only meaningful identity. Measured: qits-oci's newest tag was three days behind
     * main and predated the passwd-backed {@code build} user its step images grew when steps
     * stopped running as root, so the seed maven-base built from it could not launch a step
     * declaring {@code user: build} — phase 65 of the first scoped-boot run.
     * <p>
     * <b>ONE IDENTITY PER BOOT, and it is not a tidiness argument.</b> The seed containers are
     * scaffolding, but they are scaffolding that TOUCHES THE PLATFORM'S DATA: a seed built from
     * main applies main's Flyway migrations, and the released successor the train deploys minutes
     * later refuses to start against a schema ahead of it — "Detected applied migration not
     * resolved locally". Measured on the first restore-default boot: qits-ci's main carried V3 and
     * its release tag stopped at V2, the seed applied V3, and the deployed ci crash-looped until it
     * was unpicked by hand. Seed and successor must agree about the version, so they are built
     * from one commit.
     * <p>
     * A repository in scope with no release tag stays on main too, and says so. The warning for
     * that case belongs to the deploy phase, where the consequence is: unreleased code being
     * DEPLOYED. Here it is a log line, so a boot whose sources are simply young is not a boot that
     * exits nonzero for it.
     */
    public Phase sources() {
        return new Phase("sources", "clone or refresh the platform's sources", ctx -> {
            int restored = 0;
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
                    if (boot.git.isDetached(target)) {
                        // A previous restoring boot left this tree at a release tag, and a pull
                        // there does NOT fail — measured: it fast-forwards the detached HEAD and
                        // leaves refs/heads/main exactly where it was. The trunk would then go
                        // stale run after run while every "main's head" read in the boot — the
                        // replay wait's sha, the deploy fallback, the quiet push — answered from
                        // it. Reattaching first is what refreshes the branch, and the identity
                        // below is re-decided from it every run.
                        ctx.status("putting " + repo + " back on main");
                        Boot.must(boot.git.checkoutBranch(target, "main", ctx::log),
                                repo + " is detached and will not go back onto main — delete "
                                        + target + " and rerun");
                    }
                    ctx.status("refreshing " + repo);
                    Boot.must(boot.git.pullFastForward(target, ctx::log),
                            "refresh of " + repo + " failed — " + target + " cannot fast-forward "
                                    + "to " + from + ", so this run would build a commit that is "
                                    + "no longer anywhere. Delete that directory and rerun");
                } else {
                    ctx.status("cloning " + repo + " from " + from);
                    Boot.must(boot.git.clone(from, target, ctx::log), "clone of " + repo + " failed");
                }
                String identity = bootIdentity(boot.config.shipMains(), name,
                        boot.git.tagsNewestFirst(target, "main"));
                if (!identity.isEmpty()) {
                    ctx.status("standing " + repo + " at " + identity);
                    Boot.must(boot.git.checkoutDetached(target, identity, ctx::log),
                            repo + " will not stand at " + identity + " — the tag is reachable "
                                    + "from main but cannot be checked out, so this run cannot "
                                    + "build the release it is restoring");
                    restored++;
                }
                ctx.log(String.format("  %-26s %s  (%s)", repo, boot.git.shortHead(target),
                        identity.isEmpty() ? from : identity));
            }
            ctx.note(PlatformModel.platformRepos().size() + " repositories, " + restored
                    + " at their release");
        });
    }

    /**
     * THE BOOT'S IDENTITY for one checkout: the release tag it stands at, or empty for "stay on
     * main".
     * <p>
     * The same answer the deploy phase's {@code deployPoint} builds on — {@link
     * PlatformModel#newestRelease} is the one place that decides which tag is a release — so the
     * seed image and the successor that replaces it are the same code. Empty means main, and three
     * different things answer empty: {@code --ship-mains}, a repository that has never been
     * released, and a repository whose output carries no version identity at all.
     * <p>
     * <b>That last one is the scope, and it was learned the hard way.</b> Only a deployable or a
     * release publisher has a "last release" the platform can state — see
     * {@link PlatformModel#carriesVersionIdentity}. The step-image sources and the SPA seed sources
     * are rebuilt from source every boot and pinned by nobody, so their tags are stale by
     * construction: qits-oci's newest tag predated the {@code build} user its step images grew, and
     * a maven-base built from it could not launch a step that declares {@code user: build}.
     */
    static String bootIdentity(boolean shipMains, String name, List<String> tagsNewestFirst) {
        return shipMains || !PlatformModel.carriesVersionIdentity(name)
                ? ""
                : PlatformModel.newestRelease(tagsNewestFirst);
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
            // A token this file holds is a token an earlier run minted, and the mint phase mints
            // once per installation: every call makes another key to the first admin account.
            boot.state.registerTokenRecorded = state.registerToken().isPresent();
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
            ctx.log("  register token: " + (boot.state.registerTokenRecorded
                    ? "minted by an earlier run" : "none yet"));
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
                    // A store that is already up makes this phase pointless: it IS the Maven
                    // registry the seed builds resolve against, and every one of these libraries is
                    // published into it by every bootstrap that gets past phase 10. If a store this
                    // far along somehow has not got one, the seed build below fails by name rather
                    // than resolving nothing quietly.
                    //
                    // It is also the older half of a bind conflict that is gone: while the platform
                    // published the registry port, a temporary registry beside a running store
                    // could not bind at all. The store publishes nothing now — the host reaches it
                    // through the edge — so the port is free either way, and this is a skip on
                    // merit rather than on a bind.
                    storeAlreadyServing().ifPresent(who ->
                            ctx.skip(who + " is the registry — it serves these libraries already"));
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
                    // THE PUBLISH IS THIS PHASE'S ALONE NOW. The platform's own store publishes no
                    // host port at all since unify-ingress — it is reached at
                    // registry.<env>.localhost through the edge — so nothing here is shadowing a
                    // number the platform also binds. This container serves the seed builds that
                    // run before any edge exists, and it goes away in seed-artifacts, minutes from
                    // now; nothing has to close the port at the end of a boot.
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
     * Started by hand rather than by the stack, under the wire alias the stack service will claim,
     * so the two never run side by side under one name. Whoever already serves, serves: a deployed
     * mirror answers at that same alias from the same database and is strictly better than the seed
     * this phase would have started.
     * <p>
     * <b>The loopback publish is this phase's own and nobody else's.</b> The deployed mirror
     * publishes no host port since unify-ingress — the host reaches it at
     * {@code mirror.<env>.localhost} through the edge — so this one exists for the seed builds that
     * run before any edge does, and it goes away with the container the first cutover replaces.
     */
    public Phase seedMirrorStart() {
        return new Phase("seed-mirror",
                "have qits-platform-mirror serving before anything resolves through it", ctx -> {
            boot.docker.ensureNetwork(Boot.NETWORK, ctx::log);
            String mirror = PlatformModel.wireAlias("platform-mirror", boot.config.envName());
            String prefix = PlatformModel.pdNamePrefix("platform-mirror", boot.config.envName());
            Optional<String> serving = alreadyServing(mirror, prefix,
                    boot.docker.runningNames(), boot.docker.serviceNames());
            if (serving.isPresent()) {
                ctx.log("  " + serving.get() + " already answers as " + mirror
                        + " from the same database — no seed mirror to start");
            } else {
                boot.docker.removeContainer(mirror, null);
                Boot.must(boot.docker.run(Cmd.of(List.of(
                                "docker", "run", "-d", "--name", mirror,
                                "--network", Boot.NETWORK,
                                // SEED-ONLY, and the platform does not take it over: the deployed
                                // mirror publishes nothing, and the host dials
                                // mirror.<env>.localhost through the edge. What needs a port here
                                // is the seed image builds, which run --network host before an
                                // edge exists.
                                "-p", "127.0.0.1:" + boot.config.mirrorPort() + ":8080",
                                // The seed's own credential, on the same terms as the idp's and the
                                // bus's: this container starts before any deployer exists, so the
                                // role and the database were created by seed-postgres and handed
                                // over here. It refuses to boot without the triple.
                                //
                                // The whole store, cached bytes included: there is no blobs
                                // directory and no volume any more, so this container is stateless.
                                "-e", "QITS_RESOURCE_DB_URL=jdbc:postgresql://"
                                        + PlatformModel.wireAlias("oci-postgresql",
                                                boot.config.envName())
                                        + ":5432/qits_platform_mirror",
                                "-e", "QITS_RESOURCE_DB_USERNAME=qits_platform_mirror",
                                "-e", "QITS_RESOURCE_DB_PASSWORD="
                                        + orEmpty(boot.state.pgPlatformMirrorPassword),
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
            // Named after its wire alias, like every other seed container: this one is started by
            // hand rather than by the stack, and the name it takes has to be the same one the stack
            // service would have claimed, or the two run side by side under one name.
            String artifacts = PlatformModel.wireAlias("artifacts", boot.config.envName());
            // Whoever already serves, serves. A DEPLOYED store reads the same qits_artifacts
            // database and answers the same API at the same alias, so it is strictly better than
            // the seed this phase would have started. This run's own seed container is asked for by
            // name first, so a rerun still reports it as itself rather than as the deployer's.
            //
            // It used to be a bind conflict as well — a seed beside a running store answered "port
            // is already allocated", exit 125, and the boot stopped exactly there on the 2026-08-08
            // validation rerun. The deployed store publishes no host port since unify-ingress, so
            // that collision is gone and this check now buys only the duplicate it always meant to
            // prevent.
            Optional<String> serving = alreadyServing(artifacts,
                    PlatformModel.pdNamePrefix("artifacts", boot.config.envName()),
                    boot.docker.runningNames(), boot.docker.serviceNames())
                    .or(this::storeAlreadyServing);
            if (serving.isPresent()) {
                ctx.log("  " + serving.get() + " already serves port " + boot.config.registryPort()
                        + " from the same database — no seed store to start");
                ctx.note(serving.get() + " serves :" + boot.config.registryPort());
            } else {
                boot.docker.removeContainer(artifacts, null);
                // No git env any more, and no ci intake: this service hosts no repositories and
                // announces nothing since the byte-plane split. What is left is the store.
                //
                // The store is the whole of it: metadata AND blob bytes are rows in qits_artifacts,
                // so this container mounts nothing and a restart loses only in-flight uploads. The
                // credential comes from seed-postgres on the same terms as the mirror's — this
                // container starts before any deployer exists, so the CLI created the role and the
                // database and hands the triple over itself.
                Boot.must(boot.docker.run(Cmd.of(List.of(
                                "docker", "run", "-d", "--name", artifacts,
                                "--network", Boot.NETWORK,
                                "-p", "127.0.0.1:" + boot.config.registryPort() + ":8080",
                                "-e", "QITS_RESOURCE_DB_URL=jdbc:postgresql://"
                                        + PlatformModel.wireAlias("oci-postgresql",
                                                boot.config.envName())
                                        + ":5432/qits_artifacts",
                                "-e", "QITS_RESOURCE_DB_USERNAME=qits_artifacts",
                                "-e", "QITS_RESOURCE_DB_PASSWORD="
                                        + orEmpty(boot.state.pgArtifactsPassword),
                                "qits/artifacts:latest"))
                        .mask(orEmpty(boot.state.pgArtifactsPassword)), ctx::log),
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
     * bind, and neither needs to: the store on the other end reads the same database.
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

    /**
     * Who already serves this application, when anything does — and the two lists are asked in the
     * one order that matters: a container first, then a SERVICE.
     * <p>
     * <b>This is the check the stack broke.</b> A seed service used to be a container answering to
     * the wire alias, so {@code docker ps} could be asked for it by name. A stack ignores
     * {@code container_name} and calls the container {@code <stack>_<service>.<slot>.<taskid>}, so
     * the name test finds nothing and a phase that starts a container by hand takes a host port the
     * service already holds — {@code port is already allocated}, exit 125, which is how the same
     * class of bug stopped a boot on 2026-08-08.
     * <p>
     * A running container matches by the ALIAS (this run's or an earlier run's hand-started one) or
     * by the deployer's {@code qits-pd-…} prefix; a service matches under either of the two names a
     * stack service answers to.
     */
    static Optional<String> alreadyServing(String alias, String pdPrefix, List<String> running,
                                           List<String> services) {
        Optional<String> container = running.stream()
                .filter(name -> name.equals(alias) || name.startsWith(pdPrefix))
                .findFirst();
        if (container.isPresent()) {
            return container;
        }
        String qualified = Docker.stackService(Docker.STACK, alias);
        return services.stream()
                .filter(service -> service.equals(qualified) || service.equals(alias))
                .findFirst();
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
            //
            // In a restore that version IS the released one, because the checkout stands at the
            // release tag: the probe then answers "already published" on any platform whose store
            // survived, and where it does not, the seed publishes the released bytes under the
            // released version — which is the one publish this phase is allowed to make. The rule
            // it must never break is below: unreleased work reaching a released coordinate.
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
     * Every database {@link #seedPostgres} creates, in the order it creates them.
     * <p>
     * <b>It is here so a test can hold it against the generated seed stack.</b> A seed container is
     * handed its credential by the file that starts it, and a database nobody created is not a
     * misconfiguration a person sees — it is a container that dies at Flyway's first connect, tens
     * of phases into a boot. So the list is declared once and checked against every
     * {@code QITS_RESOURCE_*_URL} the seed spells.
     */
    public static final List<String> SEED_DATABASES = List.of(
            "qits_deployments", "qits_deployments_eventstream",
            "qits_ci", "qits_ci_eventstream",
            "qits_platform_idp", "qits_events",
            "qits_artifacts", "qits_platform_mirror",
            "qits_githost", "qits_githost_eventstream",
            "qits_containers", "qits_containers_eventstream");

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
     * qits-events, qits-artifacts, qits-platform-mirror, qits-githost (its
     * database and its outbox) and qits-containers (the same pair). Four of the twelve are
     * outboxes because the
     * eventstream library keeps its own Flyway lineage and cannot share a database with its host; ci
     * carried one from the start, the deployer joined the bus on 2026-08-10, and the git host and
     * the orchestrator are the newest publishers on it.
     * Every one of them runs Flyway at boot against a database that has to exist
     * already, and at that point in a cold boot no deployer exists to make one.
     * Everything else — projects, workspaces, observability — is
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
            String events = pgPassword(ctx, state, "PG_EVENTS_PASSWORD", "qits.pg.events-password");
            String artifacts = pgPassword(ctx, state, "PG_ARTIFACTS_PASSWORD",
                    "qits.pg.artifacts-password");
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
            boot.state.pgEventsPassword = events;
            boot.state.pgArtifactsPassword = artifacts;
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
            state.put("PG_EVENTS_PASSWORD", events);
            state.put("PG_ARTIFACTS_PASSWORD", artifacts);
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
            // deployed postgres, or the seed stack's own service, answers the same alias from this
            // very volume, and a second server on it is two writers on one cluster.
            String prefix = PlatformModel.pdNamePrefix("oci-postgresql", boot.config.envName());
            Optional<String> serving = alreadyServing(pg, prefix,
                    boot.docker.runningNames(), boot.docker.serviceNames());
            if (serving.isPresent()) {
                ctx.log("  " + serving.get() + " already serves " + pg
                        + " from the same volume — no seed server to start");
            } else {
                boot.docker.removeContainer(pg, null);
                Boot.must(boot.docker.run(Cmd.of(List.of(
                                "docker", "run", "-d", "--name", pg,
                                "--network", Boot.NETWORK,
                                // NO PUBLISHED PORT. Every consumer dials the alias on 5432 over
                                // qits-net, this CLI included — it runs in a container on that
                                // network. Nothing on the host has asked for this server since.
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
            // The WIRE ALIAS on 5432, which is the only address this server has: nothing publishes
            // it any more. It is also the address that survives the deployer's own cutover of
            // qits-oci-postgresql, because the alias is what the successor answers to.
            String url = "jdbc:postgresql://" + pg + ":5432/postgres";
            PgAdmin.awaitReady(url, "postgres", superuser, boot.config.healthTimeout(), ctx);
            ctx.log("  postgres answering on " + pg + ":5432");

            // A SURVIVING cluster's application roles are not on the recorded passwords any more:
            // from an application's first deploy on, the deployer's pd_resource registry is the
            // authority — its reconcile arm rotates each role to a fresh password and records what
            // it set, while this file's value is what the role was CREATED with. Measured on the
            // first volumes-kept re-bootstrap after rotation landed: the seed mirror died on
            // "password authentication failed" while the registry knew the working value all
            // along. So the seed containers are started with what the rows say, exactly the way
            // the deployer starts every successor. The deployer's own two roles stay on the
            // recorded values: its role is CONVERGED on them below, and its boot registration
            // rewrites their rows from the environment this phase hands it.
            Map<String, String> registry = PgAdmin.recordedPasswords(
                    "jdbc:postgresql://" + pg + ":5432/qits_deployments", "postgres", superuser);
            ci = registry.getOrDefault("qits_ci", ci);
            ciEventstream = registry.getOrDefault("qits_ci_eventstream", ciEventstream);
            platformIdp = registry.getOrDefault("qits_platform_idp", platformIdp);
            events = registry.getOrDefault("qits_events", events);
            artifacts = registry.getOrDefault("qits_artifacts", artifacts);
            platformMirror = registry.getOrDefault("qits_platform_mirror", platformMirror);
            githost = registry.getOrDefault("qits_githost", githost);
            githostEventstream =
                    registry.getOrDefault("qits_githost_eventstream", githostEventstream);
            containers = registry.getOrDefault("qits_containers", containers);
            containersEventstream =
                    registry.getOrDefault("qits_containers_eventstream", containersEventstream);
            if (!registry.isEmpty()) {
                boot.state.pgCiPassword = ci;
                boot.state.pgCiEventstreamPassword = ciEventstream;
                boot.state.pgPlatformIdpPassword = platformIdp;
                boot.state.pgEventsPassword = events;
                boot.state.pgArtifactsPassword = artifacts;
                boot.state.pgPlatformMirrorPassword = platformMirror;
                boot.state.pgGithostPassword = githost;
                boot.state.pgGithostEventstreamPassword = githostEventstream;
                boot.state.pgContainersPassword = containers;
                boot.state.pgContainersEventstreamPassword = containersEventstream;
                state.put("PG_CI_PASSWORD", ci);
                state.put("PG_CI_EVENTSTREAM_PASSWORD", ciEventstream);
                state.put("PG_PLATFORM_IDP_PASSWORD", platformIdp);
                state.put("PG_EVENTS_PASSWORD", events);
                state.put("PG_ARTIFACTS_PASSWORD", artifacts);
                state.put("PG_PLATFORM_MIRROR_PASSWORD", platformMirror);
                state.put("PG_GITHOST_PASSWORD", githost);
                state.put("PG_GITHOST_EVENTSTREAM_PASSWORD", githostEventstream);
                state.put("PG_CONTAINERS_PASSWORD", containers);
                state.put("PG_CONTAINERS_EVENTSTREAM_PASSWORD", containersEventstream);
                state.write();
                ctx.log("  " + registry.size() + " credentials read back from the deployer's "
                        + "registry — the seed uses what the rows say");
            }

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
                // exactly what ci and the idp already go through; the CLI only
                // opens the door and never alters the role again.
                provision(ctx, admin, "qits_events", events, false);
                // THE BYTE PLANE'S THREE SERVICES, FOUR DATABASES, on the same terms as everything
                // above: every one of these containers boots before any deployer exists, and each
                // dies at Flyway's first connect without its database.
                //
                // The mirror and the hosted store are the earliest consumers of any database here —
                // both are started BY HAND, before the compose file is even written, because every
                // publish after them resolves through the one and lands in the other. The git host
                // takes two, because the eventstream library keeps its outbox in a lineage of its
                // own and a push that publishes no event is a push no consumer ever learns about.
                //
                // CREATE-IF-MISSING, NEVER ALTER. That is the `false` arm every line below is on,
                // and qits_artifacts is the newest to need it said: from the first pipeline deploy
                // of qits-artifacts on, its `resources: postgresql:db` line runs and the deployer's
                // pd_resource row is the AUTHORITY on this password. A rerun of this CLI that
                // ALTERed the role would rotate a credential the registry owns and lock out the
                // running deployment. Nothing is repaired here either: a mismatch self-heals on the
                // next artifacts deploy, where the deployer's reconcile arm rotates the role and
                // records what it set. The CLI opens the door once and never touches it again.
                provision(ctx, admin, "qits_artifacts", artifacts, false);
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
            ctx.note(SEED_DATABASES.size() + " databases ready on " + pg);
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

    // --- secrets, compose, extras ----------------------------------------------------------------

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

    /** Generate one run's two ingress capabilities before compose needs githost's fingerprint. */
    public Phase bootstrapIngressPrepare() {
        return new Phase("bootstrap-ingress-prepare", "prepare the bootstrap-only ingress capability",
                ctx -> {
                    boot.ingress.prepare(ctx::log);
                    if (!boot.config.bootstrapIngress()) {
                        ctx.skip("QITS_BOOTSTRAP_INGRESS=0");
                    }
                });
    }

    /** Starts before the seed stack and never waits for or routes through the normal edge. */
    public Phase bootstrapIngressStart() {
        return new Phase("bootstrap-ingress", "start the temporary bootstrap ingress", ctx -> {
            boot.ingress.start(ctx::log);
            if (!boot.config.bootstrapIngress()) {
                ctx.skip("QITS_BOOTSTRAP_INGRESS=0");
            }
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
     * The deployer's per-application extras, as a config file on a named volume: quarkus reads
     * config/application.properties next to the binary, and a self-update's successor mounts the
     * same volume — which is the whole reason this is a file and not compose env.
     * <p>
     * <b>It writes the two PULLERS' docker credentials too</b>, and they are here rather than in a
     * phase of their own because they are the same act: a file on a config volume that a container
     * reads at a mount path. The deployer's config.json goes beside its extras on the volume it
     * already has; qits-containers gets a volume that holds nothing else. Both are LOAD-BEARING
     * since the flip landed on 2026-08-14 — the edge refuses a read with no credential — and both
     * were written before it so that closing the door was a configuration change rather than a
     * redeploy of the deployer and the orchestrator.
     */
    public Phase pdExtras() {
        return new Phase("pd-extras", "write the deployer's extras config volume", ctx -> {
            boot.docker.ensureVolume("qits-deployments-config", ctx::log);
            boot.docker.ensureVolume("qits-containers-config", ctx::log);
            String properties = ComposeTemplate.extras(tokens());
            // What the volume held BEFORE this write, as a DIGEST rather than as text: the file
            // carries the push token and every client secret, and reading it back would put both
            // on the screen and in the log.
            String before = configDigest();
            Cmd write = Cmd.of(List.of(
                            "docker", "run", "--rm", "-i",
                            "-v", "qits-deployments-config:/cfg",
                            "--entrypoint", "sh", "alpine/git",
                            "-c", "cat > /cfg/application.properties "
                                    + "&& chown 1001:0 /cfg/application.properties"))
                    .stdin(properties)
                    .mask(boot.config.pushToken())
                    // Both postgres passwords are in this file too: the deployer's own credential
                    // and the admin one it provisions every other application's database with.
                    .mask(orEmpty(boot.state.pgSuperuserPassword))
                    .mask(orEmpty(boot.state.pgDeploymentsPassword))
                    // The seed services' credentials are NOT in this file — the deployer injects
                    // their triples from its own registry, its own outbox included. Masked anyway:
                    // one each, against the day one of them is pinned here by hand.
                    .mask(orEmpty(boot.state.pgDeploymentsEventstreamPassword))
                    .mask(orEmpty(boot.state.pgCiPassword))
                    .mask(orEmpty(boot.state.pgCiEventstreamPassword))
                    .mask(orEmpty(boot.state.pgPlatformIdpPassword))
                    .mask(orEmpty(boot.state.pgEventsPassword))
                    .mask(orEmpty(boot.state.pgArtifactsPassword))
                    .mask(orEmpty(boot.state.pgPlatformMirrorPassword))
                    .mask(orEmpty(boot.state.pgGithostPassword))
                    .mask(orEmpty(boot.state.pgGithostEventstreamPassword));
            // EVERY IDP CLIENT SECRET, and the loop is what keeps the list honest. ci's used to be
            // the one spelled here, because ci's was the one value in the file. It is not: the
            // idp's own block carries a secret per client, and ci now carries the ARTIFACTS
            // client's a second time — the credential its publish steps push to the registry
            // with. A mask that has to be remembered per key is a secret on the screen the first
            // time somebody adds one.
            for (String client : PlatformModel.idpClients(boot.config.envName())) {
                write.mask(boot.state.secrets.getOrDefault(client, ""));
            }
            ProcessResult result = boot.docker.run(write, ctx::log);
            Boot.must(result, "writing the deployer's extras failed");
            ctx.log("  " + properties.lines()
                    .filter(l -> l.startsWith("qits.platform.deployments.extras."))
                    .map(l -> l.substring("qits.platform.deployments.extras.".length()).split("\\.")[0])
                    .distinct().count()
                    + " applications configured on the qits-deployments-config volume");
            dockerConfig(ctx, "qits-deployments-config", "deployments");
            dockerConfig(ctx, "qits-containers-config", "containers");
            if (!sha256(properties).equals(before)) {
                restartSeedDeployer(ctx);
            }
        });
    }

    /**
     * One puller's docker credential, as {@code config.json} on its config volume. The docker CLI
     * these two shell out to reads a FILE named by {@code DOCKER_CONFIG}, which is what both
     * generated files set on them — the containers run as uid 1001 with no home, so there is no
     * other place the CLI would look.
     * <p>
     * <b>The identity is the puller's own idp client</b>, never a borrowed one: a pull the registry
     * refuses has to name the service that was refused. The secret is the same generated value the
     * idp's own block is handed for that client — one value, recorded once in
     * {@code .qits-bootstrap.env}, read by both sides of one credential.
     * <p>
     * Rerun-safe by being a whole-file write, and not digested like the extras beside it: nothing
     * restarts on a change, because the CLI opens this file per invocation rather than at boot.
     */
    private void dockerConfig(PhaseContext ctx, String volume, String app) {
        String client = PlatformModel.wireAlias(app, boot.config.envName());
        String secret = boot.state.secrets.getOrDefault(client, "");
        Cmd write = dockerConfigWrite(volume,
                dockerConfigJson(boot.config.registryVhost(), client, secret), client, secret);
        Boot.must(boot.docker.run(write, ctx::log), "writing " + client + "'s config.json failed");
        ctx.log("  " + client + " docker credential on the " + volume + " volume, for "
                + boot.config.registryVhost() + " (every read there needs it)");
    }

    /**
     * The write itself, and the two values it must never print. The secret is one; the BASE64 is
     * the other and is not covered by the first — the raw secret is not a substring of it, so a
     * mask on the secret alone would leave the whole credential on the screen in one token.
     */
    static Cmd dockerConfigWrite(String volume, String json, String clientId, String secret) {
        return Cmd.of(List.of(
                        "docker", "run", "--rm", "-i",
                        "-v", volume + ":/cfg",
                        "--entrypoint", "sh", "alpine/git",
                        // 0600 and the image's own uid: a credential file is readable by the one
                        // container that presents it and by nobody sharing the volume later.
                        "-c", "cat > /cfg/config.json && chown 1001:0 /cfg/config.json "
                                + "&& chmod 600 /cfg/config.json"))
                .stdin(json)
                .mask(secret)
                .mask(dockerAuth(clientId, secret));
    }

    /**
     * The docker CLI's own {@code config.json}: four fixed keys around one base64 of
     * {@code <id>:<secret>}, the same bytes {@code docker login} would store. Hand-written, because
     * base64 has no character JSON escapes and the only value that could need quoting is the
     * registry host — a deployment fact, escaped here anyway.
     */
    static String dockerConfigJson(String registryHost, String clientId, String secret) {
        String host = registryHost.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"auths\":{\"" + host + "\":{\"auth\":\"" + dockerAuth(clientId, secret)
                + "\"}}}\n";
    }

    private static String dockerAuth(String clientId, String secret) {
        return Base64.getEncoder()
                .encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The digest of the config file already on the volume, or empty when there is none. Computed
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
     * <b>The deployer reads its config ONCE, at its own boot.</b> A rerun that changes the file
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
            ctx.log("  the extras changed; no seed deployer is running, so none is holding "
                    + "the old ones");
            return;
        }
        ctx.log("  the extras changed and " + name + " is older than the change — restarting it "
                + "so it deploys from the new ones");
        Boot.must(boot.docker.exec(Duration.ofMinutes(5), ctx::log, "restart", name),
                "restarting " + name + " after its extras changed failed");
        ctx.note("extras changed, " + name + " restarted");
    }

    // --- what a domain adds -----------------------------------------------------------------------

    /**
     * <b>A self-signed certificate for the domain, so the edge can start at all.</b>
     * <p>
     * The edge's keystore names two files on the qits-edge-letsencrypt volume, and a keystore whose
     * files are missing fails startup — so on a cold boot the volume has to hold something before
     * compose starts the edge with that configuration. This writes it. It is a placeholder in the
     * only sense that matters: browsers reject it, and the real PEMs land in the same two filenames
     * later in this same run — the {@code edge-acme} phase orders them once the name resolves — after
     * which the TLS registry reloads. It is also what the edge keeps when that order does not go
     * through, which is why the placeholder is still written on every path rather than only on the
     * paths that end without a certificate.
     * <p>
     * <b>Skipped whenever a certificate is already there</b>, and that check is not an optimisation:
     * overwriting would replace a REAL certificate with a self-signed one, on a running public
     * platform, every time somebody reran the boot. The existence test and the write are one
     * container so nothing can happen between them.
     * <p>
     * <b>The image is alpine/git with openssl added</b> — the same image the extras write uses one
     * phase earlier, so the boot pulls nothing new. It carries git and not openssl (measured, on
     * 2026-08-09), and the platform's other already-present images carry neither: nginx:alpine and
     * the qits service images have no openssl binary either. {@code apk add} is what the two npm
     * publish phases already do, so it adds no dependency the boot did not have.
     * <p>
     * {@code chown 1001:0} for the same reason the extras write has it: the edge runs as that
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
     * One A record the domain needs: the name relative to the apex, and the address it answers.
     * <p>
     * Written relative because that is how a dns provider's own record editor asks for it, and
     * because a name with the domain spelled into it is the commonest way to end up with
     * {@code <domain>.<domain>}.
     */
    public record ZoneRecord(String name, String value, String why) {
    }

    /**
     * <b>Every name this platform answers for, as three A records.</b> Derived from the shapes the
     * edge routes by rather than from a list of today's environments and applications, which is what
     * makes it a set that never needs revisiting.
     * <p>
     * <b>This platform serves no dns.</b> The set is what the domain's own provider has to hold, and
     * the closing report prints it — nothing here writes a record.
     * <p>
     * The edge reads at most the first two labels of a Host header: {@code <env>.<domain>} is an
     * environment's gateway, {@code <app>.<env>.<domain>} is one of its byte-plane vhosts (registry,
     * mirror, githost today), and everything else — the apex included — falls through to the default
     * environment's gateway. So the durable answer is a wildcard per DEPTH, not a record per name:
     * <ul>
     * <li>{@code @} — the apex, and it is not decoration. A wildcard never matches the apex, and the
     *     apex is the address a person types, so without this record the front door has no answer.
     * <li>{@code *} — every one-label name: {@code <env>.<domain>} for every environment there will
     *     ever be, and anything else at that depth.
     * <li>{@code *.*} — every two-label name: {@code <app>.<env>.<domain>} for every application the
     *     edge gains a vhost for. Adding an environment or an app is then a deploy and no dns step,
     *     which is the whole reason this is a wildcard.
     * </ul>
     * Every value is the same address, because every one of these names is this one host: the edge
     * is a single front door and the routing is by Host header behind it.
     * <p>
     * Depth three and beyond is deliberately left to answer NXDOMAIN, and no shape this platform
     * serves is that deep.
     */
    public static List<ZoneRecord> zoneRecords(String domain, String publicIp) {
        return List.of(
                new ZoneRecord("@", publicIp, "the apex — the browser door, and no wildcard covers it"),
                new ZoneRecord("*", publicIp, "every <env>." + domain + " gateway"),
                new ZoneRecord("*.*", publicIp, "every <app>.<env>." + domain + " vhost"));
    }

    /**
     * <b>The real certificate, ordered from Let's Encrypt in this run.</b> With a domain set, this is
     * what turns the {@code edge-cert} phase's self-signed placeholder into something a browser
     * accepts — automatically, because a bootstrap that leaves a command for a person to paste is a
     * bootstrap that has not finished.
     * <p>
     * <b>Who does what, because it is not obvious.</b> qits-platform-edge is NOT an ACME client. The
     * Quarkus TLS extension gives it three routes and one slot: {@code /.well-known/acme-challenge/}
     * on the main listener, which answers whatever token the slot holds for ANY Host — it wins over
     * the proxy, which is what makes this work for a vhost as much as for the apex — and, on the
     * unpublished management port, a way to fill that slot and a way to re-read the certificate
     * files. The protocol itself — the account, the order, the key, the CSR, the download — belongs
     * entirely to the issuer. So this phase IS the ACME client.
     * <p>
     * <b>It is a transient helper container, and that is the cheap end of a real choice.</b> The
     * alternative was an ACME library inside this binary: a JWS signer, a nonce loop, a CSR builder
     * and the native-image registrations they need, for something that runs once per certificate.
     * certbot is the reference client, it takes a hook for exactly the "you serve the challenge, I
     * run the protocol" split this edge is built around, and the container is gone the moment it
     * exits — nothing standing is added to the platform. It is the same shape as the placeholder
     * phase above, which already writes to this volume from a throwaway container.
     * <p>
     * <b>ONE name: the apex.</b> The slot holds a single challenge and refuses a second with a 400,
     * and certbot answers every name of a multi-name order before the CA validates any of them — so
     * a SAN certificate over {@code <env>.<domain>} and the app vhosts cannot be ordered through
     * this endpoint at all. The apex is the name that matters: it is {@code publicOrigin}, the
     * browser's door and the passkey's relying party. Covering the rest wants a wildcard, a wildcard
     * wants DNS-01, and DNS-01 wants a TXT record written at the domain's dns provider mid-order —
     * so it needs a provider API this program has no hook for yet.
     * <p>
     * <b>A failure warns and the boot goes on</b>, exactly as the register token's mint does. The
     * commonest reason is dns: a name the world cannot resolve yet answers nothing, and the
     * HTTP-01 challenge is fetched over precisely that name. It fixes itself in
     * minutes to hours, this program can neither hurry it nor tell it from a real misconfiguration,
     * and nothing is lost — the edge keeps the placeholder, the report prints the retry, and a rerun
     * asks again.
     * <p>
     * <b>Idempotent by reading the volume rather than by remembering.</b> The certificate that is
     * there names its own issuer: a placeholder is self-signed under the domain, a staging one says
     * STAGING, a real one names Let's Encrypt. A certificate that already matches what was asked for
     * and is not near expiry is left alone, and <b>a production certificate is never replaced by a
     * staging one</b> — a rerun that forgot to carry the mode must not take a working site down.
     */
    public Phase edgeCertificate(String domain) {
        return new Phase("edge-acme", "order the Let's Encrypt certificate for " + domain, ctx -> {
            Acme.Mode mode = Acme.mode(boot.config);
            if (mode == Acme.Mode.OFF) {
                ctx.skip("QITS_ACME_MODE=off — the edge keeps the placeholder certificate");
            }
            String email = Acme.email(boot.config, domain);
            // certbot's own state: the ACME account key and the certificate lineage. On a volume so
            // a rerun renews rather than registering a second account every boot, and so certbot's
            // own "not yet due for renewal" is available as a second line of idempotency behind the
            // one this phase makes for itself.
            boot.docker.ensureVolume(ACME_STATE_VOLUME, ctx::log);
            ctx.status("certbot certonly --manual, " + mode.word() + ", for " + domain);
            ProcessResult result = boot.docker.run(Cmd.of(List.of(
                    "docker", "run", "--rm",
                    // The management port is on qits-net and nowhere else, and so is this container.
                    "--network", Boot.NETWORK,
                    "-v", "qits-edge-letsencrypt:/cert",
                    "-v", ACME_STATE_VOLUME + ":/acme",
                    "-e", "MODE=" + mode.word(),
                    "-e", "DIRECTORY=" + mode.directory(),
                    "-e", "DOMAIN=" + domain,
                    "-e", "EMAIL=" + email,
                    "-e", "EDGE_URL=" + boot.config.edgeLetsEncryptUrl(),
                    "--entrypoint", "sh", "alpine/git", "-c", ACME_SCRIPT)), ctx::log);
            if (!result.ok()) {
                ctx.warn("no certificate for " + domain + " (exit " + result.exitCode() + "). The "
                        + "edge keeps its PLACEHOLDER certificate and browsers reject it. The usual "
                        + "reason is dns: until the domain's A records have propagated, the "
                        + "HTTP-01 challenge cannot be fetched over " + domain
                        + " — and port 80 has to reach this host from the internet too. Nothing is "
                        + "lost; a rerun asks again, and the closing report prints the command to "
                        + "do it by hand.");
                return;
            }
            // The skip is decided in the script's first lines and it exits there, so the captured
            // head holds it whole — no need to reach into the tail for it.
            String output = result.out();
            if (output.contains(SKIPPED)) {
                // Whatever is on the volume is already what was asked for, so the reload below would
                // be a no-op on files nothing changed.
                boot.state.certificate = output.contains("production") ? "production" : mode.word();
                ctx.note("kept the " + boot.state.certificate + " certificate");
                return;
            }
            boot.state.certificate = mode.word();
            // The files are in place; this is what makes them LIVE now rather than within the hour.
            // QUARKUS_TLS_RELOAD_PERIOD is 1h, so a reload that fails costs a wait and not a
            // certificate — which is why it warns in place rather than undoing the phase.
            String certs = boot.config.edgeLetsEncryptUrl() + "/certs";
            ctx.status("POST " + certs);
            Http.Response reloaded = boot.http.postJson(certs, "", Map.of());
            if (reloaded.ok()) {
                ctx.log("  the edge reloaded its certificate");
            } else {
                ctx.log("  the reload answered " + reloaded.describe() + " — the certificate is on "
                        + "the volume and the TLS registry re-reads it within the hour anyway");
            }
            ctx.note(mode.word() + " certificate issued");
        });
    }

    /** certbot's config, work and log directories, and with them the ACME account key. */
    static final String ACME_STATE_VOLUME = "qits-edge-acme";

    /** What the script prints when it decided the volume already holds the right certificate. */
    static final String SKIPPED = "qits-acme: skipped";

    /**
     * The whole ACME run, in a throwaway container: decide whether anything is needed, write the two
     * hooks, order the certificate, and put the PEMs where the edge's keystore names them.
     * <p>
     * <b>The hooks are the point of the whole design.</b> certbot runs the protocol and, for each
     * authorization, hands the token and the key authorization to the auth hook — which does the one
     * thing this platform needs: fills the edge's challenge slot over the management port. The
     * cleanup hook empties it. Neither ever touches port 80: the edge is already listening there and
     * already answers the challenge route.
     * <p>
     * <b>The slot is cleared before the order starts</b>, because it holds one challenge and answers
     * 400 to a second. A run killed between filling it and cleaning it up would otherwise block
     * every run after it, with a message about a challenge already being set that says nothing about
     * why.
     * <p>
     * <b>The lineage is named per MODE</b> — the staging and the production certificates are two
     * separate certbot lineages — so a flip from one to the other is a fresh order rather than a
     * renewal against a different ACME server, which certbot refuses without being told twice.
     * <p>
     * The ownership is not decoration: the edge runs as uid 1001 and reads these two files at
     * startup and at every reload. A key it cannot read is a reload that fails and a certificate
     * nobody sees.
     */
    static final String ACME_SCRIPT = """
            set -eu
            apk add --no-cache openssl certbot curl >/dev/null 2>&1

            # What is on the volume decides whether anything happens at all, and the certificate
            # says for itself what it is: a placeholder is self-signed under the domain, a staging
            # certificate names a STAGING issuer, a real one names Let's Encrypt.
            have=none
            if [ -f /cert/lets-encrypt.crt ]; then
              issuer=$(openssl x509 -in /cert/lets-encrypt.crt -noout -issuer 2>/dev/null || echo "")
              case "$issuer" in
                *STAGING*|*Staging*|*staging*) have=staging ;;
                *"Let's Encrypt"*|*"Lets Encrypt"*) have=production ;;
                *) have=placeholder ;;
              esac
              # Thirty days, which is the window Let's Encrypt itself asks renewals to happen in.
              if [ "$have" != placeholder ] \\
                  && ! openssl x509 -in /cert/lets-encrypt.crt -noout -checkend 2592000 \\
                       >/dev/null 2>&1; then
                have="$have-expiring"
              fi
            fi
            echo "on the volume now: $have"

            case "$have" in
              production)
                # Never replaced by a staging certificate: a rerun that forgot to carry the mode
                # must not take a working site down.
                echo "qits-acme: skipped — a production certificate is live and not near expiry"
                exit 0 ;;
              staging)
                if [ "$MODE" = staging ]; then
                  echo "qits-acme: skipped — a staging certificate is live and not near expiry"
                  exit 0
                fi ;;
            esac

            cat >/tmp/qits-acme-auth <<'HOOK'
            #!/bin/sh
            set -eu
            curl -fsS -G "$EDGE_URL/challenge" \\
              --data-urlencode "challenge-resource=$CERTBOT_TOKEN" \\
              --data-urlencode "challenge-content=$CERTBOT_VALIDATION" >/dev/null
            HOOK
            cat >/tmp/qits-acme-clean <<'HOOK'
            #!/bin/sh
            curl -fsS -X DELETE "$EDGE_URL/challenge" >/dev/null 2>&1 || true
            HOOK
            chmod +x /tmp/qits-acme-auth /tmp/qits-acme-clean

            # One slot, and a second challenge is a 400. Clear whatever a killed run left behind.
            curl -fsS -X DELETE "$EDGE_URL/challenge" >/dev/null 2>&1 || true

            certbot certonly --manual --preferred-challenges http \\
              --manual-auth-hook /tmp/qits-acme-auth \\
              --manual-cleanup-hook /tmp/qits-acme-clean \\
              --server "$DIRECTORY" --email "$EMAIL" --agree-tos --non-interactive \\
              --config-dir /acme --work-dir /acme/work --logs-dir /acme/logs \\
              --cert-name "qits-edge-$MODE" --keep-until-expiring -d "$DOMAIN"

            cp "/acme/live/qits-edge-$MODE/fullchain.pem" /cert/lets-encrypt.crt
            cp "/acme/live/qits-edge-$MODE/privkey.pem" /cert/lets-encrypt.key
            chown 1001:0 /cert/lets-encrypt.crt /cert/lets-encrypt.key
            chmod 644 /cert/lets-encrypt.crt
            chmod 640 /cert/lets-encrypt.key
            echo "qits-acme: issued a $MODE certificate for $DOMAIN"
            """;

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
        // Neither generated file publishes these two any more — the byte plane is behind the edge —
        // but both are still filled, so a comment or a line that names one renders a number rather
        // than a placeholder.
        values.put("MIRROR_PORT", String.valueOf(boot.config.mirrorPort()));
        values.put("GIT_HOST_PORT", String.valueOf(boot.config.gitHostPort()));
        // Resolved by seed-postgres, which runs before both generated files are written.
        values.put("PG_SUPERUSER_PASSWORD", orEmpty(boot.state.pgSuperuserPassword));
        values.put("PG_DEPLOYMENTS_PASSWORD", orEmpty(boot.state.pgDeploymentsPassword));
        values.put("PG_DEPLOYMENTS_EVENTSTREAM_PASSWORD",
                orEmpty(boot.state.pgDeploymentsEventstreamPassword));
        values.put("PG_CI_PASSWORD", orEmpty(boot.state.pgCiPassword));
        values.put("PG_CI_EVENTSTREAM_PASSWORD", orEmpty(boot.state.pgCiEventstreamPassword));
        values.put("PG_PLATFORM_IDP_PASSWORD", orEmpty(boot.state.pgPlatformIdpPassword));
        values.put("PG_EVENTS_PASSWORD", orEmpty(boot.state.pgEventsPassword));
        values.put("PG_ARTIFACTS_PASSWORD", orEmpty(boot.state.pgArtifactsPassword));
        values.put("PG_PLATFORM_MIRROR_PASSWORD", orEmpty(boot.state.pgPlatformMirrorPassword));
        values.put("PG_GITHOST_PASSWORD", orEmpty(boot.state.pgGithostPassword));
        values.put("PG_GITHOST_EVENTSTREAM_PASSWORD",
                orEmpty(boot.state.pgGithostEventstreamPassword));
        values.put("PG_CONTAINERS_PASSWORD", orEmpty(boot.state.pgContainersPassword));
        values.put("PG_CONTAINERS_EVENTSTREAM_PASSWORD",
                orEmpty(boot.state.pgContainersEventstreamPassword));
        values.put("IDP", boot.config.idpIssuer());
        values.put("PUSH_TOKEN", boot.config.pushToken());
        values.put("BOOTSTRAP_INGRESS_GIT_ENABLED", String.valueOf(boot.config.bootstrapIngress()));
        values.put("BOOTSTRAP_INGRESS_GIT_CAPABILITY_HASH",
                orEmpty(boot.state.bootstrapIngressGitCapabilityHash));
        values.put("BOOTSTRAP_INGRESS_GIT_REPOSITORY", orEmpty(boot.state.bootstrapIngressRepository));
        values.put("BOOTSTRAP_INGRESS_GIT_REF_PATTERN", orEmpty(boot.state.bootstrapIngressRefPattern));
        values.put("BOOTSTRAP_INGRESS_GIT_EXPIRES_AT",
                boot.state.bootstrapIngressExpiresAt == 0 ? "1970-01-01T00:00:00Z"
                        : java.time.Instant.ofEpochSecond(boot.state.bootstrapIngressExpiresAt).toString());
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
        // THE PASSKEY BINDING, and it follows the address a browser arrives at rather than standing
        // beside it: a credential registered under one rp id asserts under no other host.
        values.put("WEBAUTHN_RP_ID", boot.config.webauthnRpId());
        values.put("WEBAUTHN_ORIGINS", boot.config.webauthnOrigins());
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
