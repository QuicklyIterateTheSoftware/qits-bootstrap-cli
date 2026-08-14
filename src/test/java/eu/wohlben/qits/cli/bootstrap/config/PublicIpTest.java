package eu.wohlben.qits.cli.bootstrap.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The address is checked beside the domain and before the payload image is built, because it leaves
 * this machine: it becomes the data of every A record in a zone the internet reads back, and the
 * glue record at a registrar carries the same value. The two travel together, which is what most of
 * these tests are about.
 */
class PublicIpTest {

    private static BootstrapConfig config(String domain, String ip) {
        Map<String, String> env = new LinkedHashMap<>();
        if (domain != null) {
            env.put("QITS_DOMAIN", domain);
        }
        if (ip != null) {
            env.put("QITS_PUBLIC_IP", ip);
        }
        return TestConfig.from(env);
    }

    @Test
    void aDomainAndAnAddressAreTheOrdinaryPair() {
        assertThat(PublicIp.of(config("qits-dev.eu", "203.0.113.7"))).contains("203.0.113.7");
        // Surrounding whitespace in a .env line is not part of the address.
        assertThat(PublicIp.of(config("qits-dev.eu", "  203.0.113.7 "))).contains("203.0.113.7");
    }

    /** Neither knob set is the default platform: no zone, no records, nothing to address. */
    @Test
    void neitherKnobSetIsNoAddress() {
        assertThat(PublicIp.of(config(null, null))).isEmpty();
        assertThat(PublicIp.of(config(null, "   "))).isEmpty();
    }

    /**
     * <b>The rule the whole change hangs off.</b> Once the domain is delegated, this platform's
     * nameserver is asked for every name under it — the apex included — and a registrar cannot hold
     * an A record on its behalf. A zone with no address is a domain that resolves to nothing, so the
     * run is refused before anything is built rather than four hours in.
     */
    @Test
    void aDomainWithNoAddressIsRefusedAndTheMessageNamesBoth() {
        assertThatThrownBy(() -> PublicIp.of(config("qits-dev.eu", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QITS_PUBLIC_IP")
                .hasMessageContaining("QITS_DOMAIN")
                .hasMessageContaining("qits-dev.eu")
                // The nameserver the glue record names, so the reader knows which address is wanted.
                .hasMessageContaining("ns1.qits-dev.eu");
        // A blank value is not an answer either — a .env line left with nothing after the '='.
        assertThatThrownBy(() -> PublicIp.of(config("qits-dev.eu", "   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QITS_PUBLIC_IP");
    }

    /**
     * An address with no domain is refused rather than ignored, which is what every other check on
     * this half does. It says the person believes this run will serve public names; it will not.
     */
    @Test
    void anAddressWithNoDomainIsRefusedRatherThanIgnored() {
        assertThatThrownBy(() -> PublicIp.of(config(null, "203.0.113.7")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QITS_PUBLIC_IP")
                .hasMessageContaining("QITS_DOMAIN")
                .hasMessageContaining("203.0.113.7");
    }

    @Test
    void everyShapeThatIsNotAnIpv4AddressIsRefusedByName() {
        for (String bad : new String[]{
                "ns1.qits-dev.eu",   // a hostname: refused rather than resolved
                "qits-dev.eu",       // the domain itself
                "203.0.113",         // three octets
                "203.0.113.7.8",     // five
                "256.0.113.7",       // out of range
                "203.0.113.999",     // far out of range
                "203.0.113.007",     // a leading zero is octal to some readers and decimal to others
                "010.0.113.7",       // the same, in the first octet
                "2001:db8::1",       // IPv6: the record this writes is an A record
                "203.0.113.7/32",    // a prefix, not an address
                "203.0.113.7:8080",  // an address and a port
                "203 0 113 7"}) {    // spaces
            assertThatThrownBy(() -> PublicIp.of(config("qits-dev.eu", bad)))
                    .as("address %s", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("QITS_PUBLIC_IP")
                    .hasMessageContaining(bad.strip());
        }
    }

    /** The edges of the range are addresses, and are taken. */
    @Test
    void theEndsOfEachOctetAreStillAddresses() {
        assertThat(PublicIp.of(config("qits-dev.eu", "0.0.0.0"))).contains("0.0.0.0");
        assertThat(PublicIp.of(config("qits-dev.eu", "255.255.255.255")))
                .contains("255.255.255.255");
    }

    /** {@code --public-ip} beats {@code .env}, and a blank one is no answer at all. */
    @Test
    void theAddressIsAnsweredOnTheCommandLineAndABlankOneIsNotAnAnswer() {
        BootstrapConfig base = config("qits-dev.eu", "203.0.113.7");

        assertThat(new OverridableConfig(base).publicIp("198.51.100.9").publicIp())
                .contains("198.51.100.9");
        assertThat(new OverridableConfig(base).publicIp(null).publicIp()).contains("203.0.113.7");
        assertThat(new OverridableConfig(base).publicIp("  ").publicIp()).contains("203.0.113.7");
        // Unset stays unset: there is no default address to fall back on.
        assertThat(new OverridableConfig(config(null, null)).publicIp(null).publicIp()).isEmpty();
    }
}
