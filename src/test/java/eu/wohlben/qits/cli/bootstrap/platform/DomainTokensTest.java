package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The edge's TLS contract, on the seed stack and in the deployed edge's settings. What these pin is the one thing
 * that made a green boot end on a self-signed certificate: the DEPLOYED edge and the SEED edge read
 * their DNS-01 token from different places, and only one of the two has a swarm secret to read.
 */
class DomainTokensTest {

    private static final String TOKEN = "hetzner-token-value";

    private static Map<String, String> tokens() {
        return DomainTokens.of("wohlben.dev", "staging", "hostmaster@wohlben.dev",
                TOKEN, Optional.empty(), List.of());
    }

    /**
     * <b>The deployed edge is handed the token as a VALUE, and no file.</b> The deployer starts a
     * successor with its settings rows, and it mounts no swarm secrets, so the file the seed reads
     * names nothing on the successor. The edge prefers the file whenever it is set, which is why
     * the setting is the value form: with the file, every reconciliation failed on
     * {@code NoSuchFileException} once a minute and the platform kept the placeholder (measured
     * 2026-09-05).
     */
    @Test
    void theDeployedEdgeGetsTheTokenValueAndNoFile() {
        List<DeploymentSettings.Setting> settings = DeploymentSettings.of(
                new DeploymentSettings.Inputs("prod", "wohlben.dev", 8080, true, "staging",
                        "hostmaster@wohlben.dev", TOKEN, List.of(), "pg", "push", ""));

        assertThat(settings).filteredOn(s -> s.application().equals("qits-edge"))
                .extracting(DeploymentSettings.Setting::key)
                .contains("QITS_EDGE_ACME_HETZNER_TOKEN")
                .doesNotContain("QITS_EDGE_ACME_HETZNER_TOKEN_FILE");
        assertThat(settings).filteredOn(s -> s.key().equals("QITS_EDGE_ACME_HETZNER_TOKEN"))
                .singleElement()
                .satisfies(s -> assertThat(s.value()).isEqualTo(TOKEN))
                .satisfies(s -> assertThat(s.secret()).isTrue());
    }

    /** The seed edge keeps the swarm secret and the file form: it is a stack service, and can. */
    @Test
    void theSeedEdgeStillMountsTheSecretAndReadsTheFile() {
        String compose = tokens().get("EDGE_TLS");

        assertThat(compose).contains(
                "QITS_EDGE_ACME_HETZNER_TOKEN_FILE: /run/secrets/qits-dns-hetzner-token");
        assertThat(compose).contains("target: qits-dns-hetzner-token");
        // And it never carries the value: a stack file is not the place for one.
        assertThat(compose).doesNotContain(TOKEN);
    }

    /**
     * <b>A secret name alone is refused at preflight.</b> This program can create the secret and
     * the seed edge can mount it, but the deployed one cannot — so a boot that held only a name
     * would serve TLS all the way to the cutover and then quietly fall back to the placeholder.
     */
    @Test
    void aSwarmSecretWithNoTokenValueIsRefused() {
        assertThat(DomainTokens.hetznerTokenRefusal(true, null, "qits-dns-hetzner-token-abc"))
                .contains("QITS_DNS_HETZNER_TOKEN")
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
        // Neither configured is a different failure, and the secret phase is where it is named.
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
