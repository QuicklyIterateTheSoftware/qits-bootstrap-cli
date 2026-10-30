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
        BootstrapIngressLifecycle ingress = new BootstrapIngressLifecycle(boot);
        ingress.mode(mode);
        return ingress;
    }

    private static final Map<String, String> DOMAIN = Map.of(
            "QITS_DOMAIN", "wohlben.eu",
            "QITS_BOOTSTRAP_INGRESS_PUBLIC", "true");

    @Test
    void thePublicTlsModeIsTheDomainOverHttps() {
        assertThat(ingress(DOMAIN, BootstrapIngressMode.PUBLIC_TLS).address())
                .isEqualTo("https://wohlben.eu");
    }

    /**
     * <b>The cold-host mode.</b> A fresh machine's certificate volume is empty, so the ingress
     * serves the domain over plain HTTP; pointing the operator at 443 on a box where nothing
     * listens would be a progress page nobody can open.
     */
    @Test
    void aHostWithNoCertificateServesTheSameDomainOverPlainHttp() {
        assertThat(ingress(DOMAIN, BootstrapIngressMode.PUBLIC_HTTP).address())
                .isEqualTo("http://wohlben.eu");
    }

    @Test
    void localModeKeepsTheLoopbackOnlyDoor() {
        assertThat(ingress(Map.of(), BootstrapIngressMode.LOOPBACK).address())
                .isEqualTo("http://localhost:8481");
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
        // A node told to stay private: the volume is not even asked.
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
     * A retry reads the mode back out of the state file, so the operator is not told a different
     * address from the one the retained container listens on. A file written before there were three modes records none,
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
    void theStateFileLivesBesideTheDurableProgressJournal() {
        assertThat(BootstrapIngressLifecycle.stateFile(
                "/root/qits/.qits-bootstrap-progress.json"))
                .isEqualTo(Path.of("/root/qits/.qits-bootstrap-edge.env"));
    }
}
