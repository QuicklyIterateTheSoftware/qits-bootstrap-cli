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
 * There is no authentication here and none to add: this service has no JAX-RS surface, no machine
 * gate and no token to mint. What guards the default branch is a push option, checked inside the
 * receive-pack — see {@code qits.repositories.git.push-token}.
 */
public class GitHostApi {

    private final Http http;
    private final String base;

    public GitHostApi(Http http, String gitHostUrl) {
        this.http = http;
        this.base = gitHostUrl;
    }

    public String base() {
        return base;
    }

    /**
     * Readiness at the service's own non-application root path. It sits under {@code /git} so a
     * prefix-routing gateway reaches health and the wire protocol through one entry; the git routes
     * themselves cannot follow it, because git treats the base as opaque.
     */
    public Http.Response health() {
        return http.get(base + "/q/health/ready", Map.of());
    }

    /**
     * Creates a repository. Idempotent by design: 201 when this call created it, 200 when one was
     * already there — which is what makes the phase rerun-safe.
     */
    public Http.Response createRepository(String repoId) {
        return http.putJson(base + "/" + repoId, Json.object("defaultBranch", "main"), Map.of());
    }

    /** The clone and push URL of a repository, as reached from qits-net. */
    public String gitUrl(String repoId) {
        return base + "/" + repoId;
    }
}
