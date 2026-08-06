package eu.wohlben.qits.cli.bootstrap.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The knobs arrive the way the script's did: as environment names, from a {@code .env} file in the
 * working directory or from the real environment. Quarkus reads that file as an environment
 * source, so this proves the same conversion the running program relies on.
 */
class BootstrapConfigTest {

    private static BootstrapConfig from(Map<String, String> env) {
        return TestConfig.from(env);
    }

    @Test
    void defaultsMatchTheScriptsDefaults() {
        BootstrapConfig config = from(Map.of());

        assertThat(config.port()).isEqualTo(8080);
        assertThat(config.registryPort()).isEqualTo(8081);
        assertThat(config.pushToken()).isEqualTo("local-dev");
        assertThat(config.machineAuth()).isTrue();
        assertThat(config.skipBuild()).isFalse();
        assertThat(config.deployTimeout()).isEqualTo(Duration.ofHours(1));
        assertThat(config.envName()).isEqualTo("dev");
        assertThat(config.orgUrl()).isEqualTo("https://github.com/QuicklyIterateTheSoftware");
        // No default: unset means "find it by walking up from here" (WrapperDir), not ".".
        assertThat(config.wrapperDir()).isEmpty();
    }

    @Test
    void everyKnobIsSetByItsEnvironmentName() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("QITS_PORT", "9090");
        env.put("QITS_REGISTRY_PORT", "9091");
        env.put("QITS_PUSH_TOKEN", "not-local-dev");
        env.put("QITS_SKIP_BUILD", "1");
        env.put("QITS_MACHINE_AUTH", "0");
        env.put("QITS_DEPLOY_TIMEOUT", "600");
        env.put("QITS_WRAPPER_DIR", "/home/me/code/qits-qits");
        env.put("QITS_SRC", "/tmp/sources");
        env.put("QITS_ENV_NAME", "preprod");

        BootstrapConfig config = from(env);

        assertThat(config.port()).isEqualTo(9090);
        assertThat(config.registryPort()).isEqualTo(9091);
        assertThat(config.pushToken()).isEqualTo("not-local-dev");
        // The script's knobs are 1 and 0, not true and false.
        assertThat(config.skipBuild()).isTrue();
        assertThat(config.machineAuth()).isFalse();
        assertThat(config.deployTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(config.wrapperDir()).contains("/home/me/code/qits-qits");
        assertThat(config.src()).isEqualTo("/tmp/sources");
        assertThat(config.envName()).isEqualTo("preprod");
    }

    @Test
    void theDerivedAddressesFollowThePorts() {
        BootstrapConfig config = from(Map.of("QITS_PORT", "9090", "QITS_REGISTRY_PORT", "9091",
                "QITS_ENV_NAME", "preprod"));

        assertThat(config.artifactsUrl()).isEqualTo("http://127.0.0.1:9091/artifacts");
        assertThat(config.ciUrl()).isEqualTo("http://127.0.0.1:9090/ci");
        assertThat(config.platformDeploymentsUrl())
                .isEqualTo("http://127.0.0.1:9090/platform-deployments");
        assertThat(config.envBranch()).isEqualTo("environment/preprod");
        // One scope on the platform plane, so one branch, and it is not the tier's.
        assertThat(config.platformBranch()).isEqualTo("platform/main");
        // The issuer is a value consumers validate, not an address this program dials.
        assertThat(config.idpIssuer()).isEqualTo("http://qits-idp:8080/idp");
    }

    @Test
    void commandLineAnswersWinOverTheFile() {
        BootstrapConfig base = from(Map.of("QITS_WRAPPER_DIR", "/from/env", "QITS_SKIP_BUILD", "0"));

        BootstrapConfig effective = new OverridableConfig(base)
                .wrapperDir("/from/the/command/line")
                .skipBuild(Boolean.TRUE)
                .tui(Boolean.FALSE);

        assertThat(effective.wrapperDir()).contains("/from/the/command/line");
        assertThat(effective.skipBuild()).isTrue();
        assertThat(effective.tui()).isFalse();
        // Everything not answered on the command line still comes from the file.
        assertThat(effective.port()).isEqualTo(8080);
    }

    @Test
    void anOverrideThatWasNotGivenChangesNothing() {
        BootstrapConfig base = from(Map.of("QITS_WRAPPER_DIR", "/from/env"));

        BootstrapConfig effective = new OverridableConfig(base).wrapperDir(null).skipBuild(null);

        assertThat(effective.wrapperDir()).contains("/from/env");
        assertThat(effective.skipBuild()).isFalse();
        assertThat(effective.tui()).isTrue();
    }
}
