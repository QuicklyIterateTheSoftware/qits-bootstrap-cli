package eu.wohlben.qits.cli.bootstrap.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * What {@code QITS_DOMAIN} adds to the two generated files, as template values.
 * <p>
 * <b>Every fragment here is APPENDED to the end of a line the template already has, and that is
 * deliberate.</b> With no domain each one is the empty string, so the rendered compose file and the
 * rendered extras are byte for byte what a platform without a domain always had — no blank line,
 * no orphan comment about a feature that is off, nothing for the next reader to wonder about. With a
 * domain the fragment carries its own newlines, its own indentation and its own comments, so the
 * generated file explains itself exactly where the lines appear.
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

    /** The token values for this domain, or the empty answers when there is none. */
    public static Map<String, String> of(Optional<String> domain) {
        return of(domain, "staging", domain.map(value -> "hostmaster@" + value).orElse(""), "");
    }

    /** The complete edge runtime contract for an externally served domain. */
    public static Map<String, String> of(Optional<String> domain, String mode, String email,
            String hetznerToken) {
        return of(domain, mode, email, hetznerToken, Optional.empty());
    }

    /** The complete edge runtime contract, optionally reusing an existing Swarm secret. */
    public static Map<String, String> of(Optional<String> domain, String mode, String email,
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
    public static Map<String, String> of(Optional<String> domain, String mode, String email,
            String hetznerToken, Optional<String> existingSecret, List<String> extraSans) {
        Map<String, String> values = new LinkedHashMap<>();
        String additional = domain.isPresent() ? String.join(",", extraSans) : "";
        String secret = existingSecret.map(String::strip).filter(value -> !value.isEmpty())
                .orElseGet(() -> secretName(hetznerToken));
        // NOTE: this could be a hook to register the domain's dns records with an external dns
        // provider. Domain mode assumes dns is configured OUTSIDE this platform now: the names it
        // serves have to resolve to QITS_PUBLIC_IP before the certificate order can be answered,
        // and nothing here writes them. qits-platform-dns used to be given its own SOA and NS
        // identity here.
        values.put("LETSENCRYPT_VOLUME", domain.map(ignored -> letsEncryptVolume(secret)).orElse(""));
        values.put("EDGE_SEED_TLS_PORTS", domain.isPresent() ? EDGE_TLS_PORTS : "");
        values.put("EDGE_TLS",
                domain.map(value -> edgeTls(value, mode, email, secret, additional)).orElse(""));
        values.put("EDGE_TLS_NOTE", domain.isPresent() ? EDGE_TLS_NOTE : "");
        values.put("EDGE_TLS_ARGS", domain
                .map(value -> edgeTlsArgs(value, mode, email, hetznerToken, additional))
                .orElse(""));
        return values;
    }

    private static final String EDGE = "qits.platform.deployments.extras.qits-platform-edge.";

    private static String letsEncryptVolume(String secretName) {
        return "\n"
            + "  # THE EDGE'S TLS MATERIAL, and it is declared only because a domain is configured.\n"
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
     * {@code *.<domain>} answers for {@code editor.<domain>} and for nothing under it, and
     * {@code *.<env>.<domain>} only holds for a label the edge routes as an environment. The web
     * editor's origin is {@code editor.<project>.<domain>} — depth three under a label that is a
     * PROJECT — so no wildcard this platform orders can reach it and each one has to be named.
     * <p>
     * <b>Generic on purpose.</b> The key says "also these names" and nothing about what serves
     * them. The editor is today's reason for it and will not be the last.
     * <p>
     * <b>A name added here is not on the certificate until the edge re-orders</b>, which its
     * renewal does within the day, or a restart does at once. So a project created after this file
     * was written has an editor host that answers on a certificate it is not named in until then,
     * and browsers refuse it. The closing report says which projects are in that state.
     *
     * @param names already checked and joined with commas, or empty for the ordinary platform
     */
    private static String acmeAdditionalNames(String names, String prefix, String separator) {
        return names.isEmpty() ? ""
                : "\n" + prefix + "QITS_EDGE_ACME_ADDITIONAL_NAMES" + separator + names;
    }

    private static String edgeTls(String domain, String mode, String email, String secretName,
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
            + "      QITS_EDGE_ACME_DOMAIN: " + domain + "\n"
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
     * The comment above the edge's first extras key. A properties line carries no comment of its
     * own, so the note is a fragment at the START of the line rather than part of the keys.
     */
    private static final String EDGE_TLS_NOTE =
            "# THE DEPLOYED EDGE KEEPS THE TLS WIRING, and every part of it belongs here: the\n"
            + "# extras are what the deployer starts the successor container with, so a piece\n"
            + "# missing from them is a cutover that quietly takes 443 and the certificate away\n"
            + "# while health goes on passing on 8080. The volume is what carries the PEMs across the\n"
            + "# cutover. DNS-01 is performed directly against Hetzner's API and needs no public\n"
            + "# challenge or management listener.\n"
            + "#\n"
            + "# THE TOKEN IS THE PART THAT WAS MISSING, and it is here as a VALUE rather than as a\n"
            + "# file. The seed block mounts it as a swarm secret and reads it through\n"
            + "# QITS_EDGE_ACME_HETZNER_TOKEN_FILE; the deployer mounts no swarm secrets, so the\n"
            + "# successor found no file, every reconciliation failed on NoSuchFileException, and the\n"
            + "# platform ended on the self-signed placeholder — measured 2026-09-05. The FILE form\n"
            + "# WINS over the value when both are set, so the deployed edge must be handed the\n"
            + "# value and no file at all.\n";

    /**
     * <b>The deployed edge's TLS wiring, token included.</b> It carries
     * {@code QITS_EDGE_ACME_HETZNER_TOKEN} and deliberately NOT the {@code _FILE} spelling the seed
     * block uses: the edge prefers the file when it is set, and nothing mounts one on a deployed
     * service — the deployer has no swarm-secret support, and the edge's own deployments.yml
     * declares none.
     */
    private static String edgeTlsArgs(String domain, String mode, String email, String hetznerToken,
            String additionalNames) {
        return "\n" + EDGE + "publishes[1]=443:8443"
                    + "\n" + EDGE + "mounts[0]=volume:qits-edge-letsencrypt:/work/.letsencrypt"
                    + "\n" + EDGE
                    + "env.QUARKUS_TLS_KEY_STORE_PEM_ACME_CERT=/work/.letsencrypt/current/lets-encrypt.crt"
                    + "\n" + EDGE
                    + "env.QUARKUS_TLS_KEY_STORE_PEM_ACME_KEY=/work/.letsencrypt/current/lets-encrypt.key"
                    + "\n" + EDGE + "env.QUARKUS_TLS_RELOAD_PERIOD=1m"
                    + "\n" + EDGE + "env.QITS_EDGE_ACME_ENABLED=true"
                    + "\n" + EDGE + "env.QITS_EDGE_ACME_MODE=" + mode
                    + "\n" + EDGE + "env.QITS_EDGE_ACME_DOMAIN=" + domain
                    + "\n" + EDGE + "env.QITS_EDGE_ACME_EMAIL=" + email
                    + "\n" + EDGE + "env.QITS_EDGE_ACME_HETZNER_TOKEN=" + hetznerToken
                    + acmeAdditionalNames(additionalNames, EDGE + "env.", "=");
    }

    /**
     * <b>Why a swarm-secret-only configuration is refused.</b> {@code QITS_DNS_HETZNER_SECRET} names
     * a secret this program can create and the SEED edge can mount; the DEPLOYED edge cannot. The
     * deployer starts its successor from the extras, which carry env, mounts, publishes and aliases
     * and no swarm secrets at all — so the token has to be a value the CLI can read, and a run that
     * holds only a secret name has nothing to hand over.
     * <p>
     * <b>Refused at preflight rather than discovered at the cutover.</b> The seed edge serves TLS
     * from the mounted secret for the whole boot; the failure arrives an hour later, as a deployed
     * edge on the self-signed placeholder logging {@code NoSuchFileException} once a minute, which
     * is what happened on 2026-09-05. The option stays because the secret is the direction; the
     * deployer's gap is what blocks it.
     *
     * @return the refusal, or null when this run can hand the deployed edge a token
     */
    public static String hetznerTokenRefusal(boolean domain, boolean acmeEnabled, String token,
            String secret) {
        boolean haveToken = token != null && !token.isBlank();
        boolean haveSecret = secret != null && !secret.isBlank();
        if (!domain || !acmeEnabled || haveToken || !haveSecret) {
            return null;
        }
        return "QITS_DNS_HETZNER_SECRET names a swarm secret and QITS_DNS_HETZNER_TOKEN is unset, "
                + "so this run cannot hand the DEPLOYED edge its DNS-01 credential: the deployer "
                + "starts a successor from the extras, which carry env and mounts and no swarm "
                + "secrets, so the edge would find no token, fail every certificate order and end "
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
