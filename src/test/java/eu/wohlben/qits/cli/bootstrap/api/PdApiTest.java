package eu.wohlben.qits.cli.bootstrap.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The body of the one write this program makes to the deployer's intake, asserted whole.
 * <p>
 * It is asserted here rather than proved by a bootstrap because every identifier on that door is
 * pattern-validated: a field sent empty is a 400, and a 400 on this call is an application that
 * never deploys while the boot reports one warning among thousands of lines.
 */
class PdApiTest {

    /** The whole of it, with the repository's public address, which this run always holds. */
    @Test
    void aReleaseHandedOverNamesTheVersionTheApplicationAndThePublicPair() {
        assertThat(PdApi.softwareReleasedBody("c0ffee", "qits", "qits-ci-service", "qits-ci",
                "2026.812.101500"))
                .isEqualTo("{\"runId\":\"bootstrap\",\"repoId\":\"c0ffee\","
                        + "\"version\":\"2026.812.101500\",\"application\":\"qits-ci\","
                        + "\"projectId\":\"qits\",\"repoName\":\"qits-ci-service\"}");
    }

    /**
     * <b>Both or neither.</b> With the pair the deployer reads the deployment spec name-addressed;
     * with half of it there is nothing to address, so the half that is there is left out and the
     * deployer falls back to the storage scheme.
     */
    @Test
    void halfAPublicAddressIsNoPublicAddress() {
        assertThat(PdApi.softwareReleasedBody("c0ffee", "qits", "", "qits-ci", "2026.812.101500"))
                .doesNotContain("projectId").doesNotContain("repoName");
        assertThat(PdApi.softwareReleasedBody("c0ffee", null, "qits-ci-service", "qits-ci",
                "2026.812.101500"))
                .doesNotContain("projectId").doesNotContain("repoName");
    }

    /** An unknown application is left to the door's own fallback rather than sent blank. */
    @Test
    void anAbsentApplicationIsOmitted() {
        assertThat(PdApi.softwareReleasedBody("c0ffee", null, null, "", "2026.812.101500"))
                .isEqualTo("{\"runId\":\"bootstrap\",\"repoId\":\"c0ffee\","
                        + "\"version\":\"2026.812.101500\"}");
    }
}
