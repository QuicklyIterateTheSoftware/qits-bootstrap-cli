package eu.wohlben.qits.cli.bootstrap.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.Optional;

/**
 * The bootstrap's knobs, one for one with the ones {@code qits-local-up.sh} reads from the
 * environment.
 * <p>
 * Quarkus reads a {@code .env} file in the working directory as an environment source, so every
 * name below is set the way the script's was: {@code QITS_PORT=8080} in {@code .env} or in the
 * real environment. Both spellings reach the same property — {@code qits.registry-port} is
 * {@code QITS_REGISTRY_PORT}.
 */
@ConfigMapping(prefix = "qits")
public interface BootstrapConfig {

    /** Git org used when a repository has no local checkout to clone from. */
    @WithDefault("https://github.com/QuicklyIterateTheSoftware")
    String orgUrl();

    /**
     * The wrapper repository (qits-qits or a worktree of it) whose submodule checkouts are the
     * sources. This is what replaces the script's {@code /out} mount: the checkouts are read where
     * this run sees them, and every path it puts on a docker command line — a build context, a
     * {@code docker cp} source, the compose file — is read by the client rather than by the daemon,
     * so nothing has to mean the same thing on both sides.
     * <p>
     * Optional, and the absence is the ordinary case: this CLI lives at
     * {@code cli/qits-cli-bootstrap} inside the wrapper, so {@link WrapperDir} finds it by walking
     * up from the working directory. Set this to run from somewhere else entirely.
     */
    Optional<String> wrapperDir();

    /**
     * Where sources are cloned to. The clone is not ceremony: seed builds write a placeholder SPA
     * bundle into the tree and may commit a pipeline config, and neither belongs in the user's own
     * checkout.
     */
    @WithDefault(".qits-bootstrap-src")
    String src();

    /**
     * The host's one published port, bound by qits-platform-edge. It is what a PERSON types into a
     * browser and what the generated compose file publishes; this CLI dials the edge's wire alias
     * instead, because it runs on qits-net. qits-gateway publishes nothing itself any more.
     */
    @WithDefault("8080")
    int port();

    /**
     * Host port qits-platform-artifacts publishes for the DOCKER DAEMON's pulls and pushes.
     * localhost registries are HTTP-allowed by docker without daemon config, which is the whole
     * trick — and the daemon is the host's, so this is the number a seed build resolves through.
     * This CLI reads the registry API and the git host by wire alias, not through here.
     */
    @WithDefault("8081")
    int registryPort();

    /**
     * Host port the platform's postgres publishes.
     * <p>
     * Nothing in this CLI dials it any more — the JDBC connection goes to the wire alias on 5432
     * like every other consumer — and the knob stays because the GENERATED FILES carry it: the seed
     * compose file publishes it and so does the deployer's run-arg for qits-oci-postgresql, which
     * is what keeps a psql on the workstation working across the deployer's own cutover.
     * <p>
     * 5433 rather than 5432 so a postgres already installed on the workstation is not a bind
     * conflict this program has to explain.
     */
    @WithDefault("5433")
    int pgPort();

    /** 1 = the seed images and the daemon binary exist; skip to compose and the pushes. */
    @WithDefault("false")
    boolean skipBuild();

    /** How long to wait per application deployment. */
    @WithDefault("3600")
    Duration deployTimeout();

    /** How long to wait for a replayed release run to finish. */
    @WithDefault("1800")
    Duration releaseTimeout();

    /** How long to wait for a seed service to report ready. */
    @WithDefault("120")
    Duration healthTimeout();

    /** How often the deployment and run polls ask again. */
    @WithDefault("10")
    Duration pollInterval();

    /**
     * The git host's push token — what {@code -o qits.token=<value>} must equal to push a
     * protected default branch. A fixed default on purpose: the value must be the same across
     * reruns and nameable in the docs that teach the escape hatch.
     */
    @WithDefault("local-dev")
    String pushToken();

    /** 1 = machine-token enforcement ON for ci, deployments, platform-artifacts and the idp. */
    @WithDefault("true")
    boolean machineAuth();

