package eu.wohlben.qits.cli.bootstrap.config;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code QITS_DOMAIN}, read and checked.
 * <p>
 * <b>The check is here rather than in a phase because the value leaves this machine.</b> A typo
 * becomes a certificate request to Let's Encrypt for a name nobody owns, which rerunning the boot
 * with the spelling fixed does not undo. So the shape is settled once, before the payload image is
 * built, and the message names the knob.
 */
public final class DomainName {

    /**
     * Lowercase labels of letters, digits and inner dashes, at least two of them, no trailing dot.
     * <p>
     * Lowercase only, although DNS is case-insensitive on the wire: the value is compared as text
     * everywhere it is used, so {@code QITS.EU} and {@code qits.eu} would be two spellings of one
     * name. A trailing dot is rejected for the same reason rather than stripped — the value is also
     * spelled into a certificate request and into the records a person creates at the dns provider,
     * and a program that quietly rewrote it would leave those places disagreeing about which of
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

    static String checked(String value) {
        if (!SHAPE.matcher(value).matches() || value.length() > 253
                || Arrays.stream(value.split("\\.")).anyMatch(label -> label.length() > 63)) {
            throw new IllegalArgumentException("QITS_DOMAIN (--domain) is '" + value + "', which is "
                    + "not a domain this platform can serve. It must be a LOWERCASE DNS name of at "
                    + "least two labels, with no trailing dot and no scheme or path — qits.eu, "
                    + "qits-dev.eu. It becomes the name the edge's certificate is issued for and "
                    + "the name every record at your dns provider hangs under, so it is checked "
                    + "here rather than later. Leave it unset to run without a domain: the edge "
                    + "then stays on plain HTTP.");
        }
        return value;
    }
}
