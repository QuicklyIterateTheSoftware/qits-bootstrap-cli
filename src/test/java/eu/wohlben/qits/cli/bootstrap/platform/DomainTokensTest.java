package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The edge's TLS contract, in the two files it is written into. What these pin is the one thing
 * that made a green boot end on a self-signed certificate: the DEPLOYED edge and the SEED edge read
 * their DNS-01 token from different places, and only one of the two has a swarm secret to read.
 */
class DomainTokensTest {

    private static final String TOKEN = "hetzner-token-value";

    private static Map<String, String> tokens() {
        return DomainTokens.of(Optional.of("wohlben.dev"), "staging", "hostmaster@wohlben.dev",
                TOKEN, Optional.empty(), List.of());
    }

    /**
     * <b>The deployed edge is handed the token as a VALUE, and no file.</b> The extras are the whole
     * of what the deployer starts a successor with — env, mounts, publishes, aliases — and it mounts
     * no swarm secrets, so the file the seed reads names nothing on the successor. The edge prefers
     * the file whenever it is set, which is why the line must be absent rather than merely wrong:
     * with it, every reconciliation failed on {@code NoSuchFileException} once a minute and the
     * platform kept the placeholder (measured 2026-09-05).
     */
    @Test
    void theDeployedEdgeGetsTheTokenValueAndNoFile() {
        String extras = tokens().get("EDGE_TLS_ARGS");

        assertThat(extras).contains("env.QITS_EDGE_ACME_HETZNER_TOKEN=" + TOKEN);
        assertThat(extras).doesNotContain("QITS_EDGE_ACME_HETZNER_TOKEN_FILE");
        assertThat(extras).doesNotContain("/run/secrets/");
        // The rest of the wiring is unchanged: without any of it a cutover takes 443 away.
        assertThat(extras).contains("publishes[1]=443:8443")
                .contains("mounts[0]=volume:qits-edge-letsencrypt:/work/.letsencrypt")
                .contains("env.QITS_EDGE_ACME_MODE=staging");
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

    /** The note says which part was missing, because the next reader will ask. */
    @Test
    void theNoteExplainsWhyTheTokenIsAValueHere() {
        assertThat(tokens().get("EDGE_TLS_NOTE"))
                .contains("THE TOKEN IS THE PART THAT WAS MISSING")
                .contains("FILE form");
    }

    /**
     * <b>A secret name alone is refused at preflight.</b> This program can create the secret and
     * the seed edge can mount it, but the deployed one cannot — so a boot that held only a name
     * would serve TLS all the way to the cutover and then quietly fall back to the placeholder.
     */
    @Test
    void aSwarmSecretWithNoTokenValueIsRefused() {
        assertThat(DomainTokens.hetznerTokenRefusal(true, true, null, "qits-dns-hetzner-token-abc"))
                .contains("QITS_DNS_HETZNER_TOKEN")
                .contains("no swarm secrets");
        assertThat(DomainTokens.hetznerTokenRefusal(true, true, "  ", "some-secret")).isNotNull();
    }

    /** Everything else is allowed: a token given, no domain, or issuance off. */
    @Test
    void aRunThatCanHandOverATokenIsNotRefused() {
        // The ordinary domain platform: a token, with or without a secret name beside it.
        assertThat(DomainTokens.hetznerTokenRefusal(true, true, TOKEN, "some-secret")).isNull();
        assertThat(DomainTokens.hetznerTokenRefusal(true, true, TOKEN, null)).isNull();
        // No domain, so no certificate to order and no credential to hand anybody.
        assertThat(DomainTokens.hetznerTokenRefusal(false, true, null, "some-secret")).isNull();
        // QITS_ACME_MODE=off: the edge keeps the placeholder on purpose.
        assertThat(DomainTokens.hetznerTokenRefusal(true, false, null, "some-secret")).isNull();
        // Neither configured is a different failure, and the secret phase is where it is named.
        assertThat(DomainTokens.hetznerTokenRefusal(true, true, null, null)).isNull();
    }

    /**
     * <b>THE DOMAIN IS STATED FOR THE ROUTING AS WELL AS FOR THE ORDER — and it is stated ONCE.</b>
     * The edge reads every name it serves right to left against a domain it cannot derive
     * ({@code example.co.uk} is two labels and {@code localhost} is one), so it needs the value
     * whether issuance is on or off. It used to be told twice: {@code QITS_EDGE_ACME_DOMAIN} here,
     * and the same fact again inside the canonical origin and the return-host list. That is the
     * fan-out this retires — the edge reads the one {@code QITS_DOMAIN} qits-deployments injects —
     * so no TLS fragment spells a domain of its own any more, with issuance off or on.
     */
    @Test
    void theEdgeIsToldTheDomainItReadsNamesAgainstEvenWithIssuanceOff() {
        Map<String, String> off = DomainTokens.of(Optional.of("wohlben.dev"), "off",
                "hostmaster@wohlben.dev", TOKEN, Optional.empty(), List.of());

        // STILL TOLD, AND TOLD WHATEVER ISSUANCE IS DOING — under the single name now. The ACME
        // block spells no domain of its own, because a certificate's domain and a router's domain
        // were always the same fact; the seed service carries QITS_DOMAIN instead, which the edge
        // reads as qits.edge.domain and derives its ACME domain, its origin and its allow-list
        // from. Without it that key falls back to `localhost` and the grammar reads a tier out.
        assertThat(off.get("SEED_DOMAIN")).contains("QITS_DOMAIN: wohlben.dev");
        assertThat(off.get("EDGE_TLS")).doesNotContain("QITS_EDGE_ACME_DOMAIN");
        assertThat(off.get("EDGE_TLS_ARGS")).doesNotContain("QITS_EDGE_ACME_DOMAIN");
        assertThat(tokens().get("EDGE_TLS")).doesNotContain("QITS_EDGE_ACME_DOMAIN");
        assertThat(tokens().get("EDGE_TLS_ARGS")).doesNotContain("QITS_EDGE_ACME_DOMAIN");
    }

    /**
     * <b>ONE VARIABLE, TWO WRITERS, and this file is the second one.</b> qits-deployments writes
     * {@code QITS_DOMAIN} into every container it deploys — but the seed stack is the half where it
     * is not the deployer, this program is, so the bootstrap states it to the services it starts
     * itself. The deployer additionally needs it in its own EXTRAS, because nothing propagates to
     * the propagator and a self-update's successor is started from those lines alone.
     */
    @Test
    void theDomainIsWrittenForTheSeedServicesAndForTheDeployersOwnExtras() {
        Map<String, String> tokens = tokens();

        assertThat(tokens.get("SEED_DOMAIN")).contains("QITS_DOMAIN: wohlben.dev");
        assertThat(tokens.get("DEPLOYMENTS_DOMAIN_ARGS")).contains(
                "qits.deployments.extras.qits-deployments.env.QITS_DOMAIN=wohlben.dev");
    }

    /** A domainless platform spells none of it — the same answer it always gave. */
    @Test
    void noDomainSpellsNoTlsAtAll() {
        Map<String, String> none = DomainTokens.of(Optional.empty(), "staging", "", "",
                Optional.empty(), List.of());

        assertThat(none.get("EDGE_TLS_ARGS")).isEmpty();
        assertThat(none.get("EDGE_TLS")).isEmpty();
        assertThat(none.get("EDGE_TLS_NOTE")).isEmpty();
        // The deployer's statement of the domain obeys the same rule, and it is the one that
        // matters most: the deployer WITHHOLDS the variable where there is no domain rather than
        // writing an empty one, so a generated file that stated emptiness would disagree with the
        // very service it configures.
        assertThat(none.get("SEED_DOMAIN")).isEmpty();
        assertThat(none.get("DEPLOYMENTS_DOMAIN_ARGS")).isEmpty();
    }
}
