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

        // EVERY PUBLIC NAME SPELLS ITS ENVIRONMENT. The short idp.<domain> form used to reach the
        // default tier by fallthrough; that reading is retired with the project tier, so the
        // domain arm carries the same environment label the local one always did.
        assertThat(LoginCommand.serviceHost("idp", "qits-dev.eu", "dev"))
                .isEqualTo("https://idp.dev.qits-dev.eu");
        assertThat(LoginCommand.serviceHost("githost", "qits-dev.eu", "dev"))
                .isEqualTo("https://githost.dev.qits-dev.eu");
    }

    /**
     * <b>The environment name is in the domain-arm hostname now, so a guess is a wrong platform.</b>
     * It used not to be: {@code idp.<domain>} reached whichever tier the edge called default. A
     * guessed {@code prod} against a platform called something else does not fail at the resolver —
     * the zone's wildcards answer every shape — so it is refused before the browser opens.
     */
    @Test
    void aDomainWithNoEnvironmentNameIsRefusedRatherThanGuessed() {
        assertThat(LoginCommand.environmentName("qits-dev.eu", null)).isNull();
        assertThat(LoginCommand.environmentName("qits-dev.eu", "  ")).isNull();
        assertThat(LoginCommand.environmentRefusal("qits-dev.eu"))
                .contains("QITS_ENV_NAME")
                .contains("idp.<env>.qits-dev.eu")
                .contains("--platform-env");
    }

    /**
     * The LOCAL arm keeps its {@code prod} default: that label has always been in
     * {@code idp.prod.localhost}, this change did not move it, and the platform it names is on the
     * caller's own machine.
     */
    @Test
    void theLocalArmStillDefaultsAndAConfiguredNameAlwaysWins() {
        assertThat(LoginCommand.environmentName("", null)).isEqualTo("prod");
        assertThat(LoginCommand.environmentName("", "dev")).isEqualTo("dev");
        assertThat(LoginCommand.environmentName("qits-dev.eu", " dev ")).isEqualTo("dev");
    }
}
