package eu.wohlben.qits.cli.bootstrap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The login defaults name SERVICE HOSTS, never the door.</b> The door redirects {@code /} to the
 * projects host and 404s every other path, so a default built on it would send a browser nowhere.
 */
class LoginCommandTest {

    @Test
    void theDefaultsAreTheIdpsAndTheGitHostsOwnNames() {
        assertThat(LoginCommand.serviceHost("idp", "", "dev"))
                .isEqualTo("http://idp.dev.localhost:8080");
        assertThat(LoginCommand.serviceHost("githost", "", "dev"))
                .isEqualTo("http://githost.dev.localhost:8080");

        // With a domain the environment label is dropped: it is optional for the default tier.
        assertThat(LoginCommand.serviceHost("idp", "qits-dev.eu", "dev"))
                .isEqualTo("https://idp.qits-dev.eu");
        assertThat(LoginCommand.serviceHost("githost", "qits-dev.eu", "dev"))
                .isEqualTo("https://githost.qits-dev.eu");
    }
}
