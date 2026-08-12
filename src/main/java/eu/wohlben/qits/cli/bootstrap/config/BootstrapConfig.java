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
     * Host port qits-artifacts publishes for the DOCKER DAEMON's pulls and pushes of the platform's
     * OWN images. localhost registries are HTTP-allowed by docker without daemon config, which is
     * the whole trick — and the daemon is the host's, so this is the number a seed build resolves
     * through. This CLI reads the registry API by wire alias, not through here.
     * <p>
     * It is the HOSTED half of the two-endpoint topology; {@link #mirrorPort()} is the other. The
     * number did not move with the byte-plane split, deliberately: every committed pipeline config
     * and every {@code QITS_ARTIFACTS_REGISTRY_HOST} on this platform says 8081, and what changed
     * behind it is which service answers.
     */
    @WithDefault("8081")
    int registryPort();

    /**
     * Host port qits-platform-mirror publishes, and the THIRD-PARTY half of the two-endpoint
     * topology: Docker Hub, quay.io, the Red Hat registry, npmjs and Maven Central, each behind a
     * pull-through cache.
     * <p>
     * It is published for the same consumer the registry port is — the HOST's docker daemon, which
     * cannot resolve a qits-net alias — and it is a second port rather than a path on the first
     * because both services answer the same literal prefixes ({@code /artifacts/npm},
     * {@code /artifacts/maven}, {@code /v2}). Two services cannot share one prefix behind one
     * gateway entry, so the client's configuration is what picks between them.
     */
    @WithDefault("8082")
    int mirrorPort();

    /**
     * Host port qits-githost publishes, and the reason it publishes one at all: <b>a person pushes
     * from the host.</b>
     * <p>
     * The git host used to be inside qits-platform-artifacts and rode its published port —
     * {@code localhost:8081/artifacts/git/<repo>} was the clone url a developer typed. The split
     * moved the routes to a service of its own, so without a port of its own that door closes: the
     * wire alias resolves on qits-net and nowhere else, and a workstation is not on qits-net.
     * <p>
     * Nothing in this CLI dials it. Every phase that pushes runs INSIDE the payload container,
     * which joins qits-net in its second phase, so it uses the alias like every other member. This
     * port exists for the person and for anything else on the host.
     */
    @WithDefault("8083")
    int gitHostPort();

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

    /**
     * Host port qits-platform-dns publishes — <b>on UDP and on TCP</b>.
     * <p>
     * 53, because this is the port a registrar's delegation ultimately reaches and a nameserver on
     * any other one is a nameserver nobody can find. The service binds 8053 inside the container
     * (below 1024 needs privileges it should not hold), so the publish is what makes it 53.
     * <p>
     * <b>Both protocols, and TCP is not the optional half.</b> dnsjava drops a whole RRset that will
     * not fit a UDP answer, so a name with several records answers TC=1 and <i>zero</i> records and
     * the client's TCP retry is the only way it ever gets an answer. Resolvers also probe TCP
     * outright.
     * <p>
     * Move it when something on the host already holds 53 — systemd-resolved is the usual one. A
     * delegation cannot name a port, so any other value is a local-testing answer rather than a
     * public one.
     */
    @WithDefault("53")
    int dnsPort();

    /**
     * <b>The domain this platform serves</b>, and the one knob the whole public-name path hangs off:
     * the dns service's zone row and its SOA/NS identity ({@code ns1.<domain>},
     * {@code hostmaster.<domain>}), and the name the edge's Let's Encrypt certificate is issued for.
     * <p>
     * <b>Unset is the default and a supported state</b>, not a half-configured one: qits-platform-dns
     * runs with no zones and no SOA synthesis — which its own README documents as legal — and the
     * edge publishes the one plain-HTTP port it always did. Everything the domain adds is absent
     * rather than broken.
     * <p>
     * Set, four things follow in this run: the dns container is given its NS identity, the zone is
     * created over its API, the edge gets 80, 443 and a loopback management port with a certificate
     * slot on a volume, and the closing report prints the issuance command. What it does NOT do is
     * write A records or touch a registrar: both need this host's public IP, which the bootstrap has
     * no way to know.
     * <p>
     * Validated by {@link DomainName}, on the host half, before the payload image is built: a typo
     * here would otherwise become a zone row and a certificate request for a name nobody owns.
     */
    Optional<String> domain();

    /** 1 = the seed images and the daemon binary exist; skip to compose and the pushes. */
    @WithDefault("false")
    boolean skipBuild();

    /**
     * <b>1 = deploy local mains instead of restoring the last release.</b> {@code --ship-mains} on
     * the command line, {@code QITS_SHIP_MAINS} in {@code .env}.
     * <p>
     * A bootstrap RESTORES by default: it points the deploy ref at the commit of each deployable's
     * newest release tag, so the platform comes back as its last released self. This flag is the
     * dev loop's spelling — the deploy ref follows main's head, which is what the boot always did
     * and what shipped an unreleased stack by accident on 2026-08-08. Now it takes saying so.
     * <p>
     * It changes ONE thing: where the deploy ref points. Local mains are pushed either way (the
     * repositories need their history and the catalog needs the shape), the seed phase builds from
     * main either way, and every wait, event and deployer call downstream is identical.
     */
    @WithDefault("false")
    boolean shipMains();

    /** How long to wait per application deployment. */
    @WithDefault("3600")
    Duration deployTimeout();

    /**
     * How long to wait for a replayed release run to finish. An hour, not the half it used to be:
     * the image publishers replay a full docker build, and one uncached workspace-image build was
     * measured at 35 minutes — behind another replay in the queue, the old budget expired on a run
     * that went on to succeed.
     */
    @WithDefault("3600")
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

    /** 1 = machine-token enforcement ON for ci, deployments, artifacts and the idp. */
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

    /**
     * <b>Set by the launcher and by nothing else</b>: 1 means "you are the payload — run the
     * phases". Unset means "you are on the host — build the payload image and run yourself in it".
     * <p>
     * It is the one knob that is not a person's to answer, and it lives here rather than in a
     * schema of the host half's own, because there is one program and one configuration contract.
     * Setting it by hand runs the phases on the host, where the first address they dial does not
     * resolve.
     */
    @WithDefault("false")
    boolean inContainer();

    /** Lines of the running step's output kept for the body region. */
    @WithDefault("2000")
    int tailLines();

    /** 0 = never draw the live UI, even on a terminal that could take it. */
    @WithDefault("true")
    boolean tui();

    /**
     * 0 = do not follow the platform's own events beside the boot's output.
     * <p>
     * On by default and free when nothing answers: the feed is one poll every few seconds on a
     * daemon thread, and for the first half of a bootstrap qits-events does not exist yet.
     */
    @WithDefault("true")
    boolean eventsFeed();

    /**
     * 0 = no browser view at all: the HTTP server never binds a port.
     * <p>
     * These three are read twice — here, and in {@code application.properties}, which maps them
     * onto {@code quarkus.http.*} so the server binds what they say. Change a default in both.
     */
    @WithDefault("true")
    boolean web();

    /**
     * The browser view's port. Away from the four this platform publishes on purpose: 8080 (the
     * edge), 8081 (qits-artifacts), 8082 (qits-platform-mirror) and 8083 (qits-githost).
     */
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
     * qits-artifacts on qits-net: the platform's OWN packages — the hosted Maven repository, the
     * hosted npm registry, the hosted OCI registry, the daemon binaries and the docs bundles.
     * <p>
     * <b>Every address below is a wire alias, because this CLI runs on qits-net.</b> They were
     * {@code 127.0.0.1:<published port>} while it ran on the host. There is no switch between the
     * two: the run joins the network before it dials anything, so the in-network address is the
     * only one there is and nothing has to decide which shape to use.
     * <p>
     * <b>It carries the environment name since the byte-plane split.</b> This service is an
     * environment service again — the caches that made it platform-scoped are
     * {@link #mirrorUrl()} now — so its alias is qualified like ci's and the deployer's, and a
     * hard-coded qits-platform-artifacts resolves to nothing.
     */
    default String artifactsUrl() {
        return "http://" + envName() + "-qits-artifacts:8080/artifacts";
    }

    /**
     * qits-platform-mirror on qits-net: everything THIRD-PARTY a build resolves, cached.
     * <p>
     * Scheme, host and port with NO path, which is the one address shape in this file that has to
     * be that way: this service answers under two unrelated prefixes — {@code /mirror/q} for its
     * health and the registries' own literals ({@code /artifacts/npm}, {@code /artifacts/maven},
     * {@code /v2}) for content — so a base with a path would be right for one caller and wrong for
     * the next. Each use appends what it wants.
     * <p>
     * A PLATFORM service, so the alias carries no tier: one cache of Maven Central serves every
     * environment on the machine, which is the whole reason this half of the byte plane stayed up
     * here when the rest went back to being per-tier.
     */
    default String mirrorUrl() {
        return "http://qits-platform-mirror:8080";
    }

    /**
     * qits-githost on qits-net, at the segment its Vert.x routes hard-code: {@code /git}.
     * <p>
     * The git smart-HTTP host is a service of its own since the byte-plane split — it was never an
     * artifact, it only shared the storage — and an ENVIRONMENT service, because every one of its
     * consumers already was one. A clone url is therefore
     * {@code http://<env>-qits-githost:8080/git/<repoId>}, and the {@code /artifacts} that used to
     * be in front of it is gone with the service that owned it.
     * <p>
     * The health root is a DIFFERENT prefix — see {@link #gitHostHealthUrl()}.
     */
    default String gitHostUrl() {
        return "http://" + envName() + "-qits-githost:8080/git";
    }

    /**
     * The git host's own segment: {@code /githost}, which carries its view, its API and its health
     * at {@code /githost/q/health/ready}.
     * <p>
     * Two prefixes rather than one, because they answer to different owners. {@code /githost} is
     * the service's {@code quarkus.http.non-application-root-path} and moves with a config key;
     * {@code /git} is the git wire protocol, which treats the base as opaque and no key can move.
     * The gateway carries both on one route entry, so nothing here has to choose between them.
     */
    default String gitHostHealthUrl() {
        return "http://" + envName() + "-qits-githost:8080/githost";
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

    /**
     * qits-events — the BUS — through the edge and the gateway's route table, like ci and the
     * deployer above. The gateway routes {@code /events/*} verbatim, and this service serves
     * everything it has under that one segment, health included.
     * <p>
     * Dialled for one purpose: the seed health wait. Nothing this program does publishes or reads
     * an event over HTTP — the announcements travel ci's outbox and the deployer's subscriber — but
     * a bus nobody waited for is a bus the first green build can outrun.
     */
    default String eventsUrl() {
        return "http://qits-platform-edge:8080/events";
    }

    /**
     * qits-platform-dns' HTTP surface — its health and the zone API — <b>at its own alias, not
     * through the edge</b>, which is the one exception to the rule the two addresses above follow.
     * There is no gateway route to this service and there must not be one: the gateway proxies HTTP
     * and the record API is a service-to-service door, so every caller addresses it directly.
     * <p>
     * The path is {@code /dns} because that is the service's own
     * {@code quarkus.http.non-application-root-path} — health is {@code /dns/q/health/ready} and the
     * zones are {@code /dns/api/zones}. The wire protocol is a different door entirely: UDP and TCP
     * on 8053, published by {@link #dnsPort()}, and nothing here speaks it.
     */
    default String dnsUrl() {
        return "http://qits-platform-dns:8080/dns";
    }
}
