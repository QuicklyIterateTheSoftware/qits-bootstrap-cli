package eu.wohlben.qits.cli.bootstrap.config;

import eu.wohlben.qits.cli.bootstrap.platform.CiConcurrency;
import eu.wohlben.qits.cli.bootstrap.platform.PlatformModel;
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
     * Optional, and the absence is the ordinary case: this CLI is a submodule of the wrapper, so
     * {@link WrapperDir} finds it by walking up from the working directory. Set this to run from
     * somewhere else entirely.
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
     * {@link #gitHostVhost()} arrives on. Seed calls use their fixed service aliases on qits-net;
     * neither those services nor the byte plane publishes another host port.
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
     * DNS — a zone whose records the world has not seen yet cannot pass DNS-01. The staging
     * directory has generous limits and issues from an untrusted root, so a browser still refuses
     * the certificate; it proves the path and costs nothing when it fails.
     * <p>
     * Flipping to production is one rerun with this set. The edge notices the new mode, orders the
     * production certificate, atomically switches the PEM lineage and its TLS registry reloads it.
     * <p>
     * {@code off} keeps the placeholder certificate. Read by {@link Acme}, which refuses any other
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

    /**
     * <b>Names the edge's certificate must carry beyond the wildcards it derives</b>, separated by
     * commas or whitespace and written whole or relative to the domain.
     * <p>
     * The edge derives the apex, {@code *.<domain>}, {@code *.<env>.<domain>} per environment and
     * {@code *.<project>.<domain>} plus {@code *.<project>.<env>.<domain>} per project — every
     * depth its Host reading has, the project tier included. A wildcard covers ONE label, so what
     * belongs here is a name at some OTHER shape:
     * {@code QITS_ACME_EXTRA_SANS=status.support,legacy.acme.eu-west}.
     * <p>
     * <b>Not one per project any more.</b> That was the debt: the project wildcards are derived
     * live from qits-projects' events now, so a project created after this boot reaches the
     * certificate on its own. The knob is empty on an ordinary platform.
     * <p>
     * Generic on purpose. It says "put these names on the certificate" and knows nothing about
     * editors or projects; {@link ExtraSans} is where the shape and the refusals live, and the
     * closing report prints what a run resolved, against the 100-name cap.
     * <p>
     * Ignored with no domain, because there is nothing to issue for.
     */
    Optional<String> acmeExtraSans();

    /** Hetzner Cloud DNS token used only by the edge's DNS-01 certificate manager. */
    Optional<String> dnsHetznerToken();

    /** Existing Swarm secret containing the Hetzner token, for repeat bootstraps. */
    Optional<String> dnsHetznerSecret();

    /**
     * Legacy HTTP-01 helper URL, retained only while old bootstrap compatibility code compiles.
     * <p>
     * The host is derived like every other address on qits-net, legacy or not: the dual alias is a
     * property of the CONTAINER, so it covers the management port as readily as 8080, and a bare
     * literal left in a corner nobody reads is exactly what the withdrawal of the bare alias will
     * break silently.
     */
    default String edgeLetsEncryptUrl() {
        return "http://" + PlatformModel.wireAlias("edge", envName())
                + ":9000/q/lets-encrypt";
    }

    /**
     * <b>Whether this run configures the host against memory pressure</b> — systemd-oomd's two
     * slice rules, the omit list, and swap. {@code QITS_HOST_OOM=false} in {@code .env} turns it
     * off, and the {@code host-oom} phase then skips.
     * <p>
     * On by default because the host that livelocked was a host nobody had configured, and the
     * boot is the first heavy workload on a fresh one. Off is for a machine whose memory policy
     * somebody else owns — a managed node, or a laptop a person keeps their own swap on.
     */
    @WithDefault("true")
    boolean hostOom();

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

    /**
     * <b>1 = keep the bootstrap's seed-only caches, and any legacy buildx builder, when the run
     * ends.</b> {@code QITS_KEEP_BUILDER} in {@code .env}, off by default.
     * <p>
     * The name is older than what it now guards. It used to keep a {@code qits-bootstrap-builder-v<n>}
     * buildx builder alive, whose state volume measured 13.7 GB on wohlben.eu; the bootstrap builds
     * through {@code qits-buildkitd} now, and THAT container is never removed by a run at all —
     * it is the platform's from its first deployment on. What this flag decides is the seed's own
     * caches, {@code qits-maven-seed} and {@code qits-maven-cache}, plus the sweep of whatever
     * buildx era a host still carries.
     * <p>
     * <b>What saying yes buys, and what it costs.</b> The dev loop reruns the boot, so it wants the
     * warm caches: a re-bootstrap without them re-fetches the dependency world from Maven Central.
     * A server bootstraps once and then needs the disk, so it does not.
     */
    @WithDefault("false")
    boolean keepBuilder();

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
     * <b>The operator's override for how many builds qits-ci runs at once.</b> Unset — the ordinary
     * case — the number is COMPUTED from this host's memory by
     * {@link CiConcurrency#concurrentBuildsFor(long)}, which is where the reason lives.
     * <p>
     * Set, it is used as it stands, including a value the formula would never choose. That is the
     * point of it: the formula is a floor under a host that would otherwise be sized by a literal,
     * not a ceiling on someone who knows the machine.
     */
    Optional<Integer> ciConcurrentBuilds();

    /**
     * What the generated files are actually filled with: the override if there is one, this host's
     * computed number otherwise.
     */
    default int ciConcurrentBuildsEffective() {
        return ciConcurrentBuilds()
                .orElseGet(() -> CiConcurrency.concurrentBuildsFor(CiConcurrency.hostMemoryBytes()));
    }

    /**
     * The standing environment's name, and <b>the platform environment</b>: the one tier the
     * platform plane is deployed into. {@code --platform-env} on the command line, {@code
     * QITS_ENV_NAME} in {@code .env}.
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

    /**
     * Whether to publish the disposable ingress on the platform domain ({@code :80}/{@code :443}) so
     * the live progress page is reachable at the same URL the normal edge takes over afterwards,
     * rather than on loopback only. <b>Defaults to on, but only takes effect where a {@link #domain()}
     * is set</b> — see {@link #bootstrapIngressPublicEffective()}. So a domain node is public by
     * default with no per-node configuration, while a domainless local boot stays loopback with no
     * error. Set to {@code false} to force loopback even on a domain node.
     */
    @WithDefault("true")
    boolean bootstrapIngressPublic();

    /**
     * The effective decision every consumer reads: public only when it was requested (the default)
     * AND a domain is configured, because public mode binds {@code :80}/{@code :443} and answers
     * exactly the domain's Host header. Without a domain there is no public URL to serve, so it falls
     * back to the loopback publish rather than failing — that is what lets the default be "public" on
     * every node without breaking a domainless dev boot.
     */
    default boolean bootstrapIngressPublicEffective() {
        return bootstrapIngressPublic() && domain().isPresent();
    }
    // WHETHER PUBLIC MODE IS TLS IS NOT A CONFIGURATION QUESTION, and there is deliberately no knob
    // for it: it depends on whether the certificate volume holds a pair, which is a fact about the
    // machine. See BootstrapIngressMode.

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

    // THERE IS NO DEPLOY REF ANY MORE, and nothing here may derive one.
    //
    // envBranch() answered "environment/<name>" and every deploy pushed it. Deployment is driven by
    // a RELEASE now: qits-ci announces SoftwareRelease per published artifact, qits-deployments
    // turns the docker one into a deployment request and pulls qits/<app>:<version>. The deployer
    // matches no branch — pd_environment lost its branch column outright — so a ref by that name is
    // a branch nothing listens to, and seeding one would leave a dead ref on every repository of
    // every platform this program touches. main is the trunk, a tag is the release, and there is no
    // third ref.

    /**
     * <b>The issuer string alone.</b> It is the {@code iss} claim of every token the platform mints
     * and the base of every endpoint the discovery document advertises — a value consumers COMPARE,
     * not a name they resolve.
     * <p>
     * It used to be the dialled address as well, and the two parted company when the platform
     * service lost its bare address. {@link #idpDialUrl} is the address now. The issuer keeps the
     * bare spelling for exactly as long as it takes every consumer to be discovering from the
     * qualified one: a string compared for equality cannot be covered by a second DNS alias, and it
     * cannot hold two values, so moving it with the addresses would reject every token in flight.
     */
    default String idpIssuer() {
        return "http://qits-platform-idp:8080/idp";
    }

    /**
     * <b>The idp's address</b> — what a consumer dials for discovery and what this program dials for
     * every call it makes. Environment-qualified like every other address on qits-net, because
     * qits-deployments gives a platform service the {@code <env>-<app>} alias beside its bare name
     * and the seed stack declares the same pair.
     * <p>
     * Derived rather than spelled, so it follows {@link PlatformModel#PLATFORM_SERVICES} the way
     * {@link #platformDeploymentsUrl} and {@link #eventsUrl} do — with the difference that
     * {@link PlatformModel#dialAlias} qualifies EVERY plane, which is the whole direction of
     * travel.
     */
    default String idpDialUrl() {
        return "http://" + PlatformModel.wireAlias("idp", envName()) + ":8080/idp";
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
        return "http://" + PlatformModel.wireAlias("mirror", envName()) + ":8080";
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
     * The two prefixes belong to the git host and are dialled at its fixed seed alias.
     */
    default String gitHostHealthUrl() {
        return "http://" + envName() + "-qits-githost:8080/githost";
    }

    /**
     * qits-ci at its fixed seed alias. Deployment routes are not present until the edge projects
     * deployment events, so the bootstrap cannot use a public route for this call.
     */
    default String ciUrl() {
        return "http://" + envName() + "-qits-ci:8080/ci";
    }

    /**
     * qits-deployments at its fixed seed alias, for the reason above.
     * The route segment stayed {@code /platform-deployments} when the repository was renamed —
     * it names the component, not the repository, and every route of the service (its API, its
     * health, its client) hangs off it. Only the hostname moved.
     * <p>
     * <b>The hostname is DERIVED, and that is what the 2026-08-17 plane move cost.</b> This method
     * spelled {@code <env>-qits-deployments} and therefore did not follow
     * {@link PlatformModel#PLATFORM_SERVICES}: the moment the deployer moved plane, every call this
     * program makes to it — the environment reconcile, the build-event replay, the auth-plane probe
     * — would have gone to a name nothing answers to.
     */
    default String platformDeploymentsUrl() {
        return "http://" + PlatformModel.wireAlias("deployments", envName())
                + ":8080/platform-deployments";
    }

    /**
     * qits-events — the BUS — at its fixed seed alias. This service serves everything it has
     * under that one segment, health included.
     * <p>
     * Dialled for one purpose: the seed health wait. Nothing this program does publishes or reads
     * an event over HTTP — the announcements travel ci's outbox and the deployer's subscriber — but
     * a bus nobody waited for is a bus the first green build can outrun.
     * <p>
     * Derived for the same reason the deployer's url is: the bus moved plane on the same day.
     */
    default String eventsUrl() {
        return "http://" + PlatformModel.wireAlias("events", envName()) + ":8080/events";
    }

    /**
     * qits-configuration at its fixed seed alias, with <b>no path</b> — the same shape
     * {@link #mirrorUrl()} has and for a related reason: this exact string is also what the deployer
     * is handed as {@code QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL}, and that reader appends
     * {@code /configuration/api/applications/<app>/resolved} itself. A base carrying the segment
     * would be right here and doubled there.
     * <p>
     * <b>Its own alias rather than the edge, and that is deliberate.</b> The bootstrap's import
     * asserts {@code X-Qits-User} / {@code X-Qits-Roles} on a private qits-net hop, the way it does
     * to the deployer — and the edge strips client-supplied identity headers from every request it
     * proxies, which is exactly the property that makes it safe as a public door. Sending this
     * through it would put the write behind a header the door is built to throw away.
     * <p>
     * <b>The hostname is DERIVED, and the 2026-09-07 plane move is what that cost.</b> This method
     * spelled {@code <env>-qits-configuration} and so did not follow
     * {@link PlatformModel#PLATFORM_SERVICES}: the moment the store moved plane, the import phase's
     * health wait and its POST would both have gone to a name nothing answers to — and the same
     * string is handed to the deployer as {@code QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL}, which
     * refuses a deployment it cannot resolve rather than falling back to the file. Neither the
     * no-path shape above nor the not-the-edge argument changed with the plane; only the host did.
     */
    default String configurationUrl() {
        return "http://" + PlatformModel.wireAlias("configuration", envName()) + ":8080";
    }

    /**
     * qits-projects at its fixed alias, with <b>no path</b> — the shape
     * {@link #configurationUrl()} has and for the same family of reason: this string is also what
     * qits-ci is handed as {@code QITS_CI_PROJECTS_URL}, and that reader appends
     * {@code /projects/api/repositories} itself.
     * <p>
     * <b>Its own alias rather than the edge</b>, because this program asserts
     * {@code X-Qits-User}/{@code X-Qits-Roles} on the hop and the edge strips exactly those headers
     * from what it proxies.
     * <p>
     * It is dialled from {@code seed-health} on, because qits-projects is a seed service: the
     * alias table has to answer before this run creates the first repository, not after that
     * service's own deployment fourteen phases later.
     */
    default String projectsUrl() {
        return "http://" + envName() + "-qits-projects:8080";
    }

    /**
     * <b>The three names the HOST reaches this platform's byte plane by.</b> Each is
     * {@code <app>.<env>.localhost:<edge port>}: every {@code *.localhost} name resolves to the
     * loopback address (systemd-resolved synthesises it), so a client on the workstation arrives at
     * the edge, which routes by the NAME rather than by a path — a docker client and a git client
     * own their own roots ({@code /v2}, {@code /git}) and cannot be given a prefix.
     * <p>
     * <b>These three are the MACHINE plane's names and they have not moved with the browser
     * ones.</b> A browser name is now {@code <app>[.<env>].<project>.<domain>} — see
     * {@link #envAuthority()} — while these stay {@code <app>.<env>.localhost:<port>}, because
     * moving them moves dockerd's configuration on every workstation, the {@code FROM} line of
     * every committed Dockerfile and every clone url at once. They are named here because they are
     * the ones a person has to configure by hand: dockerd's insecure registries and mirror list,
     * and a clone url.
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
     * <b>The STATED DOMAIN, as an authority</b>: {@code <domain>} where one is configured,
     * {@code localhost:<port>} where there is none.
     * <p>
     * <b>The edge reads every name it serves right to left against this value</b>, and it has to be
     * stated because it cannot be derived — {@code example.co.uk} is two labels of domain and
     * {@code localhost} is one. The edge takes it from {@code qits.edge.acme.domain}, which
     * {@code DomainTokens} writes whenever a domain is configured, and with ACME off from the
     * authority of {@link #publicOrigin()}. Both sources are this one value, which is why the local
     * door is the bare apex: see {@link #publicOrigin()}.
     */
    default String domainAuthority() {
        return DomainName.of(this).orElse("localhost:" + port());
    }

    /**
     * <b>This platform's own project door</b>, {@code qits.<domain>} — and under the hostname
     * grammar the edge now reads, every browser name this platform serves sits inside it.
     * <p>
     * The grammar is {@code <app> [ .<env> ] .<project> .<domain>}, read right to left, each label
     * inside the one to its right: the domain holds projects, a project holds its environments, an
     * environment holds its apps. <b>The project label is MANDATORY</b> — there is no unqualified
     * application tier and no top-level {@code <env>.<domain>} tier any more — so the platform is
     * not a special case in the names: it is simply the project called {@code qits}, and
     * {@link PlatformModel#PROJECT} is that slug.
     */
    default String projectAuthority() {
        return PlatformModel.PROJECT + "." + domainAuthority();
    }

    /**
     * <b>The INNERMOST DOOR of this platform's own project, and the one place the environment label
     * lives</b>: {@code <env>.qits.<domain>}, or {@code <env>.qits.localhost:<port>} locally. An
     * application of this platform is exactly one label in front of it —
     * {@code ci.<env>.qits.<domain>}.
     * <p>
     * <b>Which door is innermost is decided by the project's {@code supportsEnvironments} flag and
     * by nothing else.</b> An env-supporting project's innermost door is its environment's, an
     * env-less project's is its own — one rule, because the grammar nests. The {@code qits} project
     * supports environments today, so the env label is here; when its {@code project.yml} declares
     * otherwise this method loses that label and every browser name below follows it, which is why
     * there is one method rather than a spelling per key.
     * <p>
     * <b>The allow-list does not wait for that.</b> {@link #browserSsoHosts()} names both shapes on
     * purpose, so a flag that flips under a running platform opens no hole and closes no door; the
     * values that must move with the flag are {@link #idpOrigin()} and {@link #webauthnOrigins()},
     * which name ONE host each and cannot hold two.
     * <p>
     * Nothing has to resolve the local form: every {@code *.localhost} name answers loopback in
     * Chromium and Firefox, and on the host through nss-myhostname or systemd-resolved — no
     * hosts-file entry. It stays a secure context too, so passkeys work over plain HTTP.
     */
    default String envAuthority() {
        return envName() + "." + projectAuthority();
    }

    /**
     * <b>The address a person's browser arrives at</b>, which is the edge's canonical session
     * origin: this platform's own PROJECT DOOR over TLS where there is a domain,
     * {@code https://qits.<domain>}, and the bare apex {@code http://localhost:<port>} where there
     * is none.
     * <p>
     * Derived rather than configured, because it is decided twice already: the port is the edge's
     * publish and the domain is the certificate's name. A third address told to a browser would be
     * a login page nobody can reach.
     * <p>
     * <b>It was the bare apex on a domain platform, and the apex is not a name any more.</b> Every
     * application address carries a project label now, so a canonical origin that carries none
     * leaves the edge nothing to compose: the apex answers an honest 404 rather than a redirect to
     * a name that 404s one hop later. Naming the project's door is what puts a front door back —
     * the edge reads THIS value by the same right-to-left grammar as a request's own Host, and a
     * name that names no project falls back to it.
     * <p>
     * <b>The local half must stay the bare apex, and that is not an inconsistency.</b> With ACME
     * off the edge has no {@code qits.edge.acme.domain} and takes the stated domain from this
     * value's authority — so {@code http://qits.localhost:8080} here would make
     * {@code qits.localhost} the DOMAIN and the grammar would read every name one tier out. On a
     * domain platform the two are independent, which is where naming the project's door is the
     * useful spelling. See {@link #domainAuthority()}.
     * <p>
     * <b>NOTHING IS SERVED ON A DOOR BUT A REDIRECT.</b> It answers {@code GET /} with a 302 to the
     * projects host and 404s every other path — the login is on the idp's own host, see
     * {@link #idpOrigin()}.
     */
    default String publicOrigin() {
        return DomainName.of(this).map(domain -> "https://" + PlatformModel.PROJECT + "." + domain)
                .orElse("http://" + domainAuthority());
    }

    /**
     * <b>Where a person logs in</b>, and an application of the {@code qits} project like every
     * other, which means {@code idp.} of {@link #envAuthority()} on both kinds of platform:
     * {@code idp.<env>.qits.<domain>} with a domain, {@code idp.<env>.qits.localhost:<port>}
     * locally. The login page is {@code <idpOrigin>/idp/login}.
     * <p>
     * It is the idp's canonical browser origin and the one origin a WebAuthn ceremony is accepted
     * from. A door serves no {@code /idp/...} path, so an address built on {@link #publicOrigin()}
     * would be a 404.
     * <p>
     * <b>It names ONE host and cannot hold two</b>, so it is one of the two values that move when
     * the {@code qits} project's {@code supportsEnvironments} flag flips — through
     * {@link #envAuthority()}, which is where that label lives and the only place it is spelled.
     * <p>
     * <b>This does NOT move {@link #webauthnRpId()}, and moving it would be the expensive
     * mistake.</b> A passkey is bound to the rp id; a credential asserts on the rp id AND its
     * children, so {@code idp.<env>.qits.<domain>} is covered by {@code <domain>} exactly as
     * {@code idp.<domain>} was. Changing the rp id invalidates every passkey ever registered
     * against this platform.
     */
    default String idpOrigin() {
        return (DomainName.of(this).isPresent() ? "https://idp." : "http://idp.") + envAuthority();
    }

    /**
     * <b>The WebAuthn relying party, which is a HOST and not a URL.</b> A passkey is bound to it and
     * asserts under no other name, so it follows the door's authority rather than standing beside
     * it.
     * <p>
     * <b>Moving the login to {@link #idpOrigin()} does not move this.</b> A credential asserts on
     * the rp id AND its children, so {@code idp.<domain>} is covered by {@code <domain>} and
     * {@code idp.<env>.localhost} by {@code <env>.localhost}. Registered passkeys keep working.
     * <p>
     * Every {@code *.localhost} name is a secure context by itself — no certificate needed — which
     * is what lets a passkey work on this platform's plain HTTP port. The one route without a
     * secure context is a raw IP, where the browser offers no ceremony at all and only a password
     * logs in.
     * <p>
     * <b>Locally it is {@code qits.localhost}, the PROJECT's door</b>, because that is what the
     * local login host is a child of: the ceremony happens at
     * {@code idp.<env>.qits.localhost:<port>}, and a credential asserts on the rp id and every name
     * under it. It was {@code <env>.localhost}, which the project label retired — that name is not
     * a parent of anything the platform serves any more, so a passkey bound to it asserts nowhere
     * and has to be registered again; the closing report says so. It is deliberately the project's
     * door rather than the environment's, so the {@code supportsEnvironments} flag cannot invalidate
     * a passkey by flipping.
     * <p>
     * The binding costs nothing here: accounts are per-installation, so a platform that gains a
     * domain registers its own from its own register token.
     */
    default String webauthnRpId() {
        return DomainName.of(this).orElse(PlatformModel.PROJECT + ".localhost");
    }

    /**
     * The origins a ceremony is accepted from — {@link #idpOrigin} and nothing else. It is a LIST on
     * the idp's side and one entry here, because the login page has one address; it moves with
     * {@link #envAuthority()} for that reason.
     */
    default String webauthnOrigins() {
        return idpOrigin();
    }

    /**
     * The browser authorities a completed IdP ceremony may return to, in this platform's own
     * project: its door, {@code *.} of that door, and {@code *.} of its environment's door.
     * <p>
     * <b>It is an ALLOW-LIST and not a parent-suffix check</b>, which is why it names shapes rather
     * than describing one. The edge and the idp both read a {@code *.} entry as exactly ONE extra
     * label on the same port, so {@code *.<env>.qits.<domain>} admits
     * {@code ci.<env>.qits.<domain>} and nothing deeper. That single wildcard is what makes ONE
     * session cover every service, since each application has a browser host of its own.
     * <p>
     * <b>The canonical origin's own authority leads the list, because the edge refuses to start
     * without it there.</b> With a domain that is the project door {@link #projectAuthority()};
     * locally it is the bare apex, for the reason {@link #publicOrigin()} gives, and the project
     * door is then named beside it.
     * <p>
     * <b>BOTH depths of the project are listed, and that is deliberate.</b> Whether an application
     * of {@code qits} is {@code <app>.qits.<domain>} or {@code <app>.<env>.qits.<domain>} is the
     * project's live {@code supportsEnvironments} flag, which this file is written long before and
     * which may flip under a running platform. An allow-list entry for a name the edge does not
     * serve admits nobody — there is no router here to confuse — so naming both costs nothing and
     * means a flip never strands a person mid-login on a host the idp will not return them to.
     * The {@code *.qits.<domain>} entry also covers the environment door itself,
     * {@code <env>.qits.<domain>}, which is one label under the project's.
     * <p>
     * <b>The idp host needs no entry of its own.</b> {@link #idpOrigin()} is {@code idp.} of
     * {@link #envAuthority()}, which a listed wildcard already admits: one extra label, same port.
     * <p>
     * <b>What is NOT on the list any more is the apex and {@code *.<domain>}.</b> The top-level
     * {@code <env>.<domain>} tier and the unqualified application tier are gone from the grammar,
     * so neither shape is a name the edge serves — and another project's names are another
     * project's to allow, which is what an allow-list is for.
     */
    default String browserSsoHosts() {
        String project = projectAuthority();
        String environment = envAuthority();
        return DomainName.of(this)
                .map(domain -> project + ",*." + project + ",*." + environment)
                .orElse(domainAuthority() + "," + project + ",*." + project + ",*." + environment);
    }

    /**
     * The parent a session cookie is shared with: the domain where there is one —
     * {@code Domain=<domain>} covers {@code <app>.<env>.qits.<domain>} and every other project's
     * names with it — and this platform's project door {@code qits.localhost} locally. A cookie
     * domain carries no port, so the local value drops it.
     * <p>
     * <b>It cannot be bare {@code localhost}</b>: that name is a public suffix and browsers drop a
     * cookie scoped to it, which is what the local platform needed a parent label for at all. It is
     * the PROJECT's door rather than the environment's for the same reason
     * {@link #webauthnRpId()} is — one label further out costs nothing and survives the
     * {@code supportsEnvironments} flag.
     */
    default String browserSsoCookieDomain() {
        return DomainName.of(this).orElse(PlatformModel.PROJECT + ".localhost");
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