    /**
     * The standing environment's name, and <b>the platform environment</b>: the tier whose branch
     * deploys the platform plane. {@code --platform-env} on the command line, {@code QITS_ENV_NAME}
     * in {@code .env}.
     * <p>
     * It is not only a label: the wire alias of every environment service is
     * {@code <this>-qits-<app>}, so it is inside every address the generated files carry, inside
     * every deployed container's name, and inside every idp client id.
     * <p>
     * {@code prod} by default, because the one environment a platform has is the one it serves
     * from. It was {@code dev} while the platform ran beside a real one; it does not.
     * <p>
     * <b>Changing it after a bootstrap is a new platform, not a rename</b>, and the {@code
     * environment} phase refuses rather than pretending otherwise. Moving the platform plane
     * between tiers on a live platform is a PATCH on the deployer's {@code pd_environment.platform},
     * and nothing here does it.
     */
    @WithDefault("prod")
    String envName();

    /** The full log of every command this run shells out to. */
    @WithDefault("qits-bootstrap-cli.log")
    String logFile();

    /** Lines of the running step's output kept for the body region. */
    @WithDefault("2000")
    int tailLines();

    /** 0 = never draw the live UI, even on a terminal that could take it. */
    @WithDefault("true")
    boolean tui();

    /**
     * 0 = no browser view at all: the HTTP server never binds a port.
     * <p>
     * These three are read twice — here, and in {@code application.properties}, which maps them
     * onto {@code quarkus.http.*} so the server binds what they say. Change a default in both.
     */
    @WithDefault("true")
    boolean web();

    /** The browser view's port. Away from 8080 (the edge) and 8081 (artifacts) on purpose. */
    @WithDefault("8480")
    int webPort();

    /**
     * What the browser view binds. 0.0.0.0 by default: on WSL2 the browser lives on the Windows
     * side, and the localhost relay does not reliably forward WSL-loopback binds — a loopback
     * default made the view unreachable for exactly the person it exists for. Set 127.0.0.1 to
     * keep it off the LAN.
     */
    @WithDefault("0.0.0.0")
    String webHost();

    /**
     * The branch that deploys, and the ONLY one. main stays the integration trunk on every
     * repository and whichever plane it is on: a platform service is deployed by a green build of
     * this same ref, because both planes ask one question of a build — does an environment listen
     * to it. {@code platform/main} is retired.
     */
    default String envBranch() {
        return "environment/" + envName();
    }

    /** The issuer string, and the address consumers dial. One value, on qits-net. */
    default String idpIssuer() {
        return "http://qits-platform-idp:8080/idp";
    }

    /**
     * qits-platform-artifacts on qits-net: the registry, the git host and the artifacts API.
     * <p>
     * <b>Every address below is a wire alias, because this CLI runs on qits-net.</b> They were
     * {@code 127.0.0.1:<published port>} while it ran on the host. There is no switch between the
     * two: the run joins the network before it dials anything, so the in-network address is the
     * only one there is and nothing has to decide which shape to use.
     */
    default String artifactsUrl() {
        return "http://qits-platform-artifacts:8080/artifacts";
    }

    /**
     * qits-ci, <b>through qits-platform-edge</b> and the gateway's route table behind it.
     * <p>
     * Not {@code <env>-qits-ci:8080} directly, which the network would resolve just as well: the
     * edge and the gateway's route table are the path every other client takes, and a bootstrap
     * that stopped exercising it would stop noticing when it breaks. Only the first hop's address
     * moved off the host port.
     */
    default String ciUrl() {
        return "http://qits-platform-edge:8080/ci";
    }

    /**
     * qits-deployments, through the edge and the gateway's route table, for the reason above.
     * The route segment stayed {@code /platform-deployments} when the repository was renamed —
     * it names the component, not the repository, and every route of the service (its API, its
     * health, its client) hangs off it. Only the hostname moved.
     */
    default String platformDeploymentsUrl() {
        return "http://qits-platform-edge:8080/platform-deployments";
    }
}
