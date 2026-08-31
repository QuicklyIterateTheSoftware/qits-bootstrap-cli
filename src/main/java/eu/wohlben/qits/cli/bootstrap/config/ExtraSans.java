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
 * <b>Why there is a knob at all.</b> The edge orders one certificate over a finite wildcard set —
 * the apex, {@code *.<domain>}, and {@code *.<env>.<domain>} per environment — because those are
 * the two depths its Host reading has. A wildcard is leftmost-only, so it covers one label and no
 * more: {@code *.<domain>} answers for {@code editor.<domain>} and for nothing under
 * {@code editor.<project>.<domain>}. The web editor is served at exactly that depth, one origin per
 * PROJECT, and a project label is not an environment label — so no wildcard this platform orders
 * can ever reach it. Such a name has to be a SAN of its own.
 * <p>
 * <b>It is deliberately not editor machinery.</b> This knob says "put these names on the
 * certificate" and knows nothing about projects, editors or workspaces. The editor is today's
 * reason and will not be the last one: any name at depth three under a label the edge does not
 * route by is the same problem with a different word in front of it.
 * <p>
 * <b>The source of the list is CONFIGURATION, not a live read, and that is a choice.</b> The two
 * generated files are written before qits-projects has answered anything — the seed stack is what
 * starts it — so a list derived from the platform's own projects would be empty on every cold boot
 * and one boot stale on every warm one. Worse, it would make the rendered extras depend on data
 * that changes without a bootstrap, and the extras are compared for change. So the names are
 * written down, the run hands them to the edge, and the closing report says which projects the
 * certificate does and does not cover. <b>A project created later is not on the certificate until
 * somebody adds its name here and the edge re-orders</b>; until then its editor host serves TLS a
 * browser refuses. That is stated in the report rather than left to be discovered.
 * <p>
 * <b>Names may be written whole or relative to the domain.</b> {@code editor.acme} and
 * {@code editor.acme.qits-dev.eu} are the same name when the domain is {@code qits-dev.eu}, and the
 * relative spelling is what a person writing one per project actually wants. <b>Every name ends up
 * inside the domain, and that is not a courtesy</b>: the edge answers its challenges by writing
 * records in this domain's own zone, so a name outside it is an order that cannot be answered — and
 * one such name fails the WHOLE order, taking the names that would have worked with it.
 * <p>
 * The cost of that rule is one mistake it cannot see: a name written whole for a DIFFERENT domain
 * ({@code editor.acme.example.com} on a platform serving {@code qits-dev.eu}) is read as a relative
 * one and becomes {@code editor.acme.example.com.qits-dev.eu}. Telling the two apart needs a public
 * suffix list, which is a second thing to keep in step for a typo. The closing report prints the
 * resolved names instead, so the mistake is on the screen rather than in a certificate nobody
 * reads.
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
                    + " and may be written relative to it — `editor.acme` is `editor.acme."
                    + domain + "`. Wildcards are not accepted here: the edge already orders "
                    + domain + ", *." + domain + " and *.<env>." + domain + " for itself, and a "
                    + "name at any other depth needs spelling out. One bad name fails the whole "
                    + "order, which is why this is refused before the run rather than by Let's "
                    + "Encrypt during it.");
        }
    }

    /**
     * The editor origin of one project, which is the shape this knob mostly holds.
     * <p>
     * Here rather than in a phase because the closing report and any future filler of the knob want
     * the same one spelling, and a second copy is a report that says a name the certificate does
     * not carry.
     */
    public static String editorHost(String projectSlug, String domain) {
        return "editor." + projectSlug + "." + domain;
    }
}
