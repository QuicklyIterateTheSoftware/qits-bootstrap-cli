package eu.wohlben.qits.cli.bootstrap.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import java.util.Map;
import java.util.Optional;

/** qits-ci: the manual event door the bring-up starts release builds with, and the run listings the
 * waits poll. */
public class CiApi {

    /** The one file whose run IS a repository's release — the identity no other run fact gives. */
    public static final String RELEASE_CONFIG = ".config/qits/ci-event-release.yml";

    /** Identity asserted on the private qits-net hop to CI's now-authorized read API. */
    private static final Map<String, String> SYSTEM_HEADERS = Map.of(
            "X-Qits-User", "qits-bootstrap",
            "X-Qits-Roles", "qits:admin");

    private final Http http;
    private final String base;

    public CiApi(Http http, String ciUrl) {
        this.http = http;
        this.base = ciUrl;
    }

    public Http.Response health() {
        return http.get(base + "/q/health/ready", SYSTEM_HEADERS);
    }

    public boolean ready() {
        return health().ok();
    }

    /**
     * <b>The manual door: {@code POST /ci/api/events/trigger}, a domain event handed to ci by
     * hand.</b> It evaluates on the request thread — 200 means the run rows exist as the call
     * returns and names them, and 503 means NOTHING was accepted, so a retry loses nothing. The
     * door demands the one project=* client, the same identity the git host announces pushes with.
     * <p>
     * <b>Every release build of a bring-up starts here now.</b> A release recipe selects
     * {@code SCMRelease}, so a pushed tag starts nothing: the deployables have needed this door
     * since their recipes moved, and the release REPLAYS joined them on 2026-09-04 when the last
     * {@code SCMPublishTag} recipe went. Nothing else publishes {@code qits/<app>:<version>} or a
     * released library, and those are the only coordinates a restore can deploy or resolve.
     * <p>
     * <b>The objection that once kept the replays away from this door is gone.</b> A hand-built
     * SCMRelease used to wake the release train — a bump run in every consumer, each calling a
     * qits-workspaces that a bring-up has not deployed yet. This endpoint publishes NOTHING on the
     * bus: it evaluates recipes and records runs. And no recipe on the estate selects
     * SoftwareRelease any more, so there is no train left to wake. It is still said only for the
     * version the checkout is already standing at, or one the platform still pins.
     * <p>
     * <b>A hand-supplied SCMRelease closes qits-ci's release join by construction</b> — the event
     * that caused the run IS the release announcement, so the green run announces its {@code
     * SoftwareRelease} per declared artifact and the deployer's own subscriber does the rest. That
     * is the ordinary path, and {@link PdApi#softwareReleased} is what stands in when it is not
     * taken: a rerun whose run is long green announces nothing, because nothing ran.
     */
    public Http.Response trigger(String eventJson, String token) {
        return http.postJson(base + "/api/events/trigger", eventJson, bearer(token));
    }

    /** The runs a trigger answered with, so a caller can say whether any recipe selected it. */
    public static List<String> triggeredRunIds(Http.Response answer) {
        List<String> runs = new ArrayList<>();
        if (!answer.ok()) {
            return runs;
        }
        Json.parse(answer.body()).path("runIds").forEach(id -> runs.add(id.asText()));
        return runs;
    }

    private static Map<String, String> bearer(String token) {
        return token == null || token.isBlank() ? Map.of()
                : Map.of("Authorization", "Bearer " + token);
    }

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
     * outright while release recipes fired on SCMRelease, which is the event a bump watches too —
     * and they select SCMRelease again since 2026-09-04. Which event selects a recipe is the
     * recipe's own business and has changed twice already, while the file that ran is what the run
     * IS.
     */
    public List<String[]> finishedEventRuns(String repoId) {
        Http.Response response = http.get(base + "/api/runs/finished?limit=20", SYSTEM_HEADERS);
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
                SYSTEM_HEADERS);
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
        Http.Response response = http.get(base + "/api/runs?repositoryId=" + repoId + "&limit=1",
                SYSTEM_HEADERS);
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
        Http.Response response = http.get(base + "/api/runs/" + runId, SYSTEM_HEADERS);
        return response.ok() ? Optional.of(Json.parse(response.body())) : Optional.empty();
    }

    /**
     * Why a run ended red, in the words of the step that ended it — the step that failed, or the
     * one aborted at its deadline. A red run otherwise reports only
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
            String status = Json.text(step, "status");
            if (!"FAILED".equals(status) && !"TIMED_OUT".equals(status)) {
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
