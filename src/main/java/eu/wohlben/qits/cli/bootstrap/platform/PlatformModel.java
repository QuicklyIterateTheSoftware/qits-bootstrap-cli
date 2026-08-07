package eu.wohlben.qits.cli.bootstrap.platform;

import java.util.ArrayList;
import java.util.List;

/**
 * Which repositories the platform is made of, and what each one is to the bootstrap. Ported from
 * the lists at the top of {@code qits-local-up.sh}, comments included: they are the reason the
 * order is what it is.
 */
public final class PlatformModel {

    /**
     * The seed: hand-built for the FIRST boot only. On later runs any of these already replaced by
     * a platform-deployments deployment is skipped at compose-up, and the deploy loop hands the
     * rest over.
     * <p>
     * qits-idp is in here because every service that enforces machine auth is: a seed ci that
     * cannot reach an issuer answers 401 to the git host's very first post-receive.
     * qits-platform-deployments is in here because it owns the topology AND the docker socket —
     * nothing can create the 'dev' environment or deploy anything until it answers. It is one
     * component: it replaces the qits-cd and qits-serviceregistry pair the seed used to carry.
     */
    public static final List<String> CORE =
            List.of("gateway", "artifacts", "ci", "platform-deployments", "idp");

    /**
     * Everything the platform deploys through itself. Order matters: observability first (quiets
     * OTLP warnings earliest), idp next (every later application's tokens are minted by it, and
     * its own cutover must not fall inside another application's deploy window), the seed's own
     * repos last, platform-deployments at the very end — its deployment is the self-update handoff.
     */
    public static final List<String> DEPLOYABLES = List.of(
            "observability", "idp", "stt", "projects", "workspaces", "events", "platform-docs",
            "gateway", "artifacts", "ci", "platform-deployments");

    /**
     * The deployables on the PLATFORM plane: one instance for the whole platform, deployed from
     * {@link #PLATFORM_BRANCH} rather than from an environment's branch, and named after 'platform'
     * rather than after a tier. The authority is each repo's {@code .config/qits/deployments.yml};
     * this list is what tells the bootstrap which ref deploys them and which container name to
     * expect.
     * <p>
     * The word used to be 'singleton'. It named a cardinality where what is being said is which
     * plane a service lives on, and qits-platform-deployments — cross-environment from its first
     * commit — is why the rename happened.
     * <p>
     * qits-platform-docs is here because the thing it reads is: there is one docs repository in
     * qits-artifacts, holding what the whole platform has published, so a second reader per
     * environment would be two front doors onto one store. It holds no state of its own, which is
     * what makes that a free choice rather than a constraint.
     */
    public static final List<String> PLATFORM_SERVICES = List.of(
            "platform-deployments", "idp", "artifacts", "ci", "events", "projects",
            "observability", "platform-docs");

    /**
     * Repositories that need a repository on the platform git host and a main push, but are not
     * applications of qits-platform-deployments. qits-projects can then inventory them,
     * qits-workspaces can release them, and qits-ci can discover their event pipelines.
     * <p>
     * ci-daemon belongs here and nowhere else. It ships no image and no health endpoint, so it is
     * not a deployable — but its binary is an ordinary release-train artifact, and a release train
     * needs the repository on the git host.
     * <p>
     * qits-cd and qits-serviceregistry are here for a different reason: qits-platform-deployments
     * is their merge-back and both are superseded, so nothing deploys them any more. Their
     * histories still belong on the git host — a platform that cannot show where its own deployer
     * came from has lost the record.
     */
    public static final List<String> SEEDED_REPOS = List.of(
            "oci", "ci-daemon", "eventstream", "spa-ui-components", "userflows",
            "platform-spa-docs",
            "integrations-angular", "integrations-quarkus", "spa-home", "spa-projects",
            "spa-workspaces", "spa-artifacts", "spa-observability", "spa-events", "spa-ci",
            "spa-cd", "cd", "serviceregistry");

    /** The publishers whose released versions the wrapper builds install. Order is dependency order. */
    public static final List<String> RELEASE_PUBLISHERS =
            List.of("spa-ui-components", "integrations-angular", "eventstream", "integrations-quarkus");

