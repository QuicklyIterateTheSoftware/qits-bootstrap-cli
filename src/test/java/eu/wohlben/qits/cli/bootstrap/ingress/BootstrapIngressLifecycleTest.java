package eu.wohlben.qits.cli.bootstrap.ingress;

import eu.wohlben.qits.cli.bootstrap.config.TestConfig;
import eu.wohlben.qits.cli.bootstrap.phases.Boot;
import eu.wohlben.qits.cli.bootstrap.proc.RunLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapIngressLifecycleTest {

    @TempDir
    Path temp;

    private BootstrapIngressLifecycle ingress(Map<String, String> config,
                                              BootstrapIngressMode mode) {
        Boot boot = new Boot(TestConfig.from(config), new RunLog(temp.resolve("run.log")));
        boot.state.bootstrapIngressPassword = "run-secret";
        BootstrapIngressLifecycle ingress = new BootstrapIngressLifecycle(boot);
        ingress.mode(mode);
        return ingress;
    }

    private static final Map<String, String> DOMAIN = Map.of(
            "QITS_DOMAIN", "wohlben.eu",
            "QITS_BOOTSTRAP_INGRESS_PUBLIC", "true");

    @Test
    void publicSeedBuildsUseTheNormalTlsDoor() {
        assertThat(ingress(DOMAIN, BootstrapIngressMode.PUBLIC_TLS).mavenRepositoryUrl())
                .isEqualTo("https://wohlben.eu/artifacts/maven/maven");
    }

    /**
     * <b>The cold-host mode, and the address is the whole of it.</b> A fresh machine's certificate
     * volume is empty, so the ingress serves the domain over plain HTTP — and every seed image
     * build is built with this url. Pointing it at 443 on a box where nothing listens is how the
     * 2026-09-05 boot died at its first seed image, with "Connection refused" rather than anything
     * about a certificate.
     */
    @Test
    void aHostWithNoCertificateServesTheSameDomainOverPlainHttp() {
        BootstrapIngressLifecycle ingress = ingress(DOMAIN, BootstrapIngressMode.PUBLIC_HTTP);

        assertThat(ingress.mavenRepositoryUrl())
                .isEqualTo("http://wohlben.eu/artifacts/maven/maven");
        assertThat(ingress.address()).isEqualTo("http://wohlben.eu");
    }

    @Test
    void localModeKeepsTheLoopbackOnlyDoor() {
        assertThat(ingress(Map.of(), BootstrapIngressMode.LOOPBACK).mavenRepositoryUrl())
                .isEqualTo("http://localhost:8481/artifacts/maven/maven");
    }

    /**
     * <b>The mode is the certificate volume's answer, not a knob's.</b> Public mode was written for
     * a re-bootstrap that keeps the pair its last run wrote; the same configuration on a machine
     * that has never run this platform has to mean something else, because the container would die
     * at startup on a key that is not there.
     */
    @Test
    void theCertificatePairOnTheVolumeDecidesWhichPublicModeItIs() {
        assertThat(BootstrapIngressLifecycle.decide(true, true))
                .isEqualTo(BootstrapIngressMode.PUBLIC_TLS);
        assertThat(BootstrapIngressLifecycle.decide(true, false))
                .isEqualTo(BootstrapIngressMode.PUBLIC_HTTP);
        // No domain, or a domain node told to stay private: the volume is not even asked.
        assertThat(BootstrapIngressLifecycle.decide(false, true))
                .isEqualTo(BootstrapIngressMode.LOOPBACK);
        assertThat(BootstrapIngressLifecycle.decide(false, false))
                .isEqualTo(BootstrapIngressMode.LOOPBACK);
    }

    /** Both public modes answer to the domain's Host header; only one of them binds 443. */
    @Test
    void bothPublicModesArePublicAndOnlyOneIsTls() {
        assertThat(BootstrapIngressMode.PUBLIC_TLS.isPublic()).isTrue();
        assertThat(BootstrapIngressMode.PUBLIC_HTTP.isPublic()).isTrue();
        assertThat(BootstrapIngressMode.LOOPBACK.isPublic()).isFalse();
        assertThat(BootstrapIngressMode.PUBLIC_TLS.scheme()).isEqualTo("https");
        assertThat(BootstrapIngressMode.PUBLIC_HTTP.scheme()).isEqualTo("http");
    }

    /**
     * A retry reads the mode back out of the state file, so the seed builds it already handed a url
     * to are not told a different one. A file written before there were three modes records none,
     * and the caller decides afresh.
     */
    @Test
    void arecordedModeSurvivesAWorkerRetry() {
        assertThat(BootstrapIngressMode.of("PUBLIC_HTTP", null))
                .isEqualTo(BootstrapIngressMode.PUBLIC_HTTP);
        assertThat(BootstrapIngressMode.of(null, null)).isNull();
        assertThat(BootstrapIngressMode.of("", null)).isNull();
        assertThat(BootstrapIngressMode.of("PUBLIC_QUIC", null)).isNull();
    }

    @Test
    void capabilityStateLivesBesideTheDurableProgressJournal() {
        assertThat(BootstrapIngressLifecycle.stateFile(
                "/root/qits/.qits-bootstrap-progress.json"))
                .isEqualTo(Path.of("/root/qits/.qits-bootstrap-edge.env"));
    }
}
