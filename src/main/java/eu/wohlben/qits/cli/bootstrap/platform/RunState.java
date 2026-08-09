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
     * The passwords of the CORE SEED SERVICES' databases: qits-ci's own store, its outbox, the
     * idp's, and the nameserver's.
     * <p>
     * These four exist because their containers boot from the seed compose file, before any
     * deployer exists to provision anything — so the CLI creates the roles and the seed carries
     * the credentials. From the first pipeline deployment onwards the deployer's registry owns
     * them, which is why the CLI creates those roles and never alters them again.
     */
    public String pgCiPassword;
    public String pgCiEventstreamPassword;
    public String pgPlatformIdpPassword;
    public String pgPlatformDnsPassword;
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
