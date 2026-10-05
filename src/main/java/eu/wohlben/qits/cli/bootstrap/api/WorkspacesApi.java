package eu.wohlben.qits.cli.bootstrap.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * qits-workspaces: the four runner doors the {@code workspaces-runner-*} phases use, and nothing
 * else. {@link CiApi}'s runner half is the twin, door for door — qits-workspaces'
 * {@code WorkspaceRunnerController} was written after qits-ci's {@code CiRunnerController}.
 * <p>
 * <b>At the service's own alias on qits-net, as qits-ci is dialled.</b> The reads and the greenlight
 * assert {@code X-Qits-User}/{@code X-Qits-Roles}, which the edge strips from what it proxies, so
 * they have to reach the service directly.
 * <p>
 * <b>Two shapes differ from qits-ci's, and both are qits-workspaces' own.</b> The listing is a bare
 * JSON array, not {@code {"runners": [...]}}. And a create or a rotation answers
 * {@code {runner, registrationToken, installLine}}: the token is a field of its own, so it is read
 * from there and never parsed out of the line.
 */
public class WorkspacesApi {

    /** Identity asserted on the private qits-net hop, as {@link CiApi} asserts it to qits-ci. */
    private static final Map<String, String> SYSTEM_HEADERS = Map.of(
            "X-Qits-User", "qits-bootstrap",
            "X-Qits-Roles", "qits:admin");

    private final Http http;
    private final String base;

    public WorkspacesApi(Http http, String workspacesUrl) {
        this.http = http;
        this.base = workspacesUrl;
    }

    public Http.Response health() {
        return http.get(base + "/q/health/ready", SYSTEM_HEADERS);
    }

    /**
     * <b>{@code POST /workspaces/api/runners}: declare a runner.</b> 201 answers the runner under
     * {@code runner}, its {@code registrationToken} and the {@code installLine} carrying it — the
     * only time either value is ever answered. 409 is a name already taken; 503 a qits-workspaces
     * that commissions nothing, or that knows no public domain to address a runner by.
     * <p>
     * <b>A machine write, with this run's own token</b> — the create and the rotation take
     * {@code qits:system} beside {@code qits:admin}, exactly as qits-ci's do.
     * <p>
     * <b>Never {@link Http.Response#describe()} a successful answer</b>: its body carries the token.
     */
    public Http.Response createRunner(String name, int slots, String token) {
        return http.postJson(base + "/api/runners",
                Json.object("name", name, "slots", Json.verbatim(String.valueOf(slots))),
                writer(token));
    }

    /** {@code GET /workspaces/api/runners}: every runner, with whether it is registered and connected. */
    public Http.Response runners() {
        return http.get(base + "/api/runners", SYSTEM_HEADERS);
    }

    /**
     * {@code POST /workspaces/api/runners/{id}/registration-token}: a fresh token for a runner that
     * has not registered, in the create's shape. A registered runner is 409.
     */
    public Http.Response rotateRegistrationToken(String runnerId, String token) {
        return http.postJson(base + "/api/runners/" + runnerId + "/registration-token", "{}",
                writer(token));
    }

    /**
     * <b>{@code POST /workspaces/api/runners/{id}/greenlight}: lift a runner's quarantine.</b>
     * {@code qits:admin} alone, so it goes as the forwarded identity on the qits-net hop, as
     * {@link CiApi#greenlight} does. A runner already in service is answered as it is.
     */
    public Http.Response greenlight(String runnerId) {
        return http.postJson(base + "/api/runners/" + runnerId + "/greenlight", "{}",
                SYSTEM_HEADERS);
    }

    /** Every runner of a {@link #runners()} answer — a bare array — or none when it did not answer. */
    public static List<JsonNode> runnersIn(Http.Response listing) {
        List<JsonNode> runners = new ArrayList<>();
        JsonNode answer = listing.ok() ? Json.parse(listing.body()) : null;
        // An object is not a listing — iterating one would read its values as runners.
        if (answer != null && answer.isArray()) {
            answer.forEach(runners::add);
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

    /** The runner a create or a rotation answered, under {@code runner}. */
    public static JsonNode runnerOf(JsonNode registration) {
        return registration.path("runner");
    }

    /**
     * The answer's {@code registrationToken}, its own field. Never logged, never put in an
     * exception, never parsed out of the install line.
     */
    public static Optional<String> registrationToken(JsonNode registration) {
        String token = Json.text(registration, "registrationToken");
        return token.isBlank() ? Optional.empty() : Optional.of(token);
    }

    /**
     * The install line's {@code QITS_WORKSPACES_RUNNER_URL='…'}: where qits-workspaces tells this
     * runner to dial it, the public {@code https://workspaces.qits.<domain>}. The address carries
     * no secret, so reading it out of the line is safe where reading the token would not be.
     */
    public static Optional<String> runnerUrl(String installLine) {
        if (installLine == null) {
            return Optional.empty();
        }
        Matcher url = RUNNER_URL.matcher(installLine);
        return url.find() ? Optional.of(url.group(1)) : Optional.empty();
    }

    private static final Pattern RUNNER_URL =
            Pattern.compile("QITS_WORKSPACES_RUNNER_URL='([^']+)'");

    /** A write's credential: the bearer when there is one, the forwarded identity otherwise. */
    private static Map<String, String> writer(String token) {
        return token == null || token.isBlank() ? SYSTEM_HEADERS
                : Map.of("Authorization", "Bearer " + token);
    }
}
