package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The edge's TLS contract, on the seed stack and in the deployed settings. What these pin since
 * qits-2618: the SEED edge alone talks to Hetzner directly, with the swarm secret, for the first
 * certificate; the deployed edge holds no token and calls through qits-egress, whose route secret is
 * the deployer setting this run writes.
 */
class DomainTokensTest {

    private static final String TOKEN = "hetzner-token-value";

    private static Map<String, String> tokens() {
        return DomainTokens.of("wohlben.dev", "staging", "hostmaster@wohlben.dev",
                TOKEN, Optional.empty(), List.of(), "198.51.100.4");
    }

    /**
     * <b>The token is qits-egress's route secret, as a VALUE, and the edge has none</b>
     * (qits-2618). The edge sends its Hetzner calls to qits-egress's {@code hetzner} route with its
     * own bearer; qits-egress injects {@code QITS_EGRESS_SECRET_HETZNER_DNS}. The deployer mounts
     * no swarm secrets, so it is the value form, and masked.
     */
    @Test
    void theTokenIsQitsEgressesRouteSecretAndTheEdgeHasNone() {
        List<DeploymentSettings.Setting> settings = DeploymentSettings.of(
                new DeploymentSettings.Inputs("prod", "wohlben.dev", 8080, true, "staging",
                        "hostmaster@wohlben.dev", TOKEN, "198.51.100.4", List.of(), "pg", "push",
                        ""));

        assertThat(settings).filteredOn(s -> s.value().equals(TOKEN))
                .singleElement()
                .satisfies(s -> assertThat(s.application()).isEqualTo("qits-egress"))
                .satisfies(s -> assertThat(s.key()).isEqualTo("QITS_EGRESS_SECRET_HETZNER_DNS"))
                .satisfies(s -> assertThat(s.secret()).isTrue());
        assertThat(settings).extracting(DeploymentSettings.Setting::key)
                .noneMatch(key -> key.startsWith("QITS_EDGE_ACME_HETZNER_TOKEN"));
    }

    /**
     * <b>Both edges are told the node address</b> (qits-2293): it is what the edge writes
     * {@code node.<domain>} with, and without it qits-2292's records stay unwritten. A plain row,
     * because an address is no secret.
     */
    @Test
    void bothEdgesAreToldTheNodeAddress() {
        String compose = tokens().get("EDGE_TLS");
        assertThat(compose).contains("\n      QITS_EDGE_DNS_NODE_ADDRESS: 198.51.100.4\n");

        List<DeploymentSettings.Setting> settings = DeploymentSettings.of(
                new DeploymentSettings.Inputs("prod", "wohlben.dev", 8080, true, "production",
                        "hostmaster@wohlben.dev", TOKEN, "198.51.100.4", List.of(), "pg", "push",
                        ""));
        assertThat(settings).filteredOn(s -> s.key().equals("QITS_EDGE_DNS_NODE_ADDRESS"))
                .singleElement()
                .satisfies(s -> assertThat(s.application()).isEqualTo("qits-edge"))
                .satisfies(s -> assertThat(s.value()).isEqualTo("198.51.100.4"))
                .satisfies(s -> assertThat(s.secret()).isFalse());
    }

    /**
     * <b>The seed edge alone talks to Hetzner directly</b> (qits-2618): the swarm secret, the file
     * form, and the API URL pointed at Hetzner rather than at the egress gateway that is the
     * deployed edge's default — qits-egress is deployed by the train, which needs the certificate
     * the seed edge orders.
     */
    @Test
    void theSeedEdgeTalksToHetznerDirectlyWithTheSwarmSecret() {
        String compose = tokens().get("EDGE_TLS");

        assertThat(compose).contains(
                "\n      QITS_EDGE_ACME_HETZNER_API_URL: https://api.hetzner.cloud/v1/\n");
        assertThat(compose).contains(
                "QITS_EDGE_ACME_HETZNER_TOKEN_FILE: /run/secrets/qits-dns-hetzner-token");
        assertThat(compose).contains("target: qits-dns-hetzner-token");
        // And it never carries the value: a stack file is not the place for one.
        assertThat(String.join("\n", tokens().values())).doesNotContain(TOKEN);
    }

