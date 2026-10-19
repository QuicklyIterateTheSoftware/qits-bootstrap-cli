package eu.wohlben.qits.cli.bootstrap.contracts;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.Matchers;
import au.com.dius.pact.consumer.dsl.PactDslJsonArray;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.bootstrap.api.ArtifactsApi;
import eu.wohlben.qits.cli.bootstrap.api.BootstrapPublishCredential;
import eu.wohlben.qits.cli.bootstrap.api.CiApi;
import eu.wohlben.qits.cli.bootstrap.api.ConfigurationApi;
import eu.wohlben.qits.cli.bootstrap.api.EventsApi;
import eu.wohlben.qits.cli.bootstrap.api.GitHostApi;
import eu.wohlben.qits.cli.bootstrap.api.Http;
import eu.wohlben.qits.cli.bootstrap.api.IdpApi;
import eu.wohlben.qits.cli.bootstrap.api.PdApi;
import eu.wohlben.qits.cli.bootstrap.api.ProjectsApi;
import eu.wohlben.qits.cli.bootstrap.api.ServiceClientsApi;
import eu.wohlben.qits.cli.bootstrap.api.WorkspacesApi;
import eu.wohlben.qits.cli.bootstrap.phases.PhaseReads;
import eu.wohlben.qits.cli.bootstrap.ui.EventFeedReads;
import eu.wohlben.qits.cli.bootstrap.workstation.TokenClient;
import eu.wohlben.qits.cli.bootstrap.workstation.WorkstationCredential;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Every REST call the bootstrap makes to a qits service, and what it reads from each answer</b>
 * (qits-1149) — the one table {@code BootstrapConsumerPactTest} (each row against a pact mock
 * server) and {@code BootstrapPactFileTest} (the committed {@code pacts/qits-bootstrap-cli_*.json})
 * are built from. {@code BootstrapCallGuardTest} holds the table to the code: every request an API
 * class can make is a row here, or a named exclusion there.
 * <p>
 * <b>One row per (trigger, call).</b> A row names the provider state its answer comes from, the
 * request the bootstrap sends, the status it expects and the JSON paths its code reads. The answer
 * itself is never written here: it is the provider's recording of that state, cut down to the read
 * paths by {@link ReadBody}. A row that reads nothing is status-only.
 * <p>
 * <b>The request is the bootstrap's, and it must be the one the provider recorded.</b> A pact
 * matches a request body exactly, so a row is live only when its method, path, query and body are
 * what the provider's recording of that state was sent, every {@code {param}} filled from the
 * state's frozen params. A value the bootstrap takes as input (a runner's slots, a network name)
 * is passed the recorded one; a value it fixes itself (a field it always sends, a page size) has to
 * be what was recorded.
 * <p>
 * <b>The one header compared is {@code Authorization}, and only where the recording carries
 * one.</b> A door that judges its caller (qits-idp's, all of them) is recorded with the state's
 * caller credential as {@code {authorization}}; a row against it sends that header as a
 * provider-state generator, so the provider's verification presents the credential its state
 * honours, and the row's own call is configured with the pair the state's header encodes — what
 * the bootstrap puts on the wire is then exactly what the state answers. A request sent without it
 * is an honest 401, never the recorded answer, so a missing header is a difference like any other
 * and keeps the row out of the pact. A literal is the bootstrap's own fixed value — the auth-plane
 * probes' junk bearer — and is sent as itself. Where the recording carries none, the identity a
 * provider's own test runs as is the provider's business.
 * <p>
 * <b>A row the provider has not recorded is PENDING</b>, not dropped — no such (state, operation)
 * yet, or one recorded with another request. Its request, state and reads are written down, it is
 * left out of the pact file, and its test is skipped naming what it needs: the row's {@code needs},
 * and for a recorded-but-different request, the difference. The moment the provider's golden
 * masters on the class path record it, the row becomes part of the pact — and the committed file,
 * compared byte for byte, has to be rewritten on purpose ({@code -Dgolden.update=true}).
 */
final class BootstrapContract {

    /** The consumer, as every pact names it: the repository. */
    static final String CONSUMER = "qits-bootstrap-cli";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A provider: its repository (what a pact is named by) and its application (the index's). */
    record Provider(String repository, String application) {
        GoldenMasters recordings() {
            return GoldenMasters.of(application);
        }
    }

    static final Provider PROJECTS = new Provider("qits-projects-service", "qits-projects");
    static final Provider EVENTS = new Provider("qits-events-service", "qits-events");
    static final Provider CI = new Provider("qits-ci-service", "qits-ci");
    static final Provider DEPLOYMENTS = new Provider("qits-deployments-service", "qits-deployments");
    static final Provider IDP = new Provider("qits-idp-service", "qits-idp");
    static final Provider GITHOST = new Provider("qits-githost-service", "qits-githost");
    static final Provider ARTIFACTS = new Provider("qits-artifacts-service", "qits-artifacts");
    static final Provider CONFIGURATION =
            new Provider("qits-configuration-service", "qits-configuration");
    static final Provider WORKSPACES = new Provider("qits-workspaces-service", "qits-workspaces");
    static final Provider CONTAINERS = new Provider("qits-containers-service", "qits-containers");

    static final List<Provider> PROVIDERS = List.of(PROJECTS, EVENTS, CI, DEPLOYMENTS, IDP,
            GITHOST, ARTIFACTS, CONFIGURATION, WORKSPACES, CONTAINERS);

    /**
     * What a row against a door that judges its caller sends: the credential the provider's state
     * names as its {@code authorization} param, as a provider-state generator.
     */
    static final Map<String, String> CALLER = Map.of("Authorization", "{authorization}");

    /** What the auth-plane probes in {@code seed-health} present: a bearer no idp issued. */
    static final Map<String, String> JUNK_BEARER = PhaseReads.PROBE_BEARER;

    /** The {@code qits-trigger} reference: the command, and the phase of it that makes the call. */
    record Trigger(String command) {
        static Trigger phase(String phase) {
            return new Trigger("qits-bootstrap bootstrap: " + phase);
        }

        Map<String, String> reference() {
            Map<String, String> ref = new LinkedHashMap<>();
            ref.put("kind", "command");
            ref.put("app", CONSUMER);
            ref.put("command", command);
            return ref;
        }
    }

    /**
     * What the bootstrap sends. {@code path}, query values and {@code body} may name a state param
     * as {@code {name}}. {@code body} is a JSON text when {@code contentType} is JSON, any other
     * text otherwise, and null for a body-less request; {@code recordedBody} says the body is DATA
     * the bootstrap passes along — a binary, a properties file — and is the recording's own.
     */
    record Request(String method, String path, Map<String, String> query,
                   Map<String, String> headers, String body, String contentType,
                   boolean recordedBody) {

        static final String JSON = "application/json";

        static Request get(String path, String... query) {
            return new Request("GET", path, pairs(query), Map.of(), null, null, false);
        }

        static Request head(String path) {
            return new Request("HEAD", path, Map.of(), Map.of(), null, null, false);
        }

        static Request delete(String path) {
            return new Request("DELETE", path, Map.of(), Map.of(), null, null, false);
        }

        /** A POST with no body at all — {@code Http.post}. */
        static Request post(String path) {
            return new Request("POST", path, Map.of(), Map.of(), null, null, false);
        }

        static Request json(String method, String path, String body) {
            return new Request(method, path, Map.of(), Map.of(), body, JSON, false);
        }

        static Request text(String method, String path, String body, String contentType,
                            String... query) {
            return new Request(method, path, pairs(query), Map.of(), body, contentType, false);
        }

        /** A request whose body is data the bootstrap carries: the recording's, byte for byte. */
        static Request data(String method, String path, String contentType, String... query) {
            return new Request(method, path, pairs(query), Map.of(), null, contentType, true);
        }

        Request with(Map<String, String> headers) {
            return new Request(method, path, query, headers, body, contentType, recordedBody);
        }

        /** This request under the state's caller credential, {@code Authorization: {authorization}}. */
        Request asCaller() {
            return with(CALLER);
        }

        /** The {@code Authorization} this request sends, or null. */
        String authorization() {
            return headers.entrySet().stream()
                    .filter(e -> "authorization".equalsIgnoreCase(e.getKey()))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
        }

        private static Map<String, String> pairs(String... keysAndValues) {
            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
                map.put(keysAndValues[i], keysAndValues[i + 1]);
            }
            return map;
        }
    }

    /**
     * The bootstrap's own code against the mock server, asserting what it made of the answer.
     * {@code op} is the recording the row is bound to, for a row that sends its body.
     */
    @FunctionalInterface
    interface Call {
        void run(String baseUrl, Map<String, String> params, JsonNode recorded,
                 GoldenMasters.Operation op) throws Exception;
    }

    /**
     * One row.
     *
     * @param reads the JSON paths the bootstrap reads from the answer; empty is status-only
     * @param needs what the provider's recording of {@code state} must hold — said for every row,
     *     because a row that is live today goes pending the day the recording moves
     */
    record Case(Provider provider, Trigger trigger, String state, String operationId,
                Request request, int status, List<String> reads, Call call, String needs) {

        String description() {
            return trigger.command() + ": " + operationId;
        }

        /** Whether the provider's golden masters record this row's request and answer. */
        boolean recorded() {
            return provider.recordings().holds(state, operationId) && difference() == null;
        }

        /**
         * How the row's request differs from the one the provider recorded in {@code state}, or
         * null when they are the same request. Only for a recorded (state, operation).
         */
        String difference() {
            GoldenMasters recordings = provider.recordings();
            GoldenMasters.Operation op = recordings.operation(state, operationId);
            Map<String, String> params = recordings.params(state);
            List<String> differences = new ArrayList<>();
            if (op.status() != status) {
                differences.add("status " + op.status() + ", not " + status);
            }
            if (!op.method().equals(request.method())) {
                differences.add("method " + op.method() + ", not " + request.method());
            }
            if (!op.path().equals(request.path())) {
                differences.add("path " + op.path() + ", not " + request.path());
            }
            Map<String, List<String>> recordedQuery = new TreeMap<>();
            op.query().forEach((k, v) -> recordedQuery.put(k,
                    v.stream().map(value -> fill(value, params)).toList()));
            Map<String, List<String>> sentQuery = new TreeMap<>();
            request.query().forEach((k, v) -> sentQuery.put(k, List.of(fill(v, params))));
            if (!recordedQuery.equals(sentQuery)) {
                differences.add("query " + recordedQuery + ", not " + sentQuery);
            }
            String body = bodyDifference(op, params);
            if (body != null) {
                differences.add(body);
            }
            String authorization = authorizationDifference(op);
            if (authorization != null) {
                differences.add(authorization);
            }
            return differences.isEmpty() ? null : String.join("; ", differences);
        }

        /**
         * Whether the row sends the {@code Authorization} the recording was sent: none where it was
         * sent none, the same {@code {param}} where it names one, and any literal of the
         * bootstrap's own (a probe's junk bearer) against a recorded one.
         */
        private String authorizationDifference(GoldenMasters.Operation op) {
            String recorded = op.authorization();
            String sent = request.authorization();
            if (recorded == null) {
                return sent == null ? null : "no Authorization header, not " + sent;
            }
            if (sent == null) {
                return "header Authorization " + recorded + ", not none";
            }
            boolean templated = PARAM.matcher(recorded).find();
            boolean sentTemplated = PARAM.matcher(sent).find();
            return !sentTemplated || (templated && recorded.equals(sent)) ? null
                    : "header Authorization " + recorded + ", not " + sent;
        }

        private String bodyDifference(GoldenMasters.Operation op, Map<String, String> params) {
            JsonNode recorded = op.body();
            if (request.recordedBody()) {
                return recorded != null && recorded.isTextual()
                        && sameType(op.contentType(), request.contentType()) ? null
                        : "body " + recorded + " (" + op.contentType() + "), not data of "
                                + request.contentType();
            }
            if (recorded == null) {
                return request.body() == null ? null
                        : "no body, not " + fill(request.body(), params);
            }
            if (request.body() == null) {
                return "body " + recorded + ", not none";
            }
            if (recorded.isTextual()) {
                String expected = fill(recorded.asText(), params);
                String sent = fill(request.body(), params);
                boolean same = isForm(request.contentType())
                        ? form(expected).equals(form(sent)) : expected.equals(sent);
                return same && sameType(op.contentType(), request.contentType()) ? null
                        : "body '" + expected + "' (" + op.contentType() + "), not '" + sent
                                + "' (" + request.contentType() + ")";
            }
            try {
                JsonNode expected = MAPPER.readTree(fill(MAPPER.writeValueAsString(recorded),
                        params));
                JsonNode sent = Request.JSON.equals(request.contentType())
                        ? MAPPER.readTree(fill(request.body(), params)) : null;
                return expected.equals(sent) ? null
                        : "body " + expected + ", not " + (sent == null ? request.body() : sent);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** Why this row is not in the pact yet. */
        String pendingReason() {
            String why = provider.recordings().holds(state, operationId)
                    ? provider.application() + " recorded " + operationId + " in '" + state
                            + "' with " + difference()
                    : provider.repository() + " records no " + operationId + " in '" + state + "'";
            return why + " — needs: " + needs;
        }
    }

    // --- the rows ---------------------------------------------------------------------------------

    /**
     * The org the bootstrap clones from ({@code BootstrapConfig#orgUrl}, the operator's input, so
     * the row passes the recorded one): every adopt hands qits-projects {@code <org>/<name>.git}.
     */
    private static final String ORG_URL = "https://forge.example.test/contract";

    private static final String SCM_RELEASE = "{\"name\":\"SCMRelease\",\"payload\":{"
            + "\"branch\":\"{version}\",\"commitSha\":\"{commitSha}\",\"projectId\":\"{projectId}\","
            + "\"repository\":\"{repositoryId}\",\"repositoryName\":\"{repositoryName}\","
            + "\"version\":\"{version}\"}}";

    private static final String MANIFEST = "/v2/{name}/manifests/{tag}";

    private static final String MAVEN_JAR =
            "/artifacts/maven/maven/{groupPath}/{artifact}/{version}/{artifact}-{version}.jar";

    static final List<Case> CASES = List.of(
            // qits-projects ---------------------------------------------------------------------
            new Case(PROJECTS, Trigger.phase("qits-project"), "a project exists", "listProjects",
                    Request.get("/projects/api/projects"), 200,
                    List.of("$.entries[*].project.id", "$.entries[*].project.name",
                            "$.entries[*].project.slug"),
                    (base, params, recorded, op) -> {
                        Http.Response listing = new ProjectsApi(new Http(), base).projects(null);
                        assertThat(listing.ok()).isTrue();
                        JsonNode project = recorded.path("entries").path(0).path("project");
                        boolean qits = "qits".equals(project.path("name").asText())
                                || "qits".equals(project.path("slug").asText());
                        assertThat(PhaseReads.qitsProjectId(listing))
                                .isEqualTo(qits ? Optional.of(project.path("id").asText())
                                        : Optional.empty());
                    }, "a listing of entries[*].project with id, name and slug"),
            new Case(PROJECTS, Trigger.phase("environment"), "a project exists", "listProjects",
                    Request.get("/projects/api/projects"), 200,
                    List.of("$.entries[*].project.slug"), projectSlugs(),
                    "a listing of entries[*].project.slug"),
            new Case(PROJECTS, Trigger.phase("summary"), "a project exists", "listProjects",
                    Request.get("/projects/api/projects"), 200,
                    List.of("$.entries[*].project.slug"), projectSlugs(),
                    "a listing of entries[*].project.slug"),
            new Case(PROJECTS, Trigger.phase("git-repos"),
                    "a project and an unadopted repository on the git host", "adoptRepository",
                    Request.json("POST", "/projects/api/projects/{projectId}/repositories/adopt",
                            "{\"repositoryId\":\"{repositoryId}\",\"name\":\"{repoName}\","
                                    + "\"url\":\"" + ORG_URL + "/{repoName}.git\","
                                    + "\"archetype\":\"SERVICE\"}"),
                    200, List.of(),
                    (base, params, recorded, op) -> assertThat(new ProjectsApi(new Http(), base)
                            .adoptRepository(params.get("projectId"), params.get("repositoryId"),
                                    params.get("repoName"),
                                    ORG_URL + "/" + params.get("repoName") + ".git",
                                    "SERVICE", null).ok()).isTrue(),
                    "the adopt recorded with the clone url the bootstrap sends — {repositoryId,"
                            + " name, url, archetype}, url the org's <name>.git — answering 200"),
            new Case(PROJECTS, Trigger.phase("git-repos"), "a repository exists",
                    "resolveRepositoryName",
                    Request.get("/projects/api/projects/{projectId}/repositories/by-name/{repoName}"),
                    200, List.of("$.repositoryId"),
                    (base, params, recorded, op) -> assertThat(PhaseReads.storageIdIn(
                            new ProjectsApi(new Http(), base).repositoryByName(
                                    params.get("projectId"), params.get("repoName"), null)))
                            .isEqualTo(recorded.path("repositoryId").asText()),
                    "params projectId and repoName; the by-name read answers {repositoryId}"),

            // qits-events -----------------------------------------------------------------------
            new Case(EVENTS, Trigger.phase("event feed, the head"), "the newest event",
                    "listEvents", Request.get("/events/api/events", "order", "desc", "limit", "1"),
                    200, List.of("$.events[*].id", "$.events[*].occurredAt"),
                    (base, params, recorded, op) -> {
                        JsonNode page = new EventsApi(new Http(), base + "/events")
                                .page(null, false, 1).orElseThrow();
                        assertThat(EventFeedReads.lastCursor(page)).contains(",");
                    }, "order=desc&limit=1 answering one event with id and occurredAt"),
            new Case(EVENTS, Trigger.phase("event feed"), "events to catch up on, fifty at a time",
                    "listEvents",
                    Request.get("/events/api/events", "order", "asc", "limit", "50",
                            "cursor", "{cursor}"),
                    200, List.of("$.events[*].id", "$.events[*].occurredAt", "$.events[*].name",
                            "$.events[*].payload", "$.events[*].description"),
                    (base, params, recorded, op) -> {
                        JsonNode page = new EventsApi(new Http(), base + "/events")
                                .page(params.get("cursor"), true, 50).orElseThrow();
                        assertThat(page.path("events")).isNotEmpty();
                        page.path("events").forEach(event ->
                                assertThat(EventFeedReads.line(event))
                                        .contains(event.path("name").asText()));
                        assertThat(EventFeedReads.lastCursor(page)).contains(",");
                    },
                    "the catch-up page at the feed's page size, order=asc&limit=50&cursor=…,"
                            + " answering events with id, occurredAt, name, payload, description"),

            // qits-ci ---------------------------------------------------------------------------
            new Case(CI, Trigger.phase("seed-health"), "the machine gate is on", "triggerEvent",
                    Request.json("POST", "/ci/api/events/trigger", "{}").with(JUNK_BEARER),
                    401, List.of(),
                    (base, params, recorded, op) -> assertThat(new Http().postJson(
                            base + "/ci/api/events/trigger", "{}", JUNK_BEARER).status())
                            .isEqualTo(401),
                    "the OIDC gate on, so {} under a bearer no idp issued answers 401"),
            new Case(CI, Trigger.phase("release-<repository>, deploy-<application>"),
                    "a repository whose release recipe selects SCMRelease", "triggerEvent",
                    Request.json("POST", "/ci/api/events/trigger", SCM_RELEASE),
                    200, List.of("$.runIds[*]"),
                    (base, params, recorded, op) -> {
                        String event = PhaseReads.releaseEvent(params.get("projectId"),
                                params.get("repositoryId"), params.get("repositoryName"),
                                params.get("version"), params.get("commitSha"));
                        assertThat(CiApi.triggeredRunIds(ci(base).trigger(event, null)))
                                .hasSameSizeAs(recorded.path("runIds"));
                    },
                    "the replayed SCMRelease answers 200 with runIds naming the run it recorded"),
            new Case(CI, Trigger.phase("release-<repository>"), "a green release run", "listRuns",
                    Request.get("/ci/api/runs", "repositoryId", "{repositoryId}", "limit", "20"),
                    200, List.of("$.runs[*].triggerType", "$.runs[*].status",
                            "$.runs[*].configPath", "$.runs[*].commitSha"),
                    (base, params, recorded, op) -> assertThat(ci(base).greenReleaseRunAt(
                            params.get("repositoryId"), params.get("commitSha"))).isTrue(),
                    "repositoryId=…&limit=20; one EVENT run, SUCCESS, configPath"
                            + " .config/qits/release.yml, at param commitSha"),
            new Case(CI, Trigger.phase("release-<repository>, deploy-<application> (the wait)"),
                    "a failed run that retries another", "getRun",
                    Request.get("/ci/api/runs/{runId}"), 200,
                    List.of("$.status", "$.steps[*].stepIndex", "$.steps[*].status",
                            "$.steps[*].output"),
                    (base, params, recorded, op) -> {
                        CiApi ci = ci(base);
                        assertThat(ci.run(params.get("runId")).map(r -> r.path("status").asText()))
                                .contains("FAILED");
                        // The mock answers ONE step, a template merged from every recorded one
                        // and valued from the first, so it cannot say which step failed: the
                        // read is asked, not its outcome.
                        assertThat(ci.failedStepOutput(params.get("runId"))).isNotNull();
                    },
                    "param runId; a FAILED run whose failed step carries its output"),
            new Case(CI, Trigger.phase("release-<repository>, deploy-<application> (the log relay)"),
                    "a running run", "getRun", Request.get("/ci/api/runs/{runId}"), 200,
                    List.of("$.status", "$.steps[*].stepIndex", "$.steps[*].output",
                            "$.live.stepIndex", "$.live.output"),
                    (base, params, recorded, op) -> assertThat(ci(base).run(params.get("runId")))
                            .isPresent(),
                    "param runId; a RUNNING run with one finished step and its live step's output"),
            new Case(CI, Trigger.phase("runner-localhost"), "no runners", "createRunner",
                    Request.json("POST", "/ci/api/runners",
                            "{\"name\":\"" + PhaseReads.LOCALHOST_RUNNER + "\",\"slots\":2}"),
                    201, List.of("$.id", "$.installScript"),
                    (base, params, recorded, op) -> {
                        Http.Response created = ci(base).createRunner(PhaseReads.LOCALHOST_RUNNER,
                                2, null);
                        JsonNode runner = eu.wohlben.qits.cli.bootstrap.api.Json.parse(created.body());
                        assertThat(runner.path("id").asText()).isNotBlank();
                        assertThat(CiApi.registrationToken(runner.path("installScript").asText()))
                                .isPresent();
                        assertThat(CiApi.runnerUrl(runner.path("installScript").asText())).isPresent();
                    },
                    "the create recorded as the bootstrap sends it, {name, slots} with no"
                            + " description, answering 201 with id and an installScript carrying"
                            + " QITS_CI_RUNNER_URL and QITS_CI_RUNNER_REGISTRATION_TOKEN"),
            new Case(CI, Trigger.phase("runner-localhost"), "an unregistered runner", "listRunners",
                    Request.get("/ci/api/runners"), 200,
                    List.of("$.runners[*].id", "$.runners[*].name", "$.runners[*].registered",
                            "$.runners[*].connected", "$.runners[*].lastSeenAt"),
                    (base, params, recorded, op) -> assertThat(CiApi.runnerNamed(ci(base).runners(),
                            PhaseReads.LOCALHOST_RUNNER)).isPresent(),
                    "one runner named localhost, never registered or seen"),
            new Case(CI, Trigger.phase("runner-localhost"), "an unregistered runner",
                    "rotateRegistrationToken",
                    Request.post("/ci/api/runners/{runnerId}/registration-token"),
                    200, List.of("$.installScript"),
                    (base, params, recorded, op) -> {
                        Http.Response answer = ci(base).rotateRegistrationToken(
                                params.get("runnerId"), null);
                        String script = eu.wohlben.qits.cli.bootstrap.api.Json.text(
                                eu.wohlben.qits.cli.bootstrap.api.Json.parse(answer.body()),
                                "installScript");
                        assertThat(CiApi.registrationToken(script)).isPresent();
                    },
                    "param runnerId; the body-less rotation answers 200 with a fresh installScript"),
            new Case(CI, Trigger.phase("runner-connected"), "a connected runner", "listRunners",
                    Request.get("/ci/api/runners"), 200,
                    List.of("$.runners[*].id", "$.runners[*].name", "$.runners[*].registered",
                            "$.runners[*].connected", "$.runners[*].quarantined",
                            "$.runners[*].quarantineReason"),
                    (base, params, recorded, op) -> assertThat(PhaseReads.ciRunnerConnected(
                            ci(base).runners(), params.get("runnerId"))).isNotNull(),
                    "param runnerId; runner localhost registered and connected, not quarantined"),
            new Case(CI, Trigger.phase("runner-connected"), "a quarantined runner",
                    "greenlightRunner", Request.post("/ci/api/runners/{runnerId}/greenlight"),
                    200, List.of(),
                    (base, params, recorded, op) -> assertThat(
                            ci(base).greenlight(params.get("runnerId")).ok()).isTrue(),
                    "param runnerId; a quarantined runner the body-less greenlight lifts (200)"),

            // qits-deployments ------------------------------------------------------------------
            new Case(DEPLOYMENTS, Trigger.phase("seed-health"), "the machine gate is on",
                    "softwareReleased",
                    Request.json("POST", "/deployments/api/events/software-released", "{}")
                            .with(JUNK_BEARER),
                    401, List.of(),
                    (base, params, recorded, op) -> assertThat(new Http().postJson(
                            base + "/deployments/api/events/software-released", "{}",
                            JUNK_BEARER).status()).isEqualTo(401),
                    "the OIDC gate on, so {} under a bearer no idp issued answers 401"),
            new Case(DEPLOYMENTS, Trigger.phase("environment"), "the platform environment exists",
                    "listEnvironments", Request.get("/deployments/api/environments"), 200,
                    List.of("$.environments[*].id", "$.environments[*].name",
                            "$.environments[*].platform"),
                    (base, params, recorded, op) -> {
                        PdApi pd = pd(base);
                        assertThat(pd.environmentId(params.get("environmentName")))
                                .contains(params.get("environmentId"));
                        assertThat(pd.platformEnvironment()).isPresent();
                    },
                    "params environmentId and environmentName; one environment, platform true"),
            new Case(DEPLOYMENTS, Trigger.phase("environment"), "no environment of the name",
                    "createEnvironment",
                    Request.json("POST", "/deployments/api/environments",
                            "{\"name\":\"{environmentName}\",\"network\":\"{network}\","
                                    + "\"platform\":true}"),
                    201, List.of("$.environment.id"),
                    (base, params, recorded, op) -> {
                        Http.Response created = pd(base).createEnvironment(
                                params.get("environmentName"), params.get("network"), null);
                        assertThat(created.status()).isEqualTo(201);
                        assertThat(eu.wohlben.qits.cli.bootstrap.api.Json.parse(created.body())
                                .path("environment").path("id").asText()).isNotBlank();
                    },
                    "params environmentName and network; the create answers 201 with"
                            + " {environment: {id}}"),
            new Case(DEPLOYMENTS, Trigger.phase("environment"), "the platform environment exists",
                    "updateEnvironment",
                    Request.json("PATCH", "/deployments/api/environments/{environmentId}",
                            "{\"platform\":true}"),
                    200, List.of(),
                    (base, params, recorded, op) -> assertThat(pd(base).patchEnvironment(
                            params.get("environmentId"), "{\"platform\":true}", null).ok()).isTrue(),
                    "param environmentId; the PATCH {platform: true} answers 200"),
            new Case(DEPLOYMENTS, Trigger.phase("seed-deploy-<application>"),
                    "a released version of a deployable repository", "softwareReleased",
                    Request.json("POST", "/deployments/api/events/software-released",
                            "{\"repoId\":\"{repoId}\",\"projectId\":\"{projectId}\","
                                    + "\"repoName\":\"{repoName}\",\"application\":\"{application}\","
                                    + "\"version\":\"{version}\"}"),
                    202, List.of(),
                    (base, params, recorded, op) -> assertThat(pd(base).softwareReleased(
                            params.get("repoId"), params.get("projectId"), params.get("repoName"),
                            params.get("application"), params.get("version"), null).status())
                            .isEqualTo(202),
                    "the software-released door answers 202"),
            new Case(DEPLOYMENTS, Trigger.phase("deploy-<application>, seed-deploy-<application>"),
                    "an application deployed in an environment", "listDeployments",
                    Request.get("/deployments/api/deployments", "environmentId", "{environmentId}"),
                    200, List.of("$.deployments[*].id", "$.deployments[*].applicationName",
                            "$.deployments[*].status", "$.deployments[*].version",
                            "$.deployments[*].containerName", "$.deployments[*].detail"),
                    (base, params, recorded, op) -> assertThat(pd(base).newestDeployment(
                            params.get("environmentId"), params.get("applicationName")))
                            .isPresent(),
                    "params environmentId and applicationName; a deployment row of it with its"
                            + " status, version, containerName and detail"),

            // qits-idp --------------------------------------------------------------------------
            new Case(IDP, Trigger.phase("every phase that presents a machine token"),
                    "a service client calling its peers", "issueToken",
                    Request.text("POST", "/idp/token",
                            "grant_type=client_credentials&audience={audience}",
                            "application/x-www-form-urlencoded").asCaller(),
                    200, List.of("$.access_token", "$.expires_in"),
                    (base, params, recorded, op) -> {
                        String[] pair = callerPair(params);
                        assertThat(new IdpApi(new Http(), base + "/idp")
                                .minted(pair[0], pair[1], params.get("audience")).value())
                                .isNotBlank();
                    },
                    "the caller's Basic pair as param authorization; a client-credentials mint"
                            + " answering access_token and expires_in"),
            new Case(IDP, Trigger.phase("register-token"), "a service client with the system role",
                    "mintRegisterToken", Request.post("/idp/api/register-tokens").asCaller(),
                    201, List.of("$.token"),
                    (base, params, recorded, op) -> {
                        assertCallerIs(params, params.get("callerClientId"),
                                params.get("clientSecret"));
                        assertThat(eu.wohlben.qits.cli.bootstrap.api.Json.text(
                                eu.wohlben.qits.cli.bootstrap.api.Json.parse(
                                        new IdpApi(new Http(), base + "/idp").mintRegisterToken(
                                                params.get("callerClientId"),
                                                params.get("clientSecret")).body()), "token"))
                                .isNotBlank();
                    },
                    "params callerClientId and clientSecret, their Basic pair as authorization;"
                            + " the body-less mint answers 201 with {token}"),
            new Case(IDP, Trigger.phase("idp-clients"), "no service client with the given id",
                    "createServiceClient",
                    Request.json("POST", "/idp/api/service-clients", "{\"clientId\":\"{clientId}\"}")
                            .asCaller(),
                    201, List.of("$.secret"),
                    (base, params, recorded, op) -> assertThat(serviceClients(base, params)
                            .create(params.get("clientId")).ok()).isTrue(),
                    "param clientId, the caller's Basic pair as authorization; the create answers"
                            + " 201 with {secret}"),
            new Case(IDP, Trigger.phase("idp-clients"), "a database service client",
                    "createServiceClient",
                    Request.json("POST", "/idp/api/service-clients", "{\"clientId\":\"{clientId}\"}")
                            .asCaller(),
                    409, List.of(),
                    (base, params, recorded, op) -> assertThat(serviceClients(base, params)
                            .create(params.get("clientId")).conflict()).isTrue(),
                    "param clientId of an existing row, the caller's Basic pair as authorization;"
                            + " the create answers 409"),
            new Case(IDP, Trigger.phase("idp-clients"), "a database service client",
                    "rotateServiceClientSecret",
                    Request.post("/idp/api/service-clients/{clientId}/secret").asCaller(),
                    200, List.of("$.secret"),
                    (base, params, recorded, op) -> assertThat(serviceClients(base, params)
                            .rotate(params.get("clientId")).ok()).isTrue(),
                    "param clientId, the caller's Basic pair as authorization; the body-less"
                            + " rotation answers 200 with {secret}"),
            new Case(IDP, Trigger.phase("images-publish, daemon-publish (the publishing credential)"),
                    "a service client with the system role", "commissionClient",
                    Request.json("POST", "/idp/api/clients", "{\"contextKind\":\""
                            + BootstrapPublishCredential.CONTEXT_KIND
                            + "\",\"contextId\":\"dev\",\"gitRefs\":[]}").asCaller(),
                    201, List.of("$.clientId", "$.secret"),
                    (base, params, recorded, op) -> {
                        assertCallerIs(params, params.get("callerClientId"),
                                params.get("clientSecret"));
                        BootstrapPublishCredential credential = new BootstrapPublishCredential(
                                new Http(), new IdpApi(new Http(), base + "/idp"), base + "/idp",
                                params.get("callerClientId"), params.get("clientSecret"), "dev",
                                "qits-platform");
                        assertThat(credential.pair().clientId()).isNotBlank();
                    },
                    "the commission recorded for the bootstrap's own kind — {contextKind:"
                            + " bootstrap-publish, contextId: dev, gitRefs: []} — under params"
                            + " callerClientId and clientSecret, their Basic pair as authorization,"
                            + " answering 201 with clientId and secret"),
            // The DELETE is made as the commissioned credential ITSELF (BootstrapPublishCredential
            // .delete), so the state's caller must be that client's own pair — not the system-role
            // caller 'a commissioned client' is recorded under.
            new Case(IDP, Trigger.phase("publish-credential-release"),
                    "a commissioned client handing itself back", "decommissionClient",
                    Request.delete("/idp/api/clients/{clientId}").asCaller(),
                    204, List.of(),
                    (base, params, recorded, op) -> {
                        assertCallerIs(params, params.get("clientId"), params.get("clientSecret"));
                        assertThat(new Http().delete(
                                base + "/idp/api/clients/" + params.get("clientId"),
                                Map.of("Authorization", Http.basic(params.get("clientId"),
                                        params.get("clientSecret")))).ok()).isTrue();
                    },
                    "params clientId and clientSecret of a commissioned client, and authorization"
                            + " the Basic of THAT pair — the client's own, holding no platform role;"
                            + " the DELETE answers 204"),
            new Case(IDP, new Trigger("qits-bootstrap login"),
                    "an authorization code issued to the git client", "issueToken",
                    Request.text("POST", "/idp/token",
                            "grant_type=authorization_code&client_id={clientId}&code={code}"
                                    + "&redirect_uri={redirectUri}&code_verifier={codeVerifier}",
                            "application/x-www-form-urlencoded"),
                    200, List.of("$.access_token", "$.refresh_token"),
                    (base, params, recorded, op) -> {
                        assertThat(params.get("clientId")).isEqualTo(TokenClient.CLIENT_ID);
                        assertThat(new TokenClient().exchange(base + "/idp", params.get("code"),
                                params.get("redirectUri"), params.get("codeVerifier"))
                                .accessToken()).isNotBlank();
                    },
                    "a PKCE code for qits-git-workstation; the token answers access_token and"
                            + " refresh_token"),
            new Case(IDP, new Trigger("qits-bootstrap git-credential"), "a git session to refresh",
                    "issueToken",
                    Request.text("POST", "/idp/token",
                            "grant_type=refresh_token&client_id={clientId}"
                                    + "&refresh_token={refreshToken}&audience={audience}",
                            "application/x-www-form-urlencoded"),
                    200, List.of("$.access_token", "$.refresh_token"),
                    (base, params, recorded, op) -> {
                        assertThat(params.get("clientId")).isEqualTo(TokenClient.CLIENT_ID);
                        assertThat(new TokenClient().refresh(new WorkstationCredential(
                                base + "/idp", params.get("audience"), null,
                                params.get("refreshToken"))).accessToken()).isNotBlank();
                    },
                    "a qits-git-workstation refresh token; the token answers access_token and"
                            + " refresh_token"),

            // qits-githost ----------------------------------------------------------------------
            new Case(GITHOST, Trigger.phase("git-repos"), "no repository with the given id",
                    "createRepository",
                    Request.json("PUT", "/git/{repositoryId}", "{\"defaultBranch\":\"main\"}"),
                    201, List.of(),
                    (base, params, recorded, op) -> assertThat(githost(base).createRepository(
                            params.get("repositoryId"), "pact-bearer").status()).isEqualTo(201),
                    "param repositoryId; the lifecycle PUT creates the bare (201)"),
            new Case(GITHOST, Trigger.phase("git-repos"), "a repository exists", "createRepository",
                    Request.json("PUT", "/git/{repositoryId}", "{\"defaultBranch\":\"main\"}"),
                    200, List.of(),
                    (base, params, recorded, op) -> assertThat(githost(base).createRepository(
                            params.get("repositoryId"), "pact-bearer").status()).isEqualTo(200),
                    "param repositoryId; the lifecycle PUT finds the bare (200)"),

            // qits-artifacts --------------------------------------------------------------------
            new Case(ARTIFACTS, Trigger.phase("daemon-publish"), "a daemon nothing has published",
                    "headDaemon", Request.head("/artifacts/daemons/{daemon}/{version}"), 404,
                    List.of(),
                    (base, params, recorded, op) -> assertThat(artifacts(base).daemonPublished(
                            params.get("daemon"), params.get("version"))).isFalse(),
                    "params daemon and version of a version the store does not hold (404)"),
            new Case(ARTIFACTS, Trigger.phase("daemon-publish"), "a daemon nothing has published",
                    "putDaemon",
                    Request.data("PUT", "/artifacts/daemons/{daemon}/{version}",
                            "application/octet-stream"),
                    201, List.of(),
                    (base, params, recorded, op) -> {
                        Path binary = Files.createTempFile("qits-ci-daemon", "");
                        Files.writeString(binary, op.body().asText());
                        assertThat(artifacts(base).publishDaemon(params.get("daemon"),
                                params.get("version"), binary, "Bearer pact").status())
                                .isEqualTo(201);
                    },
                    "params daemon and version; a qits:ci-run publish answers 201"),
            new Case(ARTIFACTS, Trigger.phase("images-publish"), "an image pushed under a tag",
                    "getImageManifest", Request.get(MANIFEST), 200, List.of(),
                    (base, params, recorded, op) -> assertThat(artifacts(base).imagePublished(
                            params.get("name"), params.get("tag"))).isTrue(),
                    "params name and tag of an image the store holds; the manifest GET answers"
                            + " 200"),
            new Case(ARTIFACTS, Trigger.phase("seed-images-publish"), "no image with the given name",
                    "getImageManifest", Request.get(MANIFEST), 404, List.of(),
                    (base, params, recorded, op) -> assertThat(artifacts(base).image(
                            params.get("name"), params.get("tag")).status()).isEqualTo(404),
                    "params name and tag of an image the store does not hold; the manifest GET"
                            + " answers 404 and nothing else"),
            new Case(ARTIFACTS, Trigger.phase("workspaces-runner-localhost"),
                    "an image pushed under a tag", "getImageManifest", Request.get(MANIFEST), 200,
                    List.of(),
                    (base, params, recorded, op) -> assertThat(artifacts(base).imagePublished(
                            params.get("name"), params.get("tag"))).isTrue(),
                    "params name and tag of an image the store holds; the manifest GET answers"
                            + " 200"),
            new Case(ARTIFACTS, Trigger.phase("release-<repository> (the packages held)"),
                    "an image pushed under a tag", "getImageManifest", Request.get(MANIFEST), 200,
                    List.of(),
                    (base, params, recorded, op) -> assertThat(artifacts(base).imagePublished(
                            params.get("name"), params.get("tag"))).isTrue(),
                    "params name and tag of an image the store holds; the manifest GET answers"
                            + " 200"),
            new Case(ARTIFACTS, Trigger.phase("release-<repository> (the packages held)"),
                    "an npm package published at a version", "headNpmTarball",
                    Request.head("/artifacts/npm/npm/{package}/-/{tarball}"), 200, List.of(),
                    (base, params, recorded, op) -> assertThat(artifacts(base).npmPublished(
                            params.get("package"), params.get("version"))).isTrue(),
                    "params package and version, tarball <package>-<version>.tgz; the HEAD"
                            + " answers 200"),
            new Case(ARTIFACTS, Trigger.phase("release-<repository> (the packages held)"),
                    "a maven jar published at a version", "getMavenFile",
                    Request.get(MAVEN_JAR), 200, List.of(),
                    (base, params, recorded, op) -> assertThat(artifacts(base).mavenPublished(
                            params.get("groupPath"), params.get("artifact"), params.get("version"),
                            "jar")).isTrue(),
                    "the JAR at the version — the bootstrap asks for .jar, never .pom — answering"
                            + " 200"),
            new Case(ARTIFACTS, Trigger.phase("publish-<artifact>"),
                    "no maven jar published at the version", "getMavenFile",
                    Request.get(MAVEN_JAR), 404, List.of(),
                    (base, params, recorded, op) -> assertThat(artifacts(base).mavenPublished(
                            params.get("groupPath"), params.get("artifact"), params.get("version"),
                            "jar")).isFalse(),
                    "the JAR at a version the store does not hold — .jar, never .pom — answering"
                            + " 404"),

            // qits-configuration ----------------------------------------------------------------
            new Case(CONFIGURATION, Trigger.phase("configuration-import"),
                    "an application with no configuration", "importProperties",
                    Request.data("POST", "/configuration/api/import", "text/plain", "env", "{env}"),
                    200, List.of(),
                    (base, params, recorded, op) -> assertThat(new ConfigurationApi(new Http(), base,
                            params.get("env")).importProperties(op.body().asText()).ok()).isTrue(),
                    "param env; the import of a properties file answers 200"),

            // qits-workspaces -------------------------------------------------------------------
            new Case(WORKSPACES, Trigger.phase("workspaces-runner-localhost"), "no runners",
                    "createRunner",
                    Request.json("POST", "/workspaces/api/runners",
                            "{\"name\":\"{runnerName}\",\"slots\":1}"),
                    201, List.of("$.runner.id", "$.registrationToken", "$.installLine"),
                    (base, params, recorded, op) -> {
                        assertThat(params.get("runnerName")).isEqualTo(PhaseReads.LOCALHOST_RUNNER);
                        JsonNode answer = eu.wohlben.qits.cli.bootstrap.api.Json.parse(
                                workspaces(base).createRunner(PhaseReads.LOCALHOST_RUNNER, 1, null)
                                        .body());
                        assertThat(WorkspacesApi.runnerOf(answer).path("id").asText()).isNotBlank();
                        assertThat(WorkspacesApi.registrationToken(answer)).isPresent();
                        assertThat(WorkspacesApi.runnerUrl(answer.path("installLine").asText()))
                                .isPresent();
                    },
                    "runnerName localhost; the create answers 201 with {runner: {id},"
                            + " registrationToken, installLine} and the line names"
                            + " QITS_WORKSPACES_RUNNER_URL"),
            new Case(WORKSPACES, Trigger.phase("workspaces-runner-localhost"),
                    "an unregistered runner", "listRunners", Request.get("/workspaces/api/runners"),
                    200,
                    List.of("$[*].id", "$[*].name", "$[*].registered", "$[*].connected",
                            "$[*].lastSeenAt"),
                    (base, params, recorded, op) -> assertThat(WorkspacesApi.runnerNamed(
                            workspaces(base).runners(), PhaseReads.LOCALHOST_RUNNER)).isPresent(),
                    "one runner named localhost, never registered or seen"),
            new Case(WORKSPACES, Trigger.phase("workspaces-runner-localhost"),
                    "an unregistered runner", "rotateRunnerRegistrationToken",
                    Request.post("/workspaces/api/runners/{runnerId}/registration-token"),
                    200, List.of("$.registrationToken", "$.installLine"),
                    (base, params, recorded, op) -> assertThat(WorkspacesApi.registrationToken(
                            eu.wohlben.qits.cli.bootstrap.api.Json.parse(workspaces(base)
                                    .rotateRegistrationToken(params.get("runnerId"), null).body())))
                            .isPresent(),
                    "param runnerId; the body-less rotation answers 200 with registrationToken"
                            + " and installLine"),
            new Case(WORKSPACES, Trigger.phase("workspaces-runner-connected"), "a connected runner",
                    "listRunners", Request.get("/workspaces/api/runners"), 200,
                    List.of("$[*].id", "$[*].name", "$[*].registered", "$[*].connected",
                            "$[*].quarantined", "$[*].quarantineReason"),
                    (base, params, recorded, op) -> assertThat(PhaseReads.workspacesRunnerConnected(
                            workspaces(base).runners(), params.get("runnerId"))).isNotNull(),
                    "param runnerId; runner localhost registered and connected, not quarantined"),
            new Case(WORKSPACES, Trigger.phase("workspaces-runner-connected"),
                    "a quarantined runner", "greenlightRunner",
                    Request.post("/workspaces/api/runners/{runnerId}/greenlight"),
                    200, List.of(),
                    (base, params, recorded, op) -> assertThat(
                            workspaces(base).greenlight(params.get("runnerId")).ok()).isTrue(),
                    "param runnerId; a quarantined runner the body-less greenlight lifts (200)"),

            // qits-containers -------------------------------------------------------------------
            new Case(CONTAINERS, Trigger.phase("seed-health"), "the machine gate is on",
                    "listOwnerContainers",
                    Request.get("/containers/api/containers/{owner}").with(JUNK_BEARER), 401,
                    List.of(),
                    (base, params, recorded, op) -> assertThat(new Http().get(
                            base + "/containers/api/containers/" + params.get("owner"),
                            JUNK_BEARER).status()).isEqualTo(401),
                    "param owner (dev-qits-ci); a bearer no idp issued answers 401"));

    /** {@code environment} and {@code summary}: the slugs of a listing, as the phase reads them. */
    private static Call projectSlugs() {
        return (base, params, recorded, op) -> {
            List<String> slugs = PhaseReads.projectSlugs(
                    new ProjectsApi(new Http(), base).projects(null));
            List<String> expected = new ArrayList<>();
            recorded.path("entries").forEach(e -> expected.add(e.path("project").path("slug").asText()));
            assertThat(slugs).containsExactlyElementsOf(expected);
        };
    }

    // --- the clients, at the paths the rows spell -------------------------------------------------
    // One address per client, relative to a mock server's root, shared with BootstrapCallGuardTest
    // so that what the guard records is in the rows' own coordinates.

    static CiApi ci(String base) {
        return new CiApi(new Http(), base + "/ci");
    }

    static PdApi pd(String base) {
        return new PdApi(new Http(), base + "/deployments");
    }

    static GitHostApi githost(String base) {
        return new GitHostApi(new Http(), base + "/git", base + "/githost");
    }

    static ArtifactsApi artifacts(String base) {
        return new ArtifactsApi(new Http(), base + "/artifacts", () -> "Bearer pact");
    }

    static WorkspacesApi workspaces(String base) {
        return new WorkspacesApi(new Http(), base + "/workspaces");
    }

    static ServiceClientsApi serviceClients(String base) {
        return new ServiceClientsApi(new Http(), base + "/idp", "qits-bootstrap", "pact-secret");
    }

    /** The client, holding the pair the state's {@code authorization} encodes. */
    static ServiceClientsApi serviceClients(String base, Map<String, String> params) {
        String[] pair = callerPair(params);
        return new ServiceClientsApi(new Http(), base + "/idp", pair[0], pair[1]);
    }

    /** The client id and secret the state's {@code Basic} {@code authorization} param encodes. */
    static String[] callerPair(Map<String, String> params) {
        String header = params.get("authorization");
        assertThat(header).as("the state's authorization param").startsWith("Basic ");
        String decoded = new String(java.util.Base64.getDecoder().decode(
                header.substring("Basic ".length())), StandardCharsets.UTF_8);
        return decoded.split(":", 2);
    }

    /** The state's caller is the pair the row's call presents, so the header sent is the one bound. */
    private static void assertCallerIs(Map<String, String> params, String clientId,
                                       String secret) {
        assertThat(Http.basic(clientId, secret)).as("the pair the call presents")
                .isEqualTo(params.get("authorization"));
    }

    private BootstrapContract() {
    }

    // --- the pact ---------------------------------------------------------------------------------

    /** The rows a provider's recordings hold, in table order. */
    static List<Case> recorded(Provider provider) {
        return CASES.stream().filter(c -> c.provider().equals(provider) && c.recorded()).toList();
    }

    /** The pact of one provider's recorded rows. */
    static V4Pact pact(Provider provider) {
        return pact(provider, recorded(provider));
    }

    /** A pact holding only {@code cases}, all of one provider. */
    static V4Pact pact(Provider provider, List<Case> cases) {
        PactBuilder builder = new PactBuilder(CONSUMER, provider.repository(), PactSpecVersion.V4);
        for (Case c : cases) {
            if (!c.provider().equals(provider)) {
                throw new IllegalArgumentException(c.description() + " is not " + provider + "'s");
            }
            interaction(builder, c);
        }
        return builder.toPact();
    }

    private static final Pattern PARAM = Pattern.compile("\\{([A-Za-z0-9_]+)}");

    private static void interaction(PactBuilder builder, Case c) {
        GoldenMasters recordings = c.provider().recordings();
        GoldenMasters.Operation op = recordings.operation(c.state(), c.operationId());
        String difference = c.difference();
        if (difference != null) {
            throw new IllegalStateException(c.description() + ": " + c.provider().application()
                    + " recorded " + difference);
        }
        Map<String, String> params = recordings.params(c.state());
        DslPart body = c.reads().isEmpty() ? null
                : ReadBody.of(recordings.json(c.state(), c.operationId()), c.reads(), op);
        Map<String, Object> references = new LinkedHashMap<>();
        Map<String, String> call = new LinkedHashMap<>();
        call.put("app", c.provider().repository());
        call.put("operationId", c.operationId());
        references.put("qits-call", call);
        references.put("qits-trigger", c.trigger().reference());
        Request r = c.request();
        builder.expectsToReceiveHttpInteraction(c.description(), http -> {
            http.state(c.state(), new LinkedHashMap<String, Object>(params));
            http.withRequest(request -> {
                request.method(r.method());
                Object path = value(r.path(), params, c);
                if (path instanceof au.com.dius.pact.consumer.dsl.Matcher matcher) {
                    request.path(matcher);
                } else {
                    request.path((String) path);
                }
                r.query().forEach((k, v) -> request.queryParameter(k, value(v, params, c)));
                r.headers().forEach((k, v) -> request.header(k, value(v, params, c)));
                if (r.recordedBody()) {
                    request.body(op.body().asText(), r.contentType());
                } else if (r.body() != null && Request.JSON.equals(r.contentType())
                        && PARAM.matcher(r.body()).find()) {
                    request.body(jsonBody(r.body(), params, c));
                } else if (r.body() != null) {
                    request.body(filled(r.body(), params, c), r.contentType());
                }
                return request;
            });
            http.willRespondWith(response -> {
                response.status(op.status());
                if (body != null) {
                    response.header("Content-Type",
                            Matchers.regexp("application/json.*", "application/json"));
                    response.body(body);
                }
                return response;
            });
            // pact-jvm 4.6's DSL has no setter for an arbitrary comment group, but the V4 model's
            // comments map is mutable and written verbatim.
            http.getInteraction().getComments().put("references", Json.toJson(references));
            return http;
        });
    }

    /**
     * A path or query value: literal when it names no param, else a provider-state expression with
     * the state's frozen example. Never a generator for a constant — pact-jvm reads an expression
     * with no {@code ${…}} as a context key, and would send {@code null}.
     */
    private static Object value(String template, Map<String, String> params, Case c) {
        if (!PARAM.matcher(template).find()) {
            return template;
        }
        return Matchers.fromProviderState(template.replace("{", "${"),
                filled(template, params, c));
    }

    /**
     * A JSON request body whose {@code {param}} strings are provider-state expressions, the way a
     * path is: a provider's state may mint its ids afresh on every run (qits-idp's {@code a
     * database service client} does), and a body frozen at the recording's example then names a
     * row the provider never made. Everything else is the template's literal value.
     */
    private static DslPart jsonBody(String template, Map<String, String> params, Case c) {
        JsonNode body;
        try {
            body = MAPPER.readTree(template);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!body.isObject()) {
            throw new IllegalStateException(c.description() + ": only an object body takes params");
        }
        PactDslJsonBody root = new PactDslJsonBody();
        jsonObject(root, body, params, c);
        return root;
    }

    private static void jsonObject(PactDslJsonBody target, JsonNode body,
                                   Map<String, String> params, Case c) {
        body.fields().forEachRemaining(field -> {
            String name = field.getKey();
            JsonNode value = field.getValue();
            if (value.isObject()) {
                PactDslJsonBody nested = target.object(name);
                jsonObject(nested, value, params, c);
                nested.closeObject();
            } else if (value.isArray()) {
                PactDslJsonArray nested = target.array(name);
                for (JsonNode element : value) {
                    if (element.isTextual() && PARAM.matcher(element.asText()).find()) {
                        nested.valueFromProviderState(element.asText().replace("{", "${"),
                                filled(element.asText(), params, c));
                    } else if (element.isTextual()) {
                        nested.stringValue(element.asText());
                    } else if (element.isNumber()) {
                        nested.numberValue(element.numberValue());
                    } else if (element.isBoolean()) {
                        nested.booleanValue(element.asBoolean());
                    } else {
                        throw new IllegalStateException(c.description() + ": " + name
                                + " holds an element a request body here never sends");
                    }
                }
                nested.closeArray();
            } else if (value.isNull()) {
                target.nullValue(name);
            } else if (value.isTextual() && PARAM.matcher(value.asText()).find()) {
                target.valueFromProviderState(name, value.asText().replace("{", "${"),
                        filled(value.asText(), params, c));
            } else if (value.isTextual()) {
                target.stringValue(name, value.asText());
            } else if (value.isNumber()) {
                target.numberValue(name, value.numberValue());
            } else if (value.isBoolean()) {
                target.booleanValue(name, value.asBoolean());
            }
        });
    }

    /** {@code template} with every {@code {param}} the state's frozen value; a missing one fails. */
    private static String filled(String template, Map<String, String> params, Case c) {
        Matcher m = PARAM.matcher(template);
        while (m.find()) {
            if (!params.containsKey(m.group(1))) {
                throw new IllegalStateException(c.description() + ": " + template + " names {"
                        + m.group(1) + "}, which state '" + c.state() + "' does not hold");
            }
        }
        return fill(template, params);
    }

    /** {@code template} with every {@code {param}} the state holds filled; any other kept as is. */
    static String fill(String template, Map<String, String> params) {
        Matcher m = PARAM.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = params.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static boolean isForm(String contentType) {
        return contentType != null && contentType.startsWith("application/x-www-form-urlencoded");
    }

    /** A form body as its pairs: the order a client writes them in is not part of the request. */
    private static Map<String, String> form(String body) {
        Map<String, String> pairs = new TreeMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            pairs.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return pairs;
    }

    /** The recording's content type and the row's, compared without parameters. */
    private static boolean sameType(String recorded, String sent) {
        String a = recorded == null ? "" : recorded.split(";")[0].trim();
        String b = sent == null ? "" : sent.split(";")[0].trim();
        return a.isEmpty() || a.equalsIgnoreCase(b);
    }
}
