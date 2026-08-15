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
     * <b>The host's one HTTP port, bound by qits-platform-edge, and now the door of the whole
     * platform.</b> It is what a PERSON types into a browser, what the host's docker daemon dials
     * at {@link #registryVhost()} and {@link #mirrorVhost()}, and what a clone of
     * {@link #gitHostVhost()} arrives on. This CLI dials the edge's wire alias instead, because it
     * runs on qits-net. qits-gateway publishes nothing itself any more, and neither does the byte
     * plane.
     */
    @WithDefault("8080")
    int port();

    /**
     * <b>A SEED-ONLY port now, and the break-glass' default.</b> The temporary Maven registry of
     * the {@code maven-seed} phase and the seed qits-artifacts container publish it on 127.0.0.1
     * for one consumer: the seed image builds, which run {@code --network host} before any edge
     * exists. Both containers are gone by the first cutover and nothing has to close the port.
     * <p>
     * <b>The PLATFORM publishes nothing here.</b> The deployed store is reached at
     * {@link #registryVhost()} through the edge — one door, method-scoped authentication — and the
     * only thing that binds this port on a running platform is the wrapper's
     * {@code qits-registry-break-glass.sh}, which opens it while a wedged edge is repaired and
     * closes it after.
     * <p>
     * This CLI reads the registry API by wire alias, not through here.
     */
    @WithDefault("8081")
    int registryPort();

    /**
     * The same seed-only story for qits-platform-mirror, which caches everything third-party a
     * build resolves: Docker Hub, quay.io, the Red Hat registry, npmjs and Maven Central.
     * <p>
     * The seed container publishes it on 127.0.0.1 so the seed image builds resolve their base
     * layers and their Maven plugins before an edge exists. The deployed mirror publishes nothing:
     * the host reaches it at {@link #mirrorVhost()}, which is also what every committed Dockerfile
     * spells in its {@code FROM} lines and what dockerd's {@code registry-mirrors} names.
     */
    @WithDefault("8082")
    int mirrorPort();

    /**
     * qits-githost's old host port. <b>Nothing publishes it any more</b> — neither the seed, which
     * comes up inside the stack, nor the deployment.
     * <p>
     * The url a person clones has moved twice: it was
     * {@code localhost:8081/artifacts/git/<repo>} while the git routes lived inside
     * qits-platform-artifacts, then this port after the byte-plane split gave the git host a
     * service of its own, and it is {@link #gitHostVhost()} through the edge now — where every
     * method, reads included, needs a bearer.
     * <p>
     * The knob stays because the number is the one to reopen by hand while an edge is being
     * repaired, and because nothing else names it. Nothing in this CLI dials it: every phase that
     * pushes runs INSIDE the payload container, which joins qits-net in its second phase.
     */
    @WithDefault("8083")
    int gitHostPort();

    /**
     * <b>The domain this platform serves</b>, and the name the edge's Let's Encrypt certificate is
     * issued for.
     * <p>
     * <b>Unset is the default and a supported state</b>, not a half-configured one: the edge
     * publishes the one plain-HTTP port it always did. Everything the domain adds is absent rather
     * than broken.
     * <p>
     * Set, the edge gets 80, 443 and a loopback management port with a certificate slot on a volume,
     * and a real Let's Encrypt certificate is ordered for the name. The closing report prints the
     * records the domain needs, which is why {@link #publicIp()} is mandatory beside this.
     * <p>
     * <b>DNS IS EXTERNAL.</b> The records live at whatever provider holds the domain, and this run
     * writes none of them. Put them in place BEFORE the run, because the certificate order is
     * answered over the public name; if they have not propagated yet the issuance warns and the
     * closing report prints the retry.
     * <p>
     * Validated by {@link DomainName}, on the host half, before the payload image is built: a typo
     * here would otherwise become a certificate request for a name nobody owns.
     */
    Optional<String> domain();

    /**
     * <b>This host's public IPv4 address, and MANDATORY whenever {@link #domain()} is set</b> —
     * {@code --public-ip} on the command line, {@code QITS_PUBLIC_IP} in {@code .env}.
     * <p>
     * It is the data of every A record the domain needs at its dns provider, and the run cannot
     * learn the address for itself — it is a container behind a NAT. So it is told, and the closing
     * report prints the records with it filled in.
     * <p>
     * Checked by {@link PublicIp} on the host half, beside the domain and in the same manner: four
     * dotted octets, a hostname refused rather than resolved, and set without a domain refused too.
     */
    Optional<String> publicIp();

    /**
     * <b>Whether this run orders a real certificate, and from which Let's Encrypt directory.</b>
     * {@code staging} (the default), {@code production}, or {@code off}.
     * <p>
     * Staging by default, deliberately: the production directory rate-limits failed orders per
     * registered domain per week, and the thing most likely to fail on a first boot is the
     * dns — a name whose records the world has not seen yet answers nothing, and an
     * HTTP-01 challenge is fetched over exactly that name. The staging directory has generous limits
     * and issues from an untrusted root, so a browser still refuses the certificate; it proves the
     * path and costs nothing when it fails.
     * <p>
     * Flipping to production is one rerun with this set — <b>no redeploy</b>. The PEM files land on
     * the {@code qits-edge-letsencrypt} volume under the same two names and the edge's TLS registry
     * reloads them within the hour, so the switch is a file change and nothing restarts.
     * <p>
     * {@code off} keeps the placeholder certificate and prints the manual command, which is what
     * this program did before issuance was part of it. Read by {@link Acme}, which refuses any other
     * word. Ignored with no domain, because there is nothing to issue for.
     */
    @WithDefault("staging")
    String acmeMode();

    /**
     * <b>The ACME account's contact address</b> — where Let's Encrypt sends expiry warnings if a
     * renewal ever stops happening.
     * <p>
     * Derived from the domain by default: {@code hostmaster@<domain>}, which is the same role
     * name the {@code hostmaster.<domain>} convention already carries. One convention, spelled
     * twice by one derivation, so a platform gains a working contact without a second knob to fill
     * in. Set this when the mail for that domain is not read.
     */
    Optional<String> acmeEmail();

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
     * The browser view's port. Away from 8080, which is the edge and the whole platform's door,
     * and away from 8081 to 8083, which the seed containers and the break-glass still use.
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
     * A separate, short-lived bootstrap ingress.  It is deliberately not a deployment extra and
     * never joins the standing platform's routing table: it exists only while this command runs.
     */
    @WithDefault("true")
    boolean bootstrapIngress();

    /** The loopback host port of the short-lived bootstrap ingress, kept away from the edge. */
    @WithDefault("8481")
    int bootstrapIngressPort();

    /** The host-side bind for that ingress.  Loopback is the safe default for a bootstrap UI. */
    @WithDefault("127.0.0.1")
    String bootstrapIngressBind();

    /** The only Host header the ingress accepts, apart from its optional port. */
    @WithDefault("localhost")
    String bootstrapIngressHost();

    /** A stale ingress cannot remain a useful capability after this run has gone away. */
    @WithDefault("8h")
    Duration bootstrapIngressTtl();

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
     * <b>qits-platform-edge's MANAGEMENT interface, and the Let's Encrypt endpoints on it</b> — the
     * challenge slot and the certificate reload.
     * <p>
     * Port 9000, a second listener of the same process, and <b>published nowhere</b>: the endpoints
     * are unauthenticated and a swarm publish cannot be bound to loopback, so the edge binds
     * 0.0.0.0 inside its container and the address is reachable on qits-net and nowhere else. This
     * run is on qits-net, which is the whole reason it can issue a certificate at all.
     * <p>
     * {@code /q} is the management root path and {@code lets-encrypt} is the extension's own
     * segment, so the two endpoints are {@code .../challenge} and {@code .../certs}.
     * <p>
     * <b>The edge is not an ACME client and this address is not an ACME one.</b> The extension adds
     * three routes and holds ONE challenge in memory; the protocol — the account, the order, the
     * key, the certificate — is run entirely by whoever is issuing. See the {@code edge-acme} phase.
     */
    default String edgeLetsEncryptUrl() {
        return "http://qits-platform-edge:9000/q/lets-encrypt";
    }

    /**
     * <b>The three names the HOST reaches this platform's byte plane by, and the one address shape
     * in this file that is not a wire alias.</b> Each is {@code <app>.<env>.localhost:<edge port>}:
     * every {@code *.localhost} name resolves to the loopback address (systemd-resolved synthesises
     * it), so a client on the workstation arrives at the edge, which routes by the NAME rather than
     * by a path — a docker client and a git client own their own roots ({@code /v2}, {@code /git})
     * and cannot be given a prefix.
     * <p>
     * <b>Nothing in this CLI dials them.</b> The run is a container on qits-net and reaches all
     * three at their aliases. They are here for the two things that speak to a person: the
     * preflight warning about the docker daemon's {@code insecure-registries}, and the closing
     * report's host-side steps.
     */
    default String registryVhost() {
        return "registry." + envName() + ".localhost:" + port();
    }

    /** The pull-through caches, at the name dockerd's {@code registry-mirrors} points to. */
    default String mirrorVhost() {
        return "mirror." + envName() + ".localhost:" + port();
    }

    /** The git host, where a clone needs a bearer for every method — there is no anonymous half. */
    default String gitHostVhost() {
        return "githost." + envName() + ".localhost:" + port();
    }

    /**
     * <b>The address a person's browser arrives at</b>, which is the edge's and no other: the
     * loopback name and the published port, or the domain over TLS when there is one — the name the
     * edge's certificate is issued for.
     * <p>
     * Derived rather than configured, because it is decided twice already: the port is the edge's
     * publish and the domain is the certificate's name. A third address told to a browser would be
     * a login page nobody can reach.
     */
    default String publicOrigin() {
        return DomainName.of(this).map(domain -> "https://" + domain)
                .orElse("http://localhost:" + port());
    }

    /**
     * <b>The WebAuthn relying party, which is a HOST and not a URL.</b> A passkey is bound to it and
     * asserts under no other name, so it follows {@link #publicOrigin} rather than standing beside
     * it.
     * <p>
     * {@code localhost} is a secure context by itself — no certificate needed — which is what lets a
     * passkey work on this platform's plain HTTP port. The one route without a secure context is a
     * raw IP, where the browser offers no ceremony at all and only a password logs in.
     * <p>
     * The binding costs nothing here: accounts are per-installation, so a platform that gains a
     * domain registers its own from its own register token.
     */
    default String webauthnRpId() {
        return DomainName.of(this).orElse("localhost");
    }

    /**
     * The origins a ceremony is accepted from — {@link #publicOrigin} and nothing else. It is a LIST
     * on the idp's side and one entry here, because this platform has one front door.
     */
    default String webauthnOrigins() {
        return publicOrigin();
    }

    /**
     * <b>Where an image built by THIS BOOTSTRAP resolves {@code eu.wohlben.qits} from, and it is the
     * seed's loopback port on purpose.</b>
     * <p>
     * Every seed build runs {@code --network host} against the HOST's daemon, minutes before any
     * edge exists — the whole point of the seed is that the platform is not up yet — so the only
     * server that can answer is the one this run publishes on 127.0.0.1: the temporary Maven
     * registry of {@code maven-seed}, then the seed qits-artifacts that replaces it on the same
     * port.
     * <p>
     * <b>A seed build must never ride a committed Dockerfile's default</b>, which is what made this
     * a knob at all. The repositories declare
     * {@code ARG QITS_MAVEN_REPOSITORY_URL} and their {@code .qits-maven-settings.xml} reads it, and
     * that default now names the edge vhost — the address of a platform that is RUNNING. It happened
     * to be this same loopback url until the unify-ingress sweep, so the bootstrap passed nothing and
     * nobody noticed the free ride; the first boot after the sweep died at the qits-platform-mirror
     * seed build with connection refused on the vhost. The build-arg is the lever, so this run
     * always pulls it.
     */
    default String seedMavenRepositoryUrl() {
        return "http://localhost:" + registryPort() + "/artifacts/maven/maven";
    }
}
