package eu.wohlben.qits.cli.bootstrap.ingress;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ingress serves the progress page and nothing else: no maven route (a seed build reads the
 * {@code qits-maven-seed} volume as a named context) and no git route (every push dials the git
 * host's wire alias from inside qits-net).
 */
class BootstrapIngressPolicyTest {

    private final BootstrapIngressPolicy policy = new BootstrapIngressPolicy();

    @Test
    void publicProgressNeedsNoCredentialButHasExactlyThreeGetDoors() {
        assertThat(policy.authorizeProgress("GET", "/").target())
                .isEqualTo(BootstrapIngressPolicy.Target.UI);
        assertThat(policy.authorizeProgress("GET", "/state.json").target())
                .isEqualTo(BootstrapIngressPolicy.Target.UI);
        assertThat(policy.authorizeProgress("GET", "/events").target())
                .isEqualTo(BootstrapIngressPolicy.Target.UI);
        assertThat(policy.authorizeProgress("HEAD", "/").status()).isEqualTo(403);
        assertThat(policy.authorizeProgress("POST", "/").status()).isEqualTo(403);
        assertThat(policy.authorizeProgress("GET", "/idp/token").status()).isEqualTo(403);
    }

    /** The two retired routes are gone, not merely gated: no credential opens them. */
    @Test
    void theMavenAndGitRoutesAreGone() {
        assertThat(policy.authorizeProgress("GET",
                "/artifacts/maven/maven/eu/wohlben/qits/example/1/example.pom").status())
                .isEqualTo(403);
        assertThat(policy.authorizeProgress("GET", "/git/qits-bootstrap/info/refs").status())
                .isEqualTo(403);
        assertThat(policy.authorizeProgress("POST", "/git/qits-bootstrap/git-receive-pack")
                .status()).isEqualTo(403);
        assertThat(policy.allowedRoute("GET", "/artifacts/maven/maven")).isFalse();
        assertThat(policy.allowedRoute("GET", "/git/qits-bootstrap/info/refs")).isFalse();
    }

    @Test
    void rejectsEveryOtherDoor() {
        assertThat(policy.authorizeProgress("GET", "/githost/api/repositories").status())
                .isEqualTo(403);
        assertThat(policy.authorizeProgress("CONNECT", "/").status()).isEqualTo(405);
        assertThat(policy.authorizeProgress("GET", "/../idp/token").status()).isEqualTo(403);
        assertThat(policy.authorizeProgress("GET", "/%2e%2e/idp/token").status()).isEqualTo(403);
        assertThat(policy.authorizeProgress("GET", "//state.json").status()).isEqualTo(403);
    }

    @Test
    void theClearTextListenerRedirectsOnlyTheThreeDoors() {
        assertThat(policy.allowedRoute("GET", "/")).isTrue();
        assertThat(policy.allowedRoute("GET", "/state.json")).isTrue();
        assertThat(policy.allowedRoute("GET", "/events")).isTrue();
        assertThat(policy.allowedRoute("GET", "/idp/token")).isFalse();
        assertThat(policy.allowedRoute("POST", "/")).isFalse();
    }
}
