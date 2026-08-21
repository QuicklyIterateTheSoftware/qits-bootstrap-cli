package eu.wohlben.qits.cli.bootstrap.platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What one run learns as it goes and later phases need. */
public class RunState {

    /** The wrapper repository: the source checkouts, the compose file, the recorded state. */
    public Path wrapperDir;
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
    /** The environment row the deployer reconciled. */
    public String environmentId;
    /**
     * <b>The storage id the git host keys each platform repository by</b>, by repository name —
     * {@code qits-ci -> qits-ci} today, see {@link PlatformModel#seedStorageId}. Filled by
     * {@code git-repos} from what {@code .qits-bootstrap.env} already recorded, so a rerun addresses
     * the bares it created rather than minting a second set.
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
     * run addresses the git host. It turns true when {@code register-repos} has put every
     * (storage id, name) pair under the qits project; before that there is no alias table to
     * resolve through and every push is id-addressed of necessity.
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

    public Path wrapperCheckout(String name) {
        return wrapperDir.resolve(PlatformModel.repoPath(name));
    }
}
