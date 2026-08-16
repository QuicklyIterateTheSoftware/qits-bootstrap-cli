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

    @Test
    void publicSeedBuildsUseTheNormalTlsDoor() {
        Boot boot = new Boot(TestConfig.from(Map.of(
                "QITS_DOMAIN", "wohlben.eu",
                "QITS_BOOTSTRAP_INGRESS_PUBLIC", "true")),
                new RunLog(temp.resolve("run.log")));
        boot.state.bootstrapIngressPassword = "run-secret";

        assertThat(new BootstrapIngressLifecycle(boot).mavenRepositoryUrl())
                .isEqualTo("https://bootstrap:run-secret@wohlben.eu/artifacts/maven/maven");
    }

    @Test
    void localModeKeepsTheLoopbackOnlyDoor() {
        Boot boot = new Boot(TestConfig.from(Map.of()), new RunLog(temp.resolve("run.log")));
        boot.state.bootstrapIngressPassword = "run-secret";

        assertThat(new BootstrapIngressLifecycle(boot).mavenRepositoryUrl())
                .isEqualTo("http://bootstrap:run-secret@localhost:8481/artifacts/maven/maven");
    }
}
