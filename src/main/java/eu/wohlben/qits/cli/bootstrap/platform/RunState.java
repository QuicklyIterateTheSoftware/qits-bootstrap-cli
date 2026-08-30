package eu.wohlben.qits.cli.bootstrap.platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** What one run learns as it goes and later phases need. */
public class RunState {

    /** The wrapper repository: the source checkouts, the compose file, the recorded state. */
    public Path wrapperDir;
    /** Its {@code .gitmodules}, read on demand — see {@link #wrapperModules()}. */
    private GitModules modules;
    private Path modulesFrom;
    /** Where sources are cloned to. */
    public Path srcDir;
    /** The generated seed compose file. */
    public Path composeFile;
    /** The ci-daemon binary this run built, if it built one. */
    public Path daemonBinary;
    /** The digest that is also the daemon's version coordinate. */
    public String daemonSha;
    /** The docker socket's group id, which ci and the deployer join. */
    public String dockerGid = "0";
    /**
     * The daemon's swarm as preflight left it — {@code active}, or {@code active (initialised by
     * this run)}. Recorded because "this bootstrap made the machine a swarm manager" is a change to
     * the host, and a run that made one has to say so.
     */
    public String swarm;
    /** The idp's client secrets: given, kept or generated. */
    public final Map<String, String> secrets = new LinkedHashMap<>();
    /**
     * The register token THIS run minted, and null on every other run. The closing report prints a
     * value only when it is this one: a token is printed on the run that made it, and after that it
     * lives in {@code .qits-bootstrap.env} where the report points.
     */
    public String registerToken;
    /** An earlier run already minted one, so this run mints nothing. */
    public boolean registerTokenRecorded;
    /**
     * <b>Which certificate the edge is serving when this run ends</b>: {@code staging},
     * {@code production}, or null for the self-signed placeholder.
     * <p>
     * Set by the {@code edge-acme} phase, and null is the honest answer in three different
     * situations — issuance was off, the order failed, or there is no domain at all — which is why
     * the closing report reads the MODE beside it rather than this word alone.
     */
    public String certificate;
    /**
     * postgres' superuser password: given, kept or generated. It applies at initdb only, so the
     * recorded value is the only way into a cluster that already exists.
     */
    public String pgSuperuserPassword;
    /** The password of the deployer's own role, converged on every rerun. */
    public String pgDeploymentsPassword;
    /**
     * The deployer's OUTBOX role, beside its own store above. The deployer joined the event bus on
     * 2026-08-10, and the eventstream library keeps its outbox in a second database with its own
     * Flyway lineage — so the seed deployer needs two credentials, exactly as the seed ci does.
     * Not converged: from the first pipeline deployment the deployer's registry owns it.
     */
    public String pgDeploymentsEventstreamPassword;
    /**
     * The passwords of the CORE SEED SERVICES' databases: qits-ci's own store, its outbox, the
     * idp's, and the bus's.
     * <p>
     * These four exist because their containers boot from the seed compose file, before any
     * deployer exists to provision anything — so the CLI creates the roles and the seed carries
     * the credentials. From the first pipeline deployment onwards the deployer's registry owns
     * them, which is why the CLI creates those roles and never alters them again.
     */
    public String pgCiPassword;
    public String pgCiEventstreamPassword;
    public String pgPlatformIdpPassword;
    public String pgEventsPassword;
    /**
     * The byte plane's three stores, on the same terms as the four above. Every one of them keeps a
     * whole store — catalog rows and blob bytes both — in one database; qits-githost takes a second
     * for the eventstream outbox, because two Flyway lineages cannot share one, which is the same
     * pair qits-ci and the deployer carry.
     */
    public String pgArtifactsPassword;
    public String pgPlatformMirrorPassword;
    public String pgGithostPassword;
    public String pgGithostEventstreamPassword;
    /**
     * The container orchestrator's pair, on the same terms as everything above. qits-containers keeps
     * the registry of rows that says which containers may exist in one database and the eventstream
     * outbox in a second, because two Flyway lineages cannot share one — the same pair qits-ci, the
     * deployer and the git host carry.
     */
    public String pgContainersPassword;
    public String pgContainersEventstreamPassword;
    public String pgPlatformEdgePassword;
    public String pgPlatformEdgeEventstreamPassword;
    /**
     * <b>qits-projects' THREE, and three is what its own deployments.yml declares</b>: its domain
     * store, the epics store beside it, and the eventstream outbox. Three Flyway lineages, so three
     * databases — the same rule that gives ci, the git host and the orchestrator two each.
     * <p>
     * They are here since the service joined the seed on 2026-08-21: its container boots from the
     * seed stack, before any deployer exists to provision anything, and it dies at Flyway's first
     * connect without all three.
     */
    public String pgProjectsPassword;
    public String pgEpicsPassword;
    public String pgProjectsEventstreamPassword;
    /** The environment row the deployer reconciled. */
    public String environmentId;
    /**
     * <b>The storage id the git host keys each platform repository by</b>, by repository name — a
     * minted UUID, see {@link PlatformModel#seedStorageId}. Filled at {@code recorded-state} from
     * what {@code .qits-bootstrap.env} already holds and then by the mint itself, so a rerun
     * addresses the bares it created rather than minting a second set.
     */
    public final Map<String, String> repositoryIds = new LinkedHashMap<>();
    /**
     * The {@code qits} project's id in qits-projects, read once that service answers. It is the
     * first segment of every public clone url — {@code /git/<projectId>/<repoName>} — and it is a
     * minted uuid, not the slug: qits-projects resolves a name by {@code project.id}.
     */
    public String projectId;
    /**
     * <b>Whether a name-addressed url resolves yet</b>, which is the one fact that decides how this
     * run addresses the git host. It turns true when {@code git-repos} has put every
     * (storage id, name) pair under the qits project — which that phase does before it pushes
     * anything, because the seed stack holds qits-projects. Only the lifecycle PUTs above it are
     * id-addressed.
     */
    public boolean repositoriesRegistered;
    /** The temporary maven-over-HTTP container that breaks the first-boot dependency cycle. */
    public String authSeedContainer;
    /** Ephemeral paths and capabilities for the bootstrap-only ingress; never persisted in state. */
    public Path bootstrapIngressEnvFile;
    public String bootstrapIngressPassword;
    public String bootstrapIngressGitCapability;
    public String bootstrapIngressGitCapabilityHash;
    public String bootstrapIngressRepository;
    public String bootstrapIngressRefPattern;
    public long bootstrapIngressExpiresAt;
    /** The closing report, printed after the display is handed back. */
    public final List<String> summary = new ArrayList<>();

