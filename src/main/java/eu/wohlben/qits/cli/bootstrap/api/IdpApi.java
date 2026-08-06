package eu.wohlben.qits.cli.bootstrap.api;

import java.util.Map;

/**
 * The issuer. The bootstrap presents the platform's own credentials because it IS the platform,
 * before there is anything to go through: the replayed build-succeeded event and the manual
 * release trigger are the two calls that need a token.
 */
public class IdpApi {

    private final InNetworkHttp http;
    private final String issuer;

    public IdpApi(InNetworkHttp http, String issuer) {
        this.http = http;
        this.issuer = issuer;
    }

    public Http.Response health() {
        return http.get(issuer + "/q/health/ready");
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
        Http.Response response = http.postForm(issuer + "/token", clientId, secret,
                Map.of("grant_type", "client_credentials", "audience", audience));
        if (!response.ok()) {
            throw new IllegalStateException("qits-idp issued no token for " + clientId
                    + " (audience " + audience + "): " + response.describe());
        }
        String token = Json.text(Json.parse(response.body()), "access_token");
        if (token.isBlank()) {
            throw new IllegalStateException("qits-idp answered without an access_token: "
                    + response.describe());
        }
        return token;
    }
}
