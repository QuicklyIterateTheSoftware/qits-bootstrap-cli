package eu.wohlben.qits.cli.bootstrap.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Which answer means what</b>, which is the whole of what this class decides. The calls
 * themselves are proved by a real bootstrap like every other remote thing here; what a unit test
 * can pin is that a 409 is a conflict and not a failure, that a secret leaves by one field and
 * reaches no message, and that the pair is presented as Basic.
 */
class ServiceClientsApiTest {

    private static final String ISSUER = "http://qits-idp:8080/idp";

    /**
     * <b>409 is a step, not a stop.</b> A row for this client already exists — an earlier boot made
     * it, or the deployer did — and the caller's repair is to rotate. A bootstrap that failed here
     * would be a platform that can never be re-bootstrapped.
     */
    @Test
    void aConflictIsSaidAsOneRatherThanAsAFailure() {
        FakeHttp http = new FakeHttp(new Http.Response(409, "{\"error\":\"conflict\"}"));

        ServiceClientsApi.Result result = api(http).create("prod-qits-projects");

        assertThat(result.ok()).isFalse();
        assertThat(result.conflict()).isTrue();
        assertThat(result.secret()).isEmpty();
        assertThat(result.detail()).isNotBlank();
    }

    @Test
    void aCreateReadsTheSecretOutOfTheAnswerAndPutsItNowhereElse() {
        FakeHttp http = new FakeHttp(new Http.Response(201,
                "{\"clientId\":\"prod-qits-projects\",\"secret\":\"issued-by-the-idp\"}"));

        ServiceClientsApi.Result result = api(http).create("prod-qits-projects");

        assertThat(result.ok()).isTrue();
        assertThat(result.conflict()).isFalse();
        assertThat(result.secret()).isEqualTo("issued-by-the-idp");
        // THE ONE FIELD ANYTHING PRINTS, and the reason describe() is not used on a success: a
        // describe() of a 201 would put the issued secret on the screen in one token.
        assertThat(result.detail()).doesNotContain("issued-by-the-idp");
        assertThat(http.urls).containsExactly(ISSUER + "/api/service-clients");
    }

    @Test
    void aRotateAsksTheClientsOwnSecretRouteAndReadsTheSameField() {
        FakeHttp http = new FakeHttp(new Http.Response(200,
                "{\"clientId\":\"prod-qits-ci\",\"secret\":\"rotated\"}"));

        assertThat(api(http).rotate("prod-qits-ci").secret()).isEqualTo("rotated");
        assertThat(http.urls)
                .containsExactly(ISSUER + "/api/service-clients/prod-qits-ci/secret");
    }

    /**
     * A refusal that is not a conflict carries the idp's own words, because a phase that stops has
     * to say what stopped it. It is only ever read on a failure, where there is no secret in the
     * body to carry.
     */
    @Test
    void anythingElseIsAPlainFailureWithTheIdpsOwnWords() {
        ServiceClientsApi.Result result = api(new FakeHttp(new Http.Response(401, "unauthorized")))
                .create("prod-qits-ci");

        assertThat(result.ok()).isFalse();
        assertThat(result.conflict()).isFalse();
        assertThat(result.detail()).contains("401").contains("unauthorized");
    }

    /** A call that never got an answer is status 0, and it fails like any other refusal. */
    @Test
    void aCallThatNeverLandedIsAnAnswerRatherThanAnException() {
        ServiceClientsApi.Result result =
                api(new FakeHttp(new Http.Response(0, "connection refused")))
                        .create("prod-qits-ci");

        assertThat(result.ok()).isFalse();
        assertThat(result.conflict()).isFalse();
        assertThat(result.detail()).contains("no answer");
    }

    /** A 200 with no secret field is not a success — the caller would hand a container nothing. */
    @Test
    void anAnswerWithNoSecretIsNotASuccess() {
        assertThat(api(new FakeHttp(new Http.Response(201, "{\"clientId\":\"x\"}")))
                .create("prod-qits-ci").ok()).isFalse();
    }

    /**
     * The idp's own client-id shape, asked BEFORE the call: a name this program derived wrongly is
     * named by the phase that derived it rather than by a 400 three hops away.
     */
    @Test
    void anIdTheIdpWouldRefuseNeverReachesTheWire() {
        FakeHttp http = new FakeHttp(new Http.Response(201, "{\"secret\":\"s\"}"));

        for (String hostile : new String[]{"PROD-QITS-CI", "1-qits-ci", "prod_qits_ci",
                "prod qits ci", "", null}) {
            assertThat(api(http).create(hostile).ok()).isFalse();
            assertThat(api(http).rotate(hostile).ok()).isFalse();
        }
        assertThat(http.urls).isEmpty();
    }

    /**
     * <b>Basic, not a bearer</b>, and that is the idp's rule rather than a choice: a commissioned
     * credential may not make a client, so the caller has to be a static pair.
     */
    @Test
    void thePairIsPresentedAsBasic() {
        FakeHttp http = new FakeHttp(new Http.Response(201, "{\"secret\":\"s\"}"));

        api(http).create("prod-qits-ci");

        assertThat(http.headers.getFirst()).containsEntry("Authorization", "Basic "
                + Base64.getEncoder().encodeToString(
                        "prod-qits-bootstrap:boot".getBytes(StandardCharsets.UTF_8)));
    }

    /** {@code source} says where the idp knows a client from, and empty when it does not know it. */
    @Test
    void theSourceIsReadAndA404IsSimplyEmpty() {
        assertThat(api(new FakeHttp(new Http.Response(200, "{\"source\":\"database\"}")))
                .source("prod-qits-ci")).contains("database");
        assertThat(api(new FakeHttp(new Http.Response(404, "{}"))).source("prod-qits-ci"))
                .isEmpty();
    }

    private static ServiceClientsApi api(Http http) {
        return new ServiceClientsApi(http, ISSUER, "prod-qits-bootstrap", "boot");
    }

    /** One canned answer, and a record of what was asked for. */
    private static final class FakeHttp extends Http {

        private final Response answer;
        private final List<String> urls = new ArrayList<>();
        private final List<Map<String, String>> headers = new ArrayList<>();

        FakeHttp(Response answer) {
            this.answer = answer;
        }

        @Override
        public Response postJson(String url, String json, Map<String, String> sent) {
            urls.add(url);
            headers.add(sent);
            return answer;
        }

        @Override
        public Response get(String url, Map<String, String> sent) {
            urls.add(url);
            headers.add(sent);
            return answer;
        }
    }
}