    public Path repoDir(String name) {
        return srcDir.resolve(PlatformModel.repo(name));
    }

    /**
     * <b>What the wrapper says about itself, re-read while it is still changing.</b> The file is
     * the authority on every repository's directory, and on a COLD start it does not exist until
     * the {@code wrapper} phase has cloned one — so this is read again whenever the last read found
     * nothing, and cached once it has an answer. Reading a small file twice costs nothing; caching
     * "no wrapper yet" over a wrapper that has since arrived would cost the boot its sources.
     */
    public GitModules wrapperModules() {
        if (modules == null || modules.isEmpty() || !wrapperDir.equals(modulesFrom)) {
            modules = GitModules.of(wrapperDir);
            modulesFrom = wrapperDir;
        }
        return modules;
    }

    /**
     * Where the wrapper puts one repository, relative to its root: what {@code .gitmodules}
     * declares, else the archetype layout the name implies.
     */
    public String wrapperPath(String name) {
        return wrapperModules().path(PlatformModel.repo(name))
                .orElseGet(() -> PlatformModel.repoPath(name));
    }

    /** The same, as a directory on this machine. */
    public Path wrapperCheckout(String name) {
        return wrapperDir.resolve(wrapperPath(name));
    }

    /** Whether the wrapper declares this repository as a submodule of its own. */
    public boolean wrapperDeclares(String name) {
        return wrapperModules().declares(PlatformModel.repo(name));
    }

    /**
     * <b>Whether this wrapper's submodules are checked out at all</b>, which is the line between
     * the two things a missing directory can mean. A wrapper cloned without them — the cold start,
     * and every rerun on that machine — has an empty directory at every gitlink and hides no local
     * work, so the org URL is the right answer for all of them. A wrapper whose siblings ARE
     * checked out is a half-initialised one, and a repository missing from it would otherwise
     * deploy the org's last push in silence.
     */
    public boolean wrapperInitialised(Predicate<Path> isCheckout) {
        return PlatformModel.platformRepos().stream()
                .anyMatch(name -> isCheckout.test(wrapperCheckout(name)));
    }
}
