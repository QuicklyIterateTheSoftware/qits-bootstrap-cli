package eu.wohlben.qits.cli.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The login defaults name SERVICE HOSTS, never the door.</b> The door redirects {@code /} to the
 * projects host and 404s every other path, so a default built on it would send a browser nowhere.
 */
class LoginCommandTest {

    @Test
    void theDefaultsAreTheIdpsAndTheGitHostsOwnNames() {
        // EVERY PUBLIC NAME CARRIES ITS PROJECT. Names are read right to left —
        // <app>.<env>.<project>.<domain> — and the project label is mandatory: this platform is
        // the project called `qits`, so both the short idp.<domain> and the project-less
        // idp.<env>.<domain> are names the edge does not serve.
        assertThat(LoginCommand.serviceHost("idp", "qits-dev.eu", "dev"))
                .isEqualTo("https://idp.dev.qits.qits-dev.eu");
        assertThat(LoginCommand.serviceHost("githost", "qits-dev.eu", "dev"))
                .isEqualTo("https://githost.dev.qits.qits-dev.eu");
    }

    /**
     * <b>A bootstrap without a domain can never pass any more (release 2026.1001.41945), so login
     * has no local arm left to fall back to.</b> An unset or blank {@code QITS_DOMAIN} is refused
     * before the browser opens, and the refusal names the knob.
     */
    @Test
    void aMissingDomainIsRefusedRatherThanFallingBackToLocalhost() {
        assertThat(LoginCommand.Settings.resolve(lookupOf(Map.of())).domain()).isBlank();
        assertThat(LoginCommand.Settings.resolve(lookupOf(Map.of("qits.domain", "  "))).domain()).isBlank();
        assertThat(LoginCommand.DOMAIN_REFUSAL).contains("QITS_DOMAIN").doesNotContain("localhost");
    }

    /**
     * <b>The environment name is in the domain-arm hostname now, so a guess is a wrong platform.</b>
     * It used not to be: {@code idp.<domain>} reached whichever tier the edge called default. A
     * guessed {@code prod} against a platform called something else does not fail at the resolver —
     * the zone's wildcards answer every shape — so it is refused before the browser opens.
     * <p>
     * There is no local arm with its own default any more either: {@code environmentName} answers
     * null for an unset or blank name whatever the domain is.
     */
    @Test
    void anUnconfiguredEnvironmentNameIsRefusedRatherThanGuessed() {
        assertThat(LoginCommand.environmentName(null)).isNull();
        assertThat(LoginCommand.environmentName("  ")).isNull();
        assertThat(LoginCommand.environmentName("dev")).isEqualTo("dev");
        assertThat(LoginCommand.environmentName(" dev ")).isEqualTo("dev");
        assertThat(LoginCommand.environmentRefusal("qits-dev.eu"))
                .contains("QITS_ENV_NAME")
                .contains("idp.<env>.qits.qits-dev.eu")
                .contains("--platform-env");
    }

    /**
     * <b>The seam reads through the supplied lookup, not {@code System.getenv}.</b> This is what
     * makes a {@code .env}-only {@code QITS_DOMAIN} work: Quarkus' MicroProfile Config sees that
     * file as a source and {@code System.getenv} does not, so the seam has to be fed through the
     * lookup function for that to matter at all.
     */
    @Test
    void theSeamResolvesFromTheSuppliedLookupNotFromTheRealEnvironment() {
        LoginCommand.Settings settings = LoginCommand.Settings.resolve(lookupOf(Map.of(
                "qits.domain", "qits-dev.eu", "qits.env-name", "dev")));
        assertThat(settings.domain()).isEqualTo("qits-dev.eu");
        assertThat(settings.environment()).isEqualTo("dev");

        String domain = settings.domain();
        String environment = LoginCommand.environmentName(settings.environment());
        assertThat(LoginCommand.serviceHost("idp", domain, environment))
                .isEqualTo("https://idp.dev.qits.qits-dev.eu");
    }

    @Test
    void idpAndGitHostOverridesComeThroughTheSameSeam() {
        LoginCommand.Settings settings = LoginCommand.Settings.resolve(lookupOf(Map.of(
                "qits.idp-url", " https://idp.example/idp ", "qits.git-host-url", "")));
        assertThat(settings.idpUrl()).contains("https://idp.example/idp");
        assertThat(settings.gitHostUrl()).isEmpty();
    }

    private static java.util.function.Function<String, Optional<String>> lookupOf(Map<String, String> values) {
        return name -> Optional.ofNullable(values.get(name));
    }
}
