package eu.wohlben.qits.cli.bootstrap.phases;

import eu.wohlben.qits.cli.bootstrap.api.BootstrapPublishCredential;
import eu.wohlben.qits.cli.bootstrap.api.Http;
import eu.wohlben.qits.cli.bootstrap.api.IdpApi;
import eu.wohlben.qits.cli.bootstrap.config.TestConfig;
import eu.wohlben.qits.cli.bootstrap.engine.Phase;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseEngine;
import eu.wohlben.qits.cli.bootstrap.engine.PhaseOutcome;
import eu.wohlben.qits.cli.bootstrap.engine.RunResult;
import eu.wohlben.qits.cli.bootstrap.ui.Ui;
import eu.wohlben.qits.cli.bootstrap.proc.RunLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>How long the bootstrap's publishing identity lives, on the two paths a boot takes.</b>
 * <p>
 * Only CI may publish to qits-artifacts (user ruling 2026-09-13). The bootstrap holds the one
 * exception, and nothing at the idp bounds it: what bounds it is this program handing the
 * credential back. The good path is a phase of its own, straight after the last publish; the other
 * path is the one that matters, because a publish that FAILED ends the run before that phase is
 * reached, and a credential nobody can see is exactly the failure the ruling exists to prevent.
 */
class PublishCredentialLifetimeTest {

    @TempDir
    Path temp;

