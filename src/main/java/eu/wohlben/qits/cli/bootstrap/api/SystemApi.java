package eu.wohlben.qits.cli.bootstrap.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * qits-system: its readiness, and the four targets doors the {@code target-*} phases use
 * ({@code TargetController} in qits-system-service's {@code targets} module). It replaced
 * {@code WorkspacesApi}, whose runner doors were the same four, door for door, when this host's
 * target replaced its workspaces runner (qits-1535).
 * <p>
 * <b>Every call presents the bootstrap's own bearer, and nothing else.</b> Owner ruling (epic
 * qits-543): {@code qits:system} opens every service door — the greenlight included since
 * qits-1333 — and no service sends the forwarded {@code X-Qits-User}/{@code X-Qits-Roles} pair, so
 * there is no pair here beside the bearer and none as a fallback when there is no bearer to present:
 * the call then fails closed, as {@link CiApi}'s do.
 * <p>
 * <b>At the service's own alias on qits-net</b>, because qits-system is not seed-deployed and
 * {@code edge-ready} proves no public name for it.
 * <p>
 * <b>The shapes are qits-system's own.</b> The listing is a bare JSON array of targets. A create
 * or a rotation answers {@code {target, registrationToken, installLine}}: the token is a field of
 * its own, so it is read from there and never parsed out of the line.
 */
public class SystemApi {

    private final Http http;
    private final String base;
    /** The bootstrap's own bearer, read per call — see {@link #headers()}. */
    private final Supplier<String> bearer;

    /**
     * @param bearer the bootstrap's own machine token ({@code qits:system}), minted against the
     *               platform-wide audience and asked fresh per call
     */
    public SystemApi(Http http, String systemUrl, Supplier<String> bearer) {
        this.http = http;
        this.base = systemUrl;
        this.bearer = bearer;
    }

    /**
     * What every call here presents: the bootstrap's own bearer.
     *
     * @throws IllegalStateException when there is none — a clear failure beats a silent anonymous
     *                               call or a forwarded-identity pair nothing here sends any more
     */
    private Map<String, String> headers() {
        String token = bearer.get();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("no bearer to present to qits-system — every call here "
                    + "needs the bootstrap's own machine token, and none was minted");
        }
        return Map.of("Authorization", "Bearer " + token);
    }

    public Http.Response health() {
        return http.get(base + "/q/health/ready", headers());
    }

    /**
     * <b>{@code POST /system/api/targets}: declare a target.</b> 201 answers the target under
     * {@code target}, its {@code registrationToken} and the {@code installLine} carrying it — the
     * only time either value is ever answered. 409 is a name already taken; 503 a qits-system that
     * commissions nothing, knows no public domain to address a runner by, or pins no runner.
     * <p>
     * <b>A machine write, with this run's own token</b> — the create takes {@code qits:system}.
     * <p>
     * <b>Never {@link Http.Response#describe()} a successful answer</b>: its body carries the token.
     */
    public Http.Response createTarget(String name, String description, int ci, int workspace,
                                      int desk) {
        String reservations = "{\"ci\":" + ci + ",\"workspace\":" + workspace + ",\"desk\":" + desk
                + "}";
        return http.postJson(base + "/api/targets",
                Json.object("name", name, "description", description,
                        "reservations", Json.verbatim(reservations)),
                headers());
    }

    /** {@code GET /system/api/targets}: every target, with whether it is registered and connected. */
    public Http.Response targets() {
        return http.get(base + "/api/targets", headers());
    }

    /**
     * {@code POST /system/api/targets/{id}/registration-token}: a fresh token for a target whose
     * runner has not registered, in the create's shape. A registered one is 409.
     */
    public Http.Response rotateRegistrationToken(String targetId) {
        return http.post(base + "/api/targets/" + targetId + "/registration-token",
                headers());
    }

    /**
     * <b>{@code POST /system/api/targets/{id}/greenlight}: lift a target's quarantine.</b> With this
     * run's own bearer: the door admits {@code qits:system} since qits-1333. A target already in
     * service is answered as it is.
     */
    public Http.Response greenlight(String targetId) {
        return http.post(base + "/api/targets/" + targetId + "/greenlight", headers());
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
}
