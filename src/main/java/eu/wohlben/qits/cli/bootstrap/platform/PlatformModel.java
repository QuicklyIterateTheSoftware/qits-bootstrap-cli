package eu.wohlben.qits.cli.bootstrap.platform;

import java.util.ArrayList;
import java.util.List;

/**
 * Which repositories the platform is made of, and what each one is to the bootstrap. Ported from
 * the lists at the top of {@code qits-local-up.sh}, comments included: they are the reason the
 * order is what it is.
 * <p>
 * A <b>name</b> here is the repository's name without the {@code qits-} prefix, and it is also the
 * deployer's application name — {@code platform-artifacts} is qits-platform-artifacts is the
 * application {@code qits-platform-artifacts}. The 2026-08-08 rename moved the plane INTO the
 * repository names, so a name now says which plane its service is on and nothing else has to.
 */
public final class PlatformModel {

    /**
     * The seed: hand-built for the FIRST boot only. On later runs any of these already replaced by
     * a qits-deployments deployment is skipped at compose-up, and the deploy loop hands the rest
     * over.
     * <p>
     * qits-platform-idp is in here because every service that enforces machine auth is: a seed ci
     * that cannot reach an issuer answers 401 to the git host's very first post-receive.
     * qits-deployments is in here because it owns the topology AND the docker socket — nothing can
     * create the environment or deploy anything until it answers. It is one component: it replaces
     * the qits-cd and qits-serviceregistry pair the seed used to carry.
     * <p>
     * qits-platform-edge joined on 2026-08-08 and is the reason the set grew rather than moved:
     * the EDGE binds the host's only published port now, and qits-gateway publishes none. Without
     * a seed edge the CLI has no door — every call it makes to qits-ci and qits-deployments enters
     * at the edge, so the edge has to be up before the first health poll, not after the first
     * deployment. That is true whichever side of the edge the caller stands on: the CLI dials
     * {@code qits-platform-edge:8080} from qits-net, a browser dials the published port, and both
     * arrive at the same process.
     * <p>
     * qits-oci-postgresql is in here because qits-deployments refuses to boot without the database
     * it holds. It is the one member that is not a service of this platform's own making — the
     * image adds nothing to upstream postgres — and it is still hand-built and hand-started for
     * the same reason as the rest: nothing can deploy it until the deployer answers.
     */
    public static final List<String> CORE = List.of(
            "gateway", "platform-edge", "platform-artifacts", "ci", "deployments", "platform-idp",
            "oci-postgresql");

    /**
     * Everything the platform deploys through itself. Order matters: observability first (quiets
     * OTLP warnings earliest), idp next (every later application's tokens are minted by it, and
     * its own cutover must not fall inside another application's deploy window), the seed's own
     * repos last, qits-deployments at the very end — its deployment is the self-update handoff.
     * <p>
     * <b>qits-platform-edge is second to last, immediately before the deployer.</b> It is the door,
     * so its cutover is the one deployment that takes THIS PROGRAM's own door away for a beat:
     * every remaining poll of a ci run or a deployment row travels qits-platform-edge:8080 -> the
     * gateway -> the service. Three consequences decide the position:
     * <ul>
     *   <li><b>Not early.</b> An edge deployed first would carry every later phase's traffic on a
     *       binder this run has not yet watched serve anything. Late, the applications it fronts
     *       are already deployed and a broken edge is the only thing left to look at.
     *   <li><b>After qits-gateway.</b> The edge forwards to {@code <env>-qits-gateway}; deploying
     *       it once the gateway it dials is the deployed one means the pair is never mid-cutover
     *       together.
     *   <li><b>Before qits-deployments.</b> The deployer's own deployment is the self-update
     *       handoff and has to stay last — and the edge's cutover is better performed by the seed
     *       deployer that has already done every other cutover in this run than by a successor
     *       whose first act it would be.
     * </ul>
     * The edge's own deploy wait needs no host port: it is a platform service, so its liveness is
     * read from {@code docker ps} rather than from a deployment row.
     * <p>
     * <b>qits-oci-postgresql is second, right after observability, and its position is the same
     * argument the idp's is.</b> It is the deployer's own database, so its cutover must never be
     * queued beside a consumer's: it goes before everything that might one day hold a connection
     * to it. The cutover replaces the compose-seeded postgres under the deployer's own feet —
     * stop-before-start frees the volume and the host port, the successor mounts the same volume,
     * {@code pg_isready} gates it, and the deployer's pool reconnects after the blip because a
     * Plan is plain values and no deployment reads the database inside the docker window. The same
     * self-referential class as the registry pulling its own successor before stopping itself.
     */
    public static final List<String> DEPLOYABLES = List.of(
            "observability", "oci-postgresql", "platform-idp", "stt", "projects", "workspaces",
            "events", "platform-docs", "gateway", "platform-artifacts", "ci", "platform-edge",
            "deployments");

