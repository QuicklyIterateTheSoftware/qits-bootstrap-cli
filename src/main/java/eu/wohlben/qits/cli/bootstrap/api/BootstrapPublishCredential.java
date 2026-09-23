package eu.wohlben.qits.cli.bootstrap.api;

import java.time.Instant;
import java.util.Map;

/**
 * <b>The bootstrap's own publishing identity, for its publish phase and no longer.</b>
 * <p>
 * Publishing to qits-artifacts is CI's door alone (user ruling 2026-09-13, "only CI may publish"),
 * so the store's anonymous publishing door is closed and {@code qits:ci-run} is the only role that
 * opens it. This run still has to publish once — the ci-daemon binary, the seed libraries, the two
 * npm packages — before there is any CI to do it for it. So it commissions ITSELF a credential of
 * kind {@code bootstrap-publish}, which the idp's own code map gives {@code qits:ci-run} and which
 * is commissioned with {@code gitRefs: []}: it may publish, and it may push nothing.
 * <p>
 * <b>The identity is short-lived by construction and nothing but this class makes it so.</b> The
 * idp enforces no lifetime; {@link #delete()} is what keeps the ruling's promise that no permanent
 * publishing identity is left behind, and it is called when the publish phase ends AND from the
 * run's own {@code finally} — a publish that failed is exactly when a leaked credential would
 * otherwise be left standing.
 * <p>
 * <b>Basic to commission, the credential's own pair to hand it back.</b> A commissioned client may
 * not commission another, so the caller here is the STATIC pair this program owns — the same one
 * {@link ServiceClientsApi} presents. The DELETE is made as the credential ITSELF: the idp lets any
 * credential hand its own back with no platform role at all, so the cleanup needs nothing the
 * commissioning call did not already have and works even where the static pair has since been
 * rotated out from under this run.
 * <p>
 * <b>Nothing here puts a secret in a message.</b> The issued pair leaves by {@link #bearer()} only,
 * and every failure carries {@link Http.Response#describe()} of a refusal, which has no secret in
 * it.
 */
public class BootstrapPublishCredential implements AutoCloseable {

    /** The commission kind whose fixed role is {@code qits:ci-run} — the idp's {@code CommissionRoles}. */
    public static final String CONTEXT_KIND = "bootstrap-publish";

    private final Http http;
    private final IdpApi idp;
    private final String clientsUrl;
    private final String ownerAuthorization;
    private final String contextId;
    private final String audience;

    /** What the idp issued, and null until the first {@link #bearer()} commissions it. */
    private String clientId;
    private String secret;

    private String token;
    private Instant expiry;
    private boolean deleted;

    /**
     * @param address     the idp's ADDRESS, {@code http://<env>-qits-platform-idp:8080/idp} — the
     *                    API sits directly under it. <b>Not its ISSUER string</b>, which is a claim
     *                    compared for equality rather than resolved, and is still spelled bare.
     * @param ownerId     the STATIC client this program owns, which is what may commission
     * @param ownerSecret its secret
     * @param contextId   what this credential is for, as the idp records it. This run's own
     *                    bootstrap client id: it says which platform's bootstrap is publishing, and
     *                    the idp puts a slug of it in the generated client id
     */
    public BootstrapPublishCredential(Http http, IdpApi idp, String address, String ownerId,
                                      String ownerSecret, String contextId, String audience) {
        this.http = http;
        this.idp = idp;
        this.clientsUrl = address + "/api/clients";
        this.ownerAuthorization = Http.basic(ownerId == null ? "" : ownerId,
                ownerSecret == null ? "" : ownerSecret);
        this.contextId = contextId;
        this.audience = audience;
    }

    /**
     * The token a publish presents, commissioning the credential on first use and re-minting it as
     * it nears its end.
     * <p>
     * <b>Commissioned lazily</b>, because a boot that publishes nothing — {@code --skip-build} on a
     * platform whose store is already full — should leave no credential at the idp at all, not even
     * for the minutes it would take to delete it again.
     *
     * @throws IllegalStateException when the idp refuses. A publish with no token is a 401 at the
     *                               store with nothing in any log to say why, so a refusal stops
     *                               the phase where it happened
     */
    public synchronized String bearer() {
        if (deleted) {
            throw new IllegalStateException("the bootstrap's publishing credential was handed back"
                    + " when the publish phase ended — nothing may publish after that");
        }
        if (clientId == null) {
            commission();
        }
        if (token == null || Instant.now().isAfter(expiry)) {
            Instant minted = Instant.now();
            IdpApi.Token issued = idp.minted(clientId, secret, audience);
            token = issued.value();
            expiry = issued.expiryFrom(minted);
        }
        return token;
    }

    /** The same, as the header value a caller puts on a request. */
    public String authorization() {
        return "Bearer " + bearer();
    }

    /** The commissioned client's id, or null while nothing has been commissioned. */
    public synchronized String clientId() {
        return clientId;
    }

    private void commission() {
        // gitRefs is sent as a LIST and not left out: absent means "no scope stated", which is a
        // credential that may push whatever its role allows, while [] is the ruling's "may push
        // nothing". The two are one character apart on the wire and opposite in effect.
        Http.Response response = http.postJson(clientsUrl,
                Json.object("contextKind", CONTEXT_KIND,
                        "contextId", contextId,
                        "gitRefs", Json.verbatim("[]")),
                Map.of("Authorization", ownerAuthorization));
        if (!response.ok()) {
            throw new IllegalStateException("the idp commissioned no publishing credential for "
                    + contextId + ": " + response.describe());
        }
        var body = Json.parse(response.body());
        String issuedId = Json.text(body, "clientId");
        String issuedSecret = Json.text(body, "secret");
        if (issuedId.isBlank() || issuedSecret.isBlank()) {
            // describe() would carry the secret when there is one, so the answer is not quoted.
            throw new IllegalStateException("the idp answered " + response.status()
                    + " without a clientId and secret pair");
        }
        clientId = issuedId;
        secret = issuedSecret;
    }

    /**
     * <b>Hands the credential back — once, and safe to call again.</b>
     * <p>
     * Idempotent because it is called from two places on purpose: the phase that ends the publish
     * half of the boot, and the run's own {@code finally}, which is what covers a publish that
     * threw. Nothing was commissioned is the same answer as it is already gone.
     * <p>
     * <b>A refusal is an answer, not an exception.</b> This is cleanup: a run must not fail at the
     * end because the idp was mid-cutover, and what a caller does with the message is print it.
     *
     * @return what happened, for a phase to log. Empty when there was nothing to hand back
     */
    public synchronized String delete() {
        if (deleted || clientId == null) {
            deleted = true;
            return "";
        }
        // As ITSELF rather than as the owner: a credential may always hand its own back, so this
        // needs no platform role and no static pair that is still current.
        Http.Response response = http.delete(clientsUrl + "/" + clientId,
                Map.of("Authorization", Http.basic(clientId, secret)));
        String outcome = response.ok() || response.status() == 404
                ? "handed back " + clientId
                : "the idp did not take " + clientId + " back: " + response.describe();
        deleted = true;
        clientId = null;
        secret = null;
        token = null;
        return outcome;
    }

    @Override
    public void close() {
        delete();
    }
}
