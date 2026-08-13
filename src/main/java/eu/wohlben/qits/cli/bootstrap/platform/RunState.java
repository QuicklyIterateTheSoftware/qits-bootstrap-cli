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
     * idp's, the nameserver's, and the bus's.
     * <p>
     * These five exist because their containers boot from the seed compose file, before any
     * deployer exists to provision anything — so the CLI creates the roles and the seed carries
     * the credentials. From the first pipeline deployment onwards the deployer's registry owns
     * them, which is why the CLI creates those roles and never alters them again.
     */
    public String pgCiPassword;
    public String pgCiEventstreamPassword;
    public String pgPlatformIdpPassword;
    public String pgPlatformDnsPassword;
    public String pgEventsPassword;
    /**
     * The byte plane's three stores, on the same terms as the five above. qits-artifacts and
     * qits-platform-mirror each keep a whole store — catalog rows and blob bytes both — in one
     * database; qits-githost keeps the pack catalog in its own and the eventstream outbox in a
     * second, because two Flyway lineages cannot share one — the same pair qits-ci and the deployer
     * carry.
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
    /** The environment row the deployer reconciled. */
    public String environmentId;
    /** The temporary maven-over-HTTP container that breaks the first-boot dependency cycle. */
    public String authSeedContainer;
    /** The closing report, printed after the display is handed back. */
    public final List<String> summary = new ArrayList<>();

    public Path repoDir(String name) {
        return srcDir.resolve(PlatformModel.repo(name));
    }

    public Path wrapperCheckout(String name) {
        return wrapperDir.resolve(PlatformModel.repoPath(name));
    }
}