    /**
     * The deployables on the PLATFORM plane: one instance for the whole platform, deployed once
     * and joined to every environment's networks, rather than one copy per tier. The authority is
     * each repo's {@code .config/qits/deployments.yml} ({@code deployment_target: platform}); this
     * list is what tells the bootstrap which container name to expect and which wire alias to dial.
     * <p>
     * The word used to be 'singleton'. It named a cardinality where what is being said is which
     * plane a service lives on.
     * <p>
     * <b>The set shrank to four on 2026-08-08 and every member now says so in its own name.</b>
     * qits-gateway, qits-ci, qits-events, qits-projects and qits-observability went back to being
     * environment services of the one environment: the only reason the gateway was ever up here
     * was that it bound the host's port, and qits-platform-edge took that job. What is left is
     * what genuinely cannot be per-tier — the edge (one host port), the idp (one issuer and one
     * signing key), qits-platform-artifacts (one registry, one git host, one blob store) and
     * qits-platform-docs (one docs repository inside that store, so a second reader per tier would
     * be two front doors onto one shelf).
     * <p>
     * There is no platform deploy ref any more. Both planes answer the same question of a green
     * build — does an environment listen to this ref — so {@code environment/<name>} is the whole
     * set and {@code platform/main} is retired.
     */
    public static final List<String> PLATFORM_SERVICES =
            List.of("platform-edge", "platform-idp", "platform-artifacts", "platform-docs");

    /**
     * Repositories that need a repository on the platform git host and a main push, but are not
     * applications of qits-deployments. qits-projects can then inventory them, qits-workspaces can
     * release them, and qits-ci can discover their event pipelines.
     * <p>
     * ci-daemon belongs here and nowhere else. It ships no image and no health endpoint, so it is
     * not a deployable — but its binary is an ordinary release-train artifact, and a release train
     * needs the repository on the git host.
     */
    public static final List<String> SEEDED_REPOS = List.of(
            "oci", "ci-daemon", "eventstream", "spa-ui-components", "userflows",
            "platform-spa-docs", "spa-deployments",
            "integrations-angular", "integrations-quarkus", "spa-home", "spa-projects",
            "spa-workspaces", "platform-spa-artifacts", "spa-observability", "spa-events",
            "spa-ci");

    /** The publishers whose released versions the wrapper installs. Order is dependency order. */
    public static final List<String> RELEASE_PUBLISHERS =
            List.of("spa-ui-components", "integrations-angular", "eventstream", "integrations-quarkus");

    private PlatformModel() {
    }

    /** Every pipeline repository: the deployables and the seeded ones. */
    public static List<String> platformRepos() {
        List<String> all = new ArrayList<>(DEPLOYABLES);
        all.addAll(SEEDED_REPOS);
        return List.copyOf(all);
    }

