package eu.wohlben.qits.cli.bootstrap.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * qits-projects: the one service that owns the alias table, and therefore the one that decides what
 * {@code /git/<projectId>/<repoName>} means.
 * <p>
 * <b>This run talks to it for one reason.</b> It creates every platform repository's bare on the git
 * host before qits-projects exists — nothing can be built out of a repository nothing hosts — so it
 * is the only party that knows both coordinates of every one of them. Handing that pairing over is
 * what makes the public clone url resolve, and it has to happen before the git host's own deployment
 * closes the id-addressed scheme behind it.
 * <p>
 * <b>The identity is asserted on a private hop</b>, the way this program talks to the deployer's and
 * qits-configuration's read APIs: {@code X-Qits-User} and {@code X-Qits-Roles} at the service's own
 * alias on qits-net, plus the bootstrap's own machine bearer when the gate is on. Deliberately not
 * through the edge, which strips client-supplied identity headers from what it proxies — the
 * property that makes it safe as a public door. It is the same pair of headers qits-ci presents to
 * this service for its own catalogue read.
 */
public class ProjectsApi {

    /**
     * Who the bootstrap says it is here. {@code qits:system} rather than {@code qits:admin}: what is
     * being registered is storage the machine minted, and the adopt route takes that role alone.
     */
    private static final Map<String, String> SYSTEM_HEADERS = Map.of(
            "X-Qits-User", "qits-bootstrap",
            "X-Qits-Roles", "qits:system");

    private final Http http;
    private final String base;

    /** @param projectsUrl scheme, host and port with no path — qits-ci is told the same */
    public ProjectsApi(Http http, String projectsUrl) {
        this.http = http;
        this.base = projectsUrl.endsWith("/")
                ? projectsUrl.substring(0, projectsUrl.length() - 1)
                : projectsUrl;
    }

    public String base() {
        return base;
    }

    /**
     * Readiness at this service's own non-application root path — {@code /projects/q}, not the bare
     * {@code /q} a prefix-routing edge could never reach.
     */
    public Http.Response health(String bearer) {
        return http.get(base + "/projects/q/health/ready", headers(bearer));
    }

    /** Every project this service holds. The bootstrap wants exactly one of them, by name. */
    public Http.Response projects(String bearer) {
        return http.get(base + "/projects/api/projects", headers(bearer));
    }

    /**
     * Registers a repository the git host already serves as a component of {@code projectId}.
     * Idempotent: an id this service already holds answers 200 with the row it found, so a rerun
     * costs one request per repository and changes nothing.
     *
     * @param repositoryId the git host's storage id — what {@code PUT /git/<id>} created
     * @param name the public coordinate, the {@code <repoName>} half of the clone url
     * @param url the forge this repository is backed up to, or null for none
     * @param archetype the wrapper directory's kind — SERVICE, LIBRARY, FRONTEND and so on
     */
    public Http.Response adoptRepository(String projectId, String repositoryId, String name,
            String url, String archetype, String bearer) {
        String body = url == null || url.isBlank()
                ? Json.object("repositoryId", repositoryId, "name", name, "archetype", archetype)
                : Json.object("repositoryId", repositoryId, "name", name, "url", url,
                        "archetype", archetype);
        return http.postJson(base + "/projects/api/projects/" + projectId + "/repositories/adopt",
                body, headers(bearer));
    }

    /**
     * What a name resolves to, asked of the same route qits-githost asks on every name-addressed
     * clone — so a 200 here is proof that the public url serves, rather than an inference from a
     * row this run wrote.
     */
    public Http.Response repositoryByName(String projectId, String name, String bearer) {
        return http.get(base + "/projects/api/projects/" + projectId + "/repositories/by-name/"
                + name, headers(bearer));
    }

    /**
     * The forwarded identity, plus the machine bearer when this platform has a gate. Both, never one
     * or the other: with the gate off there is no token to present and the headers are the identity;
     * with it on the bearer is what the service validates and the headers still name the actor.
     */
    private static Map<String, String> headers(String bearer) {
        if (bearer == null || bearer.isBlank()) {
            return SYSTEM_HEADERS;
        }
        Map<String, String> headers = new LinkedHashMap<>(SYSTEM_HEADERS);
        headers.put("Authorization", "Bearer " + bearer);
        return Map.copyOf(headers);
    }
}
