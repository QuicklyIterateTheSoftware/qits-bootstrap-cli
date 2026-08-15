package eu.wohlben.qits.cli.bootstrap.platform;

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
 * The one rule the fragments encode is <b>all or none</b>. The edge refuses to start when a keystore
 * names files that are not there, so its ports, its env and its volume are written together or not
 * at all.
 */
public final class DomainTokens {

    private DomainTokens() {
    }

    /** The token values for this domain, or the empty answers when there is none. */
    public static Map<String, String> of(Optional<String> domain) {
        Map<String, String> values = new LinkedHashMap<>();
        // NOTE: this could be a hook to register the domain's dns records with an external dns
        // provider. Domain mode assumes dns is configured OUTSIDE this platform now: the names it
        // serves have to resolve to QITS_PUBLIC_IP before the certificate order can be answered,
        // and nothing here writes them. qits-platform-dns used to be given its own SOA and NS
        // identity here.
        values.put("LETSENCRYPT_VOLUME", domain.isPresent() ? LETSENCRYPT_VOLUME : "");
        values.put("EDGE_TLS_PORTS", domain.isPresent() ? EDGE_TLS_PORTS : "");
        values.put("EDGE_TLS", domain.isPresent() ? EDGE_TLS : "");
        values.put("EDGE_TLS_NOTE", domain.isPresent() ? EDGE_TLS_NOTE : "");
        values.put("EDGE_TLS_ARGS", domain.isPresent() ? EDGE_TLS_ARGS : "");
        return values;
    }

    private static final String EDGE = "qits.platform.deployments.extras.qits-platform-edge.";

    private static final String LETSENCRYPT_VOLUME = "\n"
            + "  # THE EDGE'S TLS MATERIAL, and it is declared only because a domain is configured.\n"
            + "  # It holds the placeholder certificate this bootstrap writes before the edge first\n"
            + "  # starts with a keystore, and then the real PEMs the bootstrap's own edge-acme phase\n"
            + "  # writes over them under the same two filenames once the name resolves. A keystore\n"
            + "  # naming files that are not there refuses to start, so the\n"
            + "  # volume, the placeholder and the env on the edge are one decision.\n"
            + "  qits-edge-letsencrypt:\n"
            + "    name: qits-edge-letsencrypt";

    private static final String EDGE_TLS_PORTS = "\n"
            + "      # 80 is where the ACME HTTP-01 challenge is answered, by a route the extension\n"
            + "      # adds to this listener; 443 is the TLS port the certificate is for.\n"
            + "      #\n"
            + "      # 9000, THE MANAGEMENT INTERFACE, IS NOT PUBLISHED AT ALL. The\n"
            + "      # challenge-management endpoint is unauthenticated, and a publish has no ip\n"
            + "      # field in either mode — so the loopback bind this file used to carry cannot\n"
            + "      # be expressed, and publishing it anyway would let anyone reaching this host\n"
            + "      # complete their own ACME order for this domain. It is reached on qits-net at\n"
            + "      # qits-platform-edge:9000, which is where the bootstrap's edge-acme phase fills\n"
            + "      # the challenge slot from, and where a manual retry is pointed too.\n"
            + "      - target: 8080\n"
            + "        published: 80\n"
            + "        protocol: tcp\n"
            + "        mode: host\n"
            + "      - target: 8443\n"
            + "        published: 443\n"
            + "        protocol: tcp\n"
            + "        mode: host";

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
            + "# 9000 IS NOT PUBLISHED, here exactly as in the seed: the challenge-management\n"
            + "# endpoint is unauthenticated, and a swarm publish has no ip field to keep a port on\n"
            + "# loopback — a publish line here would put that endpoint on every interface of the\n"
            + "# host, and the swarm driver REFUSES a loopback publish for that reason. Every\n"
            + "# caller — the edge-acme phase, a renew — dials qits-platform-edge:9000 on qits-net.\n";

    private static final String EDGE_TLS_ARGS =
            "\n" + EDGE + "publishes[1]=80:8080"
                    + "\n" + EDGE + "publishes[2]=443:8443"
                    + "\n" + EDGE + "mounts[0]=volume:qits-edge-letsencrypt:/work/.letsencrypt"
                    + "\n" + EDGE
                    + "env.QUARKUS_TLS_KEY_STORE_PEM_ACME_CERT=/work/.letsencrypt/lets-encrypt.crt"
                    + "\n" + EDGE
                    + "env.QUARKUS_TLS_KEY_STORE_PEM_ACME_KEY=/work/.letsencrypt/lets-encrypt.key"
                    + "\n" + EDGE + "env.QUARKUS_TLS_RELOAD_PERIOD=1h";
}
