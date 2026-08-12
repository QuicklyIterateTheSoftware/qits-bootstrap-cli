package eu.wohlben.qits.cli.bootstrap.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import java.util.Map;
import java.util.Optional;

/** qits-ci: the run listings the waits poll. Nothing here writes — this client only reads. */
public class CiApi {

    /** The one file whose run IS a repository's release — the identity no other run fact gives. */
    public static final String RELEASE_CONFIG = ".config/qits/ci-event-release.yml";

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

    // THERE IS NO TRIGGER CALL ANY MORE, and the door it used is still there for a person.
    // POST /ci/api/events/trigger took a hand-built SCMRelease, and the release replays presented
    // one per publisher — an announcement of NOVELTY made by a restore, which woke the release
    // train against a platform that was half deployed: every consumer's bump run ended in a
    // release call to qits-workspaces, which the boot deploys minutes later. The replays push the
    // release tag and nothing else now; the recipes select on SCMPublishTag, so the push IS the
    // trigger. The endpoint, and the one project=* client that opens it, are kept for a person
    // asking qits-ci for a run by hand.

    // THERE IS NO postReceive REPLAY EITHER, and its absence is the byte-plane split's dividend.
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
     * {@code [id, status, configPath, commitSha]}. All of them rather than the newest alone,
     * because "EVENT run" is not "release run": a follow-up bump fired by an upstream's release is
     * an EVENT run of this same repository — a 1-second quiet-exit that landed NEWEST during the
     * first bus-only bootstrap and hid the release run behind it. The CONFIG PATH is what tells
     * them apart, and it is the fact to ask by: every event run is recorded at main's head, so the
     * sha collides — measured, fourth proving run, where a name-and-sha match skipped a replay
     * whose images were never published. The trigger NAME is no answer either. It collided
     * outright while release recipes fired on SCMRelease, which is the event a bump watches too;
     * they select on SCMPublishTag now, and the collision is gone with it — but which event
     * selects a recipe is the recipe's own business and has changed once already, while the file
     * that ran is what the run IS.
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
                        Json.text(run, "configPath"), Json.text(run, "commitSha")});
            }
        }
        return runs;
    }

    /**
     * Whether a green run of the RELEASE PIPELINE already exists at this commit — the question the
     * release replay's skip asks. The config path is the identity, for the reasons above: the sha
     * collides with an upstream-fired bump run of the same repository, and the trigger name is a
     * property of the recipe rather than of the run.
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
                    && RELEASE_CONFIG.equals(Json.text(run, "configPath"))
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

}
