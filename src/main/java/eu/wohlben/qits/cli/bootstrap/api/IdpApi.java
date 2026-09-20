package eu.wohlben.qits.cli.bootstrap.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * The issuer. The bootstrap presents the platform's own credentials because it IS the platform,
 * before there is anything to go through: the replayed build-succeeded event and the environment
 * reconcile are the calls that need a token. The release replays needed one too, until they became
 * a tag push — a push authenticates with the git host's own token, not with a machine one.
 * <p>
 * qits-platform-idp publishes no host port and has no unauthenticated public token route on purpose:
 * {@code /idp/token} exposed unauthenticated is a token vending machine. That is why it
 * used to be reached by a throwaway curl container on qits-net and is now dialled like everything
 * else — the exposure is unchanged, and the caller moved onto the network instead.
 */
public class IdpApi {

    private final Http http;
    private final String issuer;

    public IdpApi(Http http, String issuer) {
        this.http = http;
        this.issuer = issuer;
    }

    public Http.Response health() {
        return http.get(issuer + "/q/health/ready", Map.of());
    }

    public boolean ready() {
        return health().ok();
    }

    /**
     * A client-credentials token for one audience.
     *
     * @throws IllegalStateException when the idp refuses — a wrong or missing client secret is
     *                               {@code invalid_client}, and every hop that follows would 401
     *                               with nothing in its log to say why
     */
    public String token(String clientId, String secret, String audience) {
        return minted(clientId, secret, audience).value();
    }

    /**
     * <b>One minted token and the lifetime the idp gave it</b>, for the callers that hold a token
     * across a long phase and have to know when it stops being one.
     * <p>
     * The lifetime is READ rather than assumed. {@code qits.idp.token-ttl-seconds} is the idp's
     * setting and 3600 is today's value; a copy of that number here would be a deployment's change
     * turning into a 401 in the middle of an upload, with nothing to say why. What is hard-coded is
     * only the fallback for an answer that states none.
     */
    public record Token(String value, Duration lifetime) {

        /** How long a holder may use it before re-minting — {@link #MARGIN} short of its end. */
        public Instant expiryFrom(Instant minted) {
            return minted.plus(lifetime).minus(MARGIN);
        }
    }

    /**
     * How far before a token's end its holder re-mints. The estate's convention, and it is what
     * covers the flight time of the request the token is being minted for.
     */
    public static final Duration MARGIN = Duration.ofSeconds(60);

    /** What a token lasts when the idp's answer states no {@code expires_in}. */
    static final Duration DEFAULT_LIFETIME = Duration.ofHours(1);

    /** @see #token(String, String, String) */
    public Token minted(String clientId, String secret, String audience) {
        Http.Response response = http.postForm(issuer + "/token", clientId, secret,
                Map.of("grant_type", "client_credentials", "audience", audience));
        if (!response.ok()) {
            throw new IllegalStateException("the idp issued no token for " + clientId
                    + " (audience " + audience + "): " + response.describe());
        }
        JsonNode body = Json.parse(response.body());
        String token = Json.text(body, "access_token");
        if (token.isBlank()) {
            throw new IllegalStateException("the idp answered without an access_token: "
                    + response.describe());
        }
        return new Token(token, lifetime(Json.text(body, "expires_in")));
    }

    private static Duration lifetime(String expiresIn) {
        try {
            long seconds = Long.parseLong(expiresIn.trim());
            return seconds > 0 ? Duration.ofSeconds(seconds) : DEFAULT_LIFETIME;
        } catch (NumberFormatException stated) {
            return DEFAULT_LIFETIME;
        }
    }

    /**
     * Mints the ONE-TIME token the first account of this platform registers with.
     * <p>
     * Basic, with a STATIC client: a commissioned credential belongs to a context and may not make
     * a person, so the idp refuses one here. The answer carries the plaintext token under
     * {@code token} and carries it once — the idp keeps a fingerprint — so a token that is not read
     * out of this response is a row nobody can use.
     * <p>
     * A refusal is an ANSWER here, not an exception: the caller warns and the boot goes on. Nothing
     * else in the platform waits on a person registering.
     */
    public Http.Response mintRegisterToken(String clientId, String secret) {
        return http.postJson(issuer + "/api/register-tokens", "{}",
                Map.of("Authorization", Http.basic(clientId, secret)));
    }
}
