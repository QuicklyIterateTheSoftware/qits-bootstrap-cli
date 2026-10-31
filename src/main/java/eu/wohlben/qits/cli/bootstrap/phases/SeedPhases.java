package eu.wohlben.qits.cli.bootstrap.phases;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.bootstrap.api.Http;
import eu.wohlben.qits.cli.bootstrap.api.Json;
import eu.wohlben.qits.cli.bootstrap.api.ServiceClientsApi;
import eu.wohlben.qits.cli.bootstrap.config.Acme;
import eu.wohlben.qits.cli.bootstrap.config.ExtraSans;
import eu.wohlben.qits.cli.bootstrap.config.PublicIp;
import eu.wohlben.qits.cli.bootstrap.config.WrapperDir;
import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseContext;
import eu.wohlben.qits.cli.bootstrap.platform.BootstrapState;
import eu.wohlben.qits.cli.bootstrap.platform.TargetSlots;
import eu.wohlben.qits.cli.bootstrap.platform.ComposeTemplate;
import eu.wohlben.qits.cli.bootstrap.platform.Docker;
import eu.wohlben.qits.cli.bootstrap.platform.DomainTokens;
import eu.wohlben.qits.cli.bootstrap.platform.MavenPins;
import eu.wohlben.qits.cli.bootstrap.platform.MuslToolchain;
import eu.wohlben.qits.cli.bootstrap.platform.NpmSeed;
import eu.wohlben.qits.cli.bootstrap.platform.PgAdmin;
import eu.wohlben.qits.cli.bootstrap.platform.PinnedVersions;
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
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
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
     * <b>The run's local file repository, and what every seed build resolves the platform's own
     * jars through.</b> {@code maven-seed} deploys every {@link #SEED_LIBRARIES} entry into it with
     * {@code mvn deploy … file:///repo}, the protocol, golden-master, pact and runner seeds write
     * it, and {@code seed-toolchain} puts the musl tarballs in it. No server fronts it: a seed image
     * build reads it as the named context {@link SeedDockerfile#SEED_M2_CONTEXT} (see
     * {@link SeedDockerfile#localMaven}), and the probes ask the bytes with a {@code test -f} in a
     * throwaway container.
     * <p>
     * <b>It is also where the store's first contents come from.</b> Phase 1 publishes nothing
     * (qits-889); {@link #storeSeed} uploads its {@code eu/wohlben/qits} subtree into qits-artifacts
     * once the idp exists to commission the publishing credential.
     */
    static final String SEED_REPO_VOLUME = "qits-maven-seed";

    /**
     * The qits libraries a seed image build resolves, in DEPENDENCY ORDER, and the repository each
     * one is built from. Every one of them is deployed into the file repository below before the
     * first seed image is built, and reaches the store through {@code store-seed}.
     * <p>
     * Every pair here is forced by a pom:
     * <ul>
     *   <li>qits-registries carries the blob store as a module since 2026-08-30, and that module is
     *       written against qits-db-core, a module of qits-integrations-quarkus, since its DbRetry
     *       release (2026.813.161828) — so the integrations come FIRST. The same class of break as
     *       the 2026-08-11 one below, bought a second time on 2026-08-13: every earlier green run
     *       had a blob store with no qits dependency at all, and the day it grew one the seed
     *       resolved qits-db-core against a registry that does not exist yet — "Connection refused"
     *       on localhost:8081, seconds into the phase.
     *   <li><b>qits-blobstore needs no entry of its own any more.</b> Its repository is retired and
     *       its jar is a module of the qits-registries reactor, which is published WHOLE — so this
     *       one entry puts qits-blobstore and every registries jar in the registry, in the reactor's
     *       own order, before the first seed image is built. The pair that used to be spelled here
     *       (registries against the blob store's entities) is now inside one pom.
     *   <li>qits-eventstream is written against qits-db-core too, so the integrations come BEFORE
     *       it as well. This is the edge that broke the 2026-08-11 proving run: the integrations
     *       used to be last, and the day eventstream grew the dependency the seed died the same
     *       way, minutes into the phase.
     *   <li>qits-githost-events is written against qits-eventstream and follows it.
     *   <li><b>qits-containers-javalib joined on 2026-10-04 (qits-796), on qits-eventstream's own
     *       terms</b> — it resolves nothing of ours, so nothing above forces an order on it. It goes
     *       beside eventstream rather than after githost because qits-containers-service, which now
     *       depends on it, is otherwise unrelated to the git host's module.
     *   <li>qits-containers' client is written against qits-db-core, qits-eventstream and
     *       qits-containers-driver, so the orchestrator is last.
     *   <li><b>qits-userflows is FIRST and depends on nothing of ours</b> — playwright, jackson,
     *       rest-assured, junit. It is here at all because seventeen root poms pin it and every one
     *       of those builds resolves it: it is TEST-scoped, and {@code mvn package -DskipTests}
     *       resolves test-scoped dependencies all the same. It was in {@code SEEDED_REPOS} and
     *       nowhere else, so a cold boot's first seed image build died on
     *       {@code qits-userflows:pom:2026.904.223009 (absent)} — 2026-09-05, phase 9. First
     *       rather than last because the phase's own skip probe asks for the LAST entry.
     *   <li><b>qits-coding-agents is SECOND and depends on nothing of ours either</b> — vertx-core
     *       and junit. No seed image resolves it today: the two agent daemons that do are built by
     *       their release replays, which resolve from the store. It is here anyway because the
     *       file repository holds every qits jar a seed image could ask for, and a daemon that
     *       becomes a seed image must not be able to turn that into a failed build.
     * </ul>
     * <b>githost and containers name SERVICE repositories and publish one module of them each</b> —
     * the git host's event vocabulary, and the orchestrator's client. {@code containers-driver} is
     * not this shape: it is a library repository of its own, published WHOLE like eventstream. {@link
     * PlatformModel#mavenModule} says which modules and why.
     * <p>
     * <b>Each entry is a SET OF VERSIONS, not one.</b> The checkout's own version is published, and
     * so is every version of that library some pom in the estate still pins — see {@link
     * PinnedVersions}, and the cold boot of 2026-09-05 that died on a githost-events pin four hours
     * behind the eventstream checkout. The order above still decides which library is built before
     * which; within one library the pinned versions are built oldest first and the checkout last.
     * An older version of a library depends only on libraries ABOVE it here, whose own pinned
     * versions this same container has already deployed.
     */
    static final List<String> SEED_LIBRARIES = List.of(
            "userflows", "coding-agents", "integrations-quarkus", "registries", "eventstream",
            "containers-driver", "githost", "containers");

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
            ctx.log("  " + Docker.BUILDKITD + " (" + Docker.BUILDKIT_IMAGE
                    + "): enforced 9g memory and 4 cpu limits");
            // PRINTED, because it is derived and because getting it wrong stops the machine rather
            // than the boot: the builds it sizes run on the host daemon, outside every cgroup this
            // run sets.
            ctx.log("  qits-ci concurrent builds: " + boot.config.ciConcurrentBuildsEffective()
                    + " (" + (boot.config.ciConcurrentBuilds().isPresent()
                            ? "QITS_CI_CONCURRENT_BUILDS override, "
                            : "") + TargetSlots.describe(TargetSlots.hostMemoryBytes()) + ")");
            boot.state.swarm = ensureSwarm(boot.docker, ctx::log);
            ctx.log("  swarm: " + boot.state.swarm);
            warnAboutInsecureRegistries(ctx);
            if (!boot.git.available()) {
                throw new IllegalStateException("no git on PATH");
            }
            boot.state.dockerGid = boot.docker.socketGroupId();
            ctx.log("  docker socket group: " + boot.state.dockerGid);

            // The CLI is a submodule of the wrapper, so an unset QITS_WRAPPER_DIR is answered by
            // walking up from here rather than by assuming the working directory IS the
            // wrapper. Which of the two happened is printed: a run
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
            // Printed rather than assumed: every public name and the certificate hang under it.
            // The value was checked before the payload image was built, so this line cannot be
            // the first place a typo shows.
            ctx.log("  domain: " + boot.config.requiredDomain() + "  (its dns records are held "
                    + "outside this platform)");
            // REFUSED HERE RATHER THAN DISCOVERED AT THE CUTOVER. See DomainTokens.
            String refusal = DomainTokens.hetznerTokenRefusal(
                    Acme.mode(boot.config) != Acme.Mode.OFF,
                    boot.config.dnsHetznerToken().orElse(null),
                    boot.config.dnsHetznerSecret().orElse(null));
            if (refusal != null) {
                throw new IllegalStateException(refusal);
            }

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
     *   <li>A wrapper path that was not a checkout fell through to GitHub. A rename — or a LAYOUT
     *       CHANGE, which is the same defect for all 48 repositories at once — that outran
     *       {@link PlatformModel#repoPath} then deployed the org's last push instead of the work in
     *       the checkout, and said so in one line among thousands. Two things answer that now. The
     *       path is read from the wrapper's own {@code .gitmodules} rather than derived from the
     *       name, so a reorganised wrapper is followed rather than missed; and
     *       {@link #localSourceRefusal} stops the boot when a DECLARED module's directory holds no
     *       checkout while its siblings do.
     *       <p>
     *       <b>An EMPTY directory is still absent when the whole wrapper is</b>, and that is not a
     *       softening of the rule: git puts one at every gitlink whose submodule is not checked
     *       out, so the wrapper the cold start clones has one per repository and every one of them
     *       is meant to come from the org. It is the wrapper with SOME submodules checked out
     *       where a missing one is a defect, which is the line the refusal draws.
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
            if (boot.config.shipMains()) {
                // Said once, here, because this is the phase the flag used to change. A boot that
                // honoured it would build seeds from main and deploy the last release beside them,
                // which is the Flyway mismatch the restore exists to prevent.
                ctx.warn("--ship-mains (QITS_SHIP_MAINS) changes nothing on this platform and is "
                        + "ignored: a deployment is qits/<app>:<version> and an unreleased main "
                        + "has no version. Every checkout is stood at its newest release, seed "
                        + "images included. Cut a release request in qits-projects to deploy your "
                        + "commit");
            }
            // Asked once: a wrapper whose submodules are all uninitialised is the cold start, and
            // every repository of it legitimately comes from the org.
            boolean initialised = boot.state.wrapperInitialised(boot.git::isCheckout);
            // The read-only repositories too: their tags are where the pacts and the binary pin
            // the seed images resolve are cut from — see PlatformModel.READ_ONLY_REPOS.
            for (String name : PlatformModel.sourceRepos()) {
                String repo = PlatformModel.repo(name);
                Path localSrc = boot.state.wrapperCheckout(name);
                String refusal = localSourceRefusal(repo, localSrc, boot.state.wrapperPath(name),
                        boot.state.wrapperDeclares(name), initialised,
                        boot.git.isCheckout(localSrc),
                        Files.exists(localSrc) && !isEmptyDirectory(localSrc));
                if (refusal != null) {
                    throw new IllegalStateException(refusal);
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
                String identity = bootIdentity(name, boot.git.tagsNewestFirst(target, "main"));
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
            ctx.note(PlatformModel.sourceRepos().size() + " repositories, " + restored
                    + " at their release");
        });
    }

    /**
     * <b>Why this repository's checkout in the wrapper cannot be used — or null, which means it can
     * be, or that there honestly is none.</b> The one place the org-URL fallback is allowed to be
     * silent, and the reason it is a method of its own is that getting it wrong is invisible: a
     * bootstrap that clones from GitHub instead of from the operator's checkout runs green and
     * deploys last week's platform.
     * <p>
     * Three answers, and the middle one is the layout flip's:
     * <ul>
     *   <li>Something that is not a checkout is STANDING in the directory — the old rule, unchanged.
     *   <li>The wrapper DECLARES this repository, its directory holds no checkout, and its siblings
     *       do. That is a half-initialised wrapper, and the org fallback would build the org's last
     *       push while the work sits one submodule away. It stops the boot and names the fix.
     *   <li>Nothing there, and either the wrapper does not declare it or the wrapper has no
     *       submodule checked out at all. The second is the COLD START: the run clones the wrapper
     *       without its submodules on purpose — {@code sources} clones every repository from the
     *       org anyway — so an empty directory per gitlink is the expected state and hides nothing.
     * </ul>
     *
     * @param declared    the wrapper's {@code .gitmodules} names this repository
     * @param initialised at least one of the wrapper's submodules is checked out
     * @param occupied    the directory exists and holds something
     */
    static String localSourceRefusal(String repo, Path localSrc, String wrapperPath,
                                     boolean declared, boolean initialised, boolean checkout,
                                     boolean occupied) {
        if (checkout) {
            return null;
        }
        if (occupied) {
            return localSrc + " is not a git checkout and is not empty, so " + repo + " has no "
                    + "source and something else is standing in its place. Either the submodule is "
                    + "half-initialised, or the wrapper's .gitmodules and this directory disagree "
                    + "about where " + repo + " sits.";
        }
        if (declared && initialised) {
            return "the wrapper declares " + repo + " at " + wrapperPath + " and that directory "
                    + "holds no checkout, while its siblings do. This run would build the org's "
                    + "last push of " + repo + " instead of the work in " + localSrc + ", and say "
                    + "so in one line among thousands. Run `git submodule update --init "
                    + wrapperPath + "` in the wrapper and rerun.";
        }
        return null;
    }

    /**
     * THE BOOT'S IDENTITY for one checkout: the release tag it stands at, or empty for "stay on
     * main".
     * <p>
     * The same answer the deploy phase asks for — {@link PlatformModel#newestRelease} over the
     * version-sorted merged tags is the one place that decides which tag is a release — so the seed
     * image and the successor that replaces it are the same code. Empty means main, and two
     * different things answer empty: a repository that has never been released, and a repository
     * whose output carries no version identity at all.
     * <p>
     * <b>{@code --ship-mains} is not one of them any more, and it must never be again.</b> The flag
     * used to answer empty here AND point the deploy ref at main, so seed and successor still
     * agreed. There is no deploy ref: a deployment is {@code qits/<app>:<version>} and an
     * unreleased main has no version, so the successor is the last release whatever this answers.
     * A seed built from main beside it is the exact failure the restore was introduced to end — the
     * seed applies main's Flyway migrations and the released successor refuses to start against a
     * schema ahead of it. So the flag no longer reaches this function, and the {@code sources}
     * phase says so out loud when a run sets it.
     * <p>
     * <b>That last one is the scope, and it was learned the hard way.</b> Only a deployable or a
     * release publisher has a "last release" the platform can state — see
     * {@link PlatformModel#carriesVersionIdentity}. The step-image sources and the SPA seed sources
     * are rebuilt from source every boot and pinned by nobody, so their tags are stale by
     * construction: qits-oci's newest tag predated the {@code build} user its step images grew, and
     * a maven-base built from it could not launch a step that declares {@code user: build}.
     */
    static String bootIdentity(String name, List<String> tagsNewestFirst) {
        return PlatformModel.carriesVersionIdentity(name)
                ? PlatformModel.newestRelease(tagsNewestFirst)
                : "";
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
            // ONE identity key, and it is the bootstrap's own. The seed services' secrets are read
            // back from qits-deployments' registry by the idp-clients phase, because that is where
            // they live from the moment the idp issues them.
            String bootstrapClient = PlatformModel.bootstrapClientId(boot.config.envName());
            state.bootstrapSecret(bootstrapClient).ifPresent(secret -> {
                boot.state.bootstrapClientId = bootstrapClient;
                boot.state.bootstrapSecret = secret;
            });
            // A token this file holds is a token an earlier run minted, and the mint phase mints
            // once per installation: every call makes another key to the first admin account.
            boot.state.registerTokenRecorded = state.registerToken().isPresent();
            // WHICH STORAGE ID EACH REPOSITORY'S BARE IS UNDER, read here for the reason every other
            // recorded value is: a rerun that decided this afresh would address a bare it had not
            // created and leave the platform's whole history in the one it had.
            for (String name : PlatformModel.platformRepos()) {
                String repo = PlatformModel.repo(name);
                state.repositoryId(repo)
                        .ifPresent(id -> boot.state.repositoryIds.put(repo, id));
            }
            if (!state.exists()) {
                ctx.log("  no " + BootstrapState.FILE_NAME + " — this is a first boot");
                ctx.note("first boot");
                return;
            }
            ctx.log("  " + state.file());
            ctx.log("  recorded daemon digest: "
                    + (boot.state.daemonSha == null ? "none" : shortSha(boot.state.daemonSha)));
            ctx.log("  recorded bootstrap client secret: "
                    + (boot.state.bootstrapSecret == null ? "none yet" : "yes"));
            ctx.log("  recorded repository storage ids: " + boot.state.repositoryIds.size() + " of "
                    + PlatformModel.platformRepos().size());
            ctx.log("  register token: " + (boot.state.registerTokenRecorded
                    ? "minted by an earlier run" : "none yet"));
            ctx.note(boot.state.bootstrapSecret == null ? "no bootstrap secret yet"
                    : "kept the bootstrap secret");
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
     * steady state. The cycle is broken with a bootstrap-owned file repository, the
     * {@link #SEED_REPO_VOLUME} volume: a directory in Maven's own layout, written by
     * {@code mvn deploy … file:///repo} and read by every seed image build as a named context. No
     * server stands in front of it.
     * <p>
     * <b>The byte-plane split made this phase carry several libraries instead of one</b>, and that
     * is not a widening for its own sake: qits-artifacts, qits-platform-mirror and qits-githost are
     * built out of qits-blobstore and qits-registries, and all three of their seed images are built
     * before anything of this platform can publish a jar. A seed build resolving a library the
     * file repository does not hold fails minutes in, naming a version nobody ever deployed.
     * <p>
     * <b>The file repository holds every qits jar a seed image could ask for, whatever the plan's
     * order is.</b> Every seed image resolves from it and from nothing else of this platform — the
     * store is filled from it, by {@code store-seed}, for the release replays of phase 4, not for
     * the seed builds.
     * <p>
     * <b>One container, one deploy per entry, in the order {@link #SEED_LIBRARIES} spells.</b>
     * {@code mvn deploy} installs into the local repository on its way out, so qits-registries
     * resolves the qits-db-core the line above it just built — which is the whole reason these are
     * not one container each. The other side of that: a library built before the qits jar it
     * depends on resolves nothing local and fails naming the jar. Read the poms before moving a
     * line.
     * <p>
     * That local repository is now {@link #MAVEN_CACHE_VOLUME}, kept across runs so Central is not
     * re-read every bootstrap — and the script starts by deleting this platform's own group out of
     * it, so what one line hands the next is always this run's bytes. {@link #MAVEN_PURGE_QITS}
     * says why that is not optional.
     * <p>
     * <b>Every entry is published at every version the estate still pins, not only at the
     * checkout's.</b> Consumer poms name library versions in root-pom properties and those
     * properties lag by hours or weeks; on the live platform that is harmless, because the store
     * holds every version ever released. Here it is the boot: one lagging pin used to end this
     * phase minutes in, naming a version nothing had published. {@link PinnedVersions} closes the
     * set and {@link #seedTree} says where each version's source is put.
     */
    public Phase mavenSeed() {
        return new Phase("maven-seed",
                "seed the qits libraries for the first byte-plane image builds",
                ctx -> {
                    // Deployed again on every worker start: a deploy over an existing coordinate in
                    // a file repository simply rewrites it, and this run's bytes are the ones the
                    // seed images have to build against.
                    boot.docker.ensureVolume(SEED_REPO_VOLUME, ctx::log);
                    boot.docker.ensureVolume(MAVEN_CACHE_VOLUME, ctx::log);
                    // THE PINS, READ BEFORE THE FIRST TREE IS COPIED. What the estate still asks
                    // for decides how many trees this container holds, so the closure is the
                    // script's own input rather than something checked afterwards.
                    PinnedVersions pinned = boot.pinnedVersions(ctx);
                    Map<String, List<String>> extra = new LinkedHashMap<>();
                    for (String library : SEED_LIBRARIES) {
                        extra.put(library, pinned.extraVersions(library));
                    }
                    String cid = create(ctx, List.of(
                            "docker", "create", "--user", "root", "--entrypoint", "sh",
                            "-v", SEED_REPO_VOLUME + ":/repo", "-v", MAVEN_CACHE_MOUNT,
                            "maven:3.9-eclipse-temurin-25",
                            "-c", seedScript(extra)));
                    for (String library : SEED_LIBRARIES) {
                        String repo = PlatformModel.repo(library);
                        for (String version : extra.get(library)) {
                            copyTag(ctx, library, version, cid, seedTree(library, version));
                        }
                        ctx.log("  " + repo + " -> " + seedTree(library, null));
                        Boot.must(boot.docker.exec(Duration.ofMinutes(30), ctx::log, "cp",
                                        boot.state.repoDir(library) + "/.",
                                        cid + ":" + seedTree(library, null)),
                                "copying " + repo + " in failed");
                    }
                    startAndReap(ctx, cid, "the qits library seed failed");
                    ctx.note("file repository " + SEED_REPO_VOLUME + " filled");
                });
    }

    // --- seed images ------------------------------------------------------------------------------

    public Phase seedImage(String name) {
        return new Phase("seed-image-" + name, "build the seed image qits/" + name + ":latest", ctx -> {
            Path repo = boot.state.repoDir(name);
            ctx.status("fetching submodules of qits-" + name);
            Boot.must(boot.git.submodulesShallow(repo, ctx::log),
                    "submodule checkout of qits-" + name + " failed");

            // THE REAL CLIENT, built from the webui gitlink the checkout just stood (qits-889). Its
            // Dockerfile consumes an already-built SPA, and the bundle it gets is the one the
            // release would ship — never a placeholder: a seed service is deployed and serves
            // browsers until the train replaces it.
            if (!PlatformModel.seedUiPath(name).isEmpty()) {
                seedClient(ctx, name, repo);
            }

            Path dockerfilePath = repo.resolve(PlatformModel.dockerfilePath(name));
            String origin = PlatformModel.repo(name) + "/" + PlatformModel.dockerfilePath(name);
            // An image repository (qits-oci-postgresql) is its Dockerfile and nothing else; every
            // other seed image is a Quarkus service whose build RUN resolves the platform's jars.
            boolean imageRepository = "Dockerfile".equals(PlatformModel.dockerfilePath(name));
            // THE JARS COME OUT OF THE qits-maven-seed VOLUME, never off a server. The committed
            // build RUN derives https://registry.qits.$QITS_DOMAIN — the live platform, during a
            // bootstrap — and the rewrite points it at the volume, mounted as a named context, and
            // refuses to build a Dockerfile it cannot point there.
            String dockerfile = imageRepository
                    ? SeedDockerfile.refuseServers(SeedDockerfile.read(dockerfilePath), origin)
                    : SeedDockerfile.localMaven(SeedDockerfile.read(dockerfilePath), origin);
            Map<String, String> contexts = imageRepository
                    ? Map.of() : Map.of(SeedDockerfile.SEED_M2_CONTEXT, SEED_REPO_VOLUME);
            if (!imageRepository) {
                // Before the native build, and for every coordinate at once: what the poms pin
                // and the file repository does not hold would otherwise be found one artifact
                // per failed build, minutes into each.
                ctx.status("checking qits-" + name + "'s qits coordinates in " + SEED_REPO_VOLUME);
                mavenPreflight(ctx, name, repo);
            }
            // The warning about the four-gigabyte native build would be a lie about a
            // thirty-second pull.
            ctx.status(imageRepository
                    ? "building qits/" + name + " from its Dockerfile"
                    : "cold GraalVM native build of qits/" + name + " — ~4 GB RAM, no maven cache");
            ProcessResult result = boot.docker.buildFromStdin("qits/" + name + ":latest",
                    dockerfile, repo, List.of(), contexts, ctx::log);
            Boot.must(result, "build of qits/" + name + " failed");
        });
    }

    // --- the seed clients and the @qits packages they pin ----------------------------------------

    /**
     * <b>One {@code @qits} package this boot builds, and where its source is</b>: the release of
     * {@code producer} at {@code ref} — its tag, or its {@code release(<version>):} commit where the
     * tag is gone ({@link eu.wohlben.qits.cli.bootstrap.platform.Git#releaseRef}).
     *
     * @param needs    the {@code @qits} packages its own lock installs; none for golden masters
     * @param npmrc    a jslib's scratch {@code .npmrc}; null for golden masters
     * @param manifest a golden-masters package's {@code package.json}; null for a jslib
     */
    record NpmSource(NpmSeed.Pkg pkg, String producer, String ref, Set<NpmSeed.Pkg> needs,
                     String npmrc, String manifest) {

        NpmSeed.Build build() {
            return new NpmSeed.Build(pkg, needs, npmrc, manifest);
        }

        /** What the source archive holds: the whole tree, or the provider's golden masters. */
        String directory() {
            return NpmSeed.kind(pkg.name()) == NpmSeed.Kind.JSLIB ? "." : "golden-masters";
        }
    }

    /**
     * <b>EVERY {@code @qits} PACKAGE A SEED CLIENT PINS, built from its release into the
     * {@code qits-npm-seed} volume — before the first seed image with a client</b> (qits-889).
     * <p>
     * The pins are each seed client's committed {@code package-lock.json} at the webui gitlink its
     * service's checkout records — what {@code npm ci} installs — closed over each jslib's own
     * {@code pnpm-lock.yaml} at its release: {@code @qits/ui-components} builds against
     * {@code @qits/angular} and four providers' golden masters. Every package is built at the
     * release its VERSION names, because that is how the platform publishes them: a jslib's release
     * step builds the tag and refuses a manifest whose version is not {@code $QITS_VERSION}
     * (qits-ci {@code release-archetypes/npm-library.yml}), and a golden-masters package is the
     * provider's {@code golden-masters/} published under the provider's {@code $QITS_VERSION}
     * ({@code CiReleaseComposer.publishContractsAndDocs}).
     * <p>
     * <b>A pin this boot cannot build stops the phase, by name</b> — an unknown {@code @qits}
     * package, a version with neither a tag nor a release commit, a release without the tree. A
     * frontend built against anything else would not be the release's bundle, and the alternative
     * the seed used to take, a placeholder page, is what this replaces.
     * <p>
     * <b>One container, golden masters first, each jslib after what it installs</b>, and a package
     * the volume already holds is not built again: its source is an immutable release.
     */
    public Phase npmSeed() {
        return new Phase("seed-npm-packages", "build the @qits packages the seed clients pin",
                ctx -> {
                    Map<NpmSeed.Pkg, String> pinnedBy = new java.util.TreeMap<>();
                    for (String name : PlatformModel.seedClients()) {
                        Path repo = boot.state.repoDir(name);
                        ctx.status("reading the client lock of " + PlatformModel.repo(name));
                        Boot.must(boot.git.submodulesShallow(repo, ctx::log),
                                "submodule checkout of " + PlatformModel.repo(name) + " failed");
                        for (NpmSeed.Pkg pin : clientPins(name, repo)) {
                            pinnedBy.merge(pin, PlatformModel.repo(name), (a, b) -> a + ", " + b);
                        }
                    }
                    Map<NpmSeed.Pkg, NpmSource> sources = npmClosure(pinnedBy);
                    Map<NpmSeed.Pkg, Set<NpmSeed.Pkg>> needs = new LinkedHashMap<>();
                    sources.forEach((pkg, source) -> needs.put(pkg, source.needs()));
                    List<NpmSeed.Pkg> order = NpmSeed.buildOrder(needs);
                    boot.docker.ensureVolume(NpmSeed.VOLUME, ctx::log);
                    List<String> held = npmHolds(order.stream().map(NpmSeed.Pkg::file).toList(),
                            false);
                    List<NpmSource> wanted = new ArrayList<>();
                    for (NpmSeed.Pkg pkg : order) {
                        NpmSource source = sources.get(pkg);
                        boolean there = held.contains(pkg.file());
                        ctx.log("  " + pkg.id() + " (" + PlatformModel.repo(source.producer()) + " "
                                + source.ref() + ") — " + NpmSeed.VOLUME + ": "
                                + (there ? "held" : "to build"));
                        if (!there) {
                            wanted.add(source);
                        }
                    }
                    if (wanted.isEmpty()) {
                        ctx.skip(NpmSeed.VOLUME + " holds all " + order.size());
                    }
                    String cid = create(ctx, List.of("docker", "create", "--user", "root",
                            "--entrypoint", "sh", "-v", NpmSeed.VOLUME + ":" + NpmSeed.MOUNT,
                            NpmSeed.NODE_IMAGE,
                            "-c", NpmSeed.packagesScript(wanted.stream().map(NpmSource::build)
                                    .toList())));
                    Path exports = Files.createDirectories(boot.state.srcDir.resolve(".exports"));
                    for (NpmSource source : wanted) {
                        Path tar = exports.resolve(source.pkg().file() + ".tar");
                        try {
                            Boot.must(boot.git.archive(boot.state.repoDir(source.producer()),
                                            source.ref(), source.directory(), tar, ctx::log),
                                    "archiving " + PlatformModel.repo(source.producer()) + " "
                                            + source.ref() + " failed");
                            Boot.must(boot.docker.exec(Duration.ofMinutes(10), ctx::log, "cp",
                                            tar.toString(), cid + ":" + NpmSeed.archive(source.pkg())),
                                    "copying " + source.pkg().id() + " in failed");
                        } finally {
                            Files.deleteIfExists(tar);
                        }
                    }
                    ctx.status("building " + wanted.size() + " @qits package(s)");
                    startAndReap(ctx, cid, "building the @qits packages failed");
                    ctx.note(wanted.size() + " of " + order.size() + " built");
                });
    }

    /**
     * <b>The pins closed over the jslibs' own locks, each with its source found</b> — or the phase
     * stops naming the first pin that cannot be built and who pins it.
     */
    Map<NpmSeed.Pkg, NpmSource> npmClosure(Map<NpmSeed.Pkg, String> roots) {
        Map<NpmSeed.Pkg, String> pinnedBy = new LinkedHashMap<>(roots);
        Map<NpmSeed.Pkg, NpmSource> found = new LinkedHashMap<>();
        java.util.Deque<NpmSeed.Pkg> pending = new java.util.ArrayDeque<>(roots.keySet());
        while (!pending.isEmpty()) {
            NpmSeed.Pkg pkg = pending.removeFirst();
            if (found.containsKey(pkg)) {
                continue;
            }
            String why = pkg.id() + " (pinned by " + pinnedBy.get(pkg) + ")";
            String producer = NpmSeed.producer(pkg.name());
            if (producer == null) {
                throw new IllegalStateException(why + " cannot be built: it is neither a jslib a "
                        + "release publishes (" + String.join(", ", NpmSeed.jslibs())
                        + ") nor the golden masters of a repository this boot clones");
            }
            Path repo = boot.state.repoDir(producer);
            String ref = boot.git.releaseRef(repo, pkg.version());
            if (ref == null) {
                throw new IllegalStateException(why + " cannot be built: "
                        + PlatformModel.repo(producer) + " has neither the tag " + pkg.version()
                        + " nor a release(" + pkg.version() + "): commit in " + repo + ", and a "
                        + "seed client is built from its release or not at all");
            }
            if (NpmSeed.kind(pkg.name()) == NpmSeed.Kind.JSLIB) {
                String lock = boot.git.fileAt(repo, ref, "pnpm-lock.yaml", 200_000);
                if (lock == null) {
                    throw new IllegalStateException(why + " cannot be built: "
                            + PlatformModel.repo(producer) + " at " + ref + " has no readable "
                            + "pnpm-lock.yaml, and its build installs with --frozen-lockfile");
                }
                Set<NpmSeed.Pkg> needs = NpmSeed.pnpmLockPins(lock);
                String npmrc = NpmSeed.refuseServers(NpmSeed.scratchNpmrc(
                        boot.git.fileAt(repo, ref, ".npmrc")), PlatformModel.repo(producer) + " "
                        + ref);
                found.put(pkg, new NpmSource(pkg, producer, ref, needs, npmrc, null));
                for (NpmSeed.Pkg need : needs) {
                    pinnedBy.putIfAbsent(need, pkg.id());
                    pending.addLast(need);
                }
            } else {
                if (boot.git.namesAt(repo, ref, "golden-masters").isEmpty()) {
                    throw new IllegalStateException(why + " cannot be built: "
                            + PlatformModel.repo(producer) + " at " + ref + " has no "
                            + "golden-masters/ to pack");
                }
                String application = contractApplication(boot.git.fileAt(repo, ref, RELEASE_YML),
                        PlatformModel.application(producer));
                found.put(pkg, new NpmSource(pkg, producer, ref, Set.of(), null,
                        NpmSeed.goldenMastersManifest(pkg, application)));
            }
        }
        return found;
    }

    /**
     * The {@code @qits} packages one seed client's committed lock installs — read at the webui's
     * HEAD, the gitlink {@code submodulesShallow} stood it at. A client with no lock cannot be
     * built the way its Dockerfile asks ({@code npm ci}), and stops the phase.
     */
    Set<NpmSeed.Pkg> clientPins(String name, Path repo) {
        Path webui = repo.resolve(PlatformModel.seedWebuiPath(name));
        String origin = PlatformModel.repo(name) + "/" + PlatformModel.seedWebuiPath(name)
                + "/package-lock.json";
        String lock = boot.git.fileAt(webui, "HEAD", "package-lock.json", 200_000);
        if (lock == null) {
            throw new IllegalStateException(origin + " cannot be read at the webui gitlink, so "
                    + "qits/" + name + "'s client cannot be built — `npm ci` needs it");
        }
        return NpmSeed.packageLockPins(lock, origin);
    }

    /**
     * <b>Build one seed client into the place its Dockerfile reads</b>: the webui's committed tree
     * ({@code git archive HEAD}, so nothing of a previous run rides along) in a throwaway container,
     * against the volume's tarballs, the bundle copied out to {@link PlatformModel#seedUiPath}.
     * The checkout is never written but for that path, which is untracked: package.json, the lock
     * and the {@code .npmrc} are retargeted in the container's copy only.
     */
    void seedClient(PhaseContext ctx, String name, Path repo) throws IOException {
        String webuiPath = PlatformModel.seedWebuiPath(name);
        Path webui = repo.resolve(webuiPath);
        String origin = PlatformModel.repo(name) + "/" + webuiPath;
        Set<NpmSeed.Pkg> pins = clientPins(name, repo);
        List<String> held = npmHolds(pins.stream().map(NpmSeed.Pkg::file).toList(), true);
        List<String> missing = pins.stream().filter(pin -> !held.contains(pin.file()))
                .map(NpmSeed.Pkg::id).sorted().toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("qits/" + name + "'s client cannot be built: its lock "
                    + "pins " + String.join(", ", missing) + ", which the " + NpmSeed.VOLUME
                    + " volume does not hold. seed-npm-packages builds every @qits package a "
                    + "seed client pins, so it did not run, or read another lock than " + origin);
        }
        String sha = boot.git.head(webui);
        String npmrc = NpmSeed.refuseServers(NpmSeed.scratchNpmrc(
                boot.git.fileAt(webui, "HEAD", ".npmrc")), origin);
        String bundle = PlatformModel.seedBundlePath(name);
        ctx.status("building the client of qits/" + name + " at " + shortSha(sha));
        String cid = create(ctx, List.of("docker", "create", "--user", "root",
                "--entrypoint", "sh", "-v", NpmSeed.VOLUME + ":" + NpmSeed.MOUNT,
                NpmSeed.NODE_IMAGE, "-c", NpmSeed.frontendScript(
                        PlatformModel.repo(name) + "@" + sha, bundle, pins, npmrc)));
        try {
            Path exports = Files.createDirectories(boot.state.srcDir.resolve(".exports"));
            Path tar = exports.resolve(PlatformModel.repo(name) + "-webui.tar");
            try {
                Boot.must(boot.git.archive(webui, "HEAD", ".", tar, ctx::log),
                        "archiving " + origin + " failed");
                Boot.must(boot.docker.exec(Duration.ofMinutes(10), ctx::log, "cp",
                                tar.toString(), cid + ":" + NpmSeed.FRONTEND_ARCHIVE),
                        "copying " + origin + " in failed");
            } finally {
                Files.deleteIfExists(tar);
            }
            ProcessResult built = boot.docker.exec(Docker.BUILD_TIMEOUT, ctx::log, "start", "-a",
                    cid);
            if (!built.ok()) {
                throw new IllegalStateException("building the client of qits/" + name + " ("
                        + origin + " at " + shortSha(sha) + ") failed\n" + built.tailText(20));
            }
            Path ui = repo.resolve(PlatformModel.seedUiPath(name));
            deleteTree(ui);
            Files.createDirectories(ui);
            Boot.must(boot.docker.exec(Duration.ofMinutes(10), ctx::log, "cp",
                            cid + ":" + NpmSeed.FRONTEND_OUT + "/.", ui.toString()),
                    "copying the client of qits/" + name + " out failed");
        } finally {
            boot.docker.removeContainer(cid, null);
        }
        ctx.log("  client " + origin + " @ " + shortSha(sha) + " -> " + PlatformModel.seedUiPath(name));
    }

    /**
     * Which tarballs the npm volume holds. {@code strict}: a probe that could not run stops the
     * phase rather than answering "none" — a client preflight that read a docker failure as every
     * package missing would name the wrong problem; the package phase only rebuilds.
     */
    private List<String> npmHolds(List<String> files, boolean strict) {
        if (files.isEmpty()) {
            return List.of();
        }
        ProcessResult result = boot.docker.run(Cmd.of(NpmSeed.probeCommand(files))
                .captureLimit(files.size() + 100), null);
        if (!result.ok()) {
            if (strict) {
                throw new IllegalStateException("asking the " + NpmSeed.VOLUME + " volume for "
                        + files.size() + " packages failed\n" + result.tailText(20));
            }
            return List.of();
        }
        return result.captured().stream().map(String::strip).filter(line -> !line.isBlank())
                .toList();
    }

    /** A directory and everything under it, gone; nothing when it is not there. */
    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /**
     * <b>Every qits coordinate a seed image's poms pin is in the file repository, or the phase
     * stops with the whole list.</b> {@link MavenPins} reads the checkout's working tree — the tree
     * the build copies — every scope, versions resolved; ONE throwaway container asks the volume for
     * all of them. A version the reader cannot resolve is warned about rather than refused: it is
     * the build's to resolve, and a guess here would be a false stop.
     */
    void mavenPreflight(PhaseContext ctx, String name, Path repo) throws IOException {
        MavenPins.Pins pins = MavenPins.read(workingPoms(repo));
        for (String unresolved : pins.unresolved()) {
            ctx.warn("qits-" + name + " pins " + unresolved + " at a version this boot cannot "
                    + "resolve — the build will ask the file repository for it as Maven reads it");
        }
        if (pins.dependencies().isEmpty()) {
            ctx.log("  no qits coordinate pinned");
            return;
        }
        List<String> paths = pins.dependencies().stream()
                .map(MavenPins.Coordinate::repositoryPath).distinct().toList();
        List<String> held = fileRegistryProbe(paths);
        String refusal = preflightRefusal(name, pins.dependencies(), held);
        if (refusal != null) {
            throw new IllegalStateException(refusal);
        }
        ctx.log("  all " + paths.size() + " qits coordinates qits-" + name + " pins are in "
                + SEED_REPO_VOLUME);
    }

    /**
     * Why a seed image cannot be built out of the file repository — every pinned coordinate it
     * lacks, with the pom and scope that pin it — or null when it holds them all.
     */
    static String preflightRefusal(String name, List<MavenPins.Coordinate> pinned,
                                   List<String> held) {
        List<String> missing = new ArrayList<>();
        for (MavenPins.Coordinate coordinate : pinned) {
            if (!held.contains(coordinate.repositoryPath())) {
                missing.add("  " + coordinate.gav()
                        + ("jar".equals(coordinate.type()) ? "" : ":" + coordinate.type())
                        + (coordinate.classifier().isEmpty() ? "" : ":" + coordinate.classifier())
                        + " (" + coordinate.scope() + ", " + coordinate.pom() + ")");
            }
        }
        if (missing.isEmpty()) {
            return null;
        }
        return "qits/" + name + " cannot be built: " + missing.size() + " of the qits "
                + "coordinates its poms pin are not in the " + SEED_REPO_VOLUME + " file "
                + "repository, and a seed build resolves them from nothing else. No phase before "
                + "this one produced them — a pom pins a coordinate the seed does not know how to "
                + "make yet:\n" + String.join("\n", missing);
    }

    /**
     * Which of these paths the file repository holds — {@link #fileRegistryHolds}, except that a
     * probe that could not run stops the phase instead of answering "none": a preflight that read
     * a docker failure as every jar missing would name the wrong problem.
     */
    private List<String> fileRegistryProbe(List<String> storePaths) {
        ProcessResult result = boot.docker.run(Cmd.of(fileRegistryProbeCommand(storePaths))
                .captureLimit(storePaths.size() + 100), null);
        if (!result.ok()) {
            throw new IllegalStateException("asking the " + SEED_REPO_VOLUME + " volume for "
                    + storePaths.size() + " coordinates failed\n" + result.tailText(20));
        }
        return result.captured().stream().map(String::strip).filter(line -> !line.isBlank())
                .toList();
    }

    /** ONE container for every path: a {@code test -f} each, the held ones echoed. */
    static List<String> fileRegistryProbeCommand(List<String> storePaths) {
        StringBuilder script = new StringBuilder();
        for (String path : storePaths) {
            script.append("[ -f '/repo/").append(path).append("' ] && echo '")
                    .append(path).append("'\n");
        }
        script.append("exit 0\n");
        return List.of("docker", "run", "--rm", "-v", SEED_REPO_VOLUME + ":/repo:ro",
                "--entrypoint", "sh", "maven:3.9-eclipse-temurin-25", "-c", script.toString());
    }

    /**
     * The step images a cold boot builds, each {@code qits/build-images/<name>:latest}.
     * <p>
     * <b>ci-slot is the one a run really uses since the CI step (qits-1547).</b> Every step of a
     * run executes in one {@code qits/build-images/ci-slot} container its CI lease starts, and
     * qits-ci pins {@code ci-slot:latest} by digest at accept — so a store without it is a first
     * release replay that never runs. The others are still built for the recipes that name them.
     */
    static final List<String> STEP_IMAGES = List.of("ci-base", "maven-base", "userflows-base",
            "node-base", "node-docker-base", "ci-slot");

    /**
     * The stage a step image is exported from, or null for its Dockerfile's last. ci-slot's last
     * stage is {@code check}, the release's tool check, which nothing exports; the image is the
     * {@code ci-slot} stage, as its own release builds it.
     */
    static String stepImageTarget(String name) {
        return "ci-slot".equals(name) ? "ci-slot" : null;
    }

    /**
     * The step images pipeline configs name. qits-oci is their single source of truth, but the
     * first ci-base cannot be built by the pipeline that needs ci-base to run.
     */
    public Phase stepImage(String name) {
        return new Phase("step-image-" + name, "build qits/build-images/" + name + ":latest", ctx -> {
            Path oci = boot.state.repoDir("oci");
            // The Dockerfile is a file IN the context here, so it is named rather than rewritten in
            // memory: no mirror prefix of a committed FROM has to change for a step image.
            ProcessResult result = boot.docker.buildWithFile(
                    "qits/build-images/" + name + ":latest",
                    oci.resolve(name).resolve("Dockerfile"), oci, List.of(),
                    stepImageTarget(name), ctx::log);
            Boot.must(result, "build of qits/build-images/" + name + " failed");
            // qits-ci resolves an unqualified qits/* step image against the registry host, so a run
            // pulls registry.<env>.localhost/qits/build-images/<name>. The seed loads it into the
            // host daemon under the bare tag and the fresh registry has not got it, so tag it under
            // the registry host too: docker run then finds it locally, before any pull. This is the
            // seed's pull+retag — and the tag the images-publish phase pushes, because a run is
            // refused until the STORE answers for the image too.
            Boot.must(boot.docker.exec(ctx::log, "tag",
                    "qits/build-images/" + name + ":latest",
                    boot.config.registryVhost() + "/qits/build-images/" + name + ":latest"),
                    "tagging qits/build-images/" + name + " under the registry host failed");
        });
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

    // --- what the seed builds resolve -------------------------------------------------------------

    /**
     * The maven arguments that build ONE module of a repository, or none when the whole repository
     * is deployed.
     */
    static String mavenModuleArgs(String repoName) {
        String module = PlatformModel.mavenModule(repoName);
        return module.isEmpty() ? "" : " -pl " + module + " -am";
    }

    /**
     * <b>Where one version of one library's source sits inside a maven container.</b> The checkout
     * itself is {@code /src-<library>}; a version somebody still pins is exported beside it under
     * its own name, so one container holds every tree it has to build and the script can name them
     * without knowing how they got there.
     */
    static String seedTree(String library, String version) {
        return version == null ? "/src-" + library : "/src-" + library + "@" + version;
    }

    /**
     * Every tree of one library, in build order: the pinned versions oldest first, the checkout
     * last. Oldest first because {@code mvn deploy} installs locally on its way out — a pinned
     * version that another pinned version of the same library depends on has to be in the local
     * repository already — and the checkout last because it is what everything else prefers.
     */
    static List<String> seedTrees(String library, List<String> pinned) {
        List<String> trees = new ArrayList<>();
        for (String version : pinned) {
            trees.add(seedTree(library, version));
        }
        trees.add(seedTree(library, null));
        return trees;
    }

    /**
     * One container, every seed library at every version anything still pins, into the file
     * repository at {@code /repo}.
     *
     * @param pinned the extra versions per library — {@link PinnedVersions#extraVersions}
     */
    static String seedScript(Map<String, List<String>> pinned) {
        StringBuilder script = new StringBuilder("set -eu\n").append(MAVEN_PURGE_QITS);
        for (String library : SEED_LIBRARIES) {
            for (String tree : seedTrees(library, pinned.getOrDefault(library, List.of()))) {
                script.append("cd ").append(tree)
                        .append(" && mvn -B -ntp deploy -DskipTests")
                        .append(seedModuleArgs(library))
                        .append(MAVEN_REPO_LOCAL)
                        .append(SEED_REPOSITORY_URL)
                        .append(" -DaltDeploymentRepository=seed::default::file:///repo\n");
            }
        }
        return script.toString();
    }

    /**
     * <b>Where a {@code maven-seed} build resolves the platform's jars that are not in its local
     * repository yet: the file repository it deploys into, never the live registry.</b> Every
     * library's root pom declares the {@code qits-maven} repository as
     * {@code ${qits.maven.repository.url}}, defaulting to {@code https://registry.qits.wohlben.eu/
     * …}; a user property wins over it. What the libraries resolve that they did not build
     * themselves is the golden masters their contracts kits compile against, which
     * {@code seed-golden-masters} put there first.
     */
    static final String SEED_REPOSITORY_URL = " -Dqits.maven.repository.url=file:///repo";

    /**
     * {@link #mavenModuleArgs} plus the {@link PlatformModel#seedOptionalModules} of the library,
     * each added only where the tree being built has its {@code pom.xml} — decided in the
     * container, per tree, because the pinned versions are different trees.
     */
    static String seedModuleArgs(String library) {
        String module = PlatformModel.mavenModule(library);
        List<String> optional = PlatformModel.seedOptionalModules(library);
        if (module.isEmpty() || optional.isEmpty()) {
            return mavenModuleArgs(library);
        }
        StringBuilder args = new StringBuilder(" -pl ").append(module);
        for (String extra : optional) {
            args.append("$([ -f ").append(extra).append("/pom.xml ] && echo ,").append(extra)
                    .append(" || true)");
        }
        return args.append(" -am").toString();
    }

    /**
     * Where one version of the ONE repository a publish container holds sits. The checkout keeps
     * the {@code /src} it has always had; a pinned version is exported beside it.
     */
    static String publishTree(String version) {
        return version == null ? "/src" : "/src@" + version;
    }

    // --- the two wire contracts qits-ci compiles against -----------------------------------------

    /** The Maven group every qits library is published under, as a path. */
    static final String QITS_GROUP_PATH = "eu/wohlben/qits";

    /**
     * <b>The version of one protocol jar the ci checkout pins</b> — the value of a root-pom
     * property such as {@code qits.ci-daemon-protocol.version}.
     * <p>
     * It stops the boot rather than answering null, and says which of the three things is wrong:
     * the property is gone, it names a snapshot, or it is a reference to something else. A cold
     * boot can only publish what a release tag holds, so anything that is not a plain released
     * version is a seed-image-ci that fails ten minutes later on a coordinate nobody can name.
     */
    static String protocolPin(String ciPom, String property) {
        return protocolPin(ciPom, property, "qits-ci");
    }

    /** The same read of another service's root pom, whose refusals name that service. */
    static String protocolPin(String pom, String property, String owner) {
        java.util.regex.Matcher pin = java.util.regex.Pattern.compile(
                "<" + java.util.regex.Pattern.quote(property) + ">\\s*([^<]*?)\\s*</"
                        + java.util.regex.Pattern.quote(property) + ">").matcher(pom);
        if (!pin.find() || pin.group(1).isBlank()) {
            throw new IllegalStateException(owner + "'s root pom carries no <" + property
                    + "> — this boot does not know which protocol jar its image builds against");
        }
        String version = pin.group(1);
        if (version.endsWith("-SNAPSHOT") || version.contains("$")) {
            throw new IllegalStateException(owner + " pins <" + property + "> to " + version
                    + ", which is not a released version. A cold boot publishes the jar from its "
                    + "release tag and nothing else — release the protocol, bump the pin and rerun");
        }
        return version;
    }

    /** Where one released jar sits in a Maven repository, relative to its root. */
    static String protocolStorePath(String artifactId, String version) {
        return QITS_GROUP_PATH + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version
                + ".jar";
    }

    /**
     * One protocol module per version, built from its release tag, into the file repository.
     * {@code mvn deploy} is what writes the checksums and the metadata a Maven client expects beside
     * a jar, and the store gets those same files from {@code store-seed}.
     */
    String protocolScript(String repoName, List<String> versions) {
        StringBuilder script = new StringBuilder("set -eu\n").append(seedSettings())
                .append(MAVEN_PURGE_QITS);
        for (String version : versions) {
            script.append("cd ").append(publishTree(version))
                    .append(" && mvn -B -ntp -s /root/.m2/settings.xml deploy -DskipTests")
                    .append(mavenModuleArgs(repoName)).append(MAVEN_REPO_LOCAL)
                    .append(SEED_DEPLOYMENT).append('\n');
        }
        return script.toString();
    }

    /**
     * <b>A wire contract a seed service compiles against, seeded at the version that service
     * PINS — before its seed image, which cannot be built without it.</b>
     * <p>
     * qits-ci's reactor dropped its own {@code ci-daemon-protocol} module for a dependency on the
     * released jar, and took {@code qits-ci-runner-protocol} the same way until the CI runner
     * retired (qits-1547): external coordinates, belonging to a daemon repository rather than to a
     * library. No phase
     * published either, so a cold boot died in the ci image build on
     * {@code qits-ci-daemon-protocol:jar (absent)}. The live platform never saw it — its store
     * holds every version ever released.
     * <p>
     * <b>The version is read out of the ci checkout, because that checkout is what is about to be
     * built.</b> It is the value of a root-pom property, and it is rarely the daemon's newest
     * release: the pin moves with a bump in qits-ci, days behind the daemon.
     * <p>
     * <b>Built from the release TAG and never from the daemon's working tree.</b> A released
     * coordinate is immutable, and the store refuses to overwrite one; the checkout stands on main,
     * whose pom says the daemon's NEWEST release and may carry commits past it, so the working
     * tree is neither the pinned version nor provably any released one. A tag the clone does not
     * hold stops the boot here, with the tag named, rather than in a native build that resolves
     * nothing.
     * <p>
     * <b>Into the FILE repository only (qits-889).</b> The seed image builds resolve from it and
     * from nothing else of this platform. The store needs the jar as well — qits-ci's release
     * build, later in this boot, resolves from there — and gets it from {@code store-seed}, which
     * uploads the file repository once the idp exists. A version the file repository already holds
     * is left alone. The build resolves Maven Central direct: no mirror runs in phase 1.
     * <p>
     * <b>qits-system is the second consumer since qits-1547</b>: it compiles against
     * {@code qits-system-runner-protocol} the same way, and its seed image is built after this
     * phase for the reason qits-ci's is.
     *
     * @param consumer    the model name of the service whose root pom pins it — {@code ci},
     *                    {@code system}
     * @param repoName    the model name of the daemon repository — {@code ci-daemon},
     *                    {@code system-runner-daemon}
     * @param artifactId  the jar, {@code qits-ci-daemon-protocol}
     * @param pomProperty the property in the consumer's root pom that pins it
     */
    public Phase protocolSeed(String consumer, String repoName, String artifactId,
                              String pomProperty) {
        return protocolSeed(List.of(consumer), repoName, artifactId, pomProperty);
    }

    /**
     * The same, for a contract more than one seed service pins — each at its own version, the
     * pins lagging independently. {@code qits-workspace-daemon-protocol} is the case: qits-projects
     * and qits-system both compile against it (qits-889). One phase, every version, before the
     * first of the consumers' images.
     */
    public Phase protocolSeed(List<String> consumers, String repoName, String artifactId,
                              String pomProperty) {
        String owners = consumers.stream().map(PlatformModel::application)
                .collect(Collectors.joining(" and "));
        return new Phase("seed-" + artifactId,
                "seed " + artifactId + " at the version " + owners + " pin"
                        + (consumers.size() == 1 ? "s" : ""), ctx -> {
            java.util.LinkedHashSet<String> pinned = new java.util.LinkedHashSet<>();
            for (String consumer : consumers) {
                pinned.add(protocolPin(Files.readString(
                        boot.state.repoDir(consumer).resolve(PinnedVersions.ROOT_POM),
                        StandardCharsets.UTF_8), pomProperty, PlatformModel.application(consumer)));
            }
            List<String> versions = List.copyOf(pinned);
            String repo = PlatformModel.repo(repoName);
            for (String version : versions) {
                if (boot.git.fileAt(boot.state.repoDir(repoName), version, PinnedVersions.ROOT_POM)
                        == null) {
                    throw new IllegalStateException(owners + " pins " + artifactId + " " + version
                            + " and the " + repo + " checkout has no such tag, so there is nothing "
                            + "to build the jar from. Fetch the tags of "
                            + boot.state.repoDir(repoName) + " (git fetch --tags) and rerun");
                }
            }
            boot.docker.ensureVolume(SEED_REPO_VOLUME, ctx::log);
            boot.docker.ensureVolume(MAVEN_CACHE_VOLUME, ctx::log);
            List<String> held = fileRegistryHolds(versions.stream()
                    .map(v -> protocolStorePath(artifactId, v)).toList());
            List<String> owed = new ArrayList<>();
            for (String version : versions) {
                String path = protocolStorePath(artifactId, version);
                boolean seeded = held.contains(path);
                ctx.log("  " + path + " — file registry: " + (seeded ? "held" : "to seed"));
                if (!seeded) {
                    owed.add(version);
                }
            }
            if (owed.isEmpty()) {
                ctx.skip(artifactId + " " + String.join(", ", versions)
                        + " is in the file registry");
            }
            String cid = create(ctx, List.of(
                    "docker", "create", "--user", "root",
                    "--entrypoint", "sh", "-v", SEED_REPO_VOLUME + ":/repo",
                    "-v", MAVEN_CACHE_MOUNT,
                    "maven:3.9-eclipse-temurin-25",
                    "-c", protocolScript(repoName, owed)));
            for (String version : owed) {
                copyTag(ctx, repoName, version, cid, publishTree(version));
            }
            startAndReap(ctx, cid, artifactId + " seed failed");
            ctx.note(String.join(", ", versions));
        });
    }

    /**
     * The bearer out of an {@code Authorization} header value, which is the form npm's
     * {@code _authToken} takes: npm puts {@code Bearer } in front of it itself, so the word must
     * not be in the file twice.
     */
    static String bearerOf(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return "";
        }
        String prefix = "Bearer ";
        return authorization.regionMatches(true, 0, prefix, 0, prefix.length())
                ? authorization.substring(prefix.length()) : authorization;
    }

    /**
     * <b>Where a phase-1 Maven build resolves from, as a shell fragment: the platform's own jars
     * out of the file repository at {@code /repo}, everything else from Maven Central direct.</b>
     * <p>
     * No store and no mirror runs before the idp (qits-889), so there is nothing on qits-net to
     * resolve through. The consumer poms declare the platform's repository as {@code qits-maven} at
     * {@code ${qits.maven.repository.url}}, defaulting to the LIVE registry; an exact-id
     * {@code <mirror>} is what turns that one repository into the volume, and
     * {@link #SEED_REPOSITORY_URL} on the command line says the same to a pom that reads the
     * property itself. {@code central} is left alone: it is Maven Central already.
     * <p>
     * No {@code <server>} either. Nothing in phase 1 deploys anywhere but {@code file:///repo},
     * which takes no credential.
     */
    static String seedSettings() {
        return "mkdir -p /root/.m2 && cat > /root/.m2/settings.xml <<'SETTINGS'\n"
                + "<settings>\n"
                + "  <mirrors>\n"
                + "    <mirror>\n"
                + "      <id>qits-maven</id>\n"
                + "      <mirrorOf>qits-maven</mirrorOf>\n"
                + "      <url>file:///repo</url>\n"
                + "    </mirror>\n"
                + "  </mirrors>\n"
                + "</settings>\n"
                + "SETTINGS\n";
    }

    /** The deployment target of every phase-1 build: the file repository, never a server. */
    static final String SEED_DEPLOYMENT = SEED_REPOSITORY_URL
            + " -DaltDeploymentRepository=seed::default::file:///repo";

    // --- the ci-daemon binary ---------------------------------------------------------------------

    /**
     * A fully static musl native binary, built inside the builder image: docker cp carries the
     * source in and the binary out. container-build is off because we are already in the container
     * it would otherwise launch.
     */
    /** Where qits-ci-daemon keeps the Dockerfile whose two tarballs this run has to seed. */
    static final String MUSL_BUILDER_DOCKERFILE = "docker/Dockerfile.musl-builder";

    /**
     * <b>The musl toolchain and the zlib source, from upstream into the file repository, before the
     * builder image asks for them.</b>
     * <p>
     * qits-ci-daemon's builder image stopped fetching those two tarballs from the internet on
     * 2026-09-05 and reads them out of the platform's own Maven store instead — which is the right
     * answer for a platform that has one, and a hole in a bootstrap: nothing in the estate uploads
     * them, so a fresh host's store has neither. This phase is what gives the seed's own builder
     * build its copy, and {@code store-seed} what makes the store's copy true on the first boot as
     * well as the tenth.
     * <p>
     * <b>Downloaded, checked against the consuming repository's own pin, and only then deployed.</b>
     * {@link MuslToolchain} reads the size and the sha256 out of the Dockerfile that consumes the
     * bytes, because that is where they belong; this phase refuses to deploy anything that does not
     * hash to them. Ninety megabytes from a community host is exactly the fetch a checksum is for.
     * <p>
     * <b>{@code deploy:deploy-file} into {@code file:///repo}</b>, so the volume gets the shape a hand
     * upload left on the live platform — the tarball, its {@code .sha1} and {@code .md5}, a
     * generated pom and the metadata — and {@code store-seed} carries all of it into the store. The
     * {@code ci-daemon} phase's builder build reads it there: its {@code ADD $MUSL_URL}/{@code ADD
     * $ZLIB_URL} are rewritten into copies out of that volume ({@link SeedDockerfile#localToolchain}),
     * so the build dials no server. Whatever the volume already holds is skipped, one file at a time.
     */
    public Phase toolchainSeed() {
        return new Phase("seed-toolchain",
                "seed the musl toolchain tarballs the ci-daemon builder image reads", ctx -> {
                    Path repo = boot.state.repoDir("ci-daemon");
                    List<MuslToolchain.Tarball> declared = MuslToolchain.read(
                            Files.readString(repo.resolve(MUSL_BUILDER_DOCKERFILE),
                                    StandardCharsets.UTF_8));
                    boot.docker.ensureVolume(SEED_REPO_VOLUME, ctx::log);
                    boot.docker.ensureVolume(MAVEN_CACHE_VOLUME, ctx::log);
                    List<String> inFileRegistry = fileRegistryHolds(declared.stream()
                            .map(MuslToolchain.Tarball::storePath).toList());
                    List<MuslToolchain.Tarball> wanted = new ArrayList<>();
                    for (MuslToolchain.Tarball tarball : declared) {
                        boolean seeded = inFileRegistry.contains(tarball.storePath());
                        ctx.log("  " + tarball.storePath() + " — file registry: "
                                + (seeded ? "held" : "to seed"));
                        if (!seeded) {
                            wanted.add(tarball);
                        }
                    }
                    if (wanted.isEmpty()) {
                        ctx.skip("the file registry holds both toolchain tarballs");
                    }
                    ctx.status("downloading and seeding " + wanted.size() + " tarball(s)");
                    String cid = create(ctx, List.of(
                            "docker", "create", "--user", "root",
                            "--entrypoint", "sh", "-v", SEED_REPO_VOLUME + ":/repo",
                            "-v", MAVEN_CACHE_MOUNT,
                            "maven:3.9-eclipse-temurin-25",
                            "-c", toolchainScript(wanted)));
                    startAndReap(ctx, cid, "seeding the musl toolchain failed");
                    ctx.note(wanted.size() + " tarball(s) seeded");
                });
    }

    /**
     * Which of these paths the file repository already holds. It is a volume rather than a server,
     * so the question is a {@code test -f} in ONE throwaway container for every path, on the image
     * the phase is about to use anyway. A probe that fails answers "none", which costs a rebuild.
     */
    private List<String> fileRegistryHolds(List<String> storePaths) {
        ProcessResult result = boot.docker.run(Cmd.of(fileRegistryProbeCommand(storePaths))
                .captureLimit(storePaths.size() + 100), null);
        return result.ok()
                ? result.captured().stream().map(String::strip).filter(line -> !line.isBlank())
                        .toList()
                : List.of();
    }

    /**
     * Download, verify, deploy — one block per tarball, in one container, into the file repository.
     * <p>
     * The deploy plugin is PINNED rather than resolved as {@code deploy:deploy-file} would: a
     * prefix goal asks the repository which version is newest. It comes from Maven Central direct,
     * with no settings file at all. {@code -DgeneratePom} is left at its default so the volume gets
     * the pom and the checksums a Maven client expects beside an artifact.
     */
    static String toolchainScript(List<MuslToolchain.Tarball> tarballs) {
        StringBuilder script = new StringBuilder("set -eu\n");
        for (MuslToolchain.Tarball tarball : tarballs) {
            script.append("echo \"downloading ").append(tarball.fileName()).append(" from ")
                    .append(tarball.upstream()).append("\"\n")
                    .append("curl -fsSL --retry 3 --retry-delay 5 -o /tmp/")
                    .append(tarball.fileName()).append(" '").append(tarball.upstream()).append("'\n")
                    // The pin is the consuming repository's, and it is checked before anything is
                    // deployed: what lands in the volume reaches the store, and a store is forever.
                    .append("echo \"").append(tarball.sha256()).append("  /tmp/")
                    .append(tarball.fileName()).append("\" | sha256sum -c -\n")
                    .append("mvn -B -ntp")
                    .append(" org.apache.maven.plugins:maven-deploy-plugin:3.1.2:deploy-file")
                    .append(" -Dfile=/tmp/").append(tarball.fileName())
                    .append(" -DgroupId=").append(tarball.groupId())
                    .append(" -DartifactId=").append(tarball.artifactId())
                    .append(" -Dversion=").append(tarball.version())
                    .append(" -Dpackaging=").append(tarball.extension())
                    .append(" -DrepositoryId=seed -Durl=file:///repo")
                    .append(MAVEN_REPO_LOCAL).append('\n');
        }
        return script.toString();
    }

    public Phase ciDaemon() {
        return new Phase("ci-daemon", "build the qits-ci-daemon binary (musl static native)", ctx -> {
            Path repo = boot.state.repoDir("ci-daemon");
            // THE TARBALLS ARE THE ONES seed-toolchain PUT IN THE qits-maven-seed VOLUME, never
            // the Dockerfile's ARG default. That default names registry.qits.${QITS_DOMAIN}, which
            // during a bootstrap is the live platform or nothing, so the rewrite turns each
            // `ADD $<NAME>_URL` into a COPY out of the volume, handed in as a named context.
            String committed = Files.readString(repo.resolve(MUSL_BUILDER_DOCKERFILE),
                    StandardCharsets.UTF_8);
            List<MuslToolchain.Tarball> tarballs = MuslToolchain.read(committed);
            for (MuslToolchain.Tarball tarball : tarballs) {
                ctx.log("  " + tarball.arg() + " -> " + SEED_REPO_VOLUME + ":/"
                        + tarball.storePath());
            }
            String dockerfile = SeedDockerfile.localToolchain(SeedDockerfile.rewrite(committed),
                    PlatformModel.repo("ci-daemon") + "/" + MUSL_BUILDER_DOCKERFILE, tarballs);
            ctx.status("building the musl builder image");
            Boot.must(boot.docker.buildFromStdin("qits/graalvmce-musl-builder:jdk-25", dockerfile,
                            repo.resolve("docker"), List.of(),
                            Map.of(SeedDockerfile.SEED_M2_CONTEXT, SEED_REPO_VOLUME), ctx::log),
                    "the musl builder image failed to build");

            // --entrypoint: the builder image entrypoints to native-image itself.
            //
            // The shared download cache, for the same reason every maven container has it — and this
            // build needs it most: it reads Maven Central direct, with nothing in front of it to be
            // warm. Its reactor resolves no qits jar it does not build itself.
            boot.docker.ensureVolume(MAVEN_CACHE_VOLUME, ctx::log);
            String cid = create(ctx, List.of(
                    "docker", "create", "--user", "root", "--entrypoint", "bash",
                    "--memory", "6g", "--cpu-quota", "400000", "--cpuset-cpus", "0-3",
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

    // --- the runner library, and the golden masters the seed's builds resolve ---------------------

    /** The property qits-system and qits-system-runner-daemon pin the runner library by. */
    static final String RUNNER_PIN_PROPERTY = "qits.runner.version";

    /**
     * <b>Every {@code qits.runner.version} the seed's two runner consumers pin</b> — qits-system's
     * checkout and qits-system-runner-daemon at the tag qits-system pins — each once, in that order.
     * The two lag independently, so either may name a version the other does not.
     */
    List<String> runnerVersions() throws IOException {
        java.util.LinkedHashSet<String> versions = new java.util.LinkedHashSet<>();
        versions.add(protocolPin(Files.readString(boot.state.repoDir("system")
                .resolve(PinnedVersions.ROOT_POM), StandardCharsets.UTF_8),
                RUNNER_PIN_PROPERTY, "qits-system"));
        String runnerPom = boot.git.fileAt(boot.state.repoDir("system-runner-daemon"),
                systemRunnerVersion(boot), PinnedVersions.ROOT_POM);
        if (runnerPom != null) {
            versions.add(protocolPin(runnerPom, RUNNER_PIN_PROPERTY, "qits-system-runner-daemon"));
        }
        return List.copyOf(versions);
    }

    // --- the contract jars: golden masters and pacts (qits-1547, qits-889) -----------------------

    /**
     * <b>Every Maven build of phase 1 whose contract pins the seed owes, as pom texts.</b> A pin is
     * a {@code qits.<key>.version} property — {@link MavenPins#contractPins} reads them — and a
     * {@code -DskipTests} build resolves test scope all the same, so every one of these is a jar
     * some build below asks the file repository for:
     * <ul>
     *   <li>every pom of every seed SERVICE image's checkout — {@link #mavenSeedImages}; those are
     *       also read as reactors ({@link #seedReactors}), so a pin spelled any other way is still
     *       found by its coordinate;
     *   <li>every {@link #SEED_LIBRARIES} root pom at every version {@code maven-seed} builds: the
     *       contracts kits ({@code qits-eventstream-contracts}, {@code
     *       qits-containers-client-contracts}) COMPILE against their provider's golden masters, and
     *       a consumer resolving the kit resolves those transitively;
     *   <li>qits-system-runner-daemon at qits-system's pin and qits-runner-javalib at every version
     *       {@link #runnerVersions} names, as before qits-889.
     * </ul>
     */
    List<String> contractPoms(PhaseContext ctx) throws IOException {
        List<String> poms = new ArrayList<>();
        for (String image : mavenSeedImages()) {
            poms.addAll(workingPoms(boot.state.repoDir(image)).values());
        }
        PinnedVersions pinned = boot.pinnedVersions(ctx);
        for (String library : SEED_LIBRARIES) {
            Path pom = boot.state.repoDir(library).resolve(PinnedVersions.ROOT_POM);
            poms.add(Files.exists(pom) ? Files.readString(pom, StandardCharsets.UTF_8) : null);
            for (String version : pinned.extraVersions(library)) {
                poms.add(boot.git.fileAt(boot.state.repoDir(library), version,
                        PinnedVersions.ROOT_POM));
            }
        }
        poms.add(boot.git.fileAt(boot.state.repoDir("system-runner-daemon"),
                systemRunnerVersion(boot), PinnedVersions.ROOT_POM));
        for (String version : runnerVersions()) {
            poms.add(boot.git.fileAt(boot.state.repoDir("runner"), version,
                    PinnedVersions.ROOT_POM));
        }
        // THE WORKSPACE-SIDE NATIVE BUILDS (qits-889): the qits binary, the workspace daemon and
        // the projects daemon, each at the tags workspaceImages names. Their suites' pacts and
        // golden masters are pinned in their root poms like everyone else's.
        WorkspaceImages workspace = workspaceImages();
        for (String version : workspace.cli()) {
            poms.add(boot.git.fileAt(boot.state.repoDir("platform-access-cli"), version,
                    PinnedVersions.ROOT_POM));
        }
        for (String version : workspace.workspaces().keySet()) {
            poms.add(boot.git.fileAt(boot.state.repoDir("workspace-daemon"), version,
                    PinnedVersions.ROOT_POM));
        }
        for (String version : workspace.agents().keySet()) {
            poms.add(boot.git.fileAt(boot.state.repoDir("projects-daemon"), version,
                    PinnedVersions.ROOT_POM));
        }
        return poms;
    }

    /** Each seed service image's checkout, read as a reactor. */
    List<MavenPins.Pins> seedReactors() throws IOException {
        List<MavenPins.Pins> reactors = new ArrayList<>();
        for (String image : mavenSeedImages()) {
            reactors.add(MavenPins.read(workingPoms(boot.state.repoDir(image))));
        }
        return reactors;
    }

    /**
     * The seed images whose build is a Maven build: every {@link PlatformModel#CORE} entry but an
     * image repository, which is its Dockerfile and nothing else.
     */
    static List<String> mavenSeedImages() {
        return PlatformModel.CORE.stream()
                .filter(name -> !"Dockerfile".equals(PlatformModel.dockerfilePath(name)))
                .toList();
    }

    /**
     * <b>Every pom of a checkout's working tree, by its path from the root</b> — the tree a seed
     * image is built from. Build output, dependencies and git's own directory are passed by, and so
     * is anything under a {@code src/} directory: a pom there is a test fixture, not a module.
     */
    static Map<String, String> workingPoms(Path repo) throws IOException {
        Map<String, String> poms = new java.util.TreeMap<>((a, b) ->
                a.equals(MavenPins.ROOT_POM) ? (b.equals(a) ? 0 : -1)
                        : b.equals(MavenPins.ROOT_POM) ? 1 : a.compareTo(b));
        if (!Files.isDirectory(repo)) {
            return poms;
        }
        try (Stream<Path> walk = Files.walk(repo)) {
            for (Path pom : (Iterable<Path>) walk.filter(p -> p.getFileName().toString()
                    .equals("pom.xml"))::iterator) {
                Path relative = repo.relativize(pom);
                boolean skipped = false;
                for (Path segment : relative) {
                    String part = segment.toString();
                    if (part.equals("target") || part.equals("node_modules")
                            || part.equals(".git") || part.equals("src")) {
                        skipped = true;
                        break;
                    }
                }
                if (!skipped && Files.isRegularFile(pom)) {
                    poms.put(relative.toString().replace(java.io.File.separatorChar, '/'),
                            Files.readString(pom, StandardCharsets.UTF_8));
                }
            }
        }
        return poms;
    }

    /** {@code qits-<app>-golden-masters}: the coordinate a provider's release publishes. */
    static String goldenMastersArtifact(String app) {
        return "qits-" + app + "-golden-masters";
    }

    /**
     * <b>One contract jar this boot packs, and where its bytes come from.</b>
     *
     * @param artifactId  {@code qits-<app>-golden-masters} or {@code <consumer>-pacts-<provider>}
     * @param version     the pinned version — the release version of the repository that
     *                    publishes it
     * @param repository  the model name of that repository
     * @param ref         what that version is in the checkout: its tag, or its release commit —
     *                    {@link eu.wohlben.qits.cli.bootstrap.platform.Git#releaseRef}
     * @param provider    the provider repository a pact is with; null for golden masters
     * @param description what the platform's own packager writes into the pom
     */
    record ContractJar(String artifactId, String version, String repository, String ref,
                       String provider, String description) {

        boolean pacts() {
            return provider != null;
        }

        /** The one directory of the source the jar is packed from. */
        String directory() {
            return pacts() ? "pacts" : "golden-masters";
        }

        /** Where the archive is unpacked in the container. */
        String tree() {
            return "/contracts/" + artifactId + "@" + version;
        }

        /** Where the archive is copied into the container. */
        String archive() {
            return "/tmp/" + artifactId + "@" + version + ".tar";
        }

        String repositoryPath() {
            return protocolStorePath(artifactId, version);
        }
    }

    /**
     * <b>Every contract jar of one kind the seed's builds pin, with its source found.</b> A pin
     * whose source this boot cannot read — a repository it clones no checkout of, a version that is
     * neither a tag nor a release commit there, a release without the directory — is said once and
     * passed by: the pin closure over-approximates (a root-pom property a module never reads), and
     * the seed image preflight is what stops a boot over a coordinate a build really resolves.
     * <p>
     * <b>THE VERSION IS THE PUBLISHING REPOSITORY'S RELEASE.</b> A contract package is published
     * from that repository's release step as {@code qits artifacts publish contract … --version
     * "$QITS_VERSION"} (qits-ci-service {@code CiReleaseComposer.publishContractsAndDocs}), and
     * {@code $QITS_VERSION} is the release tag the step fetches ({@code git fetch … refs/tags/
     * $QITS_VERSION}, same class). Golden masters are the provider's, pacts the consumer's: the
     * coordinate {@code <consumer repo>-pacts-<provider repo>} names the consumer first
     * ({@code CiContracts.coordinate}). Every contract is {@code if-changed}, so the pin is the
     * newest release whose package CHANGED — still a release of that repository.
     */
    List<ContractJar> contractJars(PhaseContext ctx, boolean pacts) throws IOException {
        Map<String, java.util.Set<String>> pins =
                MavenPins.contractPins(contractPoms(ctx), seedReactors());
        List<ContractJar> jars = new ArrayList<>();
        pins.forEach((artifactId, versions) -> {
            String[] parties = MavenPins.pactParties(artifactId);
            String app = MavenPins.goldenMastersApp(artifactId);
            if (pacts ? parties == null : app == null) {
                return;
            }
            String source = pacts ? PlatformModel.sourceNameOf(parties[0])
                    : PlatformModel.sourceRepos().contains(app) ? app : null;
            if (source == null) {
                ctx.warn(artifactId + " " + versions + " — "
                        + (pacts ? parties[0] : PlatformModel.repo(app))
                        + " is no repository this boot clones, so it is not packed");
                return;
            }
            Path repo = boot.state.repoDir(source);
            for (String version : versions) {
                String ref = boot.git.releaseRef(repo, version);
                if (ref == null) {
                    ctx.warn(artifactId + " " + version + " — " + PlatformModel.repo(source)
                            + " has neither the tag nor a release(" + version + ") commit, so it"
                            + " is not packed");
                    continue;
                }
                List<String> files = boot.git.namesAt(repo, ref, pacts ? "pacts" : "golden-masters");
                if (pacts ? files.stream().noneMatch(f -> pactFor(f, parties[1])) : files.isEmpty()) {
                    ctx.warn(artifactId + " " + version + " — " + PlatformModel.repo(source)
                            + " at " + ref + " has no " + (pacts ? "pacts/*_" + parties[1]
                            + ".json" : "golden-masters/") + ", so it is not packed");
                    continue;
                }
                String description = pacts
                        ? contractApplication(boot.git.fileAt(repo, ref, RELEASE_YML), parties[0])
                                + " pacts with " + parties[1] + " (contracts)"
                        : PlatformModel.application(app) + " golden masters (contracts)";
                jars.add(new ContractJar(artifactId, version, source, ref,
                        pacts ? parties[1] : null, description));
            }
        });
        return jars;
    }

    /** The release declaration a contract's application is named in. */
    static final String RELEASE_YML = ".config/qits/release.yml";

    /**
     * Whether a {@code pacts/} entry is one provider's — the platform packager's rule
     * ({@code ContractPackager.pacts}): a top-level {@code <consumer>_<provider>.json}, with
     * something before the underscore.
     */
    static boolean pactFor(String file, String provider) {
        String name = file.substring(file.lastIndexOf('/') + 1);
        String suffix = "_" + provider + ".json";
        return name.endsWith(suffix) && name.length() > suffix.length();
    }

    /**
     * {@code contracts.application} of a release declaration — what the platform names a pact
     * package's description after — or the repository when there is none to read.
     */
    static String contractApplication(String releaseYml, String repository) {
        if (releaseYml == null) {
            return repository;
        }
        boolean inside = false;
        for (String line : releaseYml.split("\n")) {
            if (line.isBlank() || line.strip().startsWith("#")) {
                continue;
            }
            boolean indented = Character.isWhitespace(line.charAt(0));
            if (!indented) {
                inside = line.strip().equals("contracts:");
                continue;
            }
            String entry = line.strip();
            if (inside && entry.startsWith("application:")) {
                String value = entry.substring("application:".length()).strip()
                        .replaceAll("^[\"']|[\"']$", "");
                return value.isEmpty() ? repository : value;
            }
        }
        return repository;
    }

    /**
     * <b>THE GOLDEN MASTERS EVERY PHASE 1 BUILD RESOLVES, into the FILE repository — before
     * {@code maven-seed} and the first seed image.</b>
     * <p>
     * A golden-masters jar is a provider's {@code golden-masters/} directory at its release, packed
     * as a plain jar under {@code eu.wohlben.qits:qits-<app>-golden-masters} — what that provider's
     * release publishes ({@code contracts: golden-masters:} in its release.yml). Every consumer
     * pins them TEST-scoped, and {@code mvn package -DskipTests} resolves test scope all the same
     * (measured: {@code -Dmaven.test.skip=true} does too), so the edge and the mirror — the first
     * two images — die on {@code qits-idp-golden-masters:jar (absent)} without them. And the
     * contracts kits {@code maven-seed} builds COMPILE against them: qits-eventstream-contracts
     * against qits-events', qits-containers-client-contracts against qits-containers'.
     * <p>
     * <b>Nothing here dials a server</b>: the bytes are {@code git archive}s of the checkouts, the
     * jar is the JDK's {@code jar}, and the deploy plugin — pinned, the toolchain seed's rule — is
     * resolved from Maven Central into the shared cache. That is what lets it run before anything
     * of the platform is up. The store's copy is {@code store-seed}'s, after the idp.
     */
    public Phase seedGoldenMasters() {
        return contractPhase("seed-golden-masters",
                "pack the golden masters the seed builds resolve into the file repository", false);
    }

    /**
     * <b>THE PACTS THE SEED IMAGES' PROVIDER SUITES READ, into the FILE repository, beside the
     * golden masters.</b> A provider pins each consumer's pact with it TEST-scoped as
     * {@code eu.wohlben.qits:<consumer repo>-pacts-<provider repo>} — qits-platform-edge fourteen
     * frontends', qits-projects some twenty-five — and nothing on a cold platform publishes one:
     * the consumers' releases do, and none has run. So this does what each consumer's release
     * would have ({@code ContractPackager.pacts} in qits-platform-access-cli): the top-level
     * {@code pacts/*_<provider>.json} of the consumer's release, under {@code pacts/} in a jar,
     * with a pom of the coordinate and the packager's description.
     * <p>
     * The consumers include three repositories that are none of the platform's —
     * {@link PlatformModel#READ_ONLY_REPOS} — which the sources phase clones for this.
     */
    public Phase seedPacts() {
        return contractPhase("seed-pacts",
                "pack the pacts the seed images' provider suites resolve into the file repository",
                true);
    }

    private Phase contractPhase(String id, String title, boolean pacts) {
        return new Phase(id, title, ctx -> {
            List<ContractJar> all = contractJars(ctx, pacts);
            if (all.isEmpty()) {
                ctx.skip("no seed build pins " + (pacts ? "a pact" : "a golden master"));
            }
            boot.docker.ensureVolume(SEED_REPO_VOLUME, ctx::log);
            boot.docker.ensureVolume(MAVEN_CACHE_VOLUME, ctx::log);
            List<String> held = fileRegistryHolds(all.stream().map(ContractJar::repositoryPath)
                    .toList());
            List<ContractJar> wanted = new ArrayList<>();
            for (ContractJar jar : all) {
                boolean there = held.contains(jar.repositoryPath());
                ctx.log("  " + jar.artifactId() + " " + jar.version() + " ("
                        + PlatformModel.repo(jar.repository()) + " " + jar.ref() + ") — "
                        + "file registry: " + (there ? "held" : "to seed"));
                if (!there) {
                    wanted.add(jar);
                }
            }
            if (wanted.isEmpty()) {
                ctx.skip("the file registry holds all "
                        + all.size());
            }
            String cid = create(ctx, List.of("docker", "create", "--user", "root",
                    "--entrypoint", "sh",
                    "-v", SEED_REPO_VOLUME + ":/repo", "-v", MAVEN_CACHE_MOUNT,
                    "maven:3.9-eclipse-temurin-25",
                    "-c", contractScript(wanted)));
            Path exports = Files.createDirectories(boot.state.srcDir.resolve(".exports"));
            for (ContractJar jar : wanted) {
                Path tar = exports.resolve(jar.artifactId() + "@" + jar.version() + ".tar");
                try {
                    Boot.must(boot.git.archive(boot.state.repoDir(jar.repository()), jar.ref(),
                                    jar.directory(), tar, ctx::log),
                            "archiving " + jar.directory() + "/ of " + PlatformModel.repo(jar.repository())
                                    + " " + jar.ref() + " failed");
                    Boot.must(boot.docker.exec(Duration.ofMinutes(10), ctx::log, "cp",
                                    tar.toString(), cid + ":" + jar.archive()),
                            "copying " + jar.artifactId() + " " + jar.version() + " in failed");
                } finally {
                    Files.deleteIfExists(tar);
                }
            }
            startAndReap(ctx, cid, "packing the " + (pacts ? "pacts" : "golden masters")
                    + " failed");
            ctx.note(wanted.size() + " of " + all.size());
        });
    }

    /**
     * One jar per contract, packed from its unpacked archive, deployed into the file repository.
     * <p>
     * <b>The layout is the platform packager's</b> ({@code ContractPackager.jar}): the kind's root
     * directory as an entry, then a golden-masters tree whole or one provider's pact files, with a
     * manifest. <b>The pom is too</b> ({@code ContractPackager.pom}): the coordinate, {@code jar},
     * and the description — which is what the deploy plugin generates from {@code
     * -DgeneratePom.description}, beside the checksums and metadata a Maven client expects.
     * <p>
     * <b>No settings at all</b>: the plugin comes from Maven Central direct, and the target is a
     * file url, which takes no credential.
     */
    static String contractScript(List<ContractJar> jars) {
        StringBuilder script = new StringBuilder("set -eu\n");
        for (ContractJar jar : jars) {
            String file = "/tmp/" + jar.artifactId() + "-" + jar.version() + ".jar";
            script.append("mkdir -p ").append(jar.tree()).append(" && tar -xf ")
                    .append(jar.archive()).append(" -C ").append(jar.tree()).append('\n');
            if (jar.pacts()) {
                String pack = "/tmp/pack/" + jar.artifactId() + "@" + jar.version();
                script.append("mkdir -p ").append(pack).append("/pacts\n")
                        .append("find ").append(jar.tree()).append("/pacts -maxdepth 1 -type f")
                        .append(" -name '?*_").append(jar.provider()).append(".json'")
                        .append(" -exec cp {} ").append(pack).append("/pacts/ \\;\n")
                        .append("ls ").append(pack).append("/pacts | grep -q . || { echo '")
                        .append(jar.artifactId()).append(' ').append(jar.version())
                        .append(": no pacts/*_").append(jar.provider())
                        .append(".json to pack' >&2; exit 1; }\n")
                        .append("jar cf ").append(file).append(" -C ").append(pack)
                        .append(" pacts\n");
            } else {
                script.append("test -d ").append(jar.tree()).append("/golden-masters\n")
                        .append("jar cf ").append(file).append(" -C ").append(jar.tree())
                        .append(" golden-masters\n");
            }
            script.append("mvn -B -ntp")
                    .append(" org.apache.maven.plugins:maven-deploy-plugin:3.1.2:deploy-file")
                    .append(" -Dfile=").append(file)
                    .append(" -DgroupId=eu.wohlben.qits -DartifactId=").append(jar.artifactId())
                    .append(" -Dversion=").append(jar.version()).append(" -Dpackaging=jar")
                    .append(" '-DgeneratePom.description=")
                    .append(jar.description().replace("'", "")).append("'")
                    .append(MAVEN_REPO_LOCAL)
                    .append(" -DrepositoryId=seed -Durl=file:///repo")
                    .append('\n');
        }
        return script.toString();
    }

    /**
     * Drops every TEST-scoped dependency from a tree's MODULE poms, in the container's copy only.
     * POSIX {@code awk} and nothing else, so it runs in any image: a {@code <dependency>} block is
     * held until its {@code </dependency>} and dropped when it carries {@code <scope>test</scope>}.
     * It was perl first, which the alpine CI image this repository's own suite runs in has not
     * got. The seed builds jars with
     * {@code -DskipTests} and Maven resolves test scope regardless, so a
     * test-only dependency a cold platform cannot hold — qits-runner-protocol's suite reads the
     * retired {@code qits-ci-runner-protocol} — would fail a build whose jars never touch it. The
     * root pom is left alone: its dependency management is what a module's version-less entry
     * reads, and a test dependency a module declares only through it stays resolvable.
     */
    static String stripTestDependencies(String tree) {
        return "find " + tree + " -mindepth 2 -name pom.xml -not -path '*/target/*'"
                + " | while read -r pom; do awk '"
                + "inside { block = block \"\\n\" $0 } "
                + "!inside && /<dependency>/ { inside = 1; block = $0 } "
                + "!inside { print; next } "
                + "/<\\/dependency>/ { inside = 0; if (block !~ /<scope>test<\\/scope>/) print block }"
                + "' \"$pom\" > \"$pom.strip\" && mv \"$pom.strip\" \"$pom\"; done\n";
    }

    /**
     * <b>qits-runner-javalib — qits-runner-protocol, qits-runner-toolkit, the harness and
     * qits-runner-toolkit-contracts — at every version the seed's runner consumers pin, into the
     * file repository.</b> Built from the release TAG, the whole reactor, as {@link #protocolSeed}
     * builds a daemon's contract; the store gets the jars from {@code store-seed}.
     * <p>
     * qits-system's targets module compiles against the protocol and the toolkit, its runner's
     * native build against both and the contracts module at test scope, and nothing else on a
     * cold platform could put any of the four in a registry: the library is no deployable and no
     * release publisher. Before qits-1547 the CI runner's build read the same jars from the
     * PUBLIC registry by its pom default, which a cold host with no credential for it cannot.
     * <p>
     * <b>The test scope is stripped from the module poms first</b> ({@link
     * #stripTestDependencies}): the reactor's suites read four providers' golden masters and the
     * retired CI runner's contract, none of which a cold platform holds or a jar needs. The
     * golden masters the contracts module COMPILES against and the qits-containers-driver the
     * toolkit is written against are in the file repository already ({@code seed-golden-masters},
     * {@code maven-seed}).
     */
    public Phase runnerSeed() {
        return new Phase("seed-qits-runner",
                "seed qits-runner-javalib at the versions its consumers pin", ctx -> {
            List<String> versions = runnerVersions();
            boot.docker.ensureVolume(SEED_REPO_VOLUME, ctx::log);
            boot.docker.ensureVolume(MAVEN_CACHE_VOLUME, ctx::log);
            List<String> toFile = new ArrayList<>();
            List<String> held = fileRegistryHolds(versions.stream()
                    .map(v -> protocolStorePath(RUNNER_PROBE_ARTIFACT, v)).toList());
            for (String version : versions) {
                if (boot.git.fileAt(boot.state.repoDir("runner"), version,
                        PinnedVersions.ROOT_POM) == null) {
                    throw new IllegalStateException("the seed's runner consumers pin "
                            + RUNNER_PIN_PROPERTY + " " + version + " and the "
                            + PlatformModel.repo("runner") + " checkout has no such tag. Fetch "
                            + "the tags of " + boot.state.repoDir("runner") + " (git fetch "
                            + "--tags) and rerun");
                }
                boolean seeded = held.contains(protocolStorePath(RUNNER_PROBE_ARTIFACT, version));
                ctx.log("  qits-runner " + version + " — file registry: "
                        + (seeded ? "held" : "to seed"));
                if (!seeded) {
                    toFile.add(version);
                }
            }
            if (toFile.isEmpty()) {
                ctx.skip("the file registry holds qits-runner " + String.join(", ", versions));
            }
            String cid = create(ctx, List.of(
                    "docker", "create", "--user", "root",
                    "--entrypoint", "sh", "-v", SEED_REPO_VOLUME + ":/repo",
                    "-v", MAVEN_CACHE_MOUNT, "maven:3.9-eclipse-temurin-25",
                    "-c", runnerScript(toFile)));
            for (String version : toFile) {
                copyTag(ctx, "runner", version, cid, publishTree(version));
            }
            startAndReap(ctx, cid, "the qits-runner seed failed");
            ctx.note(String.join(", ", versions));
        });
    }

    /** The jar the runner seed probes a version by: the one every consumer compiles against. */
    static final String RUNNER_PROBE_ARTIFACT = "qits-runner-toolkit";

    /** The settings, the purge, then per version: the strip and the deploy into the volume. */
    static String runnerScript(List<String> versions) {
        StringBuilder script = new StringBuilder("set -eu\n").append(seedSettings())
                .append(MAVEN_PURGE_QITS);
        for (String version : versions) {
            String tree = publishTree(version);
            script.append(stripTestDependencies(tree))
                    .append("cd ").append(tree)
                    .append(" && mvn -B -ntp -s /root/.m2/settings.xml deploy -DskipTests")
                    .append(MAVEN_REPO_LOCAL).append(SEED_DEPLOYMENT).append('\n');
        }
        return script.toString();
    }

    // --- the target's runner image ---------------------------------------------------------------

    /** The qits-system-runner's image repository, as its own release publishes it. */
    static final String SYSTEM_RUNNER_IMAGE = "qits/qits-system-runner";

    /** The property in qits-system's root pom that says which runner every target is pinned to. */
    static final String SYSTEM_RUNNER_PIN_PROPERTY = "qits.system-runner-protocol.version";

    /**
     * <b>WHICH RUNNER THIS BOOT BUILDS AND STARTS: the one qits-system pins.</b> qits-system takes
     * its runner version from the protocol jar it compiles against
     * ({@code SystemRunnerBinary.VERSION}) and tells every connected runner of another version to
     * upgrade to it — so a runner built at any other version would be told to pull an image no
     * registry of a cold platform holds. Read from the system checkout on every ask, so the build
     * arm, a warm rerun and the protocol publish cannot disagree.
     */
    static String systemRunnerVersion(Boot boot) throws IOException {
        String pom = Files.readString(boot.state.repoDir("system")
                .resolve(PinnedVersions.ROOT_POM), StandardCharsets.UTF_8);
        return protocolPin(pom, SYSTEM_RUNNER_PIN_PROPERTY, "qits-system");
    }

    /** The image as the host daemon holds it after the build: {@code qits/qits-system-runner:<pin>}. */
    static String systemRunnerImage(String version) {
        return SYSTEM_RUNNER_IMAGE + ":" + version;
    }

    /**
     * The same image under the registry host — the spelling {@code images-publish} pushes it
     * under.
     */
    static String systemRunnerRegistryImage(Boot boot, String version) {
        return boot.config.registryVhost() + "/" + systemRunnerImage(version);
    }

    /**
     * <b>The {@code image} stage of the runner's Dockerfile, fed a binary that is already built.</b>
     * <p>
     * The repository's Dockerfile compiles and packages in one file: a {@code build} stage on the
     * musl toolchain and an {@code image} stage that copies the binary out of it. This boot does
     * the compile the way it does the ci-daemon's — in a container, on the shared maven cache — so
     * what it needs of the Dockerfile is the last stage alone, with its one
     * {@code COPY --from=build} turned into a copy from the build context. Everything else in that
     * stage is the repository's and stays the repository's: the base image, the state directory,
     * the entrypoint.
     * <p>
     * The {@code ARG}s above the first {@code FROM} ride along, because a {@code FROM} may name
     * one. A Dockerfile with no such stage, or whose stage copies nothing from {@code build},
     * stops the boot: guessing at a changed file would start a container with no runner in it.
     *
     * @param binary the binary's file name in the build context
     */
    static String runnerImageStage(String dockerfile, String binary) {
        List<String> lines = dockerfile.lines().toList();
        StringBuilder stage = new StringBuilder();
        int from = -1;
        boolean beforeFirstFrom = true;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.regionMatches(true, 0, "FROM ", 0, 5)) {
                beforeFirstFrom = false;
                if (line.matches("(?i)FROM\\s+.*\\s+AS\\s+image")) {
                    from = i;
                }
            } else if (beforeFirstFrom && line.regionMatches(true, 0, "ARG ", 0, 4)) {
                stage.append(line).append('\n');
            }
        }
        if (from < 0) {
            throw new IllegalStateException("the runner's docker/Dockerfile has no `FROM … AS "
                    + "image` stage — this boot packages the runner from that stage and cannot "
                    + "guess at another");
        }
        boolean copied = false;
        for (String line : lines.subList(from, lines.size())) {
            java.util.regex.Matcher copy = java.util.regex.Pattern.compile(
                    "^\\s*COPY\\s+--from=build\\s+\\S+\\s+(\\S+)\\s*$").matcher(line);
            if (copy.matches()) {
                stage.append("COPY ").append(binary).append(' ').append(copy.group(1)).append('\n');
                copied = true;
            } else {
                stage.append(line).append('\n');
            }
        }
        if (!copied) {
            throw new IllegalStateException("the image stage of the runner's docker/Dockerfile "
                    + "copies nothing from its build stage — there is no line to hand the binary "
                    + "this boot built to");
        }
        return SeedDockerfile.rewrite(stage.toString());
    }

    /**
     * The runner's native compile, in the builder container — the ci-daemon's line, one module on,
     * with the file repository mounted: unlike the ci-daemon's reactor this one resolves qits jars
     * (qits-runner-protocol, qits-runner-toolkit, and at test scope qits-runner-toolkit-contracts
     * and a golden master), which {@code seed-qits-runner} and {@code seed-golden-masters} put in
     * the volume and Maven Central never will. Everything else comes from Central direct.
     */
    static String systemRunnerBuildScript() {
        return seedSettings() + MAVEN_PURGE_QITS
                + "cd /qits-build && ./mvnw -B -ntp -s /root/.m2/settings.xml -pl system-runner -am"
                + " package -Dnative -DskipTests -Dquarkus.native.container-build=false"
                + MAVEN_REPO_LOCAL + SEED_REPOSITORY_URL;
    }

    /**
     * <b>{@code qits/qits-system-runner:<pin>}, built from the release tag qits-system pins.</b>
     * <p>
     * Since the CI step of epic qits-1481 (qits-1547) every CI run is a lease on a qits-system
     * target, so the first release replay of a cold boot runs on this host's target or nowhere —
     * and a cold platform has no registry to pull the target's runner from and no CI to build it
     * with. So this program builds the first one the way it builds the first ci-daemon, straight
     * after it and on the musl builder image that phase just made: the runner's builder Dockerfile
     * is a byte copy of the ci-daemon's, so one toolchain image serves both. It replaced the
     * qits-ci-runner image this phase built until the CI runner retired.
     * <p>
     * <b>From the TAG, and the tag is qits-system's pin</b> — see {@link #systemRunnerVersion}.
     * The checkout stands on main and is not what is built.
     * <p>
     * <b>Tagged three times</b>: under the bare name the build loads it as; under the registry
     * vhost, which is how the {@code images-publish} phase pushes it; and under the PUBLIC registry
     * name {@code registry.qits.<domain>}, which is how {@code target-localhost} starts it and how
     * qits-system names every runner's image — so the container this boot starts is already named
     * the way its successor will be, and the host pulls nothing.
     * <p>
     * <b>An image this daemon already holds is not built again</b>: the tag carries the version,
     * and a version names one commit. That is every rerun, and it is what makes this phase cost a
     * native build once per pin rather than once per boot.
     */
    public Phase systemRunnerImage() {
        return new Phase("system-runner-image", "build the qits-system-runner image qits-system pins",
                ctx -> {
            String version = systemRunnerVersion(boot);
            String image = systemRunnerImage(version);
            List<String> tags = List.of(systemRunnerRegistryImage(boot, version),
                    PipelinePhases.targetRunnerImage(boot.config.requiredDomain(), version));
            if (boot.docker.imageExists(image)) {
                tag(ctx, image, tags);
                ctx.skip(image + " is already on this host");
            }
            Path repo = boot.state.repoDir("system-runner-daemon");
            String dockerfile = boot.git.fileAt(repo, version, PlatformModel.dockerfilePath(
                    "system-runner-daemon"));
            if (dockerfile == null) {
                throw new IllegalStateException("qits-system pins the runner at " + version
                        + " and the " + PlatformModel.repo("system-runner-daemon") + " checkout "
                        + "has no such tag, so there is nothing to build " + image + " from. "
                        + "Fetch the tags of " + repo + " (git fetch --tags) and rerun");
            }
            String stage = runnerImageStage(dockerfile, "qits-system-runner");

            boot.docker.ensureVolume(MAVEN_CACHE_VOLUME, ctx::log);
            // The ci-daemon's container, with the file repository mounted read-only: the builder
            // image entrypoints to native-image itself, and this reactor resolves qits jars out
            // of the volume (Maven Central for the rest), where the ci-daemon's resolves Maven
            // Central alone.
            boot.docker.ensureVolume(SEED_REPO_VOLUME, ctx::log);
            String cid = create(ctx, List.of(
                    "docker", "create", "--user", "root",
                    "--entrypoint", "bash",
                    "--memory", "6g", "--cpu-quota", "400000", "--cpuset-cpus", "0-3",
                    "-v", SEED_REPO_VOLUME + ":/repo:ro",
                    "-v", MAVEN_CACHE_MOUNT, "qits/graalvmce-musl-builder:jdk-25",
                    "-c", systemRunnerBuildScript()));
            // The build directory sits under the sources directory because the image build below
            // mounts it as its context, and that mount is resolved by the HOST's daemon.
            Path context = boot.state.srcDir.resolve(".exports").resolve("qits-system-runner-image");
            try {
                copyTag(ctx, "system-runner-daemon", version, cid, "/qits-build");
                ctx.status("cold musl native build of the system-runner");
                ProcessResult run = boot.docker.exec(Docker.BUILD_TIMEOUT, ctx::log, "start", "-a",
                        cid);
                if (!run.ok()) {
                    throw new IllegalStateException("system-runner build failed\n"
                            + run.tailText(20));
                }
                Files.createDirectories(context);
                Boot.must(boot.docker.exec(Duration.ofMinutes(30), ctx::log, "cp",
                                cid + ":/qits-build/system-runner/target/qits-system-runner",
                                context.resolve("qits-system-runner").toString()),
                        "copying the runner binary out failed");
                ctx.status("packaging " + image);
                Boot.must(boot.docker.buildFromStdin(image, stage, context, List.of(), ctx::log),
                        "build of " + image + " failed");
            } finally {
                boot.docker.removeContainer(cid, null);
                Files.deleteIfExists(context.resolve("qits-system-runner"));
                Files.deleteIfExists(context);
            }
            tag(ctx, image, tags);
            ctx.note(version);
        });
    }

    private void tag(PhaseContext ctx, String image, List<String> tags) {
        for (String tag : tags) {
            Boot.must(boot.docker.exec(ctx::log, "tag", image, tag),
                    "tagging " + image + " as " + tag + " failed");
        }
    }

    // --- what phase 1 builds, by repository (qits-889) ---------------------------------------------

    /**
     * <b>Every repository phase 1 builds something from, and what</b> — derived from the same
     * constants the plan's phases are built from, so a repository a phase stops building drops out
     * of here with it. Together with {@link PlatformModel#DEFERRED_TO_PHASE_4} and
     * {@link PlatformModel#NOT_BUILT} it must cover every repository the model clones; a test holds
     * the three against {@link PlatformModel#sourceRepos}.
     */
    static java.util.SortedMap<String, String> phaseOneBuilds() {
        java.util.SortedMap<String, String> built = new java.util.TreeMap<>();
        for (String name : PlatformModel.CORE) {
            built.put(name, "seed image qits/" + name + ":latest");
        }
        for (String name : PlatformModel.seedClients()) {
            built.put("spa-" + name, "the client inside qits/" + name);
        }
        for (String library : SEED_LIBRARIES) {
            built.merge(library, "libraries into " + SEED_REPO_VOLUME, (a, b) -> a + "; " + b);
        }
        NpmSeed.jslibs().forEach(pkg ->
                built.put(NpmSeed.producer(pkg), "@qits packages into " + NpmSeed.VOLUME));
        built.put("oci", "the step images and qits/build-images/node-browser-base");
        built.put("ci-daemon", "the ci-daemon binary and qits-ci-daemon-protocol");
        built.put("system-runner-daemon", "qits/qits-system-runner and its protocol");
        built.put("runner", "qits-runner-javalib into " + SEED_REPO_VOLUME);
        built.put("projects-daemon", "qits/projects-daemon, qits/project-agent and its protocol");
        built.put("workspace-daemon", "qits/workspace and qits-workspace-daemon-protocol");
        built.put("oci-workspace", "qits/workspace-base");
        built.put("platform-access-cli", "the qits binary, qits-platform-access-cli-binary, pacts");
        // Contract sources only: their pacts at the pinned releases.
        for (String readOnly : PlatformModel.READ_ONLY_REPOS) {
            built.putIfAbsent(readOnly, "pacts into " + SEED_REPO_VOLUME);
        }
        return built;
    }

    // --- the images the platform starts workspaces and agents from (qits-889) -------------------

    /** The pin every workspace-image consumer names {@code qits/workspace} by: the jar IS the tag. */
    static final String WORKSPACE_IMAGE_PIN = "qits.workspace-daemon-protocol.version";

    /** The pin qits-projects names {@code qits/project-agent} by, on the same terms. */
    static final String AGENT_IMAGE_PIN = "qits.projects-daemon-protocol.version";

    /** The pin qits-workspace-oci names the {@code qits} binary it bakes in by. */
    static final String CLI_PIN = "qits.platform-access-cli-binary.version";

    /**
     * The services that start a container from {@code qits/workspace} at the version their root pom
     * pins: qits-workspaces its workspaces, qits-projects its refinement rooms, qits-system the
     * login probe of a target's workspace slot. Each lags independently.
     */
    static final List<String> WORKSPACE_IMAGE_CONSUMERS = List.of("workspaces", "projects", "system");

    /** Where qits-workspace-daemon keeps the Dockerfile of {@code qits/workspace}. */
    static final String WORKSPACE_DOCKERFILE = "docker/Dockerfile";

    /** Where qits-projects-daemon keeps the Dockerfile of {@code qits/project-agent}. */
    static final String AGENT_DOCKERFILE = "docker/Dockerfile.projects";

    /** Where qits-build-images-oci keeps the browser base qits/workspace-base starts from. */
    static final String BROWSER_BASE_DOCKERFILE = "node-browser-base/Dockerfile";

    static final String BROWSER_BASE_IMAGE = "qits/build-images/node-browser-base";
    static final String WORKSPACE_BASE_IMAGE = "qits/workspace-base";
    static final String WORKSPACE_IMAGE = "qits/workspace";
    static final String PROJECTS_DAEMON_IMAGE = "qits/projects-daemon";
    static final String AGENT_IMAGE = "qits/project-agent";

    /**
     * <b>EVERY VERSION OF EVERY WORKSPACE-SIDE IMAGE THE PLATFORM NEEDS, closed over their bases</b>
     * (qits-889) — read from the checkouts, never guessed:
     * <ul>
     *   <li>{@code qits/workspace} at every {@link #WORKSPACE_IMAGE_PIN} a consumer pins, and
     *       {@code qits/project-agent} (with the {@code qits/projects-daemon} it copies its binary
     *       from) at every {@link #AGENT_IMAGE_PIN} — the images the platform STARTS, under
     *       {@code registry.qits.<domain>/qits/<image>:<pin>};
     *   <li>{@code qits/workspace-base} at every version those images' Dockerfiles name at their
     *       tags ({@code ARG WORKSPACE_BASE}, {@code ARG BASE});
     *   <li>{@code qits/build-images/node-browser-base} and the {@code qits} binary at what each
     *       base's tag names ({@code ARG BROWSER_BASE}, the {@link #CLI_PIN} of its pom).
     * </ul>
     * Lists keep first-seen order and hold each version once.
     *
     * @param bases      workspace-base version → the browser-base version it starts from
     * @param workspaces workspace version → the workspace-base version it starts from
     * @param agents     project-agent version → the workspace-base version it starts from
     */
    record WorkspaceImages(List<String> cli, List<String> browserBases,
                           Map<String, String> bases, Map<String, String> workspaces,
                           Map<String, String> agents) {
    }

    /** The checkout reads {@link #workspaceImages} needs, as a seam a test can answer. */
    interface ReleaseFiles {
        /** One file of a repository at a release tag, or null when the tag or the file is not. */
        String at(String repository, String version, String path);
    }

    /**
     * {@link WorkspaceImages} from the consumers' root poms and the release files they lead to.
     * A tag that is not in a checkout, or a Dockerfile that names no base, stops the boot by name:
     * an image built at anything else is not the image the platform asks for.
     */
    static WorkspaceImages workspaceImages(Map<String, String> consumerPoms, ReleaseFiles files) {
        java.util.LinkedHashMap<String, String> workspaces = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, String> agents = new java.util.LinkedHashMap<>();
        for (String consumer : WORKSPACE_IMAGE_CONSUMERS) {
            String pom = consumerPoms.get(consumer);
            // A consumer that pins no workspace image starts none, and is owed none.
            if (pom == null || !pom.contains("<" + WORKSPACE_IMAGE_PIN + ">")) {
                continue;
            }
            String version = protocolPin(pom, WORKSPACE_IMAGE_PIN, PlatformModel.application(consumer));
            workspaces.computeIfAbsent(version, v -> baseOf(files, "workspace-daemon", v,
                    WORKSPACE_DOCKERFILE, "WORKSPACE_BASE"));
        }
        String projects = consumerPoms.get("projects");
        if (projects != null && projects.contains("<" + AGENT_IMAGE_PIN + ">")) {
            String version = protocolPin(projects, AGENT_IMAGE_PIN, PlatformModel.application("projects"));
            agents.computeIfAbsent(version, v -> baseOf(files, "projects-daemon", v,
                    AGENT_DOCKERFILE, "BASE"));
        }
        java.util.LinkedHashMap<String, String> bases = new java.util.LinkedHashMap<>();
        java.util.LinkedHashSet<String> cli = new java.util.LinkedHashSet<>();
        Stream.concat(workspaces.values().stream(), agents.values().stream()).forEach(base -> {
            if (bases.containsKey(base)) {
                return;
            }
            bases.put(base, baseOf(files, "oci-workspace", base, "Dockerfile", "BROWSER_BASE"));
            String pom = files.at("oci-workspace", base, PinnedVersions.ROOT_POM);
            if (pom == null) {
                throw new IllegalStateException(PlatformModel.repo("oci-workspace") + " has no "
                        + "pom.xml at " + base + ", so this boot cannot tell which qits binary "
                        + WORKSPACE_BASE_IMAGE + ":" + base + " carries");
            }
            cli.add(protocolPin(pom, CLI_PIN, PlatformModel.repo("oci-workspace") + "@" + base));
        });
        return new WorkspaceImages(List.copyOf(cli),
                bases.values().stream().distinct().toList(), Map.copyOf(bases),
                Map.copyOf(workspaces), Map.copyOf(agents));
    }

    /** The tag of the platform-built base a release's Dockerfile names in {@code ARG <arg>}. */
    private static String baseOf(ReleaseFiles files, String repository, String version,
                                 String dockerfile, String arg) {
        String text = files.at(repository, version, dockerfile);
        if (text == null) {
            throw new IllegalStateException("the platform pins " + PlatformModel.repo(repository)
                    + " " + version + " and the checkout has no " + dockerfile + " at that tag, so "
                    + "there is nothing to build the image from. Fetch the tags of "
                    + PlatformModel.repo(repository) + " (git fetch --tags) and rerun");
        }
        String tag = SeedDockerfile.tagOf(SeedDockerfile.argDefault(text, arg));
        if (tag == null || tag.isBlank() || tag.contains("$")) {
            throw new IllegalStateException(PlatformModel.repo(repository) + " " + version + "'s "
                    + dockerfile + " names no versioned base in `ARG " + arg + "=…:<version>`, so "
                    + "this boot cannot tell which one to build it on");
        }
        return tag;
    }

    /** The closure for THIS boot's checkouts. */
    WorkspaceImages workspaceImages() throws IOException {
        Map<String, String> poms = new LinkedHashMap<>();
        for (String consumer : WORKSPACE_IMAGE_CONSUMERS) {
            Path pom = boot.state.repoDir(consumer).resolve(PinnedVersions.ROOT_POM);
            if (Files.isRegularFile(pom)) {
                poms.put(consumer, Files.readString(pom, StandardCharsets.UTF_8));
            }
        }
        return workspaceImages(poms,
                (repository, version, path) -> boot.git.fileAt(boot.state.repoDir(repository),
                        version, path));
    }

    /** {@code <image>:<version>}, the local tag a seed build is loaded under. */
    static String image(String repository, String version) {
        return repository + ":" + version;
    }

    /** The layout a base is exported to in {@link Docker#OCI_SEED_VOLUME}. */
    static String layout(String repository, String version) {
        return repository.substring(repository.lastIndexOf('/') + 1) + "@" + version;
    }

    /** Where the {@code qits} binary of one version is kept between its build and its uses. */
    Path cliBinary(String version) {
        return boot.state.wrapperDir.resolve(".qits-bootstrap").resolve("qits-cli").resolve(version)
                .resolve("qits");
    }

    /**
     * <b>Every image the workspace-side phases build, as the store is asked for it</b> — the
     * browser bases, the workspace bases, the workspace images, the projects daemons and the
     * agents, each at its version. {@code images-publish} pushes them beside the step images: a
     * release build in phase 4 starts {@code FROM} the bases through the registry, and a target
     * pulls the started images from it.
     */
    List<PipelinePhases.StoreImage> workspaceStoreImages() throws IOException {
        WorkspaceImages closure = workspaceImages();
        List<PipelinePhases.StoreImage> images = new ArrayList<>();
        closure.browserBases().forEach(v -> images.add(new PipelinePhases.StoreImage(BROWSER_BASE_IMAGE, v)));
        closure.bases().keySet().stream().sorted()
                .forEach(v -> images.add(new PipelinePhases.StoreImage(WORKSPACE_BASE_IMAGE, v)));
        closure.workspaces().keySet().stream().sorted()
                .forEach(v -> images.add(new PipelinePhases.StoreImage(WORKSPACE_IMAGE, v)));
        closure.agents().keySet().stream().sorted().forEach(v -> {
            images.add(new PipelinePhases.StoreImage(PROJECTS_DAEMON_IMAGE, v));
            images.add(new PipelinePhases.StoreImage(AGENT_IMAGE, v));
        });
        return images;
    }

    /**
     * <b>One release of a repository as a build CONTEXT</b>: a detached worktree under the sources
     * directory — which the daemon resolves at its own path — removed by the caller's {@code finally}
     * through {@link #removeTree}. {@link #copyTag}'s export, kept on disk for a build to read.
     */
    private Path exportTree(PhaseContext ctx, String name, String version) {
        Path repo = boot.state.repoDir(name);
        Path tree = boot.state.srcDir.resolve(".exports").resolve(PlatformModel.repo(name) + "@"
                + version);
        boot.git.worktreeRemove(repo, tree, null);
        boot.git.worktreePrune(repo, null);
        Boot.must(boot.git.worktreeAdd(repo, version, tree, ctx::log),
                PlatformModel.repo(name) + " has no " + version + " to build from — fetch its tags "
                        + "(git fetch --tags) and rerun");
        return tree;
    }

    private void removeTree(String name, Path tree) {
        boot.git.worktreeRemove(boot.state.repoDir(name), tree, null);
    }

    /** A base this run exported, or the phase stops naming the phase that should have. */
    private Docker.LocalBase localBase(String repository, String version, String producer) {
        String layout = layout(repository, version);
        String digest = boot.docker.layoutDigest(layout);
        if (digest == null) {
            throw new IllegalStateException(image(repository, version) + " is not in the "
                    + Docker.OCI_SEED_VOLUME + " volume, and a seed build takes a platform-built "
                    + "base from there and from no registry. " + producer + " builds it — it did "
                    + "not run, or did not finish");
        }
        return new Docker.LocalBase(image(repository, version), layout, digest);
    }

    /** Whether a base is on this host AND in the layout volume, so neither has to be built again. */
    private boolean baseHeld(String repository, String version) {
        return boot.docker.imageExists(image(repository, version))
                && boot.docker.layoutDigest(layout(repository, version)) != null;
    }

    /** The registry-vhost spelling, which {@code images-publish} pushes. */
    private String vhost(String repository, String version) {
        return boot.config.registryVhost() + "/" + image(repository, version);
    }

    /** The public spelling a consumer starts the image under, so a target pulls nothing. */
    private String publicName(String repository, String version) {
        return PipelinePhases.publicRegistryHost(boot.config.requiredDomain()) + "/"
                + image(repository, version);
    }

    /**
     * <b>The {@code qits} binary at every version a workspace base bakes in, built from its release
     * tag</b> — qits-platform-access-cli's own {@code docker/Dockerfile}, stage {@code binary}: the
     * musl toolchain on GraalVM CE and a static native compile, the qits-ci-daemon's shape.
     * <p>
     * The base's release downloads it from {@code registry.qits.<domain>/artifacts/daemons/
     * qits-platform-access-cli/<version>}; a cold boot has no such store, so it is built here, from
     * the {@code qits-maven-seed} volume and upstream only. The two toolchain tarballs are the ones
     * {@code seed-toolchain} downloaded and verified against qits-ci-daemon's pins — the same two
     * coordinates, which this phase checks rather than assumes. {@code daemon-publish} puts each
     * binary in the store after the idp, for phase 4's release builds of the bases.
     */
    public Phase qitsCliSeed() {
        return new Phase("seed-qits-cli", "build the qits binary the workspace bases carry", ctx -> {
            WorkspaceImages closure = workspaceImages();
            List<String> built = new ArrayList<>();
            for (String version : closure.cli()) {
                Path out = cliBinary(version);
                if (Files.isRegularFile(out)) {
                    ctx.log("  qits " + version + " — " + out + ": held");
                    continue;
                }
                String origin = PlatformModel.repo("platform-access-cli") + "@" + version
                        + "/docker/Dockerfile";
                String committed = boot.git.fileAt(boot.state.repoDir("platform-access-cli"),
                        version, "docker/Dockerfile");
                if (committed == null) {
                    throw new IllegalStateException("a workspace base pins the qits binary at "
                            + version + " and the " + PlatformModel.repo("platform-access-cli")
                            + " checkout has no docker/Dockerfile at that tag. Fetch its tags "
                            + "(git fetch --tags) and rerun");
                }
                List<MuslToolchain.Tarball> tarballs = MuslToolchain.references(committed);
                List<String> held = fileRegistryProbe(tarballs.stream()
                        .map(MuslToolchain.Tarball::storePath).toList());
                List<String> missing = tarballs.stream().map(MuslToolchain.Tarball::storePath)
                        .filter(path -> !held.contains(path)).toList();
                if (!missing.isEmpty()) {
                    throw new IllegalStateException(origin + " ADDs " + String.join(", ", missing)
                            + ", which the " + SEED_REPO_VOLUME + " volume does not hold. "
                            + "seed-toolchain seeds what qits-ci-daemon's builder pins; the two "
                            + "repositories name different toolchains now — teach seed-toolchain "
                            + "the second pin");
                }
                String dockerfile = SeedDockerfile.refusePlatformBases(SeedDockerfile.localToolchain(
                        SeedDockerfile.localMaven(SeedDockerfile.rewrite(committed), origin), origin,
                        tarballs), origin);
                Path tree = exportTree(ctx, "platform-access-cli", version);
                String tag = image("qits-seed/qits-cli", version);
                try {
                    mavenPreflight(ctx, "platform-access-cli@" + version, tree);
                    ctx.status("cold musl native build of the qits binary " + version);
                    Boot.must(boot.docker.buildSeedImage(tag, dockerfile, tree, List.of(),
                                    Map.of(SeedDockerfile.SEED_M2_CONTEXT, SEED_REPO_VOLUME),
                                    List.of(), null, "binary", ctx::log),
                            "the build of the qits binary " + version + " failed");
                } finally {
                    removeTree("platform-access-cli", tree);
                }
                // The binary stage is FROM scratch with the one file in it: a container of it is
                // created, never started, and the file copied out.
                ProcessResult created = boot.docker.exec(ctx::log, "create", tag, "/qits");
                Boot.must(created, "creating a container of " + tag + " failed");
                String cid = created.captured().getLast().strip();
                try {
                    Files.createDirectories(out.getParent());
                    Boot.must(boot.docker.exec(Duration.ofMinutes(5), ctx::log, "cp",
                            cid + ":/qits", out.toString()), "copying the qits binary out failed");
                } finally {
                    boot.docker.removeContainer(cid, null);
                }
                built.add(version);
            }
            if (built.isEmpty()) {
                ctx.skip("every qits binary a workspace base pins is on this host ("
                        + String.join(", ", closure.cli()) + ")");
            }
            ctx.note(String.join(", ", built));
        });
    }

    /**
     * <b>{@code qits/build-images/node-browser-base} at every version a workspace base starts
     * from</b>, built from qits-build-images-oci's TAG — the step images build its main, but this
     * one is pinned by version — and exported to {@link Docker#OCI_SEED_VOLUME} for the base's
     * build to start from. Upstream bases and public package mirrors only.
     */
    public Phase browserBaseSeed() {
        return new Phase("seed-node-browser-base",
                "build the browser base the workspace bases start from", ctx -> {
            WorkspaceImages closure = workspaceImages();
            List<String> built = new ArrayList<>();
            for (String version : closure.browserBases()) {
                if (baseHeld(BROWSER_BASE_IMAGE, version)) {
                    tag(ctx, image(BROWSER_BASE_IMAGE, version), List.of(vhost(BROWSER_BASE_IMAGE, version)));
                    ctx.log("  " + image(BROWSER_BASE_IMAGE, version) + ": held");
                    continue;
                }
                String origin = PlatformModel.repo("oci") + "@" + version + "/"
                        + BROWSER_BASE_DOCKERFILE;
                String committed = boot.git.fileAt(boot.state.repoDir("oci"), version,
                        BROWSER_BASE_DOCKERFILE);
                if (committed == null) {
                    throw new IllegalStateException("a workspace base starts from "
                            + image(BROWSER_BASE_IMAGE, version) + " and the "
                            + PlatformModel.repo("oci") + " checkout has no "
                            + BROWSER_BASE_DOCKERFILE + " at that tag. Fetch its tags and rerun");
                }
                String dockerfile = SeedDockerfile.refusePlatformBases(SeedDockerfile.refuseServers(
                        SeedDockerfile.rewrite(committed), origin), origin);
                Path tree = exportTree(ctx, "oci", version);
                try {
                    ctx.status("building " + image(BROWSER_BASE_IMAGE, version));
                    Boot.must(boot.docker.buildSeedImage(image(BROWSER_BASE_IMAGE, version),
                                    dockerfile, tree, List.of(), Map.of(), List.of(),
                                    layout(BROWSER_BASE_IMAGE, version), null, ctx::log),
                            "the build of " + image(BROWSER_BASE_IMAGE, version) + " failed");
                } finally {
                    removeTree("oci", tree);
                }
                tag(ctx, image(BROWSER_BASE_IMAGE, version), List.of(vhost(BROWSER_BASE_IMAGE, version)));
                built.add(version);
            }
            if (built.isEmpty()) {
                ctx.skip("held: " + String.join(", ", closure.browserBases()));
            }
            ctx.note(String.join(", ", built));
        });
    }

    /**
     * <b>{@code qits/workspace-base} at every version an image the platform starts is built on</b>,
     * from qits-workspace-oci's tag: {@code FROM} the browser base this run exported, and the
     * {@code qits} binary {@link #qitsCliSeed} built put in the context, where the release's own
     * recipe puts the one it downloads. Exported to {@link Docker#OCI_SEED_VOLUME} too, for the
     * workspace and agent images.
     */
    public Phase workspaceBaseSeed() {
        return new Phase("seed-workspace-base",
                "build the workspace bases the workspace and agent images start from", ctx -> {
            WorkspaceImages closure = workspaceImages();
            List<String> built = new ArrayList<>();
            for (Map.Entry<String, String> entry : new java.util.TreeMap<>(closure.bases())
                    .entrySet()) {
                String version = entry.getKey();
                if (baseHeld(WORKSPACE_BASE_IMAGE, version)) {
                    tag(ctx, image(WORKSPACE_BASE_IMAGE, version),
                            List.of(vhost(WORKSPACE_BASE_IMAGE, version)));
                    ctx.log("  " + image(WORKSPACE_BASE_IMAGE, version) + ": held");
                    continue;
                }
                String origin = PlatformModel.repo("oci-workspace") + "@" + version + "/Dockerfile";
                String committed = boot.git.fileAt(boot.state.repoDir("oci-workspace"), version,
                        "Dockerfile");
                String cliVersion = protocolPin(boot.git.fileAt(boot.state.repoDir("oci-workspace"),
                        version, PinnedVersions.ROOT_POM), CLI_PIN, origin);
                Path cli = cliBinary(cliVersion);
                if (!Files.isRegularFile(cli)) {
                    throw new IllegalStateException(origin + " carries the qits binary "
                            + cliVersion + ", which is not at " + cli + ". seed-qits-cli builds "
                            + "it — it did not run, or did not finish");
                }
                Docker.LocalBase base = localBase(BROWSER_BASE_IMAGE, entry.getValue(),
                        "seed-node-browser-base");
                String dockerfile = SeedDockerfile.refusePlatformBases(SeedDockerfile.refuseServers(
                        SeedDockerfile.localBase(SeedDockerfile.rewrite(committed), origin,
                                "BROWSER_BASE", base.reference()), origin), origin);
                Path tree = exportTree(ctx, "oci-workspace", version);
                try {
                    // Where the release's recipe puts the binary it downloads: `COPY qits …`.
                    Path into = tree.resolve("qits");
                    Files.copy(cli, into, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    into.toFile().setExecutable(true, false);
                    ctx.status("building " + image(WORKSPACE_BASE_IMAGE, version) + " (qits "
                            + cliVersion + ")");
                    Boot.must(boot.docker.buildSeedImage(image(WORKSPACE_BASE_IMAGE, version),
                                    dockerfile, tree, List.of("QITS_CLI_VERSION=" + cliVersion),
                                    Map.of(), List.of(base), layout(WORKSPACE_BASE_IMAGE, version),
                                    null, ctx::log),
                            "the build of " + image(WORKSPACE_BASE_IMAGE, version) + " failed");
                } finally {
                    removeTree("oci-workspace", tree);
                }
                tag(ctx, image(WORKSPACE_BASE_IMAGE, version),
                        List.of(vhost(WORKSPACE_BASE_IMAGE, version)));
                built.add(version);
            }
            if (built.isEmpty()) {
                ctx.skip("held: " + String.join(", ", new java.util.TreeSet<>(closure.bases().keySet())));
            }
            ctx.note(String.join(", ", built));
        });
    }

    /**
     * <b>{@code qits/workspace} at every version a consumer pins, from qits-workspace-daemon's
     * tag</b>: the daemon's native compile out of the {@code qits-maven-seed} volume, on the
     * workspace base this run exported. Tagged under the public registry name the consumers start
     * it by, so a target finds it on this host and pulls nothing, and under the registry vhost for
     * {@code images-publish}.
     */
    public Phase workspaceImageSeed() {
        return new Phase("seed-workspace", "build the workspace images the platform starts", ctx -> {
            WorkspaceImages closure = workspaceImages();
            List<String> built = new ArrayList<>();
            for (Map.Entry<String, String> entry : new java.util.TreeMap<>(closure.workspaces())
                    .entrySet()) {
                String version = entry.getKey();
                String image = image(WORKSPACE_IMAGE, version);
                List<String> tags = List.of(vhost(WORKSPACE_IMAGE, version),
                        publicName(WORKSPACE_IMAGE, version));
                if (boot.docker.imageExists(image)) {
                    tag(ctx, image, tags);
                    ctx.log("  " + image + ": held");
                    continue;
                }
                String origin = PlatformModel.repo("workspace-daemon") + "@" + version + "/"
                        + WORKSPACE_DOCKERFILE;
                Docker.LocalBase base = localBase(WORKSPACE_BASE_IMAGE, entry.getValue(),
                        "seed-workspace-base");
                String dockerfile = SeedDockerfile.refusePlatformBases(SeedDockerfile.localBase(
                        SeedDockerfile.localMaven(SeedDockerfile.rewrite(boot.git.fileAt(
                                boot.state.repoDir("workspace-daemon"), version,
                                WORKSPACE_DOCKERFILE)), origin),
                        origin, "WORKSPACE_BASE", base.reference()), origin);
                Path tree = exportTree(ctx, "workspace-daemon", version);
                try {
                    mavenPreflight(ctx, "workspace-daemon@" + version, tree);
                    ctx.status("cold GraalVM native build of " + image + " — ~4 GB RAM");
                    Boot.must(boot.docker.buildSeedImage(image, dockerfile, tree, List.of(),
                                    Map.of(SeedDockerfile.SEED_M2_CONTEXT, SEED_REPO_VOLUME),
                                    List.of(base), null, null, ctx::log),
                            "the build of " + image + " failed");
                } finally {
                    removeTree("workspace-daemon", tree);
                }
                tag(ctx, image, tags);
                built.add(version);
            }
            if (built.isEmpty()) {
                ctx.skip("held: " + String.join(", ", new java.util.TreeSet<>(closure.workspaces().keySet())));
            }
            ctx.note(String.join(", ", built));
        });
    }

    /**
     * <b>{@code qits/project-agent} at the version qits-projects pins, and the
     * {@code qits/projects-daemon} it is layered from</b>, from qits-projects-daemon's tag — the
     * release's own two builds: the daemon's native image first (exported for the second), then
     * {@code docker/Dockerfile.projects}, which copies its binary onto the workspace base.
     */
    public Phase projectAgentSeed() {
        return new Phase("seed-project-agent", "build the project agent images the platform starts",
                ctx -> {
            WorkspaceImages closure = workspaceImages();
            List<String> built = new ArrayList<>();
            for (Map.Entry<String, String> entry : new java.util.TreeMap<>(closure.agents())
                    .entrySet()) {
                String version = entry.getKey();
                String agent = image(AGENT_IMAGE, version);
                List<String> agentTags = List.of(vhost(AGENT_IMAGE, version),
                        publicName(AGENT_IMAGE, version));
                if (baseHeld(PROJECTS_DAEMON_IMAGE, version) && boot.docker.imageExists(agent)) {
                    tag(ctx, image(PROJECTS_DAEMON_IMAGE, version),
                            List.of(vhost(PROJECTS_DAEMON_IMAGE, version)));
                    tag(ctx, agent, agentTags);
                    ctx.log("  " + agent + ": held");
                    continue;
                }
                Path repo = boot.state.repoDir("projects-daemon");
                String daemonOrigin = PlatformModel.repo("projects-daemon") + "@" + version
                        + "/docker/Dockerfile";
                String agentOrigin = PlatformModel.repo("projects-daemon") + "@" + version + "/"
                        + AGENT_DOCKERFILE;
                Docker.LocalBase base = localBase(WORKSPACE_BASE_IMAGE, entry.getValue(),
                        "seed-workspace-base");
                String daemonFile = SeedDockerfile.refusePlatformBases(SeedDockerfile.localMaven(
                        SeedDockerfile.rewrite(boot.git.fileAt(repo, version, "docker/Dockerfile")),
                        daemonOrigin), daemonOrigin);
                String daemon = image(PROJECTS_DAEMON_IMAGE, version);
                Path tree = exportTree(ctx, "projects-daemon", version);
                try {
                    if (!baseHeld(PROJECTS_DAEMON_IMAGE, version)) {
                        mavenPreflight(ctx, "projects-daemon@" + version, tree);
                        ctx.status("cold GraalVM native build of " + daemon + " — ~4 GB RAM");
                        Boot.must(boot.docker.buildSeedImage(daemon, daemonFile, tree, List.of(),
                                        Map.of(SeedDockerfile.SEED_M2_CONTEXT, SEED_REPO_VOLUME),
                                        List.of(), layout(PROJECTS_DAEMON_IMAGE, version), null,
                                        ctx::log),
                                "the build of " + daemon + " failed");
                    }
                    Docker.LocalBase daemonBase = localBase(PROJECTS_DAEMON_IMAGE, version,
                            "this phase's daemon build");
                    String agentFile = SeedDockerfile.refusePlatformBases(SeedDockerfile
                            .refuseServers(SeedDockerfile.localBase(SeedDockerfile.localBase(
                                    SeedDockerfile.rewrite(boot.git.fileAt(repo, version,
                                            AGENT_DOCKERFILE)), agentOrigin, "BASE",
                                    base.reference()), agentOrigin, "DAEMON_IMAGE",
                                    daemonBase.reference()), agentOrigin), agentOrigin);
                    ctx.status("building " + agent);
                    Boot.must(boot.docker.buildSeedImage(agent, agentFile, tree, List.of(),
                                    Map.of(), List.of(base, daemonBase), null, null, ctx::log),
                            "the build of " + agent + " failed");
                } finally {
                    removeTree("projects-daemon", tree);
                }
                tag(ctx, daemon, List.of(vhost(PROJECTS_DAEMON_IMAGE, version)));
                tag(ctx, agent, agentTags);
                built.add(version);
            }
            if (built.isEmpty()) {
                ctx.skip("held: " + String.join(", ", new java.util.TreeSet<>(closure.agents().keySet())));
            }
            ctx.note(String.join(", ", built));
        });
    }

    // --- the store, filled once the idp exists (qits-889) ---------------------------------------

    /** What {@link #storeSeed} uploads: this platform's own group, the whole subtree of it. */
    static final String STORE_SEED_SUBTREE = QITS_GROUP_PATH;

    /**
     * <b>THE STORE IS FILLED HERE AND NOWHERE ELSE IN THE BOOT: the whole {@code eu/wohlben/qits}
     * subtree of the {@link #SEED_REPO_VOLUME} file repository, uploaded into the stack's
     * qits-artifacts under the commissioned {@code bootstrap-publish} credential.</b>
     * <p>
     * Phase 1 builds and packs every qits artifact a seed build resolves into the volume and
     * publishes nothing: no store or mirror runs before the idp, so nothing could commission the
     * credential the store's publish door demands ("only CI may publish", user ruling 2026-09-13 —
     * the bootstrap is the one exception, and it publishes as {@code qits:ci-run} or not at all).
     * What phase 4's release replays need from the store is the same set — the libraries at every
     * pinned version, golden masters, pacts (a provider replay resolves them), the protocols, the
     * runner, the musl toolchain tarballs — so it is uploaded from the volume rather than rebuilt.
     * <p>
     * <b>ONE container, curl, one {@code PUT} per file</b>, the shape {@code mvn deploy} gives the
     * store's Maven surface ({@code MavenRoutes} in qits-registries): a dumb path store whose
     * checksums are verified against the stored blob and whose {@code maven-metadata.xml} is
     * derived per request — a client's metadata PUT is accepted and discarded, so this sends none.
     * The bearer rides on every request, PREEMPTIVELY: the store answers an uncredentialed PUT with
     * a plain 401 and no challenge, so a client that waits to be asked never authenticates.
     * <p>
     * <b>Idempotent by asking first.</b> A release path is immutable: identical bytes re-PUT are a
     * 201, different bytes a 403 — and a seed jar is rebuilt each run, so its bytes differ. So every
     * file is asked for with a {@code HEAD} first, and one the store answers 200 for is left alone
     * with its checksums; a 404 is uploaded; any other answer stops the phase. That is every rerun,
     * and on a live platform it is every released coordinate, which is not this run's to replace.
     * Within a version the POMs go last, the order {@code mvn deploy} uses, because the pom is the
     * marker a presence check reads as "published".
     * <p>
     * In both arms of the plan: a warm rerun uploads whatever the volume still holds that the
     * store lacks, which is nothing on a healthy platform.
     */
    public Phase storeSeed() {
        return new Phase("store-seed",
                "upload the file repository's qits artifacts into the store", ctx -> {
            boot.docker.ensureVolume(SEED_REPO_VOLUME, ctx::log);
            String authorization = boot.publishAuthorization(ctx);
            String base = boot.config.artifactsUrl() + "/maven/maven";
            ctx.status("uploading " + SEED_REPO_VOLUME + ":/" + STORE_SEED_SUBTREE + " to " + base);
            String cid = create(ctx, storeSeedCommand(base, authorization), authorization);
            ProcessResult run = boot.docker.exec(Docker.BUILD_TIMEOUT, ctx::log, "start", "-a",
                    cid);
            boot.docker.removeContainer(cid, null);
            if (!run.ok()) {
                throw new IllegalStateException("uploading the file repository into the store "
                        + "failed\n" + run.tailText(20));
            }
            String summary = Boot.lastWords(run);
            if (summary.contains(STORE_SEED_EMPTY)) {
                ctx.warn(SEED_REPO_VOLUME + " holds nothing under " + STORE_SEED_SUBTREE
                        + " — the store is left as it is");
            }
            ctx.note(summary.replaceFirst("^store-seed: ", ""));
        });
    }

    /** What the upload script says when the volume has nothing to give. */
    static final String STORE_SEED_EMPTY = "nothing under " + STORE_SEED_SUBTREE;

    /** The container: on qits-net, the volume read-only, the script. */
    static List<String> storeSeedCommand(String base, String authorization) {
        return List.of("docker", "create", "--network", Boot.NETWORK, "--user", "root",
                "--entrypoint", "sh", "-v", SEED_REPO_VOLUME + ":/repo:ro",
                "maven:3.9-eclipse-temurin-25", "-c", storeSeedScript(base, authorization));
    }

    /**
     * The upload, as a POSIX shell script. The header goes into a file curl reads with
     * {@code -H @file}, so the bearer is on no command line inside the container either.
     *
     * @param base          the store's Maven repository, {@code <artifactsUrl>/maven/maven}
     * @param authorization the {@code Authorization} value every request presents
     */
    static String storeSeedScript(String base, String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw new IllegalArgumentException("the store is never published to bare");
        }
        String checksums = "md5 sha1 sha256 sha512";
        StringBuilder skip = new StringBuilder(" ! -name 'maven-metadata*'");
        for (String sum : checksums.split(" ")) {
            skip.append(" ! -name '*.").append(sum).append("'");
        }
        skip.append(" ! -name '*.lastUpdated' ! -name '_remote.repositories'");
        return "set -eu\n"
                + "base='" + base + "'\n"
                + "cat > /tmp/auth <<'AUTH'\n"
                + "Authorization: " + authorization + "\n"
                + "AUTH\n"
                + "cd /repo\n"
                + "if [ ! -d " + STORE_SEED_SUBTREE + " ]; then echo 'store-seed: "
                + STORE_SEED_EMPTY + "'; exit 0; fi\n"
                // Poms last, as mvn deploy orders them: the pom is the published marker.
                + "find " + STORE_SEED_SUBTREE + " -type f" + skip + " ! -name '*.pom' | sort"
                + " > /tmp/files\n"
                + "find " + STORE_SEED_SUBTREE + " -type f -name '*.pom' | sort >> /tmp/files\n"
                + "put() {\n"
                + "  curl -sS --fail-with-body -o /tmp/answer -T \"$1\" -H @/tmp/auth"
                + " \"$base/$1\" || { echo \"PUT $1 was refused: $(cat /tmp/answer 2>/dev/null)\""
                + " >&2; exit 1; }\n"
                + "}\n"
                + "uploaded=0\n"
                + "held=0\n"
                + "while IFS= read -r f; do\n"
                + "  code=$(curl -sS -o /dev/null -w '%{http_code}' -I -H @/tmp/auth \"$base/$f\""
                + " || true)\n"
                + "  case \"$code\" in\n"
                + "    200) held=$((held + 1)); continue ;;\n"
                + "    404) ;;\n"
                + "    *) echo \"asking the store for $f answered $code\" >&2; exit 1 ;;\n"
                + "  esac\n"
                + "  put \"$f\"\n"
                + "  for sum in " + checksums + "; do\n"
                + "    if [ -f \"$f.$sum\" ]; then put \"$f.$sum\"; fi\n"
                + "  done\n"
                + "  uploaded=$((uploaded + 1))\n"
                + "done < /tmp/files\n"
                + "echo \"store-seed: $uploaded uploaded, $held already held\"\n";
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
            "qits_projects", "qits_epics", "qits_projects_eventstream",
            "qits_containers", "qits_containers_eventstream",
            "qits_platform_edge", "qits_platform_edge_eventstream",
            "qits_system", "qits_system_eventstream",
            "qits_workspaces", "qits_workspaces_eventstream");

    /**
     * The platform's postgres, running with a password of this bootstrap's choosing from its very
     * first boot, and every database a seed container boots against.
     * <p>
     * <b>Before the first container that refuses to boot without a database, whichever one that
     * is.</b> That is the idp, which {@code seed-idp} brings up alone before the compose file is
     * written. The build arm runs this among its image builds; on {@code QITS_SKIP_BUILD} there is
     * no image build, and it runs where it always did — the passwords it resolves fill the stack
     * file and the settings either way. That is why {@code BootstrapPlan} places it in each arm
     * rather than once after both: one run, one placement, and the right one for each.
     * <p>
     * <b>The image's own {@code POSTGRES_PASSWORD=qits-poc} never lives on this platform.</b> The
     * value below is generated on the first boot and recorded, and initdb takes it instead.
     * <p>
     * <b>Which databases are here, and which are deliberately not.</b> This phase provisions what
     * the SEED STACK needs: the deployer's own store AND ITS OUTBOX, and the stores of the core
     * services that come up beside it — qits-ci (its database and its outbox), qits-platform-idp,
     * qits-events, qits-artifacts, qits-platform-mirror, qits-githost (its
     * database and its outbox), qits-projects (three: its own, the epics store beside it and its
     * outbox) and qits-containers (the same pair). Five of them are
     * outboxes because the
     * eventstream library keeps its own Flyway lineage and cannot share a database with its host; ci
     * carried one from the start, the deployer joined the bus on 2026-08-10, and the git host and
     * the orchestrator are the newest publishers on it.
     * Every one of them runs Flyway at boot against a database that has to exist
     * already, and at that point in a cold boot no deployer exists to make one.
     * qits-workspaces joined on 2026-10-30 (qits-889) with a pair, on the same terms; so did
     * qits-configuration, until its retirement (qits-1396) took it out of the seed again.
     * Everything else — observability, stt, docs and the rest — is pipeline-deployed only, so the
     * deployer creates their roles and databases during their own deployments from the
     * {@code resources:} line in each repository's deployments.yml. Adding them here would put a
     * second authority on a credential that has exactly one.
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
            String projects = pgPassword(ctx, state, "PG_PROJECTS_PASSWORD",
                    "qits.pg.projects-password");
            String epics = pgPassword(ctx, state, "PG_EPICS_PASSWORD", "qits.pg.epics-password");
            String projectsEventstream = pgPassword(ctx, state,
                    "PG_PROJECTS_EVENTSTREAM_PASSWORD",
                    "qits.pg.projects-eventstream-password");
            String containers = pgPassword(ctx, state, "PG_CONTAINERS_PASSWORD",
                    "qits.pg.containers-password");
            String containersEventstream = pgPassword(ctx, state,
                    "PG_CONTAINERS_EVENTSTREAM_PASSWORD",
                    "qits.pg.containers-eventstream-password");
            String platformEdge = pgPassword(ctx, state, "PG_PLATFORM_EDGE_PASSWORD",
                    "qits.pg.platform-edge-password");
            String platformEdgeEventstream = pgPassword(ctx, state,
                    "PG_PLATFORM_EDGE_EVENTSTREAM_PASSWORD",
                    "qits.pg.platform-edge-eventstream-password");
            String system = pgPassword(ctx, state, "PG_SYSTEM_PASSWORD",
                    "qits.pg.system-password");
            String systemEventstream = pgPassword(ctx, state, "PG_SYSTEM_EVENTSTREAM_PASSWORD",
                    "qits.pg.system-eventstream-password");
            String workspaces = pgPassword(ctx, state, "PG_WORKSPACES_PASSWORD",
                    "qits.pg.workspaces-password");
            String workspacesEventstream = pgPassword(ctx, state,
                    "PG_WORKSPACES_EVENTSTREAM_PASSWORD",
                    "qits.pg.workspaces-eventstream-password");
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
            boot.state.pgProjectsPassword = projects;
            boot.state.pgEpicsPassword = epics;
            boot.state.pgProjectsEventstreamPassword = projectsEventstream;
            boot.state.pgContainersPassword = containers;
            boot.state.pgContainersEventstreamPassword = containersEventstream;
            boot.state.pgPlatformEdgePassword = platformEdge;
            boot.state.pgPlatformEdgeEventstreamPassword = platformEdgeEventstream;
            boot.state.pgSystemPassword = system;
            boot.state.pgSystemEventstreamPassword = systemEventstream;
            boot.state.pgWorkspacesPassword = workspaces;
            boot.state.pgWorkspacesEventstreamPassword = workspacesEventstream;

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
            state.put("PG_PROJECTS_PASSWORD", projects);
            state.put("PG_EPICS_PASSWORD", epics);
            state.put("PG_PROJECTS_EVENTSTREAM_PASSWORD", projectsEventstream);
            state.put("PG_CONTAINERS_PASSWORD", containers);
            state.put("PG_CONTAINERS_EVENTSTREAM_PASSWORD", containersEventstream);
            state.put("PG_PLATFORM_EDGE_PASSWORD", platformEdge);
            state.put("PG_PLATFORM_EDGE_EVENTSTREAM_PASSWORD", platformEdgeEventstream);
            state.put("PG_SYSTEM_PASSWORD", system);
            state.put("PG_SYSTEM_EVENTSTREAM_PASSWORD", systemEventstream);
            state.put("PG_WORKSPACES_PASSWORD", workspaces);
            state.put("PG_WORKSPACES_EVENTSTREAM_PASSWORD", workspacesEventstream);
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
            projects = registry.getOrDefault("qits_projects", projects);
            epics = registry.getOrDefault("qits_epics", epics);
            projectsEventstream =
                    registry.getOrDefault("qits_projects_eventstream", projectsEventstream);
            containers = registry.getOrDefault("qits_containers", containers);
            containersEventstream =
                    registry.getOrDefault("qits_containers_eventstream", containersEventstream);
            platformEdge = registry.getOrDefault("qits_platform_edge", platformEdge);
            platformEdgeEventstream =
                    registry.getOrDefault("qits_platform_edge_eventstream", platformEdgeEventstream);
            system = registry.getOrDefault("qits_system", system);
            systemEventstream = registry.getOrDefault("qits_system_eventstream", systemEventstream);
            workspaces = registry.getOrDefault("qits_workspaces", workspaces);
            workspacesEventstream = registry.getOrDefault("qits_workspaces_eventstream",
                    workspacesEventstream);
            if (!registry.isEmpty()) {
                boot.state.pgCiPassword = ci;
                boot.state.pgCiEventstreamPassword = ciEventstream;
                boot.state.pgPlatformIdpPassword = platformIdp;
                boot.state.pgEventsPassword = events;
                boot.state.pgArtifactsPassword = artifacts;
                boot.state.pgPlatformMirrorPassword = platformMirror;
                boot.state.pgGithostPassword = githost;
                boot.state.pgGithostEventstreamPassword = githostEventstream;
                boot.state.pgProjectsPassword = projects;
                boot.state.pgEpicsPassword = epics;
                boot.state.pgProjectsEventstreamPassword = projectsEventstream;
                boot.state.pgContainersPassword = containers;
                boot.state.pgContainersEventstreamPassword = containersEventstream;
                boot.state.pgPlatformEdgePassword = platformEdge;
                boot.state.pgPlatformEdgeEventstreamPassword = platformEdgeEventstream;
                boot.state.pgSystemPassword = system;
                boot.state.pgSystemEventstreamPassword = systemEventstream;
                boot.state.pgWorkspacesPassword = workspaces;
                boot.state.pgWorkspacesEventstreamPassword = workspacesEventstream;
                state.put("PG_CI_PASSWORD", ci);
                state.put("PG_CI_EVENTSTREAM_PASSWORD", ciEventstream);
                state.put("PG_PLATFORM_IDP_PASSWORD", platformIdp);
                state.put("PG_EVENTS_PASSWORD", events);
                state.put("PG_ARTIFACTS_PASSWORD", artifacts);
                state.put("PG_PLATFORM_MIRROR_PASSWORD", platformMirror);
                state.put("PG_GITHOST_PASSWORD", githost);
                state.put("PG_GITHOST_EVENTSTREAM_PASSWORD", githostEventstream);
                state.put("PG_PROJECTS_PASSWORD", projects);
                state.put("PG_EPICS_PASSWORD", epics);
                state.put("PG_PROJECTS_EVENTSTREAM_PASSWORD", projectsEventstream);
                state.put("PG_CONTAINERS_PASSWORD", containers);
                state.put("PG_CONTAINERS_EVENTSTREAM_PASSWORD", containersEventstream);
                state.put("PG_PLATFORM_EDGE_PASSWORD", platformEdge);
                state.put("PG_PLATFORM_EDGE_EVENTSTREAM_PASSWORD", platformEdgeEventstream);
                state.put("PG_SYSTEM_PASSWORD", system);
                state.put("PG_SYSTEM_EVENTSTREAM_PASSWORD", systemEventstream);
                state.put("PG_WORKSPACES_PASSWORD", workspaces);
                state.put("PG_WORKSPACES_EVENTSTREAM_PASSWORD", workspacesEventstream);
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
                // The mirror and the hosted store come up with the seed stack, and store-seed fills
                // the store straight after it is healthy. The git host
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
                // THE ALIAS TABLE'S OWNER, a seed service since 2026-08-21 and a THREE-database one
                // — the only one here. Its own store, the epics store beside it and the eventstream
                // outbox are three Flyway lineages, and its deployments.yml declares all three
                // (`postgresql:db, postgresql:epics:qits_epics,
                // postgresql:eventstream:qits_projects_eventstream`). Every name it is asked to
                // resolve is a row in the first, so a boot with these missing is a boot where no
                // clone url resolves at all.
                provision(ctx, admin, "qits_projects", projects, false);
                provision(ctx, admin, "qits_epics", epics, false);
                provision(ctx, admin, "qits_projects_eventstream", projectsEventstream, false);
                // THE ORCHESTRATOR, a seed service since 2026-08-11 and a two-database one for the
                // same reason ci and the git host are: its registry of rows and the eventstream
                // outbox are two Flyway lineages and cannot share a database. Both names are the
                // deployer's own derivation from the application name, so the rows it registers on
                // the first pipeline deployment are the rows this creates.
                provision(ctx, admin, "qits_containers", containers, false);
                provision(ctx, admin, "qits_containers_eventstream", containersEventstream, false);
                provision(ctx, admin, "qits_platform_edge", platformEdge, false);
                provision(ctx, admin, "qits_platform_edge_eventstream", platformEdgeEventstream,
                        false);
                // THE TARGETS' STORE AND ITS OUTBOX, in the seed since qits-1547: every CI run is
                // a lease on a qits-system target now, so qits-system boots from the seed stack
                // before the first release replay and refuses to boot without either. The names
                // are the deployer's own derivation from `resources: postgresql:db,
                // postgresql:eventstream:qits_system_eventstream`, so the rows it registers on the
                // first pipeline deployment are the rows this creates.
                provision(ctx, admin, "qits_system", system, false);
                provision(ctx, admin, "qits_system_eventstream", systemEventstream, false);
                // THE SERVICE PHASE 1 GAINED IN qits-889, on the same terms: it boots from the
                // seed stack before any deployer exists, and declares a domain store and the
                // eventstream outbox (`postgresql:db, postgresql:eventstream:<app>_eventstream`)
                // — so the rows the deployer registers at its first deployment are the rows this
                // creates.
                provision(ctx, admin, "qits_workspaces", workspaces, false);
                provision(ctx, admin, "qits_workspaces_eventstream", workspacesEventstream, false);
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

    // --- secrets, compose, credentials ----------------------------------------------------------------

    /**
     * <b>THE ONE CREDENTIAL THIS PROGRAM STILL RESOLVES FOR ITSELF.</b>
     * <p>
     * It used to resolve one per platform client and write them all into the seed stack. It does
     * not: a secret in a generated file is a second copy of a credential the idp already holds, and
     * the two drift the first time either side is edited. Every other client is CREATED against the
     * running idp by {@link #idpClients()} and its secret recorded in qits-deployments'
     * {@code pd_resource} registry, which is the authority for an application credential anyway.
     * <p>
     * <b>This one cannot be, because it is the credential the creating is done WITH.</b> The idp
     * seeds its first database service client from {@code QITS_IDP_SEED_CLIENT_ID} /
     * {@code _SECRET} at its first start, so the bootstrap's pair has to exist BEFORE the idp does
     * — which is why this phase is above {@code seed-idp} rather than beside the clients it makes.
     * <p>
     * Precedence unchanged in spirit: an explicit override, else what a previous run recorded, else
     * a fresh random. The recorded arm is what makes a rerun keep working — the idp's seed is
     * once-per-installation (an {@code idp_seed} marker row, not the variables), so a boot that
     * generated a new secret would present one the standing idp never accepted.
     * <p>
     * The ORIGIN reaches the screen. The value never does.
     */
    public Phase idpBootstrapClient() {
        return new Phase("idp-bootstrap-client",
                "resolve the bootstrap's idp client and record the run state", ctx -> {
                    String client = PlatformModel.bootstrapClientId(boot.config.envName());
                    BootstrapState state = new BootstrapState(
                            boot.state.wrapperDir.resolve(BootstrapState.FILE_NAME));
                    state.read();
                    Optional<String> given = ConfigProvider.getConfig()
                            .getOptionalValue("qits.idp.client." + client + ".secret", String.class)
                            .filter(value -> !value.isBlank());
                    Optional<String> kept = state.bootstrapSecret(client);
                    String origin;
                    String value;
                    if (given.isPresent()) {
                        value = given.get();
                        origin = "given";
                    } else if (kept.isPresent()) {
                        value = kept.get();
                        origin = "kept";
                    } else {
                        value = randomSecret();
                        origin = "generated";
                    }
                    boot.state.bootstrapClientId = client;
                    boot.state.bootstrapSecret = value;
                    ctx.log(String.format("  %-24s %s", client, origin));
                    state.write(boot.state.daemonSha, client, value);
                    ctx.log("  recorded in " + state.file());
                    ctx.note(origin);
                });
    }

    /**
     * <b>THE SEED SERVICES' IDP CLIENTS, created against the idp that is already running.</b>
     * <p>
     * Five applications ({@link PlatformModel#SEED_IDP_CLIENT_APPS}) and one act each: find a
     * working secret, or make one. Everything else on this platform declares an {@code idp:client}
     * resource and is handed its triple by qits-deployments at deploy time; these five are up
     * before there is a deployer to hand them anything, so the bootstrap stands in — and it stands
     * in by writing the SAME registry row the deployer would, so the deployer inherits the
     * credential rather than rotating it out from under a running service on its first sweep.
     * <p>
     * <b>The order of the three arms is what makes a rerun safe.</b>
     * <ul>
     *   <li><b>Recorded and it still works</b> — the registry row is the authority, and a token
     *       minted with it is the only proof that it is current. The probe is not ceremony: the
     *       idp stores a HASH, so a row that has gone stale looks exactly like one that has not.
     *   <li><b>Create</b> — a first boot, where the idp knows nothing about this client.
     *   <li><b>409, then rotate</b> — the client is there and its secret is unrecoverable. Rotating
     *       is the only way back to a usable pair, which is why a conflict is a step in this phase
     *       and never a failure of it.
     * </ul>
     * <p>
     * <b>A registry that is not there yet does not stop the boot.</b> On a cold machine
     * qits-deployments' Flyway has not run, so {@link PgAdmin#recordIdpClient} has no table to
     * write to. The secret is still good for this run — the seed containers are started with what
     * this phase resolved — and the next run finds no row, creates, meets the 409 and rotates. The
     * log says so rather than leaving a person to work it out.
     * <p>
     * <b>Three spellings of one identity, and only one of them follows the plane.</b> The registry
     * row is keyed by the APPLICATION and the TIER — the tier for every application, platform
     * services included, because that is what qits-deployments keys a {@code pd_resource} row by
     * and what its {@code ResourceProvisioning} refuses a deployment for lacking. The CLIENT ID in
     * that row is the WIRE ALIAS, which is bare for a platform service and qualified for an
     * environment one. Writing the tier into the alias, or the plane into the key, is a row the
     * deployer cannot find and a second credential rotated over a running container.
     * <p>
     * One aligned line per client, with the client id and the outcome. No secret reaches the
     * screen, the run log or {@code .qits-bootstrap.env}.
     */
    public Phase idpClients() {
        return new Phase("idp-clients", "create the seed services' idp clients", ctx -> {
            String env = boot.config.envName();
            ServiceClientsApi clients = new ServiceClientsApi(boot.http, boot.config.idpDialUrl(),
                    boot.state.bootstrapClientId, boot.state.bootstrapSecret);
            // The registry, read ONCE: one connection for five questions, and every row is
            // answered from the same snapshot.
            String registryUrl = deploymentsRegistryUrl();
            Map<String, PgAdmin.IdpRow> recorded = PgAdmin.recordedIdpClients(registryUrl,
                    "postgres", orEmpty(boot.state.pgSuperuserPassword), env);
            ctx.log("  " + recorded.size() + " idp client"
                    + (recorded.size() == 1 ? "" : "s") + " already in the deployer's registry");
            boolean registryWrote = true;
            for (String app : PlatformModel.SEED_IDP_CLIENT_APPS) {
                String clientId = PlatformModel.wireAlias(app, env);
                String application = PlatformModel.application(app);
                PgAdmin.IdpRow row = recorded.get(application);
                String secret = null;
                String outcome;
                if (row != null && clientId.equals(row.clientId()) && probes(clientId, row.secret())) {
                    secret = row.secret();
                    outcome = "reused";
                } else {
                    ServiceClientsApi.Result made = clients.create(clientId);
                    outcome = "created";
                    if (made.conflict()) {
                        // The row is there and its secret is a hash. Rotating is the repair, and
                        // the only one: nothing anywhere can read the old value back.
                        made = clients.rotate(clientId);
                        outcome = "rotated";
                    }
                    if (!made.ok()) {
                        throw new IllegalStateException("the idp issued no secret for " + clientId
                                + ": " + made.detail());
                    }
                    secret = made.secret();
                    // Recorded the moment it arrives, before anything else can fail: a secret this
                    // run holds and never wrote down is a credential nobody can recover.
                    // THE TIER, for every one of the five. A registry row's key is the
                    // environment's name and there is always one: a platform service is deployed
                    // into the designated environment like anything else since qits-deployments'
                    // V8 rekey, and its ResourceProvisioning REFUSES a deployment that names no
                    // environment. What IS plane-dependent is the client id above, which
                    // PlatformModel.wireAlias already answers.
                    if (!PgAdmin.recordIdpClient(registryUrl, "postgres",
                            orEmpty(boot.state.pgSuperuserPassword), application,
                            env, clientId, secret)) {
                        registryWrote = false;
                    }
                }
                boot.state.serviceClientSecrets.put(application, secret);
                ctx.log(String.format("  %-28s %s", clientId, outcome));
            }
            if (!registryWrote) {
                ctx.log("  qits-deployments' pd_resource registry does not exist yet — its Flyway "
                        + "has not run on this machine, so nothing was recorded. The seed starts "
                        + "with what this phase resolved, and the next run finds no row, creates, "
                        + "meets the 409 and rotates into a fresh one.");
            }
            ctx.note(PlatformModel.SEED_IDP_CLIENT_APPS.size() + " clients");
        });
    }

    /**
     * Whether a recorded secret is still the one the idp accepts. The service stores a hash, so
     * there is no cheaper question than asking for a token — {@code GET /service-clients/<id>} says
     * only that a row exists, which a stale secret says too.
     * <p>
     * A refusal is an ANSWER: {@link eu.wohlben.qits.cli.bootstrap.api.IdpApi#token} throws on one,
     * and here that means "rotate", not "stop".
     */
    private boolean probes(String clientId, String secret) {
        try {
            return !boot.idp.token(clientId, secret, Boot.PLATFORM_AUDIENCE).isBlank();
        } catch (RuntimeException refused) {
            return false;
        }
    }

    /**
     * The deployer's own database on the seed postgres, as an admin url. The same server
     * {@code seed-postgres} started and the same superuser credential it resolved — this phase
     * invents no second source for either, because two sources are two clusters the day one moves.
     */
    private String deploymentsRegistryUrl() {
        return "jdbc:postgresql://"
                + PlatformModel.wireAlias("oci-postgresql", boot.config.envName())
                + ":5432/qits_deployments";
    }


    // --- the one host rule this platform cannot run without ---------------------------------------

    /**
     * <b>The edge's port must REFUSE an IPv6 connection on this host's loopback, and this phase
     * makes it refuse.</b>
     * <p>
     * The byte plane is reached at {@code <app>.<env>.localhost}, and a resolver answers
     * {@code ::1} for such a name FIRST. The edge publishes its port in swarm INGRESS mode and the
     * routing mesh is IPv4-only: the v6 listener accepts the connection and then serves nothing, so
     * curl, docker, git, maven and npm all HANG instead of failing over to IPv4. A host-mode
     * publish never showed this — docker-proxy bound both families — which is why it arrives with
     * the mode.
     * <p>
     * <b>It hangs with no error, and that is what makes it expensive.</b> On 2026-09-02 a release
     * run's SBOM upload — one {@code curl -X PUT} to {@code registry.<env>.localhost} from a CI
     * step on the host network — sat on {@code [::1]:<port>} for over forty minutes and blocked the
     * whole CI queue behind it. On 2026-09-05 the same landmine timed out a CI step's
     * {@code docker push} on a cold node, an hour into a boot. Nothing timed out early and nothing
     * said anything.
     * <p>
     * The fix is one host rule, and the RESET is the load-bearing half: it makes every client fall
     * back at once, where a DROP would leave them hanging exactly as the accept does.
     * <p>
     * <b>Installed UNCONDITIONALLY rather than when a probe finds the bad state.</b> This runs in
     * the plan's first minutes and the swarm ingress does not exist until the seed stack is
     * deployed, forty phases later — so nothing is accepting v6 on that port yet and a
     * probe-and-repair would find nothing to do and leave the landmine armed for the run that
     * needs it. The rule is idempotent by {@code -C} rather than by counting, so a rerun adds no
     * duplicate. <b>It does not survive a reboot</b>, which is why every run installs it rather
     * than trusting a past one's.
     * <p>
     * <b>The payload is a container, so it acts on the host through the daemon.</b> A throwaway
     * privileged helper in the host's PID, mount and network namespaces runs the HOST's own
     * {@code ip6tables} — {@code nsenter -t 1 -m -n} — because {@code ::1} inside a container is
     * that container's loopback and its netns has no rule table worth writing. The image is
     * {@code alpine:3} for its busybox {@code nsenter} and {@code nc}, not the payload image, which
     * is free to stop being alpine.
     * <p>
     * <b>A helper that cannot run STOPS the boot.</b> No privileged containers, or a host with no
     * {@code ip6tables}, means the landmine stays armed and the run finds out an hour later inside
     * a CI step. The failure names the one command to run by hand.
     */
    public Phase ipv6Loopback() {
        return new Phase("ipv6-loopback", "reset IPv6 loopback connections to the edge port",
                ctx -> {
                    int port = boot.config.port();
                    ctx.status("installing the ip6tables reject for [::1]:" + port);
                    ProcessResult result = boot.docker.run(Cmd.of(List.of(
                            "docker", "run", "--rm", "--privileged", "--pid=host",
                            "--network", "host", "alpine:3",
                            "nsenter", "-t", "1", "-m", "-n", "--",
                            "sh", "-c", ipv6LoopbackScript(port))), ctx::log);
                    if (!result.ok()) {
                        throw new IllegalStateException("the IPv6 loopback reject for port " + port
                                + " could not be installed (exit " + result.exitCode() + "). Every "
                                + "*.localhost name resolves to ::1 first and swarm's ingress mesh "
                                + "serves IPv4 only, so a client reaching the edge by name HANGS "
                                + "rather than failing over — a CI step measured forty minutes on "
                                + "one upload. Run this on the host and rerun:\n  sudo "
                                + String.join(" ", ip6tablesReject(port)) + "\n"
                                + result.tailText(10));
                    }
                    String outcome = result.captured().stream()
                            .filter(line -> line.contains(IPV6_MARKER))
                            .reduce((first, last) -> last).orElse("");
                    ctx.log("  the rule does not survive a reboot, so every run installs it");
                    ctx.note(outcome.contains("rule=present") ? "rule already there"
                            : "rule installed");
                });
    }

    /** What the helper prints its verdict under, so the note reads the answer and not the noise. */
    static final String IPV6_MARKER = "qits-ipv6:";

    /** The rule, in one place: what is installed and what the failure tells a person to run. */
    static List<String> ip6tablesReject(int port) {
        return List.of("ip6tables", "-I", "INPUT", "-i", "lo", "-p", "tcp",
                "--dport", String.valueOf(port), "-j", "REJECT", "--reject-with", "tcp-reset");
    }

    /**
     * Probe, install-if-absent, probe again. {@code -C} is what makes it idempotent — a rerun that
     * inserted blindly would stack a duplicate rule per boot — and the two probes are for the note
     * rather than for the decision: at this point in the plan nothing listens on that port yet.
     */
    static String ipv6LoopbackScript(int port) {
        String rule = "INPUT -i lo -p tcp --dport " + port
                + " -j REJECT --reject-with tcp-reset";
        return "probe() { nc -w 2 ::1 " + port + " </dev/null >/dev/null 2>&1"
                + " && echo accepts || echo silent; }\n"
                + "before=$(probe)\n"
                + "if ip6tables -C " + rule + " 2>/dev/null; then\n"
                + "  state=present\n"
                + "else\n"
                + "  ip6tables -I " + rule + " || exit 1\n"
                + "  state=installed\n"
                + "fi\n"
                + "after=$(probe)\n"
                + "echo \"" + IPV6_MARKER + " before=$before rule=$state after=$after\"\n";
    }

    /**
     * <b>Memory pressure must cost a workload, never the host.</b>
     * <p>
     * This platform builds, runs agents and runs workspaces on the same machine it serves from, and
     * a fresh host answers a memory squeeze by livelocking: the kernel's OOM killer never fires,
     * because with swap the host crawls to a halt before an allocation fails, and with none it
     * thrashes the page cache instead. Measured on 2026-09-11 — ping answered, ssh and the
     * provider's console both timed out, and the machine needed a hard reset. A failed CI run or a
     * killed agent is always better than that.
     * <p>
     * So the bootstrap owns the host setting that acts first: {@code systemd-oomd}, reading pressure
     * stall information per cgroup and killing a leaf before the host stops answering, plus the swap
     * its documentation asks for. {@link HostOomd} is what is written and why each piece is written.
     * <p>
     * <b>It APPLIES rather than reports, through the seam {@link #ipv6Loopback} opened.</b> The same
     * throwaway privileged helper in the host's namespaces, for the same reason: the payload is a
     * container, and a host setting that only lands when a person happened to start the run as root
     * is a host setting that is missing on the node that needed it. Writing {@code /etc} is more
     * than that phase does, and it is the same power — the helper is already root in the host's
     * mount namespace.
     * <p>
     * <b>It WARNS and never stops the boot</b>, which is where it parts company with the ipv6 rule.
     * That one is load-bearing: without it every client of a vhost hangs and the platform does not
     * work. This one is a host that survives its own worst hour — everything still comes up without
     * it, so a host this program cannot configure (no systemd, no apt-get, an oomd that will not
     * start) gets the gap named and the boot goes on. A host with no systemd at all is SKIPPED
     * rather than warned: there is nothing there to be missing.
     */
    public Phase hostOom() {
        return new Phase("host-oom", "systemd-oomd and swap, so pressure kills a workload", ctx -> {
            if (!boot.config.hostOom()) {
                ctx.skip("QITS_HOST_OOM=false");
            }
            ctx.status("configuring systemd-oomd and swap on the host");
            ProcessResult result = boot.docker.run(Cmd.of(List.of(
                    "docker", "run", "--rm", "--privileged", "--pid=host",
                    "--network", "host", "alpine:3",
                    "nsenter", "-t", "1", "-m", "-n", "--",
                    "sh", "-c", HostOomd.script())), ctx::log);
            if (!result.ok()) {
                ctx.warn("the host's memory-pressure setup could not be applied (exit "
                        + result.exitCode() + "), so this host still livelocks under pressure "
                        + "instead of losing one workload. The ticket's commands are in the closing "
                        + "report. " + result.tailText(5));
                return;
            }
            HostOomd.State state = HostOomd.read(result.captured());
            if (state.systemdless()) {
                ctx.skip("no systemd on this host");
            }
            ctx.log("  " + state.describe());
            if (!state.complete()) {
                ctx.warn("this host is not fully covered against memory pressure — "
                        + state.describe() + ". Wanted: systemd-oomd active, system.slice at "
                        + "ManagedOOMMemoryPressure=kill, the root slice at ManagedOOMSwap=kill, "
                        + "and swap on. The closing report spells the host steps; the boot goes on");
                return;
            }
            ctx.note(state.swap() + ", oomd on");
        });
    }

    /**
     * <b>Is the platform's own edge already the deployer's?</b> The same question {@code seed-stack}
     * asks of every seed service — see {@code PipelinePhases.seedPlan}: a service under the bare
     * wire alias, or a {@code qits-pd-} container — asked of the edge alone.
     */
    static boolean deployerManagedEdge(List<String> running, List<String> services, String env) {
        return PipelinePhases.seedPlan(running, services, env).managed()
                .contains(PlatformModel.wireAlias("edge", env));
    }

    /**
     * <b>A RERUN OVER A DEPLOYED EDGE STARTS NO BOOTSTRAP INGRESS — neither its state file nor its
     * container.</b> The ingress exists for the local-build step of a cold boot, and the edge's seed
     * deploy retires it; on a rerun past that point the deployer's edge holds the public door, and a
     * second one would bind 80/443 against it. The edge's seed deploy has moved to the middle of
     * the boot, so a rerun that finds it live is the common rerun, not the rare one.
     */
    private void standAsideForADeployedEdge(PhaseContext ctx) {
        if (!boot.config.bootstrapIngress()) {
            return;
        }
        String env = boot.config.envName();
        if (deployerManagedEdge(boot.docker.runningNames(), boot.docker.serviceNames(), env)) {
            ctx.skip("the deployer's " + PlatformModel.wireAlias("edge", env) + " is live and "
                    + "holds the public door — no bootstrap ingress beside it");
        }
    }

    /** Decide the ingress's mode and expiry before the compose file names its address. */
    public Phase bootstrapIngressPrepare() {
        return new Phase("bootstrap-ingress-prepare", "prepare the bootstrap-only ingress",
                ctx -> {
                    standAsideForADeployedEdge(ctx);
                    boot.ingress.prepare(ctx::log);
                    if (!boot.config.bootstrapIngress()) {
                        ctx.skip("QITS_BOOTSTRAP_INGRESS=0");
                    }
                });
    }

    /** Starts before the seed stack and never waits for or routes through the normal edge. */
    public Phase bootstrapIngressStart() {
        return new Phase("bootstrap-ingress", "start the temporary bootstrap ingress", ctx -> {
            standAsideForADeployedEdge(ctx);
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
     * <b>The three PULLERS' docker credentials</b>, as {@code config.json} on the config volume
     * each seed service mounts at {@code /work/config} and names with {@code DOCKER_CONFIG}. They
     * are LOAD-BEARING since the flip landed on 2026-08-14 — the edge refuses a read with no
     * credential — so a seed deployer, orchestrator or console without one pulls nothing.
     * <p>
     * <b>The deployer's volume used to hold its extras beside it</b>, a properties file the seed
     * deployer read at boot and imported into qits-configuration later. Both are gone (qits-765):
     * the deployer takes every deployment's environment from its settings store, which
     * {@code deployment-settings} fills once it answers, and each deployed successor writes its own
     * docker credential from the idp client it is injected — so these files only serve the seed.
     */
    public Phase dockerConfigs() {
        return new Phase("docker-configs", "write the seed pullers' docker credentials", ctx -> {
            boot.docker.ensureVolume("qits-deployments-config", ctx::log);
            boot.docker.ensureVolume("qits-containers-config", ctx::log);
            boot.docker.ensureVolume("qits-system-config", ctx::log);
            dockerConfig(ctx, "qits-deployments-config", "deployments",
                    boot.config.registryVhost());
            dockerConfig(ctx, "qits-containers-config", "containers",
                    boot.config.registryVhost());
            // THE THIRD PULLER, AND THE ONLY ONE WITH TWO HOSTS IN ITS FILE. The base system
            // panels pull nothing of the platform's own — what they pull is glances, an upstream
            // image behind qits-platform-mirror — so the mirror vhost is the host that matters
            // here. The registry is written beside it anyway: it costs one line and the day this
            // console pulls a qits/* image of its own is not the day to discover the file names
            // one host.
            dockerConfig(ctx, "qits-system-config", "system",
                    boot.config.registryVhost(), boot.config.mirrorVhost());
        });
    }

    /**
     * One puller's docker credential, as {@code config.json} on its config volume. The docker CLI
     * these three shell out to reads a FILE named by {@code DOCKER_CONFIG}, which is what both
     * generated files set on them — the containers run as uid 1001 with no home, so there is no
     * other place the CLI would look.
     * <p>
     * <b>The identity is the puller's own idp client</b>, never a borrowed one: a pull the registry
     * refuses has to name the service that was refused. The secret is the same generated value the
     * idp's own block is handed for that client — one value, recorded once in
     * {@code .qits-bootstrap.env}, read by both sides of one credential.
     * <p>
     * <b>The HOSTS are the caller's, because a puller's are.</b> The deployer and the container
     * orchestrator pull {@code qits/*} and nothing else, so their file names the registry vhost
     * alone; qits-platform-system pulls glances through the MIRROR vhost, and a file that named
     * only the registry would be an unauthenticated pull the edge refuses. One credential covers
     * both: it is the same client at the same door, and the door routes by name.
     * <p>
     * Rerun-safe by being a whole-file write: nothing restarts on a change, because the CLI opens
     * this file per invocation rather than at boot.
     */
    private void dockerConfig(PhaseContext ctx, String volume, String app, String... hosts) {
        String client = PlatformModel.wireAlias(app, boot.config.envName());
        // The value the idp-clients phase resolved, keyed by the APPLICATION because the registry
        // is. A puller that is not a seed client has none here and gets an empty one: its
        // credential arrives with its own deployment, out of the idp:client resource it declares,
        // and the file is rewritten then. Said out loud rather than written silently.
        String secret = boot.state.serviceClientSecrets
                .getOrDefault(PlatformModel.application(app), "");
        if (secret.isBlank()) {
            ctx.log("  " + client + " has no secret yet — its config.json is written empty and "
                    + "its own deployment fills it from the registry");
        }
        List<String> auths = List.of(hosts);
        Cmd write = dockerConfigWrite(volume, dockerConfigJson(auths, client, secret),
                client, secret);
        Boot.must(boot.docker.run(write, ctx::log), "writing " + client + "'s config.json failed");
        ctx.log("  " + client + " docker credential on the " + volume + " volume, for "
                + String.join(" and ", auths) + " (every read there needs it)");
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
     * The docker CLI's own {@code config.json}: an {@code auths} entry per host, each one base64 of
     * {@code <id>:<secret>} — the same bytes {@code docker login} would store. Hand-written,
     * because base64 has no character JSON escapes and the only values that could need quoting are
     * the hosts, which are deployment facts and escaped here anyway.
     * <p>
     * <b>The CLI matches an entry by host and nothing else</b>, which is why more than one is not a
     * widening: a pull of {@code registry.…/qits/x} never presents the mirror's line. One secret
     * behind both, because both are the same client at the same edge.
     */
    static String dockerConfigJson(List<String> hosts, String clientId, String secret) {
        String auth = dockerAuth(clientId, secret);
        return hosts.stream()
                .map(host -> "\"" + host.replace("\\", "\\\\").replace("\"", "\\\"")
                        + "\":{\"auth\":\"" + auth + "\"}")
                .collect(Collectors.joining(",", "{\"auths\":{", "}}\n"));
    }

    static String dockerAuth(String clientId, String secret) {
        return Base64.getEncoder()
                .encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    // --- what a domain adds -----------------------------------------------------------------------

    /** Creates the immutable, content-versioned Swarm secret consumed by the edge's DNS client. */
    public Phase dnsHetznerSecret(String domain) {
        return new Phase("edge-dns-secret", "store the edge DNS credential in Swarm", ctx -> {
            if (Acme.mode(boot.config) == Acme.Mode.OFF) {
                ctx.skip("QITS_ACME_MODE=off — no DNS credential is needed");
            }
            Optional<String> configuredSecret = boot.config.dnsHetznerSecret()
                    .map(String::strip).filter(value -> !value.isEmpty());
            if (configuredSecret.isPresent()) {
                String name = configuredSecret.orElseThrow();
                Boot.must(boot.docker.run(Cmd.of("docker", "secret", "inspect", name), null),
                        "configured edge DNS Swarm secret " + name + " does not exist");
                ctx.note("kept " + name);
                return;
            }
            String token = boot.config.dnsHetznerToken()
                    .map(String::strip)
                    .filter(value -> !value.isEmpty())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "QITS_DNS_HETZNER_TOKEN is required for DNS-01 on " + domain));
            String name = DomainTokens.secretName(token);
            ProcessResult existing = boot.docker.run(
                    Cmd.of("docker", "secret", "inspect", name), null);
            if (existing.ok()) {
                ctx.note("kept " + name);
                return;
            }
            ProcessResult created = boot.docker.run(
                    Cmd.of("docker", "secret", "create", name, "-")
                            .stdin(token)
                            .mask(token), ctx::log);
            Boot.must(created, "creating the edge DNS Swarm secret failed");
            ctx.note("created " + name);
        });
    }

    /**
     * <b>A self-signed certificate for the domain, so the edge can start at all.</b>
     * <p>
     * The edge's keystore names two files on the qits-edge-letsencrypt volume, and a keystore whose
     * files are missing fails startup — so on a cold boot the volume has to hold something before
     * compose starts the edge with that configuration. This writes it. It is a placeholder in the
     * only sense that matters: browsers reject it, and the real PEMs land in the same two filenames
     * later in this same run: the deployed edge orders them itself over DNS-01 once it is up, and the
     * TLS registry reloads. It is also what the edge keeps when that order does not go
     * through, which is why the placeholder is still written on every path rather than only on the
     * paths that end without a certificate.
     * <p>
     * <b>Skipped whenever a certificate is already there</b>, and that check is not an optimisation:
     * overwriting would replace a REAL certificate with a self-signed one, on a running public
     * platform, every time somebody reran the boot. The existence test and the write are one
     * container so nothing can happen between them.
     * <p>
     * <b>The image is alpine/git with openssl added</b> — the same image the docker credential
     * writes use one phase earlier, so the boot pulls nothing new. It carries git and not openssl
     * (measured, on 2026-08-09), and the platform's other already-present images carry neither:
     * nginx:alpine and the qits service images have no openssl binary either. {@code apk add} is
     * what the two npm
     * publish phases already do, so it adds no dependency the boot did not have.
     * <p>
     * <b>{@code chown -R 1001:0 /cert}: the WHOLE volume, because the edge writes to it.</b> It is
     * not only about reading a root-owned key. The edge orders its real certificate itself and
     * installs it by creating a directory beside this one under {@code versions/} — so a
     * {@code versions/} left root-owned by {@code mkdir -p} is an
     * {@code AccessDeniedException: /work/.letsencrypt/versions/<id>} once a minute and a platform
     * that keeps the placeholder for ever, which is what 2026-09-05 measured. The whole tree is
     * the edge's; chowning the two files was the half that only let it read.
     */
    /** The script, in one place, so the ownership the edge needs is provable without a daemon. */
    static String placeholderCertificateScript(String domain) {
        return """
                    set -eu
                    if [ -f /cert/current/lets-encrypt.crt ]; then
                      echo "a certificate is already there — leaving it alone"
                      exit 0
                    fi
                    apk add --no-cache openssl >/dev/null
                    mkdir -p /cert/versions/placeholder
                    openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \\
                      -subj "/CN=%1$s" -addext "subjectAltName=DNS:%1$s,DNS:*.%1$s" \\
                      -keyout /cert/versions/placeholder/lets-encrypt.key \\
                      -out /cert/versions/placeholder/lets-encrypt.crt 2>/dev/null
                    ln -s versions/placeholder /cert/current
                    chown -R 1001:0 /cert
                    chmod 644 /cert/versions/placeholder/lets-encrypt.crt
                    chmod 640 /cert/versions/placeholder/lets-encrypt.key
                    echo "placeholder certificate written for %1$s"
                    """.formatted(domain);
    }

    public Phase placeholderCertificate(String domain) {
        return new Phase("edge-cert", "seed a placeholder certificate for " + domain
                + " on the edge's volume", ctx -> {
            boot.docker.ensureVolume("qits-edge-letsencrypt", ctx::log);
            String script = placeholderCertificateScript(domain);
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
     * <b>Every name this platform answers for, as four A records.</b> Derived from the shapes the
     * edge routes by rather than from a list of today's environments, applications and projects,
     * which is what makes it a set that never needs revisiting.
     * <p>
     * <b>This platform serves no dns.</b> The set is what the domain's own provider has to hold, and
     * the closing report prints it — nothing here writes a record. At Hetzner they are typed in by
     * hand, once.
     * <p>
     * Names are read RIGHT TO LEFT, {@code <app>[.<env>].<project>.<domain>}, each label inside the
     * one to its right: the domain holds projects, a project holds its environments, an environment
     * holds its apps. The project label is mandatory — there is no unqualified application tier and
     * no top-level {@code <env>.<domain>} tier — so a name says which project it wants or it is not
     * served. The durable answer is a wildcard per DEPTH, not a record per name, and the depths
     * have not changed even though what sits at each of them has:
     * <ul>
     * <li>{@code @} — the apex, and it is not decoration. A wildcard never matches the apex, and the
     *     apex is the address a person types, so without this record the front door has no answer.
     * <li>{@code *} — every one-label name: {@code <project>.<domain>}, every project's own door.
     * <li>{@code *.*} — every two-label name: {@code <env>.<project>.<domain>} where a project
     *     supports environments, and {@code <app>.<project>.<domain>} — its applications — where it
     *     does not.
     * <li>{@code *.*.*} — every three-label name: {@code <app>.<env>.<project>.<domain>}, an
     *     application of an env-supporting project. A project is created
     *     by a person on a running platform, so these are the depths where a record per name would
     *     mean a dns edit per project. The wildcards are what make creating a project a platform act
     *     with no operator step behind it.
     * </ul>
     * Every value is the same address, because every one of these names is this one host: the edge
     * is a single front door and the routing is by Host header behind it.
     * <p>
     * Depth five and beyond is deliberately left to answer NXDOMAIN. The edge stops reading after
     * three labels, so a deeper name could only be served by the reading it already has with a
     * label thrown away — and answering it would put a name on the internet that this platform
     * cannot say the meaning of.
     */
    public static List<ZoneRecord> zoneRecords(String domain, String publicIp) {
        return List.of(
                new ZoneRecord("@", publicIp, "the apex — the browser door, and no wildcard covers it"),
                new ZoneRecord("*", publicIp, "every <project>." + domain + " door"),
                new ZoneRecord("*.*", publicIp,
                        "every <env>.<project>." + domain + " door, and the <app>.<project>."
                                + domain + " host of a project with no environments"),
                new ZoneRecord("*.*.*", publicIp,
                        "every <app>.<env>.<project>." + domain
                                + " host — each service's UI and its wire routes"));
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

    /** The values the seed stack file is filled with. */
    Map<String, String> tokens() {
        String env = boot.config.envName();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ENV_NAME", env);
        // EVERY WIRE ALIAS, EVERY CLIENT-ID KEY AND EVERY TIER LINE, derived rather than written
        // into the templates. All three move when an application changes plane — qits-deployments
        // and qits-events did on 2026-08-17 — so a template that spelled
        // `${ENV_NAME}-qits-deployments` was a second copy of PlatformModel.wireAlias that could
        // not follow it, and one that spelled QITS_ENVIRONMENT told a platform service it had a
        // tier.
        values.putAll(PlatformModel.modelTokens(env));
        values.put("COMPOSE_FILE", boot.state.composeFile == null ? "docker-compose.qits.yml"
                : boot.state.composeFile.getFileName().toString());
        values.put("PORT", String.valueOf(boot.config.port()));
        // Neither generated file publishes this one any more — the byte plane is behind the edge —
        // but it is still filled, so a comment or a line that names it renders a number rather
        // than a placeholder.
        values.put("GIT_HOST_PORT", String.valueOf(boot.config.gitHostPort()));
        // Resolved by seed-postgres, which runs before the stack file is written.
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
        values.put("PG_PROJECTS_PASSWORD", orEmpty(boot.state.pgProjectsPassword));
        values.put("PG_EPICS_PASSWORD", orEmpty(boot.state.pgEpicsPassword));
        values.put("PG_PROJECTS_EVENTSTREAM_PASSWORD",
                orEmpty(boot.state.pgProjectsEventstreamPassword));
        values.put("PG_CONTAINERS_PASSWORD", orEmpty(boot.state.pgContainersPassword));
        values.put("PG_CONTAINERS_EVENTSTREAM_PASSWORD",
                orEmpty(boot.state.pgContainersEventstreamPassword));
        values.put("PG_PLATFORM_EDGE_PASSWORD", orEmpty(boot.state.pgPlatformEdgePassword));
        values.put("PG_PLATFORM_EDGE_EVENTSTREAM_PASSWORD",
                orEmpty(boot.state.pgPlatformEdgeEventstreamPassword));
        values.put("PG_SYSTEM_PASSWORD", orEmpty(boot.state.pgSystemPassword));
        values.put("PG_SYSTEM_EVENTSTREAM_PASSWORD",
                orEmpty(boot.state.pgSystemEventstreamPassword));
        values.put("PG_WORKSPACES_PASSWORD", orEmpty(boot.state.pgWorkspacesPassword));
        values.put("PG_WORKSPACES_EVENTSTREAM_PASSWORD",
                orEmpty(boot.state.pgWorkspacesEventstreamPassword));
        // The idp derives its own issuer from QITS_DOMAIN now (qits-730) and reads no
        // environment variable for it, so this program writes nothing for it any more. IDP_DIAL
        // is the ADDRESS, environment-qualified like every other. See BootstrapConfig.idpDialUrl.
        values.put("IDP_DIAL", boot.config.idpDialUrl());
        values.put("PUSH_TOKEN", boot.config.pushToken());
        values.put("MACHINE_REQUIRED", String.valueOf(boot.config.machineAuth()));
        values.put("DOCKER_GID", boot.state.dockerGid);
        values.put("DAEMON_SHA", boot.state.daemonSha == null ? "" : boot.state.daemonSha);
        // THE IDP'S OWN SEED PAIR, and the only credential this program still records itself. The
        // seed stack hands it to qits-platform-idp as QITS_IDP_SEED_CLIENT_ID/_SECRET, which is
        // what the idp seeds its first database service client from — once, gated by an idp_seed
        // marker row rather than by these values, so rendering them on every boot seeds nothing
        // twice. They must be rendered on every boot: `seed-idp` writes this same stack file to
        // bring the idp up alone, before there is a client to create any other client with.
        values.put("BOOTSTRAP_CLIENT_ID", orEmpty(boot.state.bootstrapClientId));
        values.put("BOOTSTRAP_CLIENT_SECRET", orEmpty(boot.state.bootstrapSecret));
        // THE FIVE SEED SERVICES' OWN CREDENTIALS, resolved by `idp-clients` against the RUNNING
        // idp and recorded in qits-deployments' pd_resource registry — the same row the deployer
        // will inject as QITS_RESOURCE_IDP_* when it replaces each seed container.
        //
        // Keyed by the APPLICATION, because the id carries the environment name and a placeholder
        // cannot be spelled with a value the template does not know yet. There is deliberately NO
        // matching id token: a client id IS the wire alias, ${ALIAS_<APP>} already renders it, and
        // a second spelling would be a copy of PlatformModel.wireAlias that cannot follow a plane
        // change. Empty until the phase has run, which is what a rerun of an earlier phase renders.
        for (String app : PlatformModel.SEED_IDP_CLIENT_APPS) {
            values.put("IDP_CLIENT_SECRET_" + PlatformModel.clientKey(app),
                    boot.state.serviceClientSecrets
                            .getOrDefault(PlatformModel.application(app), ""));
        }
        // NO BROWSER NAME IS A TOKEN ANY MORE. The door, the login host, the return-host
        // allow-list, the cookie domain, the rp id and the ceremony's origins were six tokens here
        // and seven keys across the two generated files — six readings of the ONE domain this run
        // is told, each free to go stale on its own. Two of them did, and sign-in broke on the live
        // platform. The domain is stated once, rendered once (below, for qits-deployments alone),
        // and every service derives its own names from the QITS_DOMAIN the deployer injects.
        // The accessors on BootstrapConfig that survive — publicOrigin, idpOrigin, envAuthority,
        // webauthnRpId, browserSsoCookieDomain — are the CLOSING REPORT's, which tells a person
        // where to go and what their passkey is bound to. Telling a person is not configuring a
        // service.
        //
        // What QITS_DOMAIN adds: the edge's TLS wiring and the domain itself, stated once.
        String domain = boot.config.requiredDomain();
        values.putAll(DomainTokens.of(domain, Acme.mode(boot.config).word(),
                Acme.email(boot.config, domain),
                boot.config.dnsHetznerToken().orElse(""), boot.config.dnsHetznerSecret(),
                // The names the derived wildcards cannot reach, and there are usually none: the
                // edge works the per-environment AND per-project wildcards out for itself, so
                // this is ad-hoc names only. Checked here, where a refusal is a message about a
                // knob rather than a failed order.
                ExtraSans.of(boot.config, domain)));
        // While the disposable edge owns the public domain, the seed edge remains an internal
        // service. The edge's own deployments.yml keeps 80/443 so the real edge can take them
        // at the explicit handoff: the edge's seed deploy, the end of the local-build step.
        if (boot.config.bootstrapIngress() && boot.ingress.mode().isPublic()) {
            values.put("EDGE_SEED_TLS_PORTS", "");
        }
        return values;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    // --- small helpers ----------------------------------------------------------------------------

    private String create(PhaseContext ctx, List<String> command) {
        return create(ctx, command, null);
    }

    /**
     * The same, with a secret on the command line — a publishing token inside the script the
     * container runs. {@code Cmd.mask} is what keeps it off the screen and out of the run log.
     */
    private String create(PhaseContext ctx, List<String> command, String secret) {
        List<String> labelled = new ArrayList<>(command);
        labelled.add(2, "qits.bootstrap.temporary=true");
        labelled.add(2, "--label");
        ProcessResult result = boot.docker.run(
                Cmd.of(labelled).timeout(Duration.ofMinutes(30)).mask(secret)
                        .mask(bearerOf(secret)), ctx::log);
        Boot.must(result, "docker create failed");
        List<String> lines = result.captured();
        if (lines.isEmpty()) {
            throw new IllegalStateException("docker create printed no container id");
        }
        return lines.getLast().trim();
    }

    /**
     * <b>One release tag of one repository, into a container, beside the checkout.</b> The checkout
     * stands at one commit and the seed publishes several versions, so the older ones are exported
     * as their own trees — a detached worktree, made here and removed as soon as docker has the
     * bytes, so a run leaves nothing behind in the source directory.
     * <p>
     * The export is deliberately not a second clone: the tag is already in this checkout, and a
     * clone would go to the network for bytes that are on the disk.
     */
    private void copyTag(PhaseContext ctx, String name, String version, String cid, String into) {
        Path repo = boot.state.repoDir(name);
        Path tree = boot.state.srcDir.resolve(".exports").resolve(PlatformModel.repo(name)
                + "@" + version);
        ctx.status("exporting " + PlatformModel.repo(name) + " " + version);
        boot.git.worktreeRemove(repo, tree, null);
        boot.git.worktreePrune(repo, null);
        Boot.must(boot.git.worktreeAdd(repo, version, tree, ctx::log),
                PlatformModel.repo(name) + " has no " + version + " to export — the tag is in the "
                        + "pin closure but not in the checkout");
        try {
            ctx.log("  " + PlatformModel.repo(name) + " " + version + " -> " + into);
            Boot.must(boot.docker.exec(Duration.ofMinutes(30), ctx::log, "cp",
                            tree + "/.", cid + ":" + into),
                    "copying " + PlatformModel.repo(name) + " " + version + " in failed");
        } finally {
            boot.git.worktreeRemove(repo, tree, null);
        }
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
