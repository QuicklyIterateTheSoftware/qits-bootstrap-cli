package eu.wohlben.qits.cli.bootstrap.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which Let's Encrypt directory a certificate is ordered from, and who the account belongs to. Both
 * are checked on the host half with the domain and the address, because both leave the machine.
 */
class AcmeTest {

    private static BootstrapConfig config(Map<String, String> env) {
        return TestConfig.from(env);
    }

    /**
     * <b>Staging is the default, and it is a decision rather than caution.</b> The order most likely
     * to fail is the first one — a delegation the world has not seen yet answers nothing, and the
     * HTTP-01 challenge is fetched over exactly that name — and production counts failed orders per
     * domain per week.
     */
    @Test
    void stagingIsTheDefaultDirectory() {
        assertThat(Acme.mode(config(Map.of()))).isEqualTo(Acme.Mode.STAGING);
        assertThat(Acme.Mode.STAGING.directory())
                .isEqualTo("https://acme-staging-v02.api.letsencrypt.org/directory");
    }

    @Test
    void productionAndOffAreTheOtherTwoWords() {
        assertThat(Acme.mode(config(Map.of("QITS_ACME_MODE", "production"))))
                .isEqualTo(Acme.Mode.PRODUCTION);
        assertThat(Acme.Mode.PRODUCTION.directory())
                .isEqualTo("https://acme-v02.api.letsencrypt.org/directory");
        assertThat(Acme.mode(config(Map.of("QITS_ACME_MODE", "off")))).isEqualTo(Acme.Mode.OFF);
        // Nothing to order, so nothing to order it from.
        assertThatThrownBy(Acme.Mode.OFF::directory).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void caseAndSurroundingSpaceAreNotPartOfTheWord() {
        assertThat(Acme.mode(config(Map.of("QITS_ACME_MODE", " Production "))))
                .isEqualTo(Acme.Mode.PRODUCTION);
    }

    /**
     * A typo is refused rather than defaulted. Falling back to staging would look exactly like a
     * good run and leave every browser refusing the site; falling back to production would spend a
     * real rate limit on a word nobody meant.
     */
    @Test
    void anyOtherWordIsRefusedAndTheMessageListsTheThree() {
        assertThatThrownBy(() -> Acme.mode(config(Map.of("QITS_ACME_MODE", "prod"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QITS_ACME_MODE")
                .hasMessageContaining("prod")
                .hasMessageContaining("staging")
                .hasMessageContaining("production")
                .hasMessageContaining("off");
    }

    /**
     * The contact is derived from the domain, so a platform that has a domain has a working contact
     * without a second knob filled in.
     */
    @Test
    void theContactDefaultsToTheHostmasterOfTheDomain() {
        assertThat(Acme.email(config(Map.of()), "qits-dev.eu")).isEqualTo("hostmaster@qits-dev.eu");
    }

    @Test
    void aConfiguredContactWinsAndABlankOneIsNotAnAnswer() {
        assertThat(Acme.email(config(Map.of("QITS_ACME_EMAIL", "ops@example.com")), "qits-dev.eu"))
                .isEqualTo("ops@example.com");
        assertThat(Acme.email(config(Map.of("QITS_ACME_EMAIL", "   ")), "qits-dev.eu"))
                .isEqualTo("hostmaster@qits-dev.eu");
    }

    /** Both knobs are answerable for one run, and a blank answer leaves {@code .env} alone. */
    @Test
    void bothAreAnsweredOnTheCommandLine() {
        BootstrapConfig base = config(Map.of("QITS_ACME_MODE", "off",
                "QITS_ACME_EMAIL", "from-env@example.com"));

        assertThat(Acme.mode(new OverridableConfig(base).acmeMode("production")))
                .isEqualTo(Acme.Mode.PRODUCTION);
        assertThat(Acme.mode(new OverridableConfig(base).acmeMode("  "))).isEqualTo(Acme.Mode.OFF);
        assertThat(Acme.email(new OverridableConfig(base).acmeEmail("argv@example.com"), "qits.eu"))
                .isEqualTo("argv@example.com");
        assertThat(Acme.email(new OverridableConfig(base).acmeEmail(null), "qits.eu"))
                .isEqualTo("from-env@example.com");
    }
}
