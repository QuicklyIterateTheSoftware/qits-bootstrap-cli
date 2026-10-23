package eu.wohlben.qits.cli.bootstrap.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * qits-system: its readiness, and the four targets doors the {@code target-*} phases use
 * ({@code TargetController} in qits-system-service's {@code targets} module). It replaced
 * {@code WorkspacesApi}, whose runner doors were the same four, door for door, when this host's
 * target replaced its workspaces runner (qits-1535).
 * <p>
 * <b>At the service's own alias on qits-net, as qits-ci is dialled.</b> The greenlight takes
 * {@code qits:admin} or {@code qits:admin-agent} and nothing a machine holds, so it goes as the
 * forwarded identity — {@code X-Qits-User}/{@code X-Qits-Roles}, which the edge strips from what it
 * proxies — and has to reach the service directly. The reads go the same way.
 * <p>
 * <b>The shapes are qits-system's own.</b> The listing is a bare JSON array of targets. A create
 * or a rotation answers {@code {target, registrationToken, installLine}}: the token is a field of
 * its own, so it is read from there and never parsed out of the line.
 */
public class SystemApi {

    /** Identity asserted on the private qits-net hop, as {@link CiApi} asserts it to qits-ci. */
    private static final Map<String, String> SYSTEM_HEADERS = Map.of(
            "X-Qits-User", "qits-bootstrap",
            "X-Qits-Roles", "qits:admin");

    private final Http http;
    private final String base;

    public SystemApi(Http http, String systemUrl) {
        this.http = http;
        this.base = systemUrl;
    }

    public Http.Response health() {
        return http.get(base + "/q/health/ready", SYSTEM_HEADERS);
    }

    /**
     * <b>{@code POST /system/api/targets}: declare a target.</b> 201 answers the target under
     * {@code target}, its {@code registrationToken} and the {@code installLine} carrying it — the
     * only time either value is ever answered. 409 is a name already taken; 503 a qits-system that
     * commissions nothing, knows no public domain to address a runner by, or pins no runner.
     * <p>
     * <b>A machine write, with this run's own token</b> — the create and the rotation take
     * {@code qits:system} beside the two admin roles.
     * <p>
     * <b>Never {@link Http.Response#describe()} a successful answer</b>: its body carries the token.
     */
    public Http.Response createTarget(String name, String description, int ci, int workspace,
                                      int desk, String token) {
        String minimums = "{\"ci\":" + ci + ",\"workspace\":" + workspace + ",\"desk\":" + desk
                + "}";
        return http.postJson(base + "/api/targets",
                Json.object("name", name, "description", description,
                        "minimums", Json.verbatim(minimums)),
                writer(token));
    }

    /** {@code GET /system/api/targets}: every target, with whether it is registered and connected. */
    public Http.Response targets() {
        return http.get(base + "/api/targets", SYSTEM_HEADERS);
    }

    /**
     * {@code POST /system/api/targets/{id}/registration-token}: a fresh token for a target whose
     * runner has not registered, in the create's shape. A registered one is 409.
     */
    public Http.Response rotateRegistrationToken(String targetId, String token) {
        return http.post(base + "/api/targets/" + targetId + "/registration-token",
                writer(token));
    }

    /**
     * <b>{@code POST /system/api/targets/{id}/greenlight}: lift a target's quarantine.</b> The two
     * admin roles alone, so it goes as the forwarded identity on the qits-net hop, as
     * {@link CiApi#greenlight} does. A target already in service is answered as it is.
     */
    public Http.Response greenlight(String targetId) {
        return http.post(base + "/api/targets/" + targetId + "/greenlight", SYSTEM_HEADERS);
    }

    /** Every target of a {@link #targets()} answer — a bare array — or none when it did not answer. */
    public static List<JsonNode> targetsIn(Http.Response listing) {
        List<JsonNode> targets = new ArrayList<>();
        JsonNode answer = listing.ok() ? Json.parse(listing.body()) : null;
        // An object is not a listing — iterating one would read its values as targets.
        if (answer != null && answer.isArray()) {
            answer.forEach(targets::add);
        }
        return targets;
    }

    /** The one target of this name in a {@link #targets()} answer, if it is there. */
    public static Optional<JsonNode> targetNamed(Http.Response listing, String name) {
        for (JsonNode target : targetsIn(listing)) {
            if (name.equals(Json.text(target, "name"))) {
                return Optional.of(target);
            }
        }
        return Optional.empty();
    }

    /** The target a create or a rotation answered, under {@code target}. */
    public static JsonNode targetOf(JsonNode registration) {
        return registration.path("target");
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
     * The install line's {@code QITS_SYSTEM_RUNNER_URL='…'}: where qits-system tells this runner to
     * dial it, the public {@code https://system.qits.<domain>}. The address carries no secret, so
     * reading it out of the line is safe where reading the token would not be.
     */
    public static Optional<String> runnerUrl(String installLine) {
        if (installLine == null) {
            return Optional.empty();
        }
        Matcher url = RUNNER_URL.matcher(installLine);
        return url.find() ? Optional.of(url.group(1)) : Optional.empty();
    }

    private static final Pattern RUNNER_URL =
            Pattern.compile("QITS_SYSTEM_RUNNER_URL='([^']+)'");

    /** A write's credential: the bearer when there is one, the forwarded identity otherwise. */
    private static Map<String, String> writer(String token) {
        return token == null || token.isBlank() ? SYSTEM_HEADERS
                : Map.of("Authorization", "Bearer " + token);
    }
}