    /**
     * The static clients qits-idp seeds from config. Every one of them gets a secret: a client
     * without one is refused {@code invalid_client} exactly like a wrong one, so an unused client
     * costs nothing and a used one that was forgotten costs a debugging session.
     * <p>
     * qits-platform-deployments is deliberately NOT here. It mints nothing — the merge removed the
     * oidc-client its ancestor needed to reach the registry — so it needs no client and no secret,
     * only the audience its callers may ask for. qits-cd stays for the opposite reason: a platform
     * being upgraded may still have one running, and dropping the client would lock it out.
     */
    public static final List<String> IDP_CLIENTS =
            List.of("qits-ci", "qits-cd", "qits-artifacts", "qits-workspaces", "qits-gateway");

    /**
     * The branch a platform service deploys from. It mirrors {@code environment/<name>}: a scope's
     * branch is {@code <scope>/<name>}, and the platform plane has one scope, so it has one branch.
     * <p>
     * {@code main} stays the pure integration trunk for EVERY repository — a push to it builds and
     * deploys nothing. Platform services used to deploy from main directly, which made the trunk
     * mean one thing for seven repositories and another for the rest.
     */
    public static final String PLATFORM_BRANCH = "platform/main";

    /**
     * The {@code aud} values the platform's clients may ask for: the idp's shipped five plus
     * qits-platform-deployments, which postdates them. An audience a client may not ask for is
     * {@code invalid_target}, not a silent bare call — and the key REPLACES the shipped list
     * rather than extending it, so it is restated in full.
     */
    public static final String IDP_AUDIENCES =
            "qits-ci,qits-cd,qits-artifacts,qits-workspaces,qits-gateway,qits-platform-deployments";

    private PlatformModel() {
    }

    /** Every pipeline repository: the deployables and the seeded ones. */
    public static List<String> platformRepos() {
        List<String> all = new ArrayList<>(DEPLOYABLES);
        all.addAll(SEEDED_REPOS);
        return List.copyOf(all);
    }

    /** Where a repository sits in the wrapper repository, by the role it plays. */
    public static String repoPath(String name) {
        return switch (name) {
            case "ci-daemon" -> "daemons/qits-ci-daemon";
            case "oci" -> "images/qits-oci";
            case "eventstream", "spa-ui-components", "userflows" -> "libs/qits-" + name;
            case "integrations-angular" -> "integrations/qits-integrations-angular";
            case "integrations-quarkus" -> "integrations/qits-integrations-quarkus";
            default -> name.startsWith("spa-") ? "frontends/qits-" + name : "services/qits-" + name;
        };
    }

    public static String repo(String name) {
        return "qits-" + name;
    }

    public static boolean isPlatformService(String name) {
        return PLATFORM_SERVICES.contains(name);
    }

    /**
     * What qits-platform-deployments names the container it manages for this application. Two
     * shapes because the model has two: an environment service carries its tier's name, a platform
     * service carries the word 'platform' in the same place. The prefix is {@code qits-pd-}; the
     * retired qits-cd's was {@code qits-cd-}.
     */
    public static String pdNamePrefix(String name, String envName) {
        return isPlatformService(name)
                ? "qits-pd-platform-qits-" + name + "-"
                : "qits-pd-" + envName + "-qits-" + name + "-";
    }

    /**
     * The ref whose green build deploys this application: the platform scope's branch for a
     * platform service, this environment's for everything else. {@code main} is pushed too, quietly
     * — it is the trunk both branches are fast-forwarded from and it deploys nothing on its own.
     */
    public static String deployRef(String name, String envBranch) {
        return isPlatformService(name) ? PLATFORM_BRANCH : envBranch;
    }

    /**
     * The placeholder SPA bundle a seed build needs. Seed services only need their APIs, but their
     * Dockerfiles consume an already-built SPA — and a clean checkout has no dist directory while
     * the hosted npm registry does not exist yet. The registry answers JSON only, so it has none.
     * <p>
     * qits-platform-deployments packages the qits-spa-cd submodule unchanged, so its bundle
     * directory is still named after that client — its cutover onto the new segment is a commit in
     * that repository. The path here is the one its Dockerfile's {@code test -f} guard checks, and
     * a guess would fail the seed build minutes in.
     */
    public static String seedUiPath(String name) {
        return switch (name) {
            case "gateway" -> "src/main/webui/dist/qits-spa-home/browser";
            case "platform-deployments" -> "service/src/main/webui/dist/qits-spa-cd/browser";
            default -> "service/src/main/webui/dist/qits-spa-" + name + "/browser";
        };
    }

    /** The env-var spelling of a client id: uppercase, dashes as underscores. */
    public static String clientKey(String clientId) {
        return clientId.toUpperCase().replace('-', '_');
    }
}
