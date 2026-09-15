package eu.wohlben.qits.cli.bootstrap.api;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * qits-idp's service-client API: the door this program creates the seed services' credentials at.
 * <p>
 * <b>Every seed client is made HERE rather than spelled into a generated file.</b> A client id and
 * a secret written into the seed stack are two copies of one credential that nothing keeps in step:
 * the file says what the platform was handed, the idp says what it accepted, and the moment one of
 * them is edited the other is a 401 with nothing in any log to say why. Created against the running
 * service there is one copy, the idp's, and the bootstrap is a caller like any other.
 * <p>
 * <b>Basic, not a bearer, and the caller must be a STATIC client holding {@code qits:system}.</b>
 * That is the idp's own rule and not a choice made here: a commissioned credential belongs to a
 * context and may not make a client, exactly as it may not make a person. The bootstrap's pair is
 * the one this program has — see {@code PlatformModel.bootstrapClientId} — and the idp seeds it
 * from {@code QITS_IDP_SEED_CLIENT_ID} / {@code _SECRET} at its first start.
 * <p>
 * <b>A failed call is an answer, not an exception</b> — {@link Http}'s rule, and it matters more
 * here than anywhere: this runs while the idp is still coming up, and a poll that threw would fail
 * a boot that is working.
 * <p>
 * <b>Nothing in this class ever puts a secret in a message.</b> {@link Result#detail} is built from
 * {@link Http.Response#describe()} and is only ever read on a FAILURE, where there is no secret to
 * leak; the secret itself leaves by one field and is never logged.
 */
public class ServiceClientsApi {

    /**
     * What the idp accepts as a client id, restated: {@code [a-z][a-z0-9-]{0,127}}. Asked before a
     * call rather than after, so a name this program derived wrongly is named by the phase that
     * derived it rather than by a 400 three hops away.
     */
    private static final Pattern CLIENT_ID =
            Pattern.compile("^[a-z][a-z0-9-]{0,127}$");

    /** HTTP 409: a database row for this client id is already there. */
    private static final int CONFLICT = 409;
    private static final int NOT_FOUND = 404;

    private final Http http;
    private final String base;
    private final String authorization;

    /**
     * @param issuer the idp's issuer url, e.g. {@code http://qits-platform-idp:8080/idp} — the API
     *               sits directly under it, like every other of the idp's own routes
     */
    public ServiceClientsApi(Http http, String issuer, String clientId, String secret) {
        this.http = http;
        this.base = issuer + "/api/service-clients";
        this.authorization = Http.basic(clientId == null ? "" : clientId,
                secret == null ? "" : secret);
    }

    /**
     * What one create or rotate ended as.
     *
     * @param ok       the idp issued a secret
     * @param conflict the idp refused because a row for this client id already exists — the ONE
     *                 refusal that has a repair, and {@link #rotate} is it. A recorded secret that
     *                 no longer works and a client the registry never knew about both land here.
     * @param secret   the issued value, and empty on everything but success. Never printed, never
     *                 logged, never written to {@code .qits-bootstrap.env}
     * @param detail   what the idp said, for a message a person reads. Carries no secret
     */
    public record Result(boolean ok, boolean conflict, String secret, String detail) {
    }

    /**
     * Where the idp knows this client from — {@code environment}, {@code database} or {@code both}
     * — and empty when it does not know it at all.
     * <p>
     * <b>It never answers with a secret</b>, by the idp's design: a secret is said once, at the
     * call that issued it. So this is a question about EXISTENCE and nothing else, which is exactly
     * what a rerun wants to ask before it decides between creating and rotating.
     */
    public Optional<String> source(String clientId) {
        Http.Response response = http.get(base + "/" + clientId,
                Map.of("Authorization", authorization));
        if (!response.ok()) {
            return Optional.empty();
        }
        String source = Json.text(Json.parse(response.body()), "source");
        return source == null || source.isBlank() ? Optional.empty() : Optional.of(source);
    }

    /**
     * Creates the client and reads its secret out of the answer.
     * <p>
     * <b>409 is not a failure here and the caller must not treat it as one.</b> It says a database
     * row already exists — an earlier boot made this client, or the deployer did — and the whole
     * point of {@link Result#conflict} is that the caller then rotates rather than stopping. A
     * bootstrap that failed on it would be a platform that can never be re-bootstrapped.
     */
    public Result create(String clientId) {
        String refusal = refuseBadId(clientId);
        if (refusal != null) {
            return new Result(false, false, "", refusal);
        }
        Http.Response response = http.postJson(base, Json.object("clientId", clientId),
                Map.of("Authorization", authorization));
        return read(response, "created");
    }

    /**
     * Issues a fresh secret for a client that is already there.
     * <p>
     * It is the ONLY way back to a usable credential once the idp holds a row: the stored form is a
     * hash, so a secret nobody recorded is a secret nobody can recover. A 404 here means the row is
     * not there after all, which is a create's job and not this one's.
     */
    public Result rotate(String clientId) {
        String refusal = refuseBadId(clientId);
        if (refusal != null) {
            return new Result(false, false, "", refusal);
        }
        Http.Response response = http.postJson(base + "/" + clientId + "/secret", "{}",
                Map.of("Authorization", authorization));
        return read(response, "rotated");
    }

    /** Whether this id is one the idp would accept at all, as a message or null. */
    private static String refuseBadId(String clientId) {
        return clientId != null && CLIENT_ID.matcher(clientId).matches() ? null
                : "not a client id the idp accepts ([a-z][a-z0-9-]{0,127}): " + clientId;
    }

    /**
     * One answer, as a result. The secret is taken out of the body and nothing else of the body is
     * kept on success — a describe() of a 201 would put the secret in {@link Result#detail}, which
     * is the one field this class prints.
     */
    private static Result read(Http.Response response, String what) {
        if (response.status() == CONFLICT) {
            return new Result(false, true, "", "the idp already holds a row for this client");
        }
        if (!response.ok()) {
            return new Result(false, false, "",
                    response.status() == NOT_FOUND ? "no such client at the idp"
                            : response.describe());
        }
        String secret = Json.text(Json.parse(response.body()), "secret");
        if (secret == null || secret.isBlank()) {
            return new Result(false, false, "",
                    "the idp answered " + response.status() + " with no 'secret' field");
        }
        return new Result(true, false, secret, what);
    }
}
