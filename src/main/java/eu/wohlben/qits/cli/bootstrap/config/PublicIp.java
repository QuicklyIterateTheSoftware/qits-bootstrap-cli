package eu.wohlben.qits.cli.bootstrap.config;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code QITS_PUBLIC_IP}, read and checked, and <b>mandatory whenever {@link DomainName} answers</b>.
 * <p>
 * <b>Why the bootstrap needs an address at all.</b> Every name this platform serves resolves to
 * this one host, and the A records that say so live at the domain's own dns provider — this platform
 * serves no dns. The run still needs the address: the closing report prints the records with it
 * filled in, and the certificate order is answered over a name that carries it. The value cannot be
 * derived — a container behind a NAT sees a private address, and asking a third party what it thinks
 * this host's address is would make the whole public-name path depend on a service nobody here owns.
 * It is an input, and the person who holds the domain already knows it.
 * <p>
 * <b>Checked here, beside the domain, for the same reason the domain is.</b> Both values leave this
 * machine — into the records a person types at a dns provider, and into a certificate request — so
 * the shape is settled on the host half, before the payload image is built, where a wrong value
 * costs one line rather than a four-hour boot.
 * <p>
 * <b>An IPv4 literal, and a name is refused rather than resolved.</b> The value becomes the data of
 * an A record, which holds four octets and nothing else, and a program that resolved a name here
 * would put whatever some other resolver believed today into a record that then answers for it.
 */
public final class PublicIp {

    /**
     * Four dotted octets, each one to three digits. The range and the leading-zero rule are checked
     * separately below, because a regex that said all of it would say it unreadably.
     */
    private static final Pattern SHAPE = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private PublicIp() {
    }

    /**
     * The configured address, checked, or empty when there is no domain and no address.
     * <p>
     * The pairing is the whole point of this method, so it reads the domain too: with a domain and
     * no address the run is refused, and with an address and no domain it is refused as well.
     */
    public static Optional<String> of(BootstrapConfig config) {
        Optional<String> address = config.publicIp().map(String::strip).filter(v -> !v.isEmpty());
        Optional<String> domain = DomainName.of(config);
        if (domain.isEmpty()) {
            address.ifPresent(PublicIp::refuseWithoutDomain);
            return Optional.empty();
        }
        return Optional.of(checked(address.orElseThrow(() -> missing(domain.get()))));
    }

    /**
     * <b>Refused rather than ignored, which is what every other check on this half does.</b> There
     * is no ignore-with-a-note anywhere in this configuration surface: a misspelled domain stops the
     * run and a re-bootstrap under a second environment name stops it too, both because the person
     * believed something about the run that is not true and a note in the scrollback of a four-hour
     * boot is not read. An address with no domain is exactly that belief — this platform will serve
     * public names — and this run will serve none. One line fixes it, in whichever direction the
     * person meant.
     */
    private static void refuseWithoutDomain(String value) {
        throw new IllegalArgumentException("QITS_PUBLIC_IP (--public-ip) is '" + value + "', but "
                + "QITS_DOMAIN (--domain) is unset, so this run has no public name to serve and no "
                + "certificate to issue. The address is only ever the data of this platform's own A "
                + "records. Set the domain as well, or drop the address: a platform with no domain "
                + "is a supported platform — the edge stays on plain HTTP.");
    }

    private static IllegalArgumentException missing(String domain) {
        return new IllegalArgumentException("QITS_DOMAIN (--domain) is '" + domain + "' but "
                + "QITS_PUBLIC_IP (--public-ip) is unset, and a domain without an address is a name "
                + "nobody can reach. Every name under " + domain + " — the apex included — has to "
                + "resolve to THIS host, and the A records that say so live at your dns provider: "
                + "this platform serves no dns. The run needs the address anyway, to print those "
                + "records and to order the certificate. Set QITS_PUBLIC_IP to it, as four dotted "
                + "octets — 203.0.113.7. It cannot be derived: this run is a container behind a "
                + "NAT.");
    }

    static String checked(String value) {
        if (!SHAPE.matcher(value).matches() || !octetsAreInRange(value)) {
            throw new IllegalArgumentException("QITS_PUBLIC_IP (--public-ip) is '" + value + "', "
                    + "which is not an IPv4 address. It must be four dotted octets of 0 to 255 with "
                    + "no leading zeros — 203.0.113.7. A HOSTNAME is refused rather than resolved: "
                    + "this value becomes the data of A records that a resolver on the internet then "
                    + "reads back, and a name looked up here would freeze whatever some other "
                    + "resolver happened to answer today into the record that is supposed to be the "
                    + "authority for it. It is also the address the glue record at your registrar "
                    + "carries, so the two are one value on purpose.");
        }
        return value;
    }

    /**
     * The range, plus the leading-zero rule. {@code 010.1.1.1} is refused rather than read as ten:
     * a leading zero is octal to some resolvers and decimal to others, so the one thing certain
     * about that spelling is that two readers disagree about which address it is.
     */
    private static boolean octetsAreInRange(String value) {
        for (String octet : value.split("\\.")) {
            if (octet.length() > 1 && octet.charAt(0) == '0') {
                return false;
            }
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }
}