    /**
     * <b>A secret name alone is refused at preflight</b>, as before qits-2618. The seed edge can
     * mount it, but qits-egress's setting — which the deployed edge's DNS-01 goes through — needs
     * the value, and a swarm secret's value never leaves the swarm.
     */
    @Test
    void aSwarmSecretWithNoTokenValueIsRefused() {
        assertThat(DomainTokens.hetznerTokenRefusal(true, null, "qits-dns-hetzner-token-abc"))
                .contains("Set QITS_DNS_HETZNER_TOKEN")
                .contains("QITS_EGRESS_SECRET_HETZNER_DNS")
                .contains("no swarm secrets");
        assertThat(DomainTokens.hetznerTokenRefusal(true, "  ", "some-secret")).isNotNull();
    }

    /** Everything else is allowed: a token given, or issuance off. */
    @Test
    void aRunThatCanHandOverATokenIsNotRefused() {
        // The ordinary domain platform: a token, with or without a secret name beside it.
        assertThat(DomainTokens.hetznerTokenRefusal(true, TOKEN, "some-secret")).isNull();
        assertThat(DomainTokens.hetznerTokenRefusal(true, TOKEN, null)).isNull();
        // QITS_ACME_MODE=off: the edge keeps the placeholder on purpose.
        assertThat(DomainTokens.hetznerTokenRefusal(false, null, "some-secret")).isNull();
        // Neither configured is a different failure: dns-records names the missing token.
        assertThat(DomainTokens.hetznerTokenRefusal(true, null, null)).isNull();
    }

    /**
     * <b>THE DOMAIN IS STATED FOR THE ROUTING AS WELL AS FOR THE ORDER — and it is stated ONCE.</b>
     * The edge reads every name it serves right to left against a domain it cannot derive
     * ({@code example.co.uk} is two labels), so it needs the value
     * whether issuance is on or off. It used to be told twice: {@code QITS_EDGE_ACME_DOMAIN} here,
     * and the same fact again inside the canonical origin and the return-host list. That is the
     * fan-out this retires — the edge reads the one {@code QITS_DOMAIN} qits-deployments injects —
     * so no TLS fragment spells a domain of its own any more, with issuance off or on.
     */
    @Test
    void theEdgeIsToldTheDomainItReadsNamesAgainstEvenWithIssuanceOff() {
        Map<String, String> off = DomainTokens.of("wohlben.dev", "off",
                "hostmaster@wohlben.dev", TOKEN, Optional.empty(), List.of());

        // STILL TOLD, AND TOLD WHATEVER ISSUANCE IS DOING — under the single name now. The ACME
        // block spells no domain of its own, because a certificate's domain and a router's domain
        // were always the same fact; the seed service carries QITS_DOMAIN instead, which the edge
        // reads as qits.edge.domain and derives its ACME domain, its origin and its allow-list
        // from. Without it that key falls back to `localhost` and the grammar reads a tier out.
        assertThat(off.get("SEED_DOMAIN")).contains("QITS_DOMAIN: wohlben.dev");
        assertThat(off.get("EDGE_TLS")).doesNotContain("QITS_EDGE_ACME_DOMAIN");
        assertThat(tokens().get("EDGE_TLS")).doesNotContain("QITS_EDGE_ACME_DOMAIN");
    }

    /**
     * <b>ONE VARIABLE, TWO WRITERS, and this file is the second one.</b> qits-deployments writes
     * {@code QITS_DOMAIN} into every container it deploys — but the seed stack is the half where it
     * is not the deployer, this program is, so the bootstrap states it to the services it starts
     * itself. The deployer's own successor reads it from its settings row, because nothing
     * propagates to the propagator; the stack-file tokens carry no deployer fragment any more.
     */
    @Test
    void theDomainIsWrittenForTheSeedServicesAndNoExtrasFragmentRemains() {
        Map<String, String> tokens = tokens();

        assertThat(tokens.get("SEED_DOMAIN")).contains("QITS_DOMAIN: wohlben.dev");
        assertThat(tokens).doesNotContainKeys("DEPLOYMENTS_DOMAIN_ARGS", "EDGE_TLS_ARGS",
                "EDGE_TLS_NOTE");
    }
}
