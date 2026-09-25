package eu.wohlben.qits.cli.bootstrap.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>The bootstrap's own publishing identity, and the three things about it that are not a remote
 * call.</b> What it ASKS the idp for — because {@code gitRefs: []} and no {@code gitRefs} at all
 * are one character apart on the wire and opposite in effect — that it re-mints rather than holding
 * a token past its end, and that handing it back happens exactly once however many times it is
 * asked for. The calls themselves are proved by a real bootstrap, like every other remote thing
 * here.
 */
class BootstrapPublishCredentialTest {

    private static final String ISSUER = "http://qits-idp:8080/idp";
    private static final String CLIENTS = ISSUER + "/api/clients";

    /**
     * <b>The commission, whole.</b> The kind is what gives the credential {@code qits:ci-run} in
     * the idp's own code map — publishing is CI's door — and the empty ref list is the other half
     * of the ruling: it may publish, and it may push nothing. Left out, the idp reads "no scope
     * stated", which is a credential that may push whatever its role allows.
     */
    @Test
    void theCommissionAsksForTheKindWhoseRoleIsCiRunAndForNoGitRefsAtAll() {
        FakeHttp http = new FakeHttp();

        credential(http).bearer();

        assertThat(http.urls).startsWith(CLIENTS);
        assertThat(http.bodies.getFirst())
                .contains("\"contextKind\":\"bootstrap-publish\"")
                .contains("\"contextId\":\"prod-qits-bootstrap\"")
                .contains("\"gitRefs\":[]");
        // A commissioned client may not commission another, so the caller is the static pair this
        // program owns — Basic, as the idp's own rule has it.
        assertThat(http.headers.getFirst()).containsEntry("Authorization", basic(
                "prod-qits-bootstrap", "boot-secret"));
    }

    /** One commission per run, however many publishes ask for a bearer. */
    @Test
    void theCredentialIsCommissionedOnceAndTheTokenIsHeldUntilItNearsItsEnd() {
        FakeHttp http = new FakeHttp();
        BootstrapPublishCredential credential = credential(http);

        assertThat(credential.bearer()).isEqualTo("minted-1");
        assertThat(credential.bearer()).isEqualTo("minted-1");

        assertThat(http.urls).containsExactly(CLIENTS, ISSUER + "/token");
        assertThat(credential.clientId()).isEqualTo("dyn-bootstrap-publish-prod-qits-boot-abc");
    }

    /**
     * <b>A token shorter than the margin is re-minted every time it is asked for.</b> The margin
     * covers the flight time of the request the token is being minted for; a token with less than
     * that left is one that expires mid-upload, which is a 401 in the middle of forty megabytes.
     */
    @Test
    void aTokenInsideTheMarginIsMintedAgain() {
        FakeHttp http = new FakeHttp();
        http.tokenLifetimeSeconds = 30;
        BootstrapPublishCredential credential = credential(http);

        assertThat(credential.bearer()).isEqualTo("minted-1");
        assertThat(credential.bearer()).isEqualTo("minted-2");

        assertThat(http.urls).containsExactly(CLIENTS, ISSUER + "/token", ISSUER + "/token");
    }

    /**
     * <b>Handed back as ITSELF, once.</b> The idp lets any credential give its own back with no
     * platform role at all, so the cleanup needs nothing the commission had; and it is called from
     * two places on purpose — the phase that ends the publishes, and the run's own finally — so a
     * second call must be silence rather than a 404 nobody can explain.
     */
    @Test
    void theCredentialIsHandedBackAsItselfAndOnlyOnce() {
        FakeHttp http = new FakeHttp();
        BootstrapPublishCredential credential = credential(http);
        credential.bearer();

        assertThat(credential.delete())
                .isEqualTo("handed back dyn-bootstrap-publish-prod-qits-boot-abc");
        assertThat(credential.delete()).isEmpty();
        credential.close();

        assertThat(http.urls).containsExactly(CLIENTS, ISSUER + "/token",
                CLIENTS + "/dyn-bootstrap-publish-prod-qits-boot-abc");
        assertThat(http.headers.getLast()).containsEntry("Authorization",
                basic("dyn-bootstrap-publish-prod-qits-boot-abc", "issued-secret"));
    }

