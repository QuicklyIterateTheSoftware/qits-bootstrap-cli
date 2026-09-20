package eu.wohlben.qits.cli.bootstrap.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>What this run presents at the store.</b> Publishing to qits-artifacts is CI's door alone (user
 * ruling 2026-09-13), so the PUT carries a {@code bootstrap-publish} credential and the probes
 * carry the run's own machine token — the store's reads are behind the machine gate too. Where
 * there is nothing to present, nothing is sent: that is the seed store this run started itself,
 * before any idp existed to commission anything at.
 */
class ArtifactsApiTest {

    private static final String BASE = "http://prod-qits-artifacts:8080/artifacts";

    /**
     * <b>The one publish this class makes.</b> It presents the credential it is given, and not the
     * one the reads use — a publishing identity that lives for the publish phase, and a machine
     * token that lives for the boot.
     */
    @Test
    void theDaemonPublishCarriesTheCredentialItIsGiven() {
        FakeHttp http = new FakeHttp();

        new ArtifactsApi(http, BASE).publishDaemon("qits-ci-daemon", "abc123",
                Path.of("/tmp/qits-ci-daemon"), "Bearer publish-token");

        assertThat(http.urls).containsExactly(BASE + "/daemons/qits-ci-daemon/abc123");
        assertThat(http.headers.getFirst())
                .containsEntry("Authorization", "Bearer publish-token");
    }

    /** Nothing to present is nothing sent — the ungated seed store, as it was. */
    @Test
    void aPublishWithNoCredentialIsMadeBare() {
        FakeHttp http = new FakeHttp();

        new ArtifactsApi(http, BASE).publishDaemon("qits-ci-daemon", "abc123",
                Path.of("/tmp/qits-ci-daemon"), null);

        assertThat(http.headers.getFirst()).isEmpty();
    }

    /**
     * <b>The probe is credentialed too, and it has to be.</b> A gated store answers an anonymous
     * HEAD with a 401, which is not a 404: the probe would read "already published" out of a
     * refusal and skip the publish the boot needs.
     */
    @Test
    void everyProbeCarriesTheRunsOwnReadToken() {
        FakeHttp http = new FakeHttp();
        ArtifactsApi artifacts = new ArtifactsApi(http, BASE, () -> "Bearer read-token");

        artifacts.daemonPublished("qits-ci-daemon", "abc123");
        artifacts.npmPublished("@qits/ui-components", "0.0.4");
        artifacts.mavenPublished("eu/wohlben/qits", "qits-eventstream", "1.2.3", "jar");
        artifacts.imagePublished("qits/ci", "2026.919.1");

        assertThat(http.headers).allSatisfy(sent ->
                assertThat(sent).containsEntry("Authorization", "Bearer read-token"));
        // The manifest Accept header is still there beside it: a registry answers 404 for a
        // manifest whose media type the caller did not ask for.
        assertThat(http.headers.getLast()).containsEntry("Accept", ArtifactsApi.MANIFEST_TYPES);
    }

    /**
     * <b>Health is asked bare, and stays that way.</b> It is the readiness endpoint every wait in
     * this program polls — including the ones that run before any idp exists — so credentialing it
     * would put a token mint inside a five-second poll for nothing.
     */
    @Test
    void healthIsTheOneCallThatPresentsNothing() {
        FakeHttp http = new FakeHttp();

        new ArtifactsApi(http, BASE, () -> "Bearer read-token").health();

        assertThat(http.headers.getFirst()).isEmpty();
    }

    /** No token yet is no header — every probe before {@code seed-idp} meets the ungated seed. */
    @Test
    void aProbeWithNoTokenYetIsMadeBare() {
        FakeHttp http = new FakeHttp();

        new ArtifactsApi(http, BASE, () -> null).daemonPublished("qits-ci-daemon", "abc123");

        assertThat(http.headers.getFirst()).isEmpty();
    }

    private static final class FakeHttp extends Http {

        private final List<String> urls = new ArrayList<>();
        private final List<Map<String, String>> headers = new ArrayList<>();

        private Response record(String url, Map<String, String> sent) {
            urls.add(url);
            headers.add(sent);
            return new Response(200, "{}");
        }

        @Override
        public Response get(String url, Map<String, String> sent) {
            return record(url, sent);
        }

        @Override
        public Response head(String url, Map<String, String> sent) {
            return record(url, sent);
        }

        @Override
        public Response putFile(String url, Path file, Duration timeout,
                                Map<String, String> sent) {
            return record(url, sent);
        }
    }
}