    /**
     * Where a repository sits in the wrapper repository, by the role it plays.
     * <p>
     * <b>A wrong path here used to be silent.</b> The sources phase fell back to GitHub whenever
     * the wrapper path was not a checkout, so a misspelling ignored local commits and cloned last
     * week's platform instead. It is loud now: {@code SeedPhases.sources} fails when the directory
     * EXISTS and is not a checkout, and only an absent directory is answered by the org URL. The
     * rule below was walked against the wrapper on 2026-08-08 and every name in this file resolves
     * to a real checkout, so there is nothing left to special-case — but a rename that outruns
     * this method now stops the boot in the first minute rather than deploying the wrong sha.
     */
    public static String repoPath(String name) {
        return switch (name) {
            case "ci-daemon" -> "daemons/qits-ci-daemon";
            case "oci", "oci-postgresql" -> "images/qits-" + name;
            // Framework glue is shared code, so the integrations sit in libs/ like any other lib.
            case "eventstream", "spa-ui-components", "userflows",
                 "integrations-angular", "integrations-quarkus" -> "libs/qits-" + name;
            // Anything served at a URL is a frontend, whether it is spelled qits-spa-<x> or
            // qits-platform-spa-<x>. Both spellings exist and both are checked: the artifacts
            // client is qits-platform-spa-artifacts, the deployments client is qits-spa-deployments
            // — the plane moved from the client's name to the service's and back again.
            default -> name.startsWith("spa-") || name.startsWith("platform-spa-")
                    ? "frontends/qits-" + name
                    : "services/qits-" + name;
        };
    }

    /**
     * Where a repository keeps the Dockerfile its seed image is built from, relative to its own
     * root.
     * <p>
     * A service keeps it in {@code docker/} beside the other build files. An image repository IS
     * the Dockerfile, so it keeps it at the root — and its own pipeline config says
     * {@code -f Dockerfile}, which is the answer this method has to agree with. The path used to
     * be a literal in {@code SeedPhases.seedImage}, where the first repository that spelled it
     * differently would have failed with "no such file" minutes into a run.
     */
    public static String dockerfilePath(String name) {
        return switch (name) {
            case "oci-postgresql" -> "Dockerfile";
            default -> "docker/Dockerfile";
        };
    }

    public static String repo(String name) {
        return "qits-" + name;
    }

    public static boolean isPlatformService(String name) {
        return PLATFORM_SERVICES.contains(name);
    }

    /**
     * The <b>wire alias</b>: the address peers dial, and the name a cutover finds its predecessor
     * by. It is the deployer's {@code PdNetworks.alias}, restated here because the seed containers
     * have to answer to the same names the deployed ones will — a run-arg that injects
     * {@code http://prod-qits-ci:8080} is wrong for the seven minutes before ci is deployed unless
     * the seed ci is already reachable under that name.
     * <ul>
     *   <li><b>An environment service</b> is {@code <env>-<app>} — {@code prod-qits-gateway}. The
     *       qualifier is what lets two tiers hold one application's address on the network they
     *       share.
     *   <li><b>A platform service</b> keeps the bare application name — {@code
     *       qits-platform-idp}. There is one instance, so there is nothing to qualify it against,
     *       and the repository name carries the plane already.
     * </ul>
     */
    public static String wireAlias(String name, String envName) {
        return isPlatformService(name) ? repo(name) : envName + "-" + repo(name);
    }

    /**
     * What qits-deployments names the container it manages for this application — the twin of its
     * own {@code ContainerNames.of}. Two shapes because the model has two: an environment service
     * carries its tier's name, a platform service has no tier and so DROPS the segment rather than
     * filling it. It used to read {@code qits-pd-platform-<app>-}, which said the word twice once
     * the repositories carried the plane themselves ({@code qits-pd-platform-qits-platform-idp-}).
     * <p>
     * The prefix is {@code qits-pd-}; the retired qits-cd's was {@code qits-cd-}.
     */
    public static String pdNamePrefix(String name, String envName) {
        return isPlatformService(name)
                ? "qits-pd-" + repo(name) + "-"
                : "qits-pd-" + envName + "-" + repo(name) + "-";
    }