    /**
     * <b>A publish that FAILED is exactly when a credential would be left standing</b>, so the
     * hand-back runs on that path too — and it is the same idempotent call, because on a run that
     * got there the normal way the phase has already made it.
     */
    @Test
    void aPublishThatThrewStillHandsTheCredentialBack() {
        FakeHttp http = new FakeHttp();
        BootstrapPublishCredential credential = credential(http);

        try (credential) {
            credential.bearer();
            throw new IllegalStateException("the publish failed");
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("the publish failed");
        }

        assertThat(http.urls).endsWith(CLIENTS + "/dyn-bootstrap-publish-prod-qits-boot-abc");
        // And nothing may publish afterwards: the identity is gone, and asking for a bearer says
        // so rather than quietly commissioning a second one.
        assertThatThrownBy(credential::bearer)
                .hasMessageContaining("handed back");
    }

    /** A run that never published leaves nothing at the idp — not even for a moment. */
    @Test
    void aRunThatCommissionedNothingSendsNoDelete() {
        FakeHttp http = new FakeHttp();

        assertThat(credential(http).delete()).isEmpty();

        assertThat(http.urls).isEmpty();
    }

    /** A refusal to commission stops the phase, with the idp's own words and no secret in them. */
    @Test
    void anIdpThatRefusesToCommissionStopsThePublish() {
        FakeHttp http = new FakeHttp();
        http.commission = new Http.Response(403, "a commissioned client may not commission another");

        assertThatThrownBy(() -> credential(http).bearer())
                .hasMessageContaining("403")
                .hasMessageContaining("prod-qits-bootstrap");
    }

    /**
     * <b>The hand-back never fails a run.</b> It is cleanup at the end of a boot, and the idp being
     * mid-cutover is not a reason to fail one — what the caller does with the message is print it.
     */
    @Test
    void anIdpThatWillNotTakeItBackIsSaidRatherThanThrown() {
        FakeHttp http = new FakeHttp();
        BootstrapPublishCredential credential = credential(http);
        credential.bearer();
        http.decommission = new Http.Response(0, "connection refused");

        assertThat(credential.delete()).contains("did not take").contains("no answer");
    }

    private static BootstrapPublishCredential credential(Http http) {
        return new BootstrapPublishCredential(http, new IdpApi(http, ISSUER), ISSUER,
                "prod-qits-bootstrap", "boot-secret", "prod-qits-bootstrap", "qits-platform");
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** The idp, as far as this class can see it: a commission, a token endpoint and a DELETE. */
    private static final class FakeHttp extends Http {

        private final List<String> urls = new ArrayList<>();
        private final List<String> bodies = new ArrayList<>();
        private final List<Map<String, String>> headers = new ArrayList<>();
        private int minted;
        private long tokenLifetimeSeconds = 3600;
        private Response commission = new Response(201,
                "{\"clientId\":\"dyn-bootstrap-publish-prod-qits-boot-abc\","
                        + "\"secret\":\"issued-secret\",\"gitRefs\":[]}");
        private Response decommission = new Response(204, "");

        @Override
        public Response postJson(String url, String json, Map<String, String> sent) {
            urls.add(url);
            bodies.add(json);
            headers.add(sent);
            return commission;
        }

        @Override
        public Response postForm(String url, String user, String password,
                                 Map<String, String> form) {
            urls.add(url);
            bodies.add(form.toString());
            headers.add(Map.of("Authorization", basic(user, password)));
            return new Response(200, "{\"access_token\":\"minted-" + (++minted)
                    + "\",\"expires_in\":" + tokenLifetimeSeconds + "}");
        }

        @Override
        public Response delete(String url, Map<String, String> sent) {
            urls.add(url);
            headers.add(sent);
            return decommission;
        }
    }
}
