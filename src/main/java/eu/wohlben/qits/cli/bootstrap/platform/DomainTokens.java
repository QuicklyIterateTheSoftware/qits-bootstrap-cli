package eu.wohlben.qits.cli.bootstrap.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * What {@code QITS_DOMAIN} adds to the seed stack file, as template values.
 * <p>
 * <b>Every fragment here is APPENDED to the end of a line the template already has.</b> The
 * fragment carries its own newlines, its own indentation and its own comments, so the generated
 * file explains itself exactly where the lines appear. The domain is required — a run without one
 * is refused before anything renders — so every fragment is always filled; the one that can still
 * come out empty is the seed edge's TLS ports, which {@code SeedPhases} blanks while the bootstrap
 * ingress holds 443.
 * <p>
 * <b>The indentation is spelled out rather than written as a text block, because here it is data.</b>
 * The templates are text blocks whose common indent is stripped before these values are substituted,
 * so a fragment has to carry the OUTPUT's indentation: six spaces for a compose environment key, four
 * for a service field, two for a volume name.
 * <p>
 * The one rule the fragments encode is <b>all or none</b>. The edge refuses to start when a keystore
 * names files that are not there, so its ports, its env and its volume are written together or not
 * at all.
 */
public final class DomainTokens {

    private DomainTokens() {
    }

    /** The token values for this domain, with the defaults a fixture wants. */
    public static Map<String, String> of(String domain) {
        return of(domain, "staging", "hostmaster@" + domain, "");
    }

    /** The complete edge runtime contract for an externally served domain. */
    public static Map<String, String> of(String domain, String mode, String email,
            String hetznerToken) {
        return of(domain, mode, email, hetznerToken, Optional.empty());
    }

    /** The complete edge runtime contract, optionally reusing an existing Swarm secret. */
    public static Map<String, String> of(String domain, String mode, String email,
            String hetznerToken, Optional<String> existingSecret) {
        return of(domain, mode, email, hetznerToken, existingSecret, List.of());
    }

    /**
     * The complete edge runtime contract, with the names the certificate must carry beyond the
     * wildcards the edge derives for itself.
     *
     * @param extraSans checked names inside the domain — see {@code ExtraSans}. Empty is the
     *     ordinary platform and spells no key at all: an empty list and an absent one are the same
     *     answer, and a key holding nothing is a line for the next reader to wonder about.
     */
    public static Map<String, String> of(String domain, String mode, String email,
            String hetznerToken, Optional<String> existingSecret, List<String> extraSans) {
        Map<String, String> values = new LinkedHashMap<>();
        String additional = String.join(",", extraSans);
        String secret = existingSecret.map(String::strip).filter(value -> !value.isEmpty())
                .orElseGet(() -> secretName(hetznerToken));
        // NOTE: this could be a hook to register the domain's dns records with an external dns
        // provider. Domain mode assumes dns is configured OUTSIDE this platform now: the names it
        // serves have to resolve to QITS_PUBLIC_IP before the certificate order can be answered,
        // and nothing here writes them. qits-platform-dns used to be given its own SOA and NS
        // identity here.
        values.put("LETSENCRYPT_VOLUME", letsEncryptVolume(secret));
        values.put("EDGE_SEED_TLS_PORTS", EDGE_TLS_PORTS);
        values.put("EDGE_TLS", edgeTls(mode, email, secret, additional));
        values.put("SEED_DOMAIN", seedDomain(domain));
        return values;
    }

    /**
     * <b>THE STATED DOMAIN, ON EVERY SEED SERVICE THE BOOTSTRAP STARTS ITSELF.</b>
     * <p>
     * <b>{@code QITS_DOMAIN} has TWO writers, and that is the same shape {@code QITS_ENVIRONMENT}
     * has.</b> qits-deployments writes it into every container IT deploys — but the seed stack is
     * the half where qits-deployments is not the deployer, this program is, so a seed container is
     * told by whoever started it. Leaving the seed services out would be a deployed estate that
     * knows its domain and a boot that does not: the seed edge is the provable break, because
     * {@code qits.edge.domain} defaults to {@code localhost} and an edge that believes the domain
     * is {@code localhost} reads every name it serves one tier out and orders a certificate for
     * nothing.
     * <p>
     * <b>It is pasted on every seed service rather than on the two that read it today</b>, for the
     * reason the deployer writes it for every service: a value each consumer DERIVES from is not a
     * per-service setting, and a service that starts deriving tomorrow should need no edit here.
     * The one seed container that does not get it is the upstream postgres image, which is not a
     * qits application at all — the same reason it carries no observability url and no tier.
     */
    private static String seedDomain(String domain) {
        return "\n"
            + "      # THE PLATFORM'S DOMAIN, STATED ONCE AND DERIVED FROM EVERYWHERE — origins,\n"
            + "      # allow-lists, cookie parent, rp id, ACME domain, each worked out by the\n"
            + "      # service itself. TWO WRITERS, like QITS_ENVIRONMENT: qits-deployments writes\n"
            + "      # it into every container it deploys, and this file writes it for the seed\n"
            + "      # services, which start before any deployer exists.\n"
            + "      QITS_DOMAIN: " + domain;
    }

    private static String letsEncryptVolume(String secretName) {
        return "\n"
            + "  # THE EDGE'S TLS MATERIAL.\n"
            + "  # It holds the placeholder certificate this bootstrap writes before the edge first\n"
            + "  # starts with a keystore; the edge replaces it with versioned DNS-01 PEMs and\n"
            + "  # atomically switches the current symlink once validation succeeds. A keystore\n"
            + "  # naming files that are not there refuses to start, so the\n"
            + "  # volume, the placeholder and the env on the edge are one decision.\n"
            + "  qits-edge-letsencrypt:\n"
            + "    name: qits-edge-letsencrypt\n"
            + "secrets:\n"
            + "  " + secretName + ":\n"
            + "    external: true";
    }