    /**
     * The placeholder SPA bundle a seed build needs, or empty when the service has no client.
     * Seed services only need their APIs, but their Dockerfiles consume an already-built SPA — and
     * a clean checkout has no dist directory while the hosted npm registry does not exist yet. The
     * registry answers JSON only, so it has none.
     * <p>
     * <b>Every seed service is spelled out</b>, because a bundle directory is the Angular project
     * key and moves whenever its client is renamed — three of the six name their client something
     * other than {@code qits-spa-<name>}, and two have no client at all. The path is the one the
     * service's Dockerfile checks with {@code test -f}, so a stale spelling fails the seed build
     * minutes in rather than at the edit.
     * <p>
     * Empty is a real answer, not a gap: qits-platform-idp ships no client, and qits-platform-edge
     * serves no paths of its own at all. {@code SeedPhases.seedImage} writes no placeholder for
     * them. A seed service added without a line here also gets none — and the Dockerfile's own
     * {@code test -f} names the exact path it wanted, which is the clearest failure available.
     */
    public static String seedUiPath(String name) {
        return switch (name) {
            case "gateway" -> "src/main/webui/dist/qits-spa-home/browser";
            case "platform-artifacts" ->
                    "service/src/main/webui/dist/qits-platform-spa-artifacts/browser";
            case "deployments" -> "service/src/main/webui/dist/qits-spa-deployments/browser";
            case "ci" -> "service/src/main/webui/dist/qits-spa-ci/browser";
            default -> "";
        };
    }

    /**
     * The static clients qits-platform-idp seeds from config, for this environment. Every one of
     * them gets a secret: a client without one is refused {@code invalid_client} exactly like a
     * wrong one, so an unused client costs nothing and a used one that was forgotten costs a
     * debugging session.
     * <p>
     * <b>A client id is a wire alias</b>, which is why this is a method and not a constant: an
     * environment service's identity carries its tier ({@code prod-qits-ci}), a platform service's
     * does not ({@code qits-platform-artifacts}). With the default environment name this is
     * exactly the list qits-platform-idp ships. With another one it follows, which the shipped
     * list cannot — and the id is part of the config KEY
     * ({@code qits.idp.client.<id>.secret}), so a client the deployment spells differently from
     * the token request is {@code invalid_client} with nothing in any log to say why.
     * <p>
     * qits-deployments is deliberately NOT here. It mints nothing — the merge removed the
     * oidc-client its ancestor needed to reach the registry — so it needs no client and no secret,
     * only the audience its callers may ask for. qits-cd is gone for the opposite reason to the
     * one that kept it: the client ids all moved on 2026-08-08, so no id in this list is one a
     * pre-rename platform would recognise anyway.
     */
    public static List<String> idpClients(String envName) {
        return IDP_CLIENT_APPS.stream().map(app -> wireAlias(app, envName)).toList();
    }

    /**
     * The APPLICATIONS behind those clients. The generated files are keyed by this name rather than
     * by the client id — {@code IDP_SECRET_CI}, not {@code IDP_SECRET_PROD_QITS_CI} — because the
     * id moves with the environment name and a placeholder cannot be spelled with a value that is
     * not known until the run starts.
     */
    public static final List<String> IDP_CLIENT_APPS =
            List.of("ci", "platform-artifacts", "workspaces", "gateway");

    /**
     * The {@code aud} values the platform's clients may ask for: every client above plus
     * qits-deployments, which holds no client because it only receives. An audience a client may
     * not ask for is {@code invalid_target}, not a silent bare call — and the key REPLACES the
     * shipped list rather than extending it, so it is restated in full.
     */
    public static String idpAudiences(String envName) {
        List<String> audiences = new ArrayList<>(idpClients(envName));
        audiences.add(wireAlias("deployments", envName));
        return String.join(",", audiences);
    }

    /** The env-var spelling of a client id: uppercase, dashes as underscores. */
    public static String clientKey(String clientId) {
        return clientId.toUpperCase().replace('-', '_');
    }
}
