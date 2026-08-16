package eu.wohlben.qits.cli.bootstrap.ingress;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapIngressPolicyTest {

    private static final String PASSWORD = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
    private final BootstrapIngressPolicy policy = new BootstrapIngressPolicy("localhost", PASSWORD);

    @Test
    void admitsOnlyTheUiAndSmartHttpShapes() {
        assertThat(decide("GET", "/").target()).isEqualTo(BootstrapIngressPolicy.Target.UI);
        assertThat(decide("GET", "/events").target()).isEqualTo(BootstrapIngressPolicy.Target.UI);
        assertThat(decide("GET", "/git/qits-bootstrap/info/refs").target())
                .isEqualTo(BootstrapIngressPolicy.Target.GIT);
        assertThat(decide("POST", "/git/qits-bootstrap/git-receive-pack").target())
                .isEqualTo(BootstrapIngressPolicy.Target.GIT);
        assertThat(decide("POST", "/git/qits-bootstrap/git-upload-pack").target())
                .isEqualTo(BootstrapIngressPolicy.Target.GIT);
        assertThat(decide("GET", "/artifacts/maven/maven/eu/wohlben/qits/example/1/example.pom")
                .target()).isEqualTo(BootstrapIngressPolicy.Target.MAVEN);
    }

    @Test
    void rejectsWrongAndExpiredCapabilitiesAndEveryOtherDoor() {
        assertThat(policy.authorize("GET", "localhost", "/", basic("wrong")).status()).isEqualTo(401);
        assertThat(policy.authorize("GET", "wrong.example", "/", basic(PASSWORD)).status()).isEqualTo(421);
        assertThat(decide("GET", "/githost/api/repositories").status()).isEqualTo(403);
        assertThat(decide("POST", "/artifacts/maven/maven/x").status()).isEqualTo(403);
        assertThat(decide("CONNECT", "/").status()).isEqualTo(405);
        assertThat(decide("GET", "/git/../idp/token").status()).isEqualTo(403);
        assertThat(decide("GET", "/git/%2e%2e/idp/token").status()).isEqualTo(403);
        assertThat(decide("OPTIONS", "/git/qits-bootstrap/info/refs").status()).isEqualTo(403);
        policy.close();
        assertThat(policy.authorize("GET", "localhost", "/", basic(PASSWORD)).status()).isEqualTo(401);
    }

    @Test
    void publicProgressNeedsNoCredentialButHasExactlyThreeGetDoors() {
        assertThat(policy.authorizeProgress("GET", "/").target())
                .isEqualTo(BootstrapIngressPolicy.Target.UI);
        assertThat(policy.authorizeProgress("GET", "/state.json").target())
                .isEqualTo(BootstrapIngressPolicy.Target.UI);
        assertThat(policy.authorizeProgress("GET", "/events").target())
                .isEqualTo(BootstrapIngressPolicy.Target.UI);
        assertThat(policy.authorizeProgress("HEAD", "/").status()).isEqualTo(403);
        assertThat(policy.authorizeProgress("GET", "/idp/token").status()).isEqualTo(403);
        assertThat(policy.authorizeProgress("GET", "/git/qits-bootstrap/info/refs").status())
                .isEqualTo(403);
    }

    private BootstrapIngressPolicy.Decision decide(String method, String path) {
        return policy.authorize(method, "localhost:8481", path, basic(PASSWORD));
    }

    private static String basic(String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                ("bootstrap:" + password).getBytes(StandardCharsets.UTF_8));
    }
}
