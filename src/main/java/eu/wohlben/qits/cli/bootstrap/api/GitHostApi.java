package eu.wohlben.qits.cli.bootstrap.api;

import java.util.Map;

/**
 * qits-githost: the platform's git smart-HTTP host, and since the byte-plane split a service of its
 * own rather than a set of routes inside the artifacts store. It was never an artifact — it only
 * shared the blob storage — and every one of its consumers is an environment service, so it is one
 * too.
 * <p>
 * <b>The lifecycle surface is unchanged and that is deliberate</b>: the same idempotent
 * {@code PUT /git/<repoId>} with {@code {"defaultBranch": "main"}}, answering 201 when the call
 * created the repository and 200 when one was already there. Only the host and the prefix moved —
 * {@code <env>-qits-githost:8080/git} where it was {@code qits-platform-artifacts:8080/artifacts/git}.
 * <p>
 * The lifecycle endpoints are machine-only. Callers supply a short-lived bearer addressed to this
 * git host; this small HTTP facade deliberately does not mint or cache it.
 */
public class GitHostApi {

    private final Http http;
    private final String base;
    private final String healthBase;

    public GitHostApi(Http http, String gitHostUrl, String gitHostHealthUrl) {
        this.http = http;
        this.base = gitHostUrl;
        this.healthBase = gitHostHealthUrl;
    }

    public String base() {
        return base;
    }

    /**
     * Readiness at the service's own non-application root path, which is {@code /githost} and not
     * the {@code /git} the wire protocol answers on. Both prefixes are served directly by the git host, so a
     * caller reaches both; only this program has to know they are two.
     */
    public Http.Response health() {
        return http.get(healthBase + "/q/health/ready", Map.of());
    }

    /**
     * Creates a repository. Idempotent by design: 201 when this call created it, 200 when one was
     * already there — which is what makes the phase rerun-safe.
     */
    public Http.Response createRepository(String repoId, String bearer) {
        return http.putJson(base + "/" + repoId, Json.object("defaultBranch", "main"), authorization(bearer));
    }

    /**
     * <b>The INTERNAL, id-addressed url</b> — {@code /git/<storage id>}, the address of the store
     * and not of a repository anyone may hold.
     * <p>
     * It stays because two callers legitimately have nothing else: the lifecycle {@code PUT} above,
     * which creates the bare a name will later be an alias for, and every push this run makes
     * before qits-projects exists to resolve a name. Both are the bootstrap's own window. From
     * {@code register-repos} onward the run addresses {@link #gitUrl(String, String)} instead, and
     * the deployed git host closes this scheme to everything but qits-projects' own client
     * ({@code qits.githost.storage-client}).
     */
    public String gitUrl(String repoId) {
        return base + "/" + repoId;
    }

    /**
     * <b>The PUBLIC clone and push url</b> — {@code /git/<projectId>/<repoName>}, as reached from
     * qits-net. The one address CI, the daemons, a deploy push and a person ever hold.
     * <p>
     * The git host resolves the name per request through qits-projects' alias table, so this url
     * answers only once the pair is registered there. {@code projectId} is qits-projects' project
     * id — a minted uuid — because that is what its by-name lookup is keyed by.
     */
    public String gitUrl(String projectId, String repoName) {
        return base + "/" + projectId + "/" + repoName;
    }

    private static Map<String, String> authorization(String bearer) {
        if (bearer == null || bearer.isBlank() || bearer.indexOf('\r') >= 0 || bearer.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("A qits-githost bearer is required");
        }
        return Map.of("Authorization", "Bearer " + bearer);
    }
}
