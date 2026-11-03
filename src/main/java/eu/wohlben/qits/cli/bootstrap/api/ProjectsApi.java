package eu.wohlben.qits.cli.bootstrap.api;

import java.util.Map;
import java.util.function.Supplier;

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
 * <b>The identity is the bootstrap's own bearer</b>, presented at the service's own alias on
 * qits-net — the way this program talks to the deployer's and ci's own authorized APIs — and
 * nothing else. Deliberately not through the edge, which strips client-supplied identity headers
 * from what it proxies. Owner ruling 2026-10-18: no forwarded {@code X-Qits-User}/
 * {@code X-Qits-Roles} pair beside it, and no pair as a fallback when there is no bearer to
 * present — the call fails closed instead.
 */
public class ProjectsApi {

    private final Http http;
    private final String base;
    /** The bootstrap's own bearer, read per call — see {@link #headers()}. */
    private final Supplier<String> bearer;

    /**
     * @param projectsUrl scheme, host and port with no path — qits-ci is told the same
     * @param bearer the bootstrap's own machine token, minted against the platform-wide audience
     */
    public ProjectsApi(Http http, String projectsUrl, Supplier<String> bearer) {
        this.http = http;
        this.base = projectsUrl.endsWith("/")
                ? projectsUrl.substring(0, projectsUrl.length() - 1)
                : projectsUrl;
        this.bearer = bearer;
    }

    public String base() {
        return base;
    }

    /**
     * What every call here presents: the bootstrap's own bearer.
     *
     * @throws IllegalStateException when there is none to present
     */
    private Map<String, String> headers() {
        String token = bearer.get();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("no bearer to present to qits-projects — every call "
                    + "here needs the bootstrap's own machine token, and none was minted");
        }
        return Map.of("Authorization", "Bearer " + token);
    }

    /**
     * Readiness at this service's own non-application root path — {@code /projects/q}, not the bare
     * {@code /q} a prefix-routing edge could never reach.
     */
    public Http.Response health() {
        return http.get(base + "/projects/q/health/ready", headers());
    }

    /** Every project this service holds. The bootstrap wants exactly one of them, by name. */
    public Http.Response projects() {
        return http.get(base + "/projects/api/projects", headers());
    }

    /**
     * Registers a repository the git host already serves as a component of {@code projectId}.
     * Idempotent: an id this service already holds answers 200 with the row it found, so a rerun
     * costs one request per repository and changes nothing.
     *
     * @param repositoryId the git host's storage id — what {@code PUT /git/<id>} created
     * @param name the public coordinate, the {@code <repoName>} half of the clone url
     * @param url the forge this repository is backed up to, or null for none
     */
    public Http.Response adoptRepository(String projectId, String repositoryId, String name,
            String url) {
        String body = url == null || url.isBlank()
                ? Json.object("repositoryId", repositoryId, "name", name)
                : Json.object("repositoryId", repositoryId, "name", name, "url", url);
        return http.postJson(base + "/projects/api/projects/" + projectId + "/repositories/adopt",
                body, headers());
    }

    /**
     * What a name resolves to, asked of this service's own alias table — the one qits-githost now
     * keeps a copy of for itself, pushed by this service on every name change — so a 200 here is
     * proof that the public url serves, rather than an inference from a row this run wrote.
     */
    public Http.Response repositoryByName(String projectId, String name) {
        return http.get(base + "/projects/api/projects/" + projectId + "/repositories/by-name/"
                + name, headers());
    }
}
