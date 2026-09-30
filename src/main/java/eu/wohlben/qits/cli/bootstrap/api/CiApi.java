package eu.wohlben.qits.cli.bootstrap.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** qits-ci: the manual event door the bring-up starts release builds with, and the run listings the
 * waits poll. */
public class CiApi {

    /**
     * The one file whose run IS a repository's release — the identity no other run fact gives.
     * <p>
     * <b>It is the SLOT file now, not a trigger file.</b> Every repository on the estate declares
     * {@code .config/qits/release.yml} — slots, an archetype and the artifacts it publishes — and
     * qits-ci composes the two trigger documents from it at evaluation time, stamping the composed
     * run with this path. The hand-written pair it replaced
     * ({@code ci-event-release-request.yml} / {@code ci-event-release.yml}) survives in no
     * repository, so the old value matched no run row and every question asked by config path
     * silently answered no. <b>The value must equal {@code CiReleaseSlotParser.CONFIG_PATH} in
     * qits-ci</b>, which is what stamps the row; they move together or the questions below stop
     * being asked of anything.
     */
    public static final String RELEASE_SLOTS = ".config/qits/release.yml";

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
     * is the ONLY path now. {@code PdApi#softwareReleased} used to stand in when it was not taken —
     * a rerun whose run is long green announces nothing, because nothing ran — and that intake is
     * gone from the deployer: there is no third state between the right version running and a
     * rebuild, so a boot that wants a version deployed replays the release and lets the build
     * publish it.
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

    // --- the runners ------------------------------------------------------------------------------

    /**
     * <b>{@code POST /ci/api/runners}: declare a runner.</b> 201 answers the runner flat, plus
     * {@code installScript} — the one line that carries its registration token, and the only place
     * qits-ci ever writes that value. 409 is a name already taken, and 503 a qits-ci whose oidc
     * client is off: it commissions nothing, a runner's credential included.
     * <p>
     * <b>A machine write, with this run's own token</b>: the four lifecycle writes take
     * {@code qits:system} beside {@code qits:admin}, and a machine caller must present a token
     * addressed to {@code qits-platform} — {@code MachineAuth.require}, the same audience every
     * guarded service here validates. With the gate off there is no token, and the forwarded
     * identity is the whole credential, as it is on the reads.
     * <p>
     * <b>Never {@link Http.Response#describe()} a successful answer</b>: its body carries the token.
     */
    public Http.Response createRunner(String name, int slots, String token) {
        // NO PLANE, deliberately: qits-ci's default is EDGE where it knows the platform's domain
        // and INTERNAL where it knows none, which is exactly the choice this runner needs. The
        // answer says which it made.
        return http.postJson(base + "/api/runners",
                Json.object("name", name, "slots", Json.verbatim(String.valueOf(slots))),
                writer(token));
    }

    /** {@code GET /ci/api/runners}: every runner, with whether it is registered and connected. */
    public Http.Response runners() {
        return http.get(base + "/api/runners", SYSTEM_HEADERS);
    }

    /**
     * {@code POST /ci/api/runners/{id}/registration-token}: a fresh registration token for a runner
     * that has not registered, in the same shape as the create's answer. A registered runner is
     * 409 — it has spent its registration and needs no token.
     */
    public Http.Response rotateRegistrationToken(String runnerId, String token) {
        return http.postJson(base + "/api/runners/" + runnerId + "/registration-token", "{}",
                writer(token));
    }

    /**
     * <b>{@code POST /ci/api/runners/{id}/greenlight}: lift a runner's quarantine.</b> The door is
     * {@code qits:admin} alone — no machine role opens it — so it goes as the forwarded identity
     * the reads use, on the qits-net hop where nothing stands between this run and the service. It
     * states an outcome: a runner already in service is answered as it is.
     */
    public Http.Response greenlight(String runnerId) {
        return http.postJson(base + "/api/runners/" + runnerId + "/greenlight", "{}",
                SYSTEM_HEADERS);
    }

    /** Every runner of a {@link #runners()} answer, or none when the listing did not answer. */
    public static List<JsonNode> runnersIn(Http.Response listing) {
        List<JsonNode> runners = new ArrayList<>();
        if (listing.ok()) {
            Json.parse(listing.body()).path("runners").forEach(runners::add);
        }
        return runners;
    }

    /** The one runner of this name in a {@link #runners()} answer, if it is there. */
    public static Optional<JsonNode> runnerNamed(Http.Response listing, String name) {
        for (JsonNode runner : runnersIn(listing)) {
            if (name.equals(Json.text(runner, "name"))) {
                return Optional.of(runner);
            }
        }
        return Optional.empty();
    }

    /**
     * The install line's {@code QITS_CI_RUNNER_REGISTRATION_TOKEN='…'}. qits-ci answers the token
     * nowhere else — not as a field of its own, not on any read — so the line is the value's one
     * carrier, and {@code RunnerInstallScript.line} quotes it in single quotes because the token's
     * shape ({@code RunnerInstallScript.TOKEN}) never contains one.
     */
    public static Optional<String> registrationToken(String installScript) {
        if (installScript == null) {
            return Optional.empty();
        }
        Matcher token = REGISTRATION_TOKEN.matcher(installScript);
        return token.find() ? Optional.of(token.group(1)) : Optional.empty();
    }

    /**
     * The install line's {@code QITS_CI_RUNNER_URL='…'}: where qits-ci tells THIS runner to dial it,
     * composed for the plane it chose — the public {@code https://ci.qits.<domain>} on EDGE, its
     * qits-net alias on INTERNAL. Read rather than composed again here, so the container is told
     * what the line would have told it.
     */
    public static Optional<String> runnerUrl(String installScript) {
        if (installScript == null) {
            return Optional.empty();
        }
        Matcher url = RUNNER_URL.matcher(installScript);
        return url.find() ? Optional.of(url.group(1)) : Optional.empty();
    }

    private static final Pattern RUNNER_URL = Pattern.compile("QITS_CI_RUNNER_URL='([^']+)'");

    private static final Pattern REGISTRATION_TOKEN =
            Pattern.compile("QITS_CI_RUNNER_REGISTRATION_TOKEN='([^']+)'");

    /** A write's credential: the bearer when there is one, the forwarded identity otherwise. */
    private static Map<String, String> writer(String token) {
        return token == null || token.isBlank() ? SYSTEM_HEADERS : bearer(token);
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
     * <p>
     * <b>A composed run carries {@link #RELEASE_SLOTS} as its config path</b>, which is the whole
     * of what the slot-file migration changed here: qits-ci stamps the row with the file it
     * composed the document from, not with a file anybody committed as a pipeline. While this
     * constant still named the retired trigger file the match could not succeed, so the skip was
     * dead and every replay rebuilt what the registry already held.
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
                    && RELEASE_SLOTS.equals(Json.text(run, "configPath"))
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
