package eu.wohlben.qits.cli.bootstrap.workstation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class PkceTest {
    @Test
    void createsRfc7636S256Challenge() throws Exception {
        Pkce pkce = Pkce.create();

        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(pkce.verifier().getBytes(StandardCharsets.US_ASCII)));

        assertThat(pkce.verifier()).hasSizeBetween(43, 128);
        assertThat(pkce.challenge()).isEqualTo(expected);
        assertThat(Pkce.state()).doesNotContain("=");
    }
}
