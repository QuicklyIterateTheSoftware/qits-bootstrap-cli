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
     * sources. This is what replaces the script's {@code /out} mount: the CLI runs on the host and
     * reads the checkouts where they are.
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
     * The host's one published port, bound by qits-platform-edge. Everything this CLI reads
     * through it — qits-ci, qits-deployments, and the edge's own health — arrives edge -> the
     * environment's gateway -> the service. qits-gateway publishes nothing itself any more.
     */
    @WithDefault("8080")
    int port();

    /**
     * Host port qits-platform-artifacts publishes for the DOCKER DAEMON's pulls and pushes.
     * localhost registries are HTTP-allowed by docker without daemon config, which is the whole
     * trick — and it is the CLI's own door to the git host and the registry API too.
     */
    @WithDefault("8081")
    int registryPort();

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
     * The standing environment's name. It is not only a label: the wire alias of every environment
     * service is {@code <this>-qits-<app>}, so it is inside every address the generated files
     * carry, inside every deployed container's name, and inside every idp client id.
     * <p>
     * {@code prod} because the one environment a platform has is the one it serves from. It was
     * {@code dev} while the platform ran beside a real one; it does not.
     */
    @WithDefault("prod")
    String envName();

    /** The image used to reach a service that publishes no host port (qits-platform-idp). */
    @WithDefault("curlimages/curl:latest")
    String curlImage();

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

    /** qits-platform-artifacts from the host: the registry, the git host and the artifacts API. */
    default String artifactsUrl() {
        return "http://127.0.0.1:" + registryPort() + "/artifacts";
    }

    /** qits-ci, as seen from the host: through the edge and the gateway's route table. */
    default String ciUrl() {
        return "http://127.0.0.1:" + port() + "/ci";
    }

    /**
     * qits-deployments, as seen from the host: through the edge and the gateway's route table.
     * The route segment stayed {@code /platform-deployments} when the repository was renamed —
     * it names the component, not the repository, and every route of the service (its API, its
     * health, its client) hangs off it. Only the hostname moved.
     */
    default String platformDeploymentsUrl() {
        return "http://127.0.0.1:" + port() + "/platform-deployments";
    }
}
