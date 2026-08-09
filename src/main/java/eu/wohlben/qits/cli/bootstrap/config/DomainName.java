package eu.wohlben.qits.cli.bootstrap.config;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code QITS_DOMAIN}, read and checked, plus the two names derived from it.
 * <p>
 * <b>The check is here rather than in a phase because the value leaves this machine.</b> A typo
 * becomes a zone row in the dns service and a certificate request to Let's Encrypt for a name
 * nobody owns, and neither is undone by rerunning the boot with the spelling fixed. So the shape is
 * settled once, before the payload image is built, and the message names the knob.
 * <p>
 * <b>Both derived names are conventions of this bootstrap and not of the dns service</b>, which
 * knows only what it is told: {@code ns1.<domain>} is the nameserver hostname a registrar's NS
 * record points at, and {@code hostmaster.<domain>} is the SOA's rname. They travel together —
 * the dns service turns SOA/NS synthesis OFF unless it has both — which is why one method here
 * answers for the pair.
 */
public final class DomainName {

    /**
     * Lowercase labels of letters, digits and inner dashes, at least two of them, no trailing dot.
     * <p>
     * Lowercase only, although DNS is case-insensitive on the wire: the zone fqdn is stored and
     * compared as text by the dns service, and {@code QITS.EU} and {@code qits.eu} would be two
     * zones that answer for one name. A trailing dot is rejected for the same reason rather than
     * stripped — the value is also spelled into a certificate request and a registrar's NS record,
     * and a program that quietly rewrote it would leave three places disagreeing about which of
     * them is right.
     */
    private static final Pattern SHAPE = Pattern.compile(
            "[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+");

    private DomainName() {
    }

    /** The configured domain, checked, or empty when there is none. */
    public static Optional<String> of(BootstrapConfig config) {
        return config.domain().map(String::strip).filter(value -> !value.isEmpty())
                .map(DomainName::checked);
    }

    /** The nameserver hostname a registrar delegates to, and the SOA's mname. */
    public static String nsName(String domain) {
        return "ns1." + domain;
    }

    /** The SOA's rname, as a hostname. */
    public static String hostmaster(String domain) {
        return "hostmaster." + domain;
    }

    static String checked(String value) {
        if (!SHAPE.matcher(value).matches() || value.length() > 253
                || Arrays.stream(value.split("\\.")).anyMatch(label -> label.length() > 63)) {
            throw new IllegalArgumentException("QITS_DOMAIN (--domain) is '" + value + "', which is "
                    + "not a domain this platform can serve. It must be a LOWERCASE DNS name of at "
                    + "least two labels, with no trailing dot and no scheme or path — qits.eu, "
                    + "qits-dev.eu. It becomes the zone row in qits-platform-dns, the nameserver "
                    + "name a registrar delegates to (ns1." + value + "), the SOA's hostmaster "
                    + "address and the name the edge's certificate is issued for, so it is checked "
                    + "here rather than in four places later. Leave it unset to run without a "
                    + "domain: dns then serves no zones and the edge stays on plain HTTP.");
        }
        return value;
    }
}
