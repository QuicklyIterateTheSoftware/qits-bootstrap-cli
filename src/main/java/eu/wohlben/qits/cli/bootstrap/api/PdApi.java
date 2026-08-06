package eu.wohlben.qits.cli.bootstrap.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * qits-platform-deployments: the environments it owns and the deployment rows it records. One
 * component now — the merge-back of qits-cd and qits-serviceregistry — so an environment write is
 * a row here rather than a proxied call to a second service.
 * <p>
 * Every route sits under {@code /platform-deployments/api}, which is what the gateway routes
 * verbatim; the base this is built with carries the segment and nothing repeats it.
 */
public class PdApi {

    private final Http http;
    private final String base;

    public PdApi(Http http, String platformDeploymentsUrl) {
        this.http = http;
        this.base = platformDeploymentsUrl;
    }

    public Http.Response health() {
        return http.get(base + "/q/health/ready", Map.of());
    }

    public boolean ready() {
        return health().ok();
    }

    /** The id of the environment with this name, if there is one. */
    public Optional<String> environmentId(String name) {
        Http.Response response = http.get(base + "/api/environments", Map.of());
        if (!response.ok()) {
            return Optional.empty();
        }
        for (JsonNode environment : Json.parse(response.body()).path("environments")) {
            if (name.equals(Json.text(environment, "name"))) {
                return Optional.of(Json.text(environment, "id"));
            }
        }
        return Optional.empty();
    }


    /** The writes are machine-guarded on the merged deployer (cd's ancestors were not). */
    private static Map<String, String> bearer(String token) {
        return token == null || token.isBlank() ? Map.of()
                : Map.of("Authorization", "Bearer " + token);
    }
    public Http.Response createEnvironment(String name, String branch, String network,
            String token) {
        return http.postJson(base + "/api/environments",
                Json.object("name", name, "branch", branch, "network", network),
                bearer(token));
    }

    /**
     * RECONCILE, NEVER RECREATE. A DELETE tears down every container of the environment, which
     * here is the whole platform, the deployer included.
     */
    public Http.Response patchEnvironment(String id, String json, String token) {
        return http.patchJson(base + "/api/environments/" + id, json, bearer(token));
    }

    /** The newest deployment row of an application in an environment. */
    public Optional<JsonNode> newestDeployment(String environmentId, String applicationName) {
        Http.Response response = http.get(base + "/api/deployments?environmentId=" + environmentId, Map.of());
        if (!response.ok()) {
            return Optional.empty();
        }
        for (JsonNode deployment : Json.parse(response.body()).path("deployments")) {
            if (applicationName.equals(Json.text(deployment, "applicationName"))) {
                return Optional.of(deployment);
            }
        }
        return Optional.empty();
    }

    /**
     * Hands the deployer the build-succeeded event a green run should have announced. The intake is
     * idempotent enough for a replay: a (repo, branch, sha) it already deployed becomes a no-op
     * cutover to the same image. The branch is an argument, not 'main': the event is matched
     * against the ref that DEPLOYS the application, and an event naming the other ref matches
     * nothing.
     */
    public Http.Response buildSucceeded(String repoId, String branch, String commitSha, String token) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (token != null && !token.isBlank()) {
            headers.put("Authorization", "Bearer " + token);
        }
        return http.postJson(base + "/api/events/build-succeeded",
                Json.object("runId", "bootstrap", "repoId", repoId, "branch", branch,
                        "commitSha", commitSha),
                headers);
    }
}