    /** A run that failed in a publish still hands the credential back on its way out. */
    @Test
    void aRunThatDiedInAPublishHandsTheCredentialBackAnyway() {
        FakeIdp idp = new FakeIdp();
        Boot boot = boot(idp);
        List<Phase> phases = List.of(
                new Phase("publish-something", "publish", ctx -> {
                    throw new IllegalStateException("the store refused the upload");
                }),
                new Phase("publish-credential-release", "hand it back",
                        ctx -> boot.releasePublishCredential()));

        RunResult result = boot.runPhases(new PhaseEngine(new SilentUi()), phases);

        // The run ended at the failure — the hand-back phase never ran — and the credential went
        // back anyway.
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.outcomes()).hasSize(1);
        assertThat(idp.deleted).containsExactly("dyn-bootstrap-publish-prod-qits-boot-abc");
    }

    /** And on the ordinary path the phase does it, with nothing left for the finally to do. */
    @Test
    void onAGoodRunThePhaseHandsItBackAndTheFinallyFindsNothing() {
        FakeIdp idp = new FakeIdp();
        Boot boot = boot(idp);
        List<Phase> phases = List.of(new Phase("publish-credential-release", "hand it back",
                ctx -> assertThat(boot.releasePublishCredential()).isNotEmpty()));

        assertThat(boot.runPhases(new PhaseEngine(new SilentUi()), phases).exitCode())
                .isZero();

        assertThat(idp.deleted).containsExactly("dyn-bootstrap-publish-prod-qits-boot-abc");
    }

    /** A run that commissioned nothing has nothing to hand back, and says nothing about it. */
    @Test
    void aRunThatPublishedNothingLeavesNothingBehind() {
        FakeIdp idp = new FakeIdp();
        Boot boot = new Boot(TestConfig.from(Map.of()), new RunLog(temp.resolve("run.log")));

        assertThat(boot.runPhases(new PhaseEngine(new SilentUi()),
                List.of(new Phase("nothing", "nothing", ctx -> {
                }))).exitCode()).isZero();

        assertThat(boot.releasePublishCredential()).isEmpty();
        assertThat(idp.deleted).isEmpty();
    }

    // --- never a bare publish (qits-889) --------------------------------------------------------

    /** A boot whose idp is the fake above, dialled for real through {@code publishAuthorization}. */
    private Boot dialling(FakeIdp idp, Map<String, String> env) {
        Boot boot = new Boot(TestConfig.from(env), new RunLog(temp.resolve("run.log")),
                new eu.wohlben.qits.cli.bootstrap.proc.ProcessRunner(
                        new RunLog(temp.resolve("runner.log"))), idp);
        boot.state.bootstrapClientId = "prod-qits-bootstrap";
        boot.state.bootstrapSecret = "boot";
        return boot;
    }

    /**
     * <b>The machine gate being off changes nothing</b>: the credential is commissioned and
     * presented all the same. The store with its gate off ignores it; the bootstrap does not know
     * how to publish without one.
     */
    @Test
    void withTheGateOffTheCredentialIsStillCommissionedAndPresented() {
        FakeIdp idp = new FakeIdp();
        Boot boot = dialling(idp, Map.of("QITS_MACHINE_AUTH", "0"));
        CiLogStreamTest.Recorder ctx = new CiLogStreamTest.Recorder();

        assertThat(boot.publishAuthorization(ctx)).isEqualTo("Bearer minted");
        assertThat(boot.publishPair(ctx).clientId())
                .isEqualTo("dyn-bootstrap-publish-prod-qits-boot-abc");
        assertThat(idp.commissioned).isEqualTo(1);
        assertThat(ctx.logs).anyMatch(line -> line.contains("machine gate is off")
                && line.contains("presented all the same"));
        // And it is handed back like any other.
        assertThat(boot.releasePublishCredential()).contains("handed back");
    }

    /** No recorded bootstrap secret is no credential, and the phase stops saying so. */
    @Test
    void noBootstrapSecretFailsThePublishRatherThanPublishingBare() {
        for (String gate : List.of("0", "1")) {
            FakeIdp idp = new FakeIdp();
            Boot boot = dialling(idp, Map.of("QITS_MACHINE_AUTH", gate));
            boot.state.bootstrapSecret = null;

            assertThatThrownBy(() -> boot.publishAuthorization(new CiLogStreamTest.Recorder()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no publishing credential")
                    .hasMessageContaining("never publishes without one");
            assertThatThrownBy(() -> boot.publishPair(new CiLogStreamTest.Recorder()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(idp.commissioned).isZero();
        }
    }

    /** No idp answering is no credential either — whatever the gate says. */
    @Test
    void noIdpFailsThePublishRatherThanPublishingBare() {
        for (String gate : List.of("0", "1")) {
            FakeIdp idp = new FakeIdp();
            idp.healthy = false;
            Boot boot = dialling(idp, Map.of("QITS_MACHINE_AUTH", gate));

            assertThatThrownBy(() -> boot.publishAuthorization(new CiLogStreamTest.Recorder()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is not answering")
                    .hasMessageContaining("never publishes without one");
            assertThat(idp.commissioned).isZero();
        }
    }

    /** A commission the idp refuses stops the phase with the idp's answer. */
    @Test
    void aRefusedCommissionFailsThePublish() {
        FakeIdp idp = new FakeIdp();
        idp.commission = 403;
        Boot boot = dialling(idp, Map.of());

        assertThatThrownBy(() -> boot.publishAuthorization(new CiLogStreamTest.Recorder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("commissioned no publishing credential");
    }

    /** A boot holding a credential that has already minted a bearer — as a publish leaves it. */
    private Boot boot(FakeIdp idp) {
        Boot boot = new Boot(TestConfig.from(Map.of()), new RunLog(temp.resolve("run.log")));
        BootstrapPublishCredential credential = new BootstrapPublishCredential(idp,
                new IdpApi(idp, FakeIdp.ISSUER), FakeIdp.ISSUER, "prod-qits-bootstrap", "boot",
                "prod-qits-bootstrap", Boot.PLATFORM_AUDIENCE);
        credential.bearer();
        boot.publishCredential(credential);
        return boot;
    }

    /** The display is not what this test is about, so it is fed and says nothing. */
    private static final class SilentUi implements Ui {

        @Override
        public void started(List<Phase> phases) {
        }

        @Override
        public void phaseStarted(int index, Phase phase) {
        }

        @Override
        public void output(String line) {
        }

        @Override
        public void status(String status) {
        }

        @Override
        public void phaseFinished(PhaseOutcome outcome) {
        }

        @Override
        public void finished(RunResult result) {
        }

        @Override
        public void message(String line) {
        }

        @Override
        public void event(String line) {
        }

        @Override
        public void close() {
        }
    }

    /** The idp as this test needs it: it commissions, it mints, and it remembers what came back. */
    private static final class FakeIdp extends Http {

        private static final String ISSUER = "http://qits-idp:8080/idp";

        private final List<String> deleted = new ArrayList<>();
        private boolean healthy = true;
        private int commission = 201;
        private int commissioned;

        @Override
        public Response get(String url, Map<String, String> headers) {
            return url.endsWith("/q/health/ready") && healthy ? new Response(200, "")
                    : new Response(503, "down");
        }

        @Override
        public Response postJson(String url, String json, Map<String, String> headers) {
            if (commission != 201) {
                return new Response(commission, "refused");
            }
            commissioned++;
            return new Response(201, "{\"clientId\":\"dyn-bootstrap-publish-prod-qits-boot-abc\","
                    + "\"secret\":\"issued\"}");
        }

        @Override
        public Response postForm(String url, String user, String password,
                                 Map<String, String> form) {
            return new Response(200, "{\"access_token\":\"minted\",\"expires_in\":3600}");
        }

        @Override
        public Response delete(String url, Map<String, String> headers) {
            deleted.add(url.substring(url.lastIndexOf('/') + 1));
            return new Response(204, "");
        }
    }
}
