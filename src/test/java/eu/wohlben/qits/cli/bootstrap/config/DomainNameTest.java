package eu.wohlben.qits.cli.bootstrap.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The domain is checked before the payload image is built, because the value leaves this machine: it
 * becomes a zone row in a public nameserver and a certificate request to Let's Encrypt. A rerun with
 * the spelling fixed undoes neither.
 */
class DomainNameTest {

    private static BootstrapConfig config(String domain) {
        return TestConfig.from(domain == null ? Map.of() : Map.of("QITS_DOMAIN", domain));
    }

    @Test
    void aDomainIsReadAndTheTwoNamesAreDerivedFromIt() {
        assertThat(DomainName.of(config("qits-dev.eu"))).contains("qits-dev.eu");
        // Both are conventions of this bootstrap: the dns service only knows what it is told.
        assertThat(DomainName.nsName("qits-dev.eu")).isEqualTo("ns1.qits-dev.eu");
        assertThat(DomainName.hostmaster("qits-dev.eu")).isEqualTo("hostmaster.qits-dev.eu");
    }

    @Test
    void unsetAndBlankAreBothNoDomain() {
        assertThat(DomainName.of(config(null))).isEmpty();
        assertThat(DomainName.of(config("   "))).isEmpty();
        // Surrounding whitespace in a .env line is not part of the name.
        assertThat(DomainName.of(config(" qits.eu "))).contains("qits.eu");
    }

    @Test
    void everyShapeThatIsNotADomainIsRefusedByName() {
        for (String bad : new String[]{
                "QITS.EU",          // uppercase: the zone fqdn is compared as text
                "qits.eu.",         // a trailing dot is a third spelling of one name
                "localhost",        // one label cannot be delegated
                "https://qits.eu",  // a URL, not a name
                "qits.eu/dns",      // a path
                "-qits.eu",         // a label may not start with a dash
                "qits..eu",         // an empty label
                "qits eu"}) {       // a space
            assertThatThrownBy(() -> DomainName.of(config(bad)))
                    .as("domain %s", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("QITS_DOMAIN")
                    .hasMessageContaining(bad.strip());
        }
    }

    @Test
    void aLabelLongerThanDnsAllowsIsRefused() {
        String tooLong = "a".repeat(64) + ".eu";

        assertThatThrownBy(() -> DomainName.of(config(tooLong)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
