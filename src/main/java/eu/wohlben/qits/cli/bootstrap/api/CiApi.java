package eu.wohlben.qits.cli.bootstrap.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

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

    // THERE IS NO postReceive REPLAY ANY MORE, and its absence is the byte-plane split's dividend.
    // POST /ci/api/events/post-receive was the git host's fire-and-forget announcement, and this
    // CLI re-made it whenever a pushed sha had no run after a minute — a real loss, measured twice
    // on this platform. qits-githost publishes SCMPublishCommit through the eventstream outbox
    // instead and qits-ci consumes it durably, so a push that landed is a row that will be
    // delivered: a ci that was down, restarting or mid-cutover reads it back. The endpoint is gone
    // from qits-ci, and a replay of it would be a call to nothing.

    /**
     * The id and status of the newest finished EVENT run of a repository, if one has finished.
     * The id travels with the status so a caller can hold a baseline: on a rerun the newest
     * finished row is the PREVIOUS attempt's, and reading it as this attempt's outcome fails a
     * phase in zero seconds while the fresh run is still executing — measured on the first prod
     * bootstrap, the same stale-row family the deploy wait already guards against.
     */
    public Optional<String[]> finishedEventRun(String repoId) {
        List<String[]> all = finishedEventRuns(repoId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /**
     * Every finished EVENT run of a repository in the window, newest first, as
     * {@code [id, status, triggerEventName, commitSha]}. All of them rather than the newest alone,
     * because "EVENT run" is not "release run": a follow-up bump fired by an upstream's
     * SoftwareRelease is an EVENT run of this same repository — a 1-second quiet-exit that landed
     * NEWEST during the first bus-only bootstrap and hid the release run behind it. The trigger
     * event's NAME is what tells them apart, and it rides on the run row itself — resolving the
     * event against qits-events would find nothing for a manually triggered run, whose event never
     * touches the bus.
     */
    public List<String[]> finishedEventRuns(String repoId) {
        Http.Response response = http.get(base + "/api/runs/finished?limit=20", Map.of());
        if (!response.ok()) {
            return List.of();
        }
        List<String[]> runs = new ArrayList<>();
        for (JsonNode run : Json.parse(response.body()).path("runs")) {
            if (repoId.equals(Json.text(run, "repoId"))
                    && "EVENT".equals(Json.text(run, "triggerType"))
                    && !Json.text(run, "status").isBlank()) {
                runs.add(new String[] {Json.text(run, "id"), Json.text(run, "status"),
                        Json.text(run, "triggerEventName"), Json.text(run, "commitSha")});
            }
        }
        return runs;
    }

    /**
     * Whether a green SCMRelease-triggered run already exists at this commit — the question the
     * release replay's skip asks. The sha rather than the version, because the version lives only
     * on the trigger event and a manually triggered run's event is nowhere to resolve; the sha is
     * on the run row, and the tag the replay would push names exactly one.
     */
    public boolean greenReleaseRunAt(String repoId, String commitSha) {
        Http.Response response = http.get(base + "/api/runs?repositoryId=" + repoId + "&limit=20",
                Map.of());
        if (!response.ok()) {
            return false;
        }
        for (JsonNode run : Json.parse(response.body()).path("runs")) {
            if ("EVENT".equals(Json.text(run, "triggerType"))
                    && "SUCCESS".equals(Json.text(run, "status"))
                    && "SCMRelease".equals(Json.text(run, "triggerEventName"))
                    && commitSha.equals(Json.text(run, "commitSha"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The newest run of a repository, whatever it is. The caller needs the whole row, not just the
     * status: a repository can hold several runs at one commit, so only the row's id says whether
     * the newest one is this phase's or an earlier one's.
     */
    public Optional<JsonNode> newestRun(String repoId) {
        Http.Response response = http.get(base + "/api/runs?repositoryId=" + repoId + "&limit=1", Map.of());
        if (!response.ok()) {
            return Optional.empty();
        }
        for (JsonNode run : Json.parse(response.body()).path("runs")) {
            return Optional.of(run);
        }
        return Optional.empty();
    }

    /**
     * One run with its steps, their output and — while it runs — the step in flight, as
     * {@code live}. This is qits-ci's whole log surface: it serves no SSE and no websocket for run
     * output, so following a build along is polling here, which is what its own client does.
     */
    public Optional<JsonNode> run(String runId) {
        Http.Response response = http.get(base + "/api/runs/" + runId, Map.of());
        return response.ok() ? Optional.of(Json.parse(response.body())) : Optional.empty();
    }

    /**
     * Why a run failed, in the words of the step that failed it. A red run otherwise reports only
     * its status, and the reason is three API calls away — which is three calls made by hand, at
     * the point where a bootstrap has just stopped and the operator has the least context. The tail
     * is bounded because a build log is not a thing to print in full.
     */
    public Optional<String> failedStepOutput(String runId) {
        Optional<JsonNode> run = run(runId);
        if (run.isEmpty()) {
            return Optional.empty();
        }
        for (JsonNode step : run.get().path("steps")) {
            if (!"FAILED".equals(Json.text(step, "status"))) {
                continue;
            }
            String output = Json.text(step, "output");
            if (output.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(output.length() <= 1200 ? output
                    : "…" + output.substring(output.length() - 1200));
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
