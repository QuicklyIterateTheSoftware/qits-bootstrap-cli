package eu.wohlben.qits.cli.bootstrap.api;

import eu.wohlben.qits.cli.bootstrap.platform.PlatformModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The resolver every bearer call is dialled through: the alias during a cold boot, the public name
 * once the edge is ready, and never back.
 */
class EdgeAddressesTest {

    private static final String CI = "http://prod-qits-ci:8080/ci";

    private final EdgeAddresses addresses = new EdgeAddresses(() -> Optional.of("qits-dev.eu"));

    @Test
    void beforeTheEdgeIsReadyEveryServiceIsDialledAtItsAlias() {
        assertThat(addresses.edgeReady()).isFalse();
        assertThat(addresses.base("ci", CI)).isEqualTo(CI);
        assertThat(addresses.throughEdge("ci")).isFalse();
    }

    @Test
    void onceTheEdgeIsReadyAProvedServiceIsDialledAtItsPublicNameWithTheSamePath() {
        addresses.ready(List.of("ci", "projects", "idp", "registry"));

        assertThat(addresses.base("ci", CI)).isEqualTo("https://ci.qits.qits-dev.eu/ci");
        assertThat(addresses.base("projects", "http://prod-qits-projects:8080"))
                .isEqualTo("https://projects.qits.qits-dev.eu");
        assertThat(addresses.base("idp", "http://prod-qits-idp:8080/idp"))
                .isEqualTo("https://idp.qits.qits-dev.eu/idp");
        assertThat(addresses.base("registry", "http://prod-qits-artifacts:8080/artifacts"))
                .isEqualTo("https://registry.qits.qits-dev.eu/artifacts");
    }

    /** A name the edge was not seen to serve stays on its alias: a guess would be a 404 or a 503. */
    @Test
    void aServiceTheEdgeDidNotProveStaysOnItsAliasAfterTheLatch() {
        addresses.ready(List.of("ci"));

        assertThat(addresses.edgeReady()).isTrue();
        assertThat(addresses.base("deployments", "http://prod-qits-deployments:8080/deployments"))
                .isEqualTo("http://prod-qits-deployments:8080/deployments");
    }

    /** The latch only widens: a later, narrower pass takes nothing back, and so does nothing else. */
    @Test
    void theLatchNeverGoesBackToTheAlias() {
        addresses.ready(List.of("ci", "projects"));
        addresses.ready(List.of("idp"));
        addresses.ready(List.of());

        assertThat(addresses.base("ci", CI)).isEqualTo("https://ci.qits.qits-dev.eu/ci");
        assertThat(addresses.base("projects", "http://prod-qits-projects:8080"))
                .startsWith("https://projects.");
        assertThat(addresses.throughEdge("idp")).isTrue();
    }

    /** A supplier asks per call, so a client built before the latch follows it on its next call. */
    @Test
    void aRouteBuiltBeforeTheLatchFollowsItOnTheNextCall() {
        Supplier<String> route = addresses.route("ci", CI);
        assertThat(route.get()).isEqualTo(CI);

        addresses.ready(List.of("ci"));

        assertThat(route.get()).isEqualTo("https://ci.qits.qits-dev.eu/ci");
    }

    /** The domain is read when the latch is set, so a run with none is refused there, by name. */
    @Test
    void withoutADomainTheLatchIsRefusedNamingTheSetting() {
        EdgeAddresses none = new EdgeAddresses(Optional::empty);

        assertThat(none.base("ci", CI)).isEqualTo(CI);
        assertThatThrownBy(() -> none.ready(List.of("ci")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QITS_DOMAIN");
        assertThat(none.edgeReady()).isFalse();
        assertThat(new EdgeAddresses(() -> Optional.of("  ")).base("ci", CI)).isEqualTo(CI);
        assertThatThrownBy(() -> new EdgeAddresses(() -> Optional.of("  ")).ready(List.of("ci")))
                .hasMessage(EdgeAddresses.missingDomain());
    }

    @Test
    void thePathOfAnAliasIsCarriedOverWithoutATrailingSlash() {
        assertThat(EdgeAddresses.path("http://a:8080")).isEmpty();
        assertThat(EdgeAddresses.path("http://a:8080/")).isEmpty();
        assertThat(EdgeAddresses.path("http://a:8080/ci/")).isEqualTo("/ci");
        assertThat(EdgeAddresses.path("http://a:8080/deployments")).isEqualTo("/deployments");
    }

    /** The project label is spelled here for the api package; it must be the model's. */
    @Test
    void theProjectLabelIsThePlatformModels() {
        assertThat(EdgeAddresses.PROJECT).isEqualTo(PlatformModel.PROJECT);
        assertThat(EdgeAddresses.host("registry", "qits-dev.eu"))
                .isEqualTo("registry.qits.qits-dev.eu");
    }

    /** A routed client really dials the moved name: the supplier is asked per request. */
    @Test
    void aRoutedClientDialsThePublicNameAfterTheLatch() {
        RecordingHttp http = new RecordingHttp();
        CiApi ci = new CiApi(http, addresses.route("ci", CI), () -> "t");

        ci.health();
        addresses.ready(List.of("ci"));
        ci.health();

        assertThat(http.urls).containsExactly(CI + "/q/health/ready",
                "https://ci.qits.qits-dev.eu/ci/q/health/ready");
        assertThat(http.sent).allSatisfy(headers ->
                assertThat(headers).containsExactly(Map.entry("Authorization", "Bearer t")));
    }

    private static final class RecordingHttp extends Http {
        final List<String> urls = new java.util.ArrayList<>();
        final List<Map<String, String>> sent = new java.util.ArrayList<>();

        @Override
        public Response get(String url, Map<String, String> headers) {
            urls.add(url);
            sent.add(headers);
            return new Response(200, "");
        }
    }
}