    private static final String EDGE_TLS_PORTS = "\n"
            + "      # DNS-01 needs no public challenge listener; only the TLS port is added.\n"
            + "      - target: 8443\n"
            + "        published: 443\n"
            + "        protocol: tcp\n"
            + "        mode: host";

    /**
     * The extra SANs, as one env line or as nothing.
     * <p>
     * <b>The certificate the edge derives is a wildcard set, and a wildcard covers ONE label.</b>
     * Names are read right to left — {@code <app>[.<env>].<project>.<domain>} — so the set is the
     * apex, {@code *.<domain>} for every project's door, {@code *.<project>.<domain>} per project
     * and {@code *.<env>.<project>.<domain>} per environment of a project that has them: every
     * depth the edge's Host reading has. This key is what carries a name of some OTHER shape, and
     * on an ordinary platform there is none — it comes out EMPTY, and an empty list spells no key
     * at all.
     * <p>
     * <b>A project's hosts are not among them.</b> {@code <app>[.<env>].<project>.<domain>} sits
     * inside the derived per-project wildcards, which the edge works out LIVE from qits-projects'
     * ProjectCreated events — so a project created after this file was written reaches the
     * certificate at the edge's next order, which its own creation event triggers. That is the
     * whole of what this key used to be filled with, and it is gone.
     * <p>
     * <b>Generic on purpose.</b> The key says "also these names" and nothing about what serves
     * them. It is empty on an ordinary platform, and an empty list spells no key at all.
     *
     * @param names already checked and joined with commas, or empty for the ordinary platform
     */
    private static String acmeAdditionalNames(String names, String prefix, String separator) {
        return names.isEmpty() ? ""
                : "\n" + prefix + "QITS_EDGE_ACME_ADDITIONAL_NAMES" + separator + names;
    }

    private static String edgeTls(String mode, String email, String secretName,
            String additionalNames) {
        return "\n"
            + "      # WHERE THE CERTIFICATE IS READ FROM, and the whole of what wakes the image's\n"
            + "      # certificate support: it remains inert\n"
            + "      # until a keystore names files. The TLS registry re-reads them every reload\n"
            + "      # period; the in-process certificate manager renews before expiry.\n"
            + "      #\n"
            + "      # quarkus.http.insecure-requests stays at its default (enabled) and is\n"
            + "      # deliberately not set: every health poll in this boot and in the deployer\n"
            + "      # speaks plain HTTP. Flipping it to `redirect` is a change to make together\n"
            + "      # with those pollers, not before them.\n"
            + "      QUARKUS_TLS_KEY_STORE_PEM_ACME_CERT: /work/.letsencrypt/current/lets-encrypt.crt\n"
            + "      QUARKUS_TLS_KEY_STORE_PEM_ACME_KEY: /work/.letsencrypt/current/lets-encrypt.key\n"
            + "      QUARKUS_TLS_RELOAD_PERIOD: 1m\n"
            + "      QITS_EDGE_ACME_ENABLED: \"true\"\n"
            + "      QITS_EDGE_ACME_MODE: " + mode + "\n"
            + "      QITS_EDGE_ACME_EMAIL: " + email + "\n"
            + "      QITS_EDGE_ACME_HETZNER_TOKEN_FILE: /run/secrets/qits-dns-hetzner-token"
            + acmeAdditionalNames(additionalNames, "      ", ": ") + "\n"
            + "    volumes:\n"
            + "      - qits-edge-letsencrypt:/work/.letsencrypt\n"
            + "    secrets:\n"
            + "      - source: " + secretName + "\n"
            + "        target: qits-dns-hetzner-token";
    }

    /**
     * <b>Why a swarm-secret-only configuration is refused.</b> {@code QITS_DNS_HETZNER_SECRET} names
     * a secret this program can create and the SEED edge can mount; the DEPLOYED edge cannot. The
     * deployer starts its successor with its settings, which carry env and no swarm secrets at all —
     * so the token has to be a value the CLI can write into the settings store, and a run that holds
     * only a secret name has nothing to hand over.
     * <p>
     * <b>Refused at preflight rather than discovered at the cutover.</b> The seed edge serves TLS
     * from the mounted secret for the whole boot; the failure arrives an hour later, as a deployed
     * edge on the self-signed placeholder logging {@code NoSuchFileException} once a minute, which
     * is what happened on 2026-09-05. The option stays because the secret is the direction; the
     * deployer's gap is what blocks it.
     *
     * @return the refusal, or null when this run can hand the deployed edge a token
     */
    public static String hetznerTokenRefusal(boolean acmeEnabled, String token, String secret) {
        boolean haveToken = token != null && !token.isBlank();
        boolean haveSecret = secret != null && !secret.isBlank();
        if (!acmeEnabled || haveToken || !haveSecret) {
            return null;
        }
        return "QITS_DNS_HETZNER_SECRET names a swarm secret and QITS_DNS_HETZNER_TOKEN is unset, "
                + "so this run cannot hand the DEPLOYED edge its DNS-01 credential: the deployer "
                + "starts a successor with its settings, which carry env and no swarm secrets, "
                + "so the edge would find no token, fail every certificate order and end "
                + "on the self-signed placeholder. Set QITS_DNS_HETZNER_TOKEN to the token's value "
                + "and rerun; the seed edge keeps using the secret either way.";
    }

    public static String secretName(String token) {
        if (token == null || token.isBlank()) {
            return "qits-dns-hetzner-token-missing";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return "qits-dns-hetzner-token-" + java.util.HexFormat.of().formatHex(digest, 0, 6);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
