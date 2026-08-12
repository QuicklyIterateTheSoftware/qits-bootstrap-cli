package eu.wohlben.qits.cli.bootstrap.platform;

import eu.wohlben.qits.cli.bootstrap.config.DomainName;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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
 * The one rule the fragments encode is <b>both or neither</b>. The dns service turns SOA and NS
 * synthesis off unless it holds a nameserver name AND a hostmaster address, and the edge refuses to
 * start when a keystore names files that are not there — so the pair of dns variables, and the edge's
 * ports, env and volume, are written together or not at all.
 */
public final class DomainTokens {

    private DomainTokens() {
    }

    /** The token values for this domain, or the empty answers when there is none. */
    public static Map<String, String> of(Optional<String> domain) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("DNS_IDENTITY", domain.map(DomainTokens::dnsIdentity).orElse(""));
        values.put("DNS_IDENTITY_ARGS", domain.map(DomainTokens::dnsIdentityArgs).orElse(""));
        values.put("LETSENCRYPT_VOLUME", domain.isPresent() ? LETSENCRYPT_VOLUME : "");
        values.put("EDGE_TLS_PORTS", domain.isPresent() ? EDGE_TLS_PORTS : "");
        values.put("EDGE_TLS", domain.isPresent() ? EDGE_TLS : "");
        values.put("EDGE_TLS_NOTE", domain.isPresent() ? EDGE_TLS_NOTE : "");
        values.put("EDGE_TLS_ARGS", domain.isPresent() ? EDGE_TLS_ARGS : "");
        return values;
    }

    /** The dns container's own identity: the two variables SOA and NS synthesis needs. */
    private static String dnsIdentity(String domain) {
        return "\n"
                + "      # ITS PUBLIC IDENTITY, AND BOTH OR NEITHER. Blank either one and this\n"
                + "      # service answers A and CNAME records perfectly while serving no SOA at\n"
                + "      # all, so resolvers cannot negative-cache and come back for every\n"
                + "      # nonexistent name forever. It degrades as load and latency rather than as\n"
                + "      # an outage, and one boot log line is the whole warning. Both names are\n"
                + "      # conventions of the bootstrap; the service only knows what it is told.\n"
                + "      QITS_DNS_NS_NAMES: " + DomainName.nsName(domain) + "\n"
                + "      QITS_DNS_HOSTMASTER: " + DomainName.hostmaster(domain);
    }

    /** The nameserver's two variables, as extras keys appended after its last one. */
    private static String dnsIdentityArgs(String domain) {
        return "\n" + DNS + "env.QITS_DNS_NS_NAMES=" + DomainName.nsName(domain)
                + "\n" + DNS + "env.QITS_DNS_HOSTMASTER=" + DomainName.hostmaster(domain);
    }

    private static final String EDGE = "qits.platform.deployments.extras.qits-platform-edge.";

    private static final String DNS = "qits.platform.deployments.extras.qits-platform-dns.";

    private static final String LETSENCRYPT_VOLUME = "\n"
            + "  # THE EDGE'S TLS MATERIAL, and it is declared only because a domain is configured.\n"
            + "  # It holds the placeholder certificate this bootstrap writes before the edge first\n"
            + "  # starts with a keystore, and then the real PEMs that\n"
            + "  # `quarkus tls lets-encrypt issue-certificate` writes over them under the same two\n"
            + "  # filenames. A keystore naming files that are not there refuses to start, so the\n"
            + "  # volume, the placeholder and the env on the edge are one decision.\n"
            + "  qits-edge-letsencrypt:\n"
            + "    name: qits-edge-letsencrypt";

    private static final String EDGE_TLS_PORTS = "\n"
            + "      # 80 is where the ACME HTTP-01 challenge is answered, by a route the extension\n"
            + "      # adds to this listener; 443 is the TLS port the certificate is for. 9000 is\n"
            + "      # the MANAGEMENT interface and is bound to loopback on purpose: the\n"
            + "      # challenge-management endpoint is unauthenticated, so on a public port it\n"
            + "      # would let anyone on the internet complete their own ACME order for this\n"
            + "      # domain.\n"
            + "      - \"80:8080\"\n"
            + "      - \"443:8443\"\n"
            + "      - \"127.0.0.1:9000:9000\"";

    private static final String EDGE_TLS = "\n"
            + "      # WHERE THE CERTIFICATE IS READ FROM, and the whole of what wakes the image's\n"
            + "      # dormant lets-encrypt support: the build-time flags are baked in and inert\n"
            + "      # until a keystore names files. The TLS registry re-reads them every reload\n"
            + "      # period, which is what makes a renewal a CLI run rather than a redeploy.\n"
            + "      #\n"
            + "      # quarkus.http.insecure-requests stays at its default (enabled) and is\n"
            + "      # deliberately not set: every health poll in this boot and in the deployer\n"
            + "      # speaks plain HTTP. Flipping it to `redirect` is a change to make together\n"
            + "      # with those pollers, not before them.\n"
            + "      QUARKUS_TLS_KEY_STORE_PEM_ACME_CERT: /work/.letsencrypt/lets-encrypt.crt\n"
            + "      QUARKUS_TLS_KEY_STORE_PEM_ACME_KEY: /work/.letsencrypt/lets-encrypt.key\n"
            + "      QUARKUS_TLS_RELOAD_PERIOD: 1h\n"
            + "    volumes:\n"
            + "      - qits-edge-letsencrypt:/work/.letsencrypt";

    /**
     * The comment above the edge's first extras key. A properties line carries no comment of its
     * own, so the note is a fragment at the START of the line rather than part of the keys.
     */
    private static final String EDGE_TLS_NOTE =
            "# THE DEPLOYED EDGE KEEPS THE TLS WIRING, and every part of it belongs here: the\n"
            + "# extras are what the deployer starts the successor container with, so a piece\n"
            + "# missing from them is a cutover that quietly takes 443 and the certificate away\n"
            + "# while health goes on passing on 8080. The volume is what carries the PEMs across the\n"
            + "# cutover.\n"
            + "#\n"
            + "# 9000 STAYS ON LOOPBACK, and under swarm that REFUSES the deployment rather than\n"
            + "# widening it: a service publish has no ip field, so the only thing an orchestrator\n"
            + "# could do with this line is put an unauthenticated ACME challenge-management endpoint\n"
            + "# on every interface of the host. A domain on a swarm platform needs this port fronted\n"
            + "# some other way; a refused deployment is how that decision gets made deliberately.\n";

    private static final String EDGE_TLS_ARGS =
            "\n" + EDGE + "publishes[1]=80:8080"
                    + "\n" + EDGE + "publishes[2]=443:8443"
                    + "\n" + EDGE + "publishes[3]=127.0.0.1:9000:9000"
                    + "\n" + EDGE + "mounts[0]=volume:qits-edge-letsencrypt:/work/.letsencrypt"
                    + "\n" + EDGE
                    + "env.QUARKUS_TLS_KEY_STORE_PEM_ACME_CERT=/work/.letsencrypt/lets-encrypt.crt"
                    + "\n" + EDGE
                    + "env.QUARKUS_TLS_KEY_STORE_PEM_ACME_KEY=/work/.letsencrypt/lets-encrypt.key"
                    + "\n" + EDGE + "env.QUARKUS_TLS_RELOAD_PERIOD=1h";
}
