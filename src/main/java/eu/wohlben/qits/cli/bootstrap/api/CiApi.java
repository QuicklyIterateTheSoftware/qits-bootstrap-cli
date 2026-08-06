package eu.wohlben.qits.cli.bootstrap.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** qits-ci: the manual event trigger and the run listings the waits poll. */
public class CiApi {

    private final Http http;
    private final String base;

    public CiApi(Http http, String ciUrl) {
        this.http = http;
        this.base = ciUrl;
    }

    public Http.Response health() {
        return http.get(base + "/q/health/ready", Map.of());
    }

    public boolean ready() {
        return health().ok();
    }

    /**
     * Stands in for the announcement a real release would have made. The trigger demands the one
     * project=* client — the same identity the git host uses to announce pushes.
     */
    public Http.Response trigger(String eventJson, String token) {
        return http.postJson(base + "/api/events/trigger", eventJson, bearer(token));
    }

    /** Replays a post-receive announcement the git host made and nobody heard. */
    public Http.Response postReceive(String eventJson, String token) {
        return http.postJson(base + "/api/events/post-receive", eventJson, bearer(token));
    }

    /** The status of the newest finished EVENT run of a repository, if it has finished. */
    public Optional<String> finishedEventRunStatus(String repoId) {
        Http.Response response = http.get(base + "/api/runs/finished?limit=20", Map.of());
        if (!response.ok()) {
            return Optional.empty();
        }
        JsonNode runs = Json.parse(response.body()).path("runs");
        for (JsonNode run : runs) {
            if (repoId.equals(Json.text(run, "repoId")) && "EVENT".equals(Json.text(run, "triggerType"))) {
                String status = Json.text(run, "status");
                return status.isBlank() ? Optional.empty() : Optional.of(status);
            }
        }
        return Optional.empty();
    }

    /** The status of the run of one commit, if qits-ci has one. */
    public Optional<String> runStatus(String repoId, String commitSha) {
        Http.Response response = http.get(base + "/api/runs?repositoryId=" + repoId + "&limit=1", Map.of());
        if (!response.ok()) {
            return Optional.empty();
        }
        for (JsonNode run : Json.parse(response.body()).path("runs")) {
            if (commitSha.equals(Json.text(run, "commitSha"))) {
                String status = Json.text(run, "status");
                return status.isBlank() ? Optional.empty() : Optional.of(status);
            }
        }
        return Optional.empty();
    }

    private static Map<String, String> bearer(String token) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (token != null && !token.isBlank()) {
            headers.put("Authorization", "Bearer " + token);
        }
        return headers;
    }
}
