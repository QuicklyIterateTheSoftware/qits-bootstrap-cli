package eu.wohlben.qits.cli.bootstrap.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code QITS_ACME_EXTRA_SANS}, read and checked: <b>the names the edge's certificate must carry
 * beyond the shapes it derives for itself.</b>
 * <p>
 * <b>Why there is a knob at all, and it is smaller than it was.</b> The edge orders one certificate
 * over a wildcard set it works out for itself — the apex, {@code *.<domain>},
 * {@code *.<env>.<domain>} per environment, and {@code *.<project>.<domain>} plus
 * {@code *.<project>.<env>.<domain>} per project. A wildcard is leftmost-only, so it covers one
 * label and no more, and that set is exactly the depths the edge's Host reading has. A name at any
 * OTHER shape has to be a SAN of its own, and this is where it is written.
 * <p>
 * <b>It is deliberately not editor machinery.</b> This knob says "put these names on the
 * certificate" and knows nothing about projects, editors or workspaces. It never did — the editor
 * was its reason and is not any more.
 * <p>
 * <b>THE PER-PROJECT DEBT THIS KNOB CARRIED IS RETIRED.</b> It used to hold one name per project,
 * {@code editor.<project>.<domain>}, because the edge derived no wildcard that could reach a
 * project label and the two generated files are written before qits-projects has answered anything
 * — so a list read from the platform would have been empty on every cold boot and one boot stale on
 * every warm one. The cost was a platform where <b>a project created later was not on the
 * certificate until somebody added its name here and the edge re-ordered</b>, and until then its
 * editor host served TLS a browser refuses. That is over: the edge derives the per-project
 * wildcards LIVE from qits-projects' ProjectCreated events, so a project reaches the certificate at
 * the edge's next order — which its own creation event triggers. No bootstrap step, no restart, and
 * nothing to write here. The knob remains for names outside the derived shapes, and it is empty on
 * an ordinary platform.
 * <p>
 * <b>The 100-name cap is the live set's cost, and it belongs beside the knob.</b> A certificate
 * carries {@code 2 + E + P + P*E + extras} names — Let's Encrypt allows 100, and an order over the
 * cap fails whole. Every name written here spends one of those, which is the reason to keep this
 * list to names nothing derives.
 * <p>
 * <b>Names may be written whole or relative to the domain.</b> {@code status.support} and
 * {@code status.support.qits-dev.eu} are the same name when the domain is {@code qits-dev.eu}, and
 * the relative spelling is the one a person actually writes. <b>Every name ends up inside the
 * domain, and that is not a courtesy</b>: the edge answers its challenges by writing records in
 * this domain's own zone, so a name outside it is an order that cannot be answered — and one such
 * name fails the WHOLE order, taking the names that would have worked with it.
 * <p>
 * The cost of that rule is one mistake it cannot see: a name written whole for a DIFFERENT domain
 * ({@code status.support.example.com} on a platform serving {@code qits-dev.eu}) is read as a
 * relative one and becomes {@code status.support.example.com.qits-dev.eu}. Telling the two apart
 * needs a public suffix list, which is a second thing to keep in step for a typo. The closing
 * report prints the resolved names instead, so the mistake is on the screen rather than in a
 * certificate nobody reads.
 */
public final class ExtraSans {

    /** One DNS label: letters, digits and inner dashes, lowercase. */
    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

    private ExtraSans() {
    }

    /**
     * The configured extra names for this domain, checked, in the order they were written and with
     * duplicates dropped.
     *
     * @param domain the configured domain, or empty — with no domain there is no certificate, so
     *     the answer is empty whatever was configured
     */
    public static List<String> of(BootstrapConfig config, Optional<String> domain) {
        return domain.map(name -> of(config.acmeExtraSans().orElse(""), name))
                .orElseGet(List::of);
    }

    /**
     * The parse itself. Separated by commas, by whitespace or by both, because a value that has to
     * be written into a {@code .env} line and also onto a command line meets both habits.
     */
    public static List<String> of(String configured, String domain) {
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        String suffix = "." + domain;
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String written : configured.split("[,\\s]+")) {
            String value = written.strip().toLowerCase(Locale.ROOT);
            while (value.endsWith(".")) {
                value = value.substring(0, value.length() - 1);
            }
            if (value.isEmpty()) {
                continue;
            }
            names.add(value.endsWith(suffix) || value.equals(domain) ? value : value + suffix);
        }
        List<String> checked = new ArrayList<>(names);
        checked.forEach(name -> check(name, domain));
        return List.copyOf(checked);
    }

    /**
     * <b>Every name lives inside the domain and every label is a label.</b> A wildcard is refused
     * too: a wildcard is what the edge already derives, and one written by hand here would either
     * duplicate a name it has or ask for a depth its Host reading does not serve.
     */
    private static void check(String name, String domain) {
        String refusal = null;
        if (name.equals(domain)) {
            refusal = "it is the apex, which the edge already orders for itself";
        } else if (!name.endsWith("." + domain)) {
            refusal = "it is not inside " + domain;
        } else if (name.length() > 253) {
            refusal = "it is longer than 253 characters";
        } else {
            for (String label : name.split("\\.")) {
                if (!LABEL.matcher(label).matches()) {
                    refusal = "'" + label + "' is not a DNS label";
                    break;
                }
            }
        }
        if (refusal != null) {
            throw new IllegalArgumentException("QITS_ACME_EXTRA_SANS (--acme-extra-san) holds '"
                    + name + "', which cannot go on this platform's certificate: " + refusal
                    + ". Every extra name must be a LOWERCASE DNS name under " + domain
                    + " and may be written relative to it — `status.support` is `status.support."
                    + domain + "`. Wildcards are not accepted here: the edge derives its own — "
                    + domain + ", *." + domain + ", *.<env>." + domain + ", *.<project>." + domain
                    + " and *.<project>.<env>." + domain + " — from the environments it serves and "
                    + "from qits-projects' project events, so this knob is for names outside those "
                    + "shapes and nothing else. One bad name fails the whole order, which is why "
                    + "this is refused before the run rather than by Let's Encrypt during it.");
        }
    }
}
