package eu.wohlben.qits.cli.bootstrap.contracts;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.Matchers;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Every REST call the bootstrap makes to a qits service, and what it reads from each answer</b>
 * (qits-1149) — the one table {@code BootstrapConsumerPactTest} (each row against a pact mock
 * server) and {@code BootstrapPactFileTest} (the committed {@code pacts/qits-bootstrap-cli_*.json})
 * are built from.
 * <p>
 * <b>One row per (trigger, call).</b> A row names the provider state its answer comes from, the
 * request the bootstrap sends, the status it expects and the JSON paths its code reads. The answer
 * itself is never written here: it is the provider's recording of that state, cut down to the read
 * paths by {@link ReadBody}. A row that reads nothing is status-only.
 * <p>
 * <b>A row whose provider records no such (state, operation) yet is PENDING</b>, not dropped. Its
 * request, state and reads are written down, it is left out of the pact file, and its test is
 * skipped naming the state it needs. The moment the provider's golden masters on the class path
 * record it, the row becomes part of the pact — and the committed file, compared byte for byte, has
 * to be rewritten on purpose ({@code -Dgolden.update=true}). {@link #NEEDS} says what each recording
 * must contain.
 * <p>
 * The {@code operationId}s of providers that declare none yet (qits-ci, qits-deployments, qits-idp,
 * qits-githost, qits-artifacts, qits-configuration, qits-workspaces, qits-containers) are the
 * resource methods' names, proposed for the provider to declare.
 */
final class BootstrapContract {

    /** The consumer, as every pact names it: the repository. */
    static final String CONSUMER = "qits-bootstrap-cli";

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

    /** What the auth-plane probes in {@code seed-health} present: a bearer no idp issued. */
    static final Map<String, String> JUNK_BEARER =
            Map.of("Authorization", "Bearer qits-bootstrap-auth-plane-probe");

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
     * What the bootstrap sends. {@code path} and query values may name a state param as
     * {@code {name}}. Headers are bound only where the answer depends on them — the probes' junk
     * bearer; the identity a provider's own test runs as is the provider's business.
     */
    record Request(String method, String path, Map<String, String> query,
                   Map<String, String> headers, Supplier<DslPart> json, String text,
                   String contentType) {

        static Request get(String path, String... query) {
            return new Request("GET", path, pairs(query), Map.of(), null, null, null);
        }

        static Request head(String path) {
            return new Request("HEAD", path, Map.of(), Map.of(), null, null, null);
        }

        static Request delete(String path) {
            return new Request("DELETE", path, Map.of(), Map.of(), null, null, null);
        }

        static Request json(String method, String path, Supplier<DslPart> body) {
            return new Request(method, path, Map.of(), Map.of(), body, null, null);
        }

        static Request text(String method, String path, String body, String contentType,
                            String... query) {
            return new Request(method, path, pairs(query), Map.of(), null, body, contentType);
        }

        Request with(Map<String, String> headers) {
            return new Request(method, path, query, headers, json, text, contentType);
        }

        private static Map<String, String> pairs(String... keysAndValues) {
            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
                map.put(keysAndValues[i], keysAndValues[i + 1]);
            }
            return map;
        }
    }

    /** The bootstrap's own code against the mock server, asserting what it made of the answer. */
    @FunctionalInterface
    interface Call {
        void run(String baseUrl, Map<String, String> params, JsonNode recorded) throws Exception;
    }

    /**
     * One row.
     *
     * @param reads the JSON paths the bootstrap reads from the answer; empty is status-only
     * @param needs for a pending row: what the provider's recording of {@code state} must hold
     */
    record Case(Provider provider, Trigger trigger, String state, String operationId,
                Request request, int status, List<String> reads, Call call, String needs) {

        String description() {
            return trigger.command() + ": " + operationId;
        }

        /** Whether the provider's golden masters on the class path record this row's answer. */
        boolean recorded() {
            return provider.recordings().holds(state, operationId);
        }

        /** Why this row is not in the pact yet. */
        String pendingReason() {
            return "needs provider state '" + state + "' for " + operationId + " in "
                    + provider.repository() + ": " + needs;
        }
    }

    // --- the rows ---------------------------------------------------------------------------------

    private static final String EVENT_CURSOR =
            "2026-01-01T00:00:00Z,00000000-0000-4000-8000-000000000003";

    static final List<Case> CASES = List.of(
            // qits-projects ---------------------------------------------------------------------
            new Case(PROJECTS, Trigger.phase("qits-project"), "a project exists", "listProjects",
                    Request.get("/projects/api/projects"), 200,
                    List.of("$.entries[*].project.id", "$.entries[*].project.name",
                            "$.entries[*].project.slug"),
                    (base, params, recorded) -> {
                        Http.Response listing = new ProjectsApi(new Http(), base).projects(null);
                        assertThat(listing.ok()).isTrue();
                        JsonNode project = recorded.path("entries").path(0).path("project");
                        boolean qits = "qits".equals(project.path("name").asText())
                                || "qits".equals(project.path("slug").asText());
                        assertThat(PhaseReads.qitsProjectId(listing))
                                .isEqualTo(qits ? Optional.of(project.path("id").asText())
                                        : Optional.empty());
                    }, null),
            new Case(PROJECTS, Trigger.phase("environment"), "a project exists", "listProjects",
                    Request.get("/projects/api/projects"), 200,
                    List.of("$.entries[*].project.slug"), projectSlugs(), null),
            new Case(PROJECTS, Trigger.phase("summary"), "a project exists", "listProjects",
                    Request.get("/projects/api/projects"), 200,
                    List.of("$.entries[*].project.slug"), projectSlugs(), null),
            new Case(PROJECTS, Trigger.phase("git-repos"),
                    "a project and an unadopted repository on the git host", "adoptRepository",
                    Request.json("POST", "/projects/api/projects/{projectId}/repositories/adopt",
                            () -> new PactDslJsonBody()
                                    .stringType("repositoryId", "00000000-0000-4000-8000-000000000002")
                                    .stringType("name", "qits-ci-service")
                                    .stringType("url", "https://github.com/QuicklyIterateTheSoftware/qits-ci-service.git")
                                    .stringType("archetype", "SERVICE")),
                    200, List.of(),
                    (base, params, recorded) -> assertThat(new ProjectsApi(new Http(), base)
                            .adoptRepository(params.get("projectId"), params.get("repositoryId"),
                                    "qits-ci-service",
                                    "https://github.com/QuicklyIterateTheSoftware/qits-ci-service.git",
                                    "SERVICE", null).ok()).isTrue(),
                    "params projectId and repositoryId (a storage id qits-githost holds); the adopt"
                            + " answers 200"),
            new Case(PROJECTS, Trigger.phase("git-repos"), "a repository exists",
                    "resolveRepositoryName",
                    Request.get("/projects/api/projects/{projectId}/repositories/by-name/{repoName}"),
                    200, List.of("$.repositoryId"),
                    (base, params, recorded) -> assertThat(PhaseReads.storageIdIn(
                            new ProjectsApi(new Http(), base).repositoryByName(
                                    params.get("projectId"), params.get("repoName"), null)))
                            .isEqualTo(recorded.path("repositoryId").asText()),
                    "param repoName (the repository's name) beside projectId; resolveRepositoryName"
                            + " answers {repositoryId}"),

            // qits-events -----------------------------------------------------------------------
            new Case(EVENTS, Trigger.phase("event feed, the head"), "a few recent events",
                    "listEvents", Request.get("/events/api/events", "order", "desc", "limit", "1"),
                    200, List.of("$.events[*].id", "$.events[*].occurredAt"),
                    (base, params, recorded) -> {
                        JsonNode page = new EventsApi(new Http(), base + "/events")
                                .page(null, false, 1).orElseThrow();
                        assertThat(EventFeedReads.lastCursor(page)).contains(",");
                    }, null),
            new Case(EVENTS, Trigger.phase("event feed"), "a few recent events", "listEvents",
                    Request.get("/events/api/events", "order", "asc", "limit", "50",
                            "cursor", EVENT_CURSOR),
                    200, List.of("$.events[*].id", "$.events[*].occurredAt", "$.events[*].name",
                            "$.events[*].payload", "$.events[*].description"),
                    (base, params, recorded) -> {
                        JsonNode page = new EventsApi(new Http(), base + "/events")
                                .page(EVENT_CURSOR, true, 50).orElseThrow();
                        assertThat(page.path("events")).isNotEmpty();
                        page.path("events").forEach(event ->
                                assertThat(EventFeedReads.line(event))
                                        .contains(event.path("name").asText()));
                        assertThat(EventFeedReads.lastCursor(page)).contains(",");
                    }, null),

            // qits-ci ---------------------------------------------------------------------------
            new Case(CI, Trigger.phase("seed-health"), "the machine gate is on", "trigger",
                    Request.json("POST", "/ci/api/events/trigger", PactDslJsonBody::new)
                            .with(JUNK_BEARER),
                    401, List.of(),
                    (base, params, recorded) -> assertThat(new Http().postJson(
                            base + "/ci/api/events/trigger", "{}", JUNK_BEARER).status())
                            .isEqualTo(401),
                    "the OIDC gate on, so a bearer no idp issued answers 401"),
            new Case(CI, Trigger.phase("release-<repository>, deploy-<application>"),
                    "a repository whose release recipe selects SCMRelease", "trigger",
                    Request.json("POST", "/ci/api/events/trigger", () -> new PactDslJsonBody()
                            .stringValue("name", "SCMRelease")
                            .object("payload")
                            .stringType("branch", "2026.1001.1")
                            .stringType("commitSha", "0123456789abcdef0123456789abcdef01234567")
                            .stringType("projectId", "00000000-0000-4000-8000-000000000001")
                            .stringType("repository", "00000000-0000-4000-8000-000000000002")
                            .stringType("repositoryName", "qits-ci-service")
                            .stringType("version", "2026.1001.1")
                            .closeObject()),
                    200, List.of("$.runIds[*]"),
                    (base, params, recorded) -> {
                        String event = "{\"name\":\"SCMRelease\",\"payload\":{"
                                + "\"branch\":\"2026.1001.1\","
                                + "\"commitSha\":\"0123456789abcdef0123456789abcdef01234567\","
                                + "\"projectId\":\"00000000-0000-4000-8000-000000000001\","
                                + "\"repository\":\"00000000-0000-4000-8000-000000000002\","
                                + "\"repositoryName\":\"qits-ci-service\","
                                + "\"version\":\"2026.1001.1\"}}";
                        assertThat(CiApi.triggeredRunIds(ci(base).trigger(event, null)))
                                .hasSameSizeAs(recorded.path("runIds"));
                    },
                    "a repository whose release recipe selects SCMRelease; the trigger answers 200"
                            + " with runIds naming the run it recorded"),
            new Case(CI, Trigger.phase("release-<repository>"), "a green release run", "listRuns",
                    Request.get("/ci/api/runs", "repositoryId", "{repositoryId}", "limit", "20"),
                    200, List.of("$.runs[*].triggerType", "$.runs[*].status",
                            "$.runs[*].configPath", "$.runs[*].commitSha"),
                    (base, params, recorded) -> assertThat(ci(base).greenReleaseRunAt(
                            params.get("repositoryId"), params.get("commitSha"))).isTrue(),
                    "params repositoryId and commitSha; one EVENT run, SUCCESS, configPath"
                            + " .config/qits/release.yml, at that commitSha"),
            new Case(CI, Trigger.phase("release-<repository>, deploy-<application> (the wait)"),
                    "a failed run", "getRun", Request.get("/ci/api/runs/{runId}"), 200,
                    List.of("$.status", "$.steps[*].stepIndex", "$.steps[*].status",
                            "$.steps[*].output"),
                    (base, params, recorded) -> {
                        CiApi ci = ci(base);
                        assertThat(ci.run(params.get("runId")).map(r -> r.path("status").asText()))
                                .contains("FAILED");
                        assertThat(ci.failedStepOutput(params.get("runId"))).isPresent();
                    },
                    "param runId; a FAILED run whose failed step carries its output"),
            new Case(CI, Trigger.phase("release-<repository>, deploy-<application> (the log relay)"),
                    "a running run", "getRun", Request.get("/ci/api/runs/{runId}"), 200,
                    List.of("$.status", "$.steps[*].stepIndex", "$.steps[*].output",
                            "$.live.stepIndex", "$.live.output"),
                    (base, params, recorded) -> assertThat(ci(base).run(params.get("runId")))
                            .isPresent(),
                    "param runId; a RUNNING run with one finished step and its live step's output"),
            new Case(CI, Trigger.phase("runner-localhost"), "no runners", "create",
                    Request.json("POST", "/ci/api/runners", () -> new PactDslJsonBody()
                            .stringValue("name", PhaseReads.LOCALHOST_RUNNER)
                            .integerType("slots", 2)),
                    201, List.of("$.id", "$.installScript"),
                    (base, params, recorded) -> {
                        Http.Response created = ci(base).createRunner(PhaseReads.LOCALHOST_RUNNER,
                                2, null);
                        JsonNode runner = eu.wohlben.qits.cli.bootstrap.api.Json.parse(created.body());
                        assertThat(runner.path("id").asText()).isNotBlank();
                        assertThat(CiApi.registrationToken(runner.path("installScript").asText()))
                                .isPresent();
                        assertThat(CiApi.runnerUrl(runner.path("installScript").asText())).isPresent();
                    },
                    "no runner rows; the create answers 201 with the runner's id and an installScript"
                            + " carrying QITS_CI_RUNNER_URL and QITS_CI_RUNNER_REGISTRATION_TOKEN"),
            new Case(CI, Trigger.phase("runner-localhost"), "an unregistered runner", "list",
                    Request.get("/ci/api/runners"), 200,
                    List.of("$.runners[*].id", "$.runners[*].name", "$.runners[*].registered",
                            "$.runners[*].connected", "$.runners[*].lastSeenAt"),
                    (base, params, recorded) -> assertThat(CiApi.runnerNamed(ci(base).runners(),
                            PhaseReads.LOCALHOST_RUNNER)).isPresent(),
                    "param runnerId; one runner named localhost, never registered or seen"),
            new Case(CI, Trigger.phase("runner-localhost"), "an unregistered runner",
                    "rotateRegistrationToken",
                    Request.json("POST", "/ci/api/runners/{runnerId}/registration-token",
                            PactDslJsonBody::new),
                    200, List.of("$.installScript"),
                    (base, params, recorded) -> {
                        Http.Response answer = ci(base).rotateRegistrationToken(
                                params.get("runnerId"), null);
                        String script = eu.wohlben.qits.cli.bootstrap.api.Json.text(
                                eu.wohlben.qits.cli.bootstrap.api.Json.parse(answer.body()),
                                "installScript");
                        assertThat(CiApi.registrationToken(script)).isPresent();
                    },
                    "param runnerId; the rotation answers 200 with a fresh installScript"),
            new Case(CI, Trigger.phase("runner-connected"), "a connected runner", "list",
                    Request.get("/ci/api/runners"), 200,
                    List.of("$.runners[*].id", "$.runners[*].name", "$.runners[*].registered",
                            "$.runners[*].connected", "$.runners[*].quarantined",
                            "$.runners[*].quarantineReason"),
                    (base, params, recorded) -> assertThat(PhaseReads.ciRunnerConnected(
                            ci(base).runners(), params.get("runnerId"))).isNotNull(),
                    "param runnerId; runner localhost registered and connected, not quarantined"),
            new Case(CI, Trigger.phase("runner-connected"), "a quarantined runner", "greenlight",
                    Request.json("POST", "/ci/api/runners/{runnerId}/greenlight",
                            PactDslJsonBody::new),
                    200, List.of(),
                    (base, params, recorded) -> assertThat(
                            ci(base).greenlight(params.get("runnerId")).ok()).isTrue(),
                    "param runnerId; a quarantined runner the greenlight lifts (200)"),

            // qits-deployments ------------------------------------------------------------------
            new Case(DEPLOYMENTS, Trigger.phase("seed-health"), "the machine gate is on",
                    "softwareReleased",
                    Request.json("POST", "/deployments/api/events/software-released",
                            PactDslJsonBody::new).with(JUNK_BEARER),
                    401, List.of(),
                    (base, params, recorded) -> assertThat(new Http().postJson(
                            base + "/deployments/api/events/software-released", "{}",
                            JUNK_BEARER).status()).isEqualTo(401),
                    "the OIDC gate on, so a bearer no idp issued answers 401"),
            new Case(DEPLOYMENTS, Trigger.phase("environment"), "the platform environment exists",
                    "list", Request.get("/deployments/api/environments"), 200,
                    List.of("$.environments[*].id", "$.environments[*].name",
                            "$.environments[*].platform"),
                    (base, params, recorded) -> {
                        PdApi pd = pd(base);
                        assertThat(pd.environmentId(params.get("environmentName")))
                                .contains(params.get("environmentId"));
                        assertThat(pd.platformEnvironment()).isPresent();
                    },
                    "params environmentId and environmentName; one environment, platform true"),
            new Case(DEPLOYMENTS, Trigger.phase("environment"), "no environments", "create",
                    Request.json("POST", "/deployments/api/environments", () -> new PactDslJsonBody()
                            .stringType("name", "dev")
                            .stringValue("network", "qits-net")
                            .booleanValue("platform", true)),
                    201, List.of("$.environment.id"),
                    (base, params, recorded) -> {
                        Http.Response created = pd(base).createEnvironment("dev", "qits-net", null);
                        assertThat(created.status()).isEqualTo(201);
                        assertThat(eu.wohlben.qits.cli.bootstrap.api.Json.parse(created.body())
                                .path("environment").path("id").asText()).isNotBlank();
                    },
                    "no environment rows; the create answers 201 with {environment: {id}}"),
            new Case(DEPLOYMENTS, Trigger.phase("environment"), "the platform environment exists",
                    "update",
                    Request.json("PATCH", "/deployments/api/environments/{environmentId}",
                            () -> new PactDslJsonBody().booleanValue("platform", true)),
                    200, List.of(),
                    (base, params, recorded) -> assertThat(pd(base).patchEnvironment(
                            params.get("environmentId"), "{\"platform\":true}", null).ok()).isTrue(),
                    "param environmentId; the PATCH answers 200"),
            new Case(DEPLOYMENTS, Trigger.phase("seed-deploy-<application>"),
                    "a seed-deployed application", "softwareReleased",
                    Request.json("POST", "/deployments/api/events/software-released",
                            () -> new PactDslJsonBody()
                                    .stringType("repoId", "00000000-0000-4000-8000-000000000002")
                                    .stringType("projectId", "00000000-0000-4000-8000-000000000001")
                                    .stringType("repoName", "qits-ci-service")
                                    .stringType("application", "qits-ci")
                                    .stringType("version", "2026.1001.1")),
                    202, List.of(),
                    (base, params, recorded) -> assertThat(pd(base).softwareReleased(
                            "00000000-0000-4000-8000-000000000002",
                            "00000000-0000-4000-8000-000000000001", "qits-ci-service", "qits-ci",
                            "2026.1001.1", null).status()).isEqualTo(202),
                    "the software-released door answers 202"),
            new Case(DEPLOYMENTS, Trigger.phase("deploy-<application>, seed-deploy-<application>"),
                    "an active deployment", "list",
                    Request.get("/deployments/api/deployments", "environmentId", "{environmentId}"),
                    200, List.of("$.deployments[*].id", "$.deployments[*].applicationName",
                            "$.deployments[*].status", "$.deployments[*].version",
                            "$.deployments[*].containerName", "$.deployments[*].detail"),
                    (base, params, recorded) -> assertThat(pd(base).newestDeployment(
                            params.get("environmentId"), params.get("applicationName")))
                            .isPresent(),
                    "params environmentId and applicationName; one ACTIVE deployment row with its"
                            + " version, containerName and detail"),

            // qits-idp --------------------------------------------------------------------------
            new Case(IDP, Trigger.phase("every phase that presents a machine token"),
                    "a static client", "token",
                    Request.text("POST", "/idp/token",
                            "grant_type=client_credentials&audience=qits-platform",
                            "application/x-www-form-urlencoded"),
                    200, List.of("$.access_token", "$.expires_in"),
                    (base, params, recorded) -> assertThat(new IdpApi(new Http(), base + "/idp")
                            .minted(params.get("clientId"), params.get("clientSecret"),
                                    "qits-platform").value()).isNotBlank(),
                    "params clientId and clientSecret of a static client; the token answers 200"
                            + " with access_token and expires_in"),
            new Case(IDP, Trigger.phase("register-token"), "a static client holding qits:system",
                    "mint",
                    Request.json("POST", "/idp/api/register-tokens", PactDslJsonBody::new),
                    201, List.of("$.token"),
                    (base, params, recorded) -> assertThat(eu.wohlben.qits.cli.bootstrap.api.Json
                            .text(eu.wohlben.qits.cli.bootstrap.api.Json.parse(
                                    new IdpApi(new Http(), base + "/idp").mintRegisterToken(
                                            params.get("clientId"), params.get("clientSecret"))
                                            .body()), "token")).isNotBlank(),
                    "params clientId and clientSecret; the mint answers with {token}"),
            new Case(IDP, Trigger.phase("idp-clients"), "a static client holding qits:system",
                    "create",
                    Request.json("POST", "/idp/api/service-clients",
                            () -> new PactDslJsonBody().stringType("clientId", "dev-qits-ci")),
                    201, List.of("$.secret"),
                    (base, params, recorded) -> assertThat(serviceClients(base, params)
                            .create("dev-qits-ci").ok()).isTrue(),
                    "params clientId and clientSecret; no service client dev-qits-ci, so the create"
                            + " answers 201 with {secret}"),
            new Case(IDP, Trigger.phase("idp-clients"), "a service client", "create",
                    Request.json("POST", "/idp/api/service-clients",
                            () -> new PactDslJsonBody().stringType("clientId", "dev-qits-ci")),
                    409, List.of(),
                    (base, params, recorded) -> assertThat(serviceClients(base, params)
                            .create(params.get("serviceClientId")).conflict()).isTrue(),
                    "params clientId, clientSecret and serviceClientId of an existing row; the"
                            + " create answers 409"),
            new Case(IDP, Trigger.phase("idp-clients"), "a service client", "rotate",
                    Request.json("POST", "/idp/api/service-clients/{serviceClientId}/secret",
                            PactDslJsonBody::new),
                    200, List.of("$.secret"),
                    (base, params, recorded) -> assertThat(serviceClients(base, params)
                            .rotate(params.get("serviceClientId")).ok()).isTrue(),
                    "params clientId, clientSecret and serviceClientId; the rotation answers 200"
                            + " with {secret}"),
            new Case(IDP, Trigger.phase("images-publish, daemon-publish (the publishing credential)"),
                    "a static client holding qits:system", "commission",
                    Request.json("POST", "/idp/api/clients", () -> new PactDslJsonBody()
                            .stringValue("contextKind", "bootstrap-publish")
                            .stringType("contextId", "dev")
                            .array("gitRefs").closeArray()),
                    201, List.of("$.clientId", "$.secret"),
                    (base, params, recorded) -> {
                        BootstrapPublishCredential credential = new BootstrapPublishCredential(
                                new Http(), new IdpApi(new Http(), base + "/idp"), base + "/idp",
                                params.get("clientId"), params.get("clientSecret"), "dev",
                                "qits-platform");
                        assertThat(credential.pair().clientId()).isNotBlank();
                    },
                    "params clientId and clientSecret; the commission answers 201 with clientId"
                            + " and secret"),
            new Case(IDP, Trigger.phase("publish-credential-release"), "a commissioned client",
                    "decommission", Request.delete("/idp/api/clients/{commissionedClientId}"),
                    204, List.of(),
                    (base, params, recorded) -> assertThat(new Http().delete(
                            base + "/idp/api/clients/" + params.get("commissionedClientId"),
                            Map.of("Authorization", Http.basic(params.get("commissionedClientId"),
                                    params.get("commissionedClientSecret")))).ok()).isTrue(),
                    "params commissionedClientId and commissionedClientSecret; the client hands"
                            + " itself back (204)"),
            new Case(IDP, new Trigger("qits-bootstrap login"), "an authorization code", "token",
                    Request.text("POST", "/idp/token",
                            "grant_type=authorization_code&client_id=qits-git-workstation",
                            "application/x-www-form-urlencoded"),
                    200, List.of("$.access_token", "$.refresh_token"),
                    (base, params, recorded) -> assertThat(new TokenClient().exchange(
                            base + "/idp", params.get("code"), params.get("redirectUri"),
                            params.get("verifier")).accessToken()).isNotBlank(),
                    "params code, redirectUri and verifier of a PKCE authorization for"
                            + " qits-git-workstation; the token answers access_token and"
                            + " refresh_token"),
            new Case(IDP, new Trigger("qits-bootstrap git-credential"), "a refresh token", "token",
                    Request.text("POST", "/idp/token",
                            "grant_type=refresh_token&client_id=qits-git-workstation",
                            "application/x-www-form-urlencoded"),
                    200, List.of("$.access_token", "$.refresh_token"),
                    (base, params, recorded) -> assertThat(new TokenClient().refresh(
                            new WorkstationCredential(base + "/idp", params.get("audience"),
                                    null, params.get("refreshToken"))).accessToken()).isNotBlank(),
                    "params refreshToken and audience of a qits-git-workstation login; the token"
                            + " answers access_token and refresh_token"),

            // qits-githost ----------------------------------------------------------------------
            new Case(GITHOST, Trigger.phase("git-repos"), "no repository with the given id",
                    "createRepository",
                    Request.json("PUT", "/git/{repositoryId}",
                            () -> new PactDslJsonBody().stringValue("defaultBranch", "main")),
                    201, List.of(),
                    (base, params, recorded) -> assertThat(githost(base).createRepository(
                            params.get("repositoryId"), "pact-bearer").status()).isEqualTo(201),
                    "param repositoryId; the lifecycle PUT creates the bare (201)"),
            new Case(GITHOST, Trigger.phase("git-repos"), "a repository exists", "createRepository",
                    Request.json("PUT", "/git/{repositoryId}",
                            () -> new PactDslJsonBody().stringValue("defaultBranch", "main")),
                    200, List.of(),
                    (base, params, recorded) -> assertThat(githost(base).createRepository(
                            params.get("repositoryId"), "pact-bearer").status()).isEqualTo(200),
                    "param repositoryId; the lifecycle PUT finds the bare (200)"),

            // qits-artifacts --------------------------------------------------------------------
            new Case(ARTIFACTS, Trigger.phase("daemon-publish"), "no daemon version", "daemonVersion",
                    Request.head("/artifacts/daemons/{daemon}/{version}"), 404, List.of(),
                    (base, params, recorded) -> assertThat(artifacts(base).daemonPublished(
                            params.get("daemon"), params.get("version"))).isFalse(),
                    "params daemon and version of a version the store does not hold (404)"),
            new Case(ARTIFACTS, Trigger.phase("daemon-publish"), "no daemon version",
                    "publishDaemon",
                    Request.text("PUT", "/artifacts/daemons/{daemon}/{version}", "\u007fELF",
                            "application/octet-stream"),
                    201, List.of(),
                    (base, params, recorded) -> {
                        Path binary = Files.createTempFile("qits-ci-daemon", "");
                        Files.writeString(binary, "\u007fELF");
                        assertThat(artifacts(base).publishDaemon(params.get("daemon"),
                                params.get("version"), binary, "Bearer pact").ok()).isTrue();
                    },
                    "params daemon and version; a qits:ci-run publish answers 201"),

            // qits-configuration ----------------------------------------------------------------
            new Case(CONFIGURATION, Trigger.phase("configuration-import"), "no configuration",
                    "importProperties",
                    Request.text("POST", "/configuration/api/import",
                            "# qits-ci\nqits-ci.QITS_EXAMPLE=value\n", "text/plain", "env", "dev"),
                    200, List.of(),
                    (base, params, recorded) -> assertThat(new ConfigurationApi(new Http(), base,
                            "dev").importProperties("# qits-ci\nqits-ci.QITS_EXAMPLE=value\n")
                            .ok()).isTrue(),
                    "an empty store; the import of one line for env dev answers 200"),

            // qits-workspaces -------------------------------------------------------------------
            new Case(WORKSPACES, Trigger.phase("workspaces-runner-localhost"), "no runners",
                    "create",
                    Request.json("POST", "/workspaces/api/runners", () -> new PactDslJsonBody()
                            .stringValue("name", PhaseReads.LOCALHOST_RUNNER)
                            .integerType("slots", 2)),
                    201, List.of("$.runner.id", "$.registrationToken", "$.installLine"),
                    (base, params, recorded) -> {
                        JsonNode answer = eu.wohlben.qits.cli.bootstrap.api.Json.parse(
                                workspaces(base).createRunner(PhaseReads.LOCALHOST_RUNNER, 2, null)
                                        .body());
                        assertThat(WorkspacesApi.runnerOf(answer).path("id").asText()).isNotBlank();
                        assertThat(WorkspacesApi.registrationToken(answer)).isPresent();
                        assertThat(WorkspacesApi.runnerUrl(answer.path("installLine").asText()))
                                .isPresent();
                    },
                    "no runner rows; the create answers 201 with {runner: {id}, registrationToken,"
                            + " installLine} and the line names QITS_WORKSPACES_RUNNER_URL"),
            new Case(WORKSPACES, Trigger.phase("workspaces-runner-localhost"),
                    "an unregistered runner", "list", Request.get("/workspaces/api/runners"), 200,
                    List.of("$[*].id", "$[*].name", "$[*].registered", "$[*].connected",
                            "$[*].lastSeenAt"),
                    (base, params, recorded) -> assertThat(WorkspacesApi.runnerNamed(
                            workspaces(base).runners(), PhaseReads.LOCALHOST_RUNNER)).isPresent(),
                    "param runnerId; one runner named localhost, never registered or seen"),
            new Case(WORKSPACES, Trigger.phase("workspaces-runner-localhost"),
                    "an unregistered runner", "rotateRegistrationToken",
                    Request.json("POST", "/workspaces/api/runners/{runnerId}/registration-token",
                            PactDslJsonBody::new),
                    200, List.of("$.registrationToken", "$.installLine"),
                    (base, params, recorded) -> assertThat(WorkspacesApi.registrationToken(
                            eu.wohlben.qits.cli.bootstrap.api.Json.parse(workspaces(base)
                                    .rotateRegistrationToken(params.get("runnerId"), null).body())))
                            .isPresent(),
                    "param runnerId; the rotation answers 200 with registrationToken and"
                            + " installLine"),
            new Case(WORKSPACES, Trigger.phase("workspaces-runner-connected"), "a connected runner",
                    "list", Request.get("/workspaces/api/runners"), 200,
                    List.of("$[*].id", "$[*].name", "$[*].registered", "$[*].connected",
                            "$[*].quarantined", "$[*].quarantineReason"),
                    (base, params, recorded) -> assertThat(PhaseReads.workspacesRunnerConnected(
                            workspaces(base).runners(), params.get("runnerId"))).isNotNull(),
                    "param runnerId; runner localhost registered and connected, not quarantined"),
            new Case(WORKSPACES, Trigger.phase("workspaces-runner-connected"),
                    "a quarantined runner", "greenlight",
                    Request.json("POST", "/workspaces/api/runners/{runnerId}/greenlight",
                            PactDslJsonBody::new),
                    200, List.of(),
                    (base, params, recorded) -> assertThat(
                            workspaces(base).greenlight(params.get("runnerId")).ok()).isTrue(),
                    "param runnerId; a quarantined runner the greenlight lifts (200)"),

            // qits-containers -------------------------------------------------------------------
            new Case(CONTAINERS, Trigger.phase("seed-health"), "the machine gate is on",
                    "listOwner",
                    Request.get("/containers/api/containers/{owner}").with(JUNK_BEARER), 401,
                    List.of(),
                    (base, params, recorded) -> assertThat(new Http().get(
                            base + "/containers/api/containers/" + params.get("owner"),
                            JUNK_BEARER).status()).isEqualTo(401),
                    "param owner (dev-qits-ci); a bearer no idp issued answers 401"));

    /** {@code environment} and {@code summary}: the slugs of a listing, as the phase reads them. */
    private static Call projectSlugs() {
        return (base, params, recorded) -> {
            List<String> slugs = PhaseReads.projectSlugs(
                    new ProjectsApi(new Http(), base).projects(null));
            List<String> expected = new ArrayList<>();
            recorded.path("entries").forEach(e -> expected.add(e.path("project").path("slug").asText()));
            assertThat(slugs).containsExactlyElementsOf(expected);
        };
    }

    private static CiApi ci(String base) {
        return new CiApi(new Http(), base + "/ci");
    }

    private static PdApi pd(String base) {
        return new PdApi(new Http(), base + "/deployments");
    }

    private static GitHostApi githost(String base) {
        return new GitHostApi(new Http(), base + "/git", base + "/githost");
    }

    private static ArtifactsApi artifacts(String base) {
        return new ArtifactsApi(new Http(), base + "/artifacts", () -> "Bearer pact");
    }

    private static WorkspacesApi workspaces(String base) {
        return new WorkspacesApi(new Http(), base + "/workspaces");
    }

    private static ServiceClientsApi serviceClients(String base, Map<String, String> params) {
        return new ServiceClientsApi(new Http(), base + "/idp", params.get("clientId"),
                params.get("clientSecret"));
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
        if (op.status() != c.status()) {
            throw new IllegalStateException(c.description() + " expects " + c.status() + " but "
                    + c.provider().application() + " recorded " + op.status() + " in '" + c.state()
                    + "'");
        }
        if (!op.method().equals(c.request().method())) {
            throw new IllegalStateException(c.description() + " sends " + c.request().method()
                    + " but " + c.provider().application() + " recorded " + op.method());
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
                r.headers().forEach(request::header);
                if (r.json() != null) {
                    request.body(r.json().get());
                } else if (r.text() != null) {
                    request.body(r.text(), r.contentType());
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
        Matcher m = PARAM.matcher(template);
        if (!m.find()) {
            return template;
        }
        m.reset();
        StringBuilder example = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = params.get(name);
            if (value == null) {
                throw new IllegalStateException(c.description() + ": " + template + " names {"
                        + name + "}, which state '" + c.state() + "' does not hold");
            }
            m.appendReplacement(example, Matcher.quoteReplacement(value));
        }
        m.appendTail(example);
        return Matchers.fromProviderState(template.replace("{", "${"), example.toString());
    }
}
