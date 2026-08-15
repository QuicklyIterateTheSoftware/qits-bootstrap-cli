package eu.wohlben.qits.cli.bootstrap.config;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * {@code QITS_ACME_MODE} and {@code QITS_ACME_EMAIL}, read and checked: <b>which Let's Encrypt
 * directory this run orders the edge's certificate from, and who the account belongs to.</b>
 * <p>
 * The whole reason there is a mode at all is that the two directories fail differently. Production
 * counts failed orders per registered domain per week and locks the domain out when the count is
 * reached; staging counts almost nothing. The order most likely to fail on a first boot is the
 * first one — the domain's records have to have propagated before an HTTP-01 challenge
 * can be fetched over the delegated name — so the default is the directory a failure is free in.
 * <p>
 * <b>Both values are checked on the host half, beside the domain and the address</b>, and for the
 * same reason: they leave the machine. The mode picks the server an account is created on and the
 * email is registered with it.
 */
public final class Acme {

    /** Where a certificate comes from, and whether one is ordered at all. */
    public enum Mode {

        /**
         * The testing directory: generous limits, and an untrusted root, so a browser still refuses
         * what it issues. It proves the records, the challenge and the reload for free.
         */
        STAGING("https://acme-staging-v02.api.letsencrypt.org/directory"),

        /** The real one, and the one with the weekly failure limit. */
        PRODUCTION("https://acme-v02.api.letsencrypt.org/directory"),

        /**
         * Order nothing. The edge keeps the self-signed placeholder the {@code edge-cert} phase
         * wrote, which is what this program did before issuance was part of it, and the closing
         * report prints the command to do it by hand.
         */
        OFF(null);

        private final String directory;

        Mode(String directory) {
            this.directory = directory;
        }

        /** The ACME directory URL, and never called on {@link #OFF}. */
        public String directory() {
            if (directory == null) {
                throw new IllegalStateException("QITS_ACME_MODE is off: there is no directory");
            }
            return directory;
        }

        /** The word a person writes, and the word the report prints back. */
        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private Acme() {
    }

    /**
     * The configured mode. Refused rather than defaulted on an unknown word: a typo that fell back
     * to staging would look exactly like a successful run and leave a browser refusing the site,
     * and a typo that fell back to production would spend a real rate limit.
     */
    public static Mode mode(BootstrapConfig config) {
        String value = config.acmeMode().strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(Mode.values()).filter(mode -> mode.word().equals(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("QITS_ACME_MODE (--acme-mode) is '"
                        + config.acmeMode() + "', which is not one of "
                        + Arrays.stream(Mode.values()).map(Mode::word)
                                .collect(Collectors.joining(", "))
                        + ". staging is the default and issues from an untrusted root — it proves "
                        + "the records and the challenge without spending production's weekly "
                        + "failure limit. production issues the certificate a browser accepts. off "
                        + "keeps the self-signed placeholder and prints the manual command."));
    }

    /**
     * The account's contact address: what was configured, or {@code hostmaster@<domain>}.
     * <p>
     * The derivation is not a guess — {@code hostmaster} is the convention for the role that
     * answers for a domain, so a platform that has a domain has a contact by construction and
     * nobody has to fill a second knob in to get a certificate.
     */
    public static String email(BootstrapConfig config, String domain) {
        return config.acmeEmail().map(String::strip).filter(value -> !value.isEmpty())
                .orElse("hostmaster@" + domain);
    }
}
