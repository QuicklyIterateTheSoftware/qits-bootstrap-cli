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
        // 5433, not 5432: a postgres already installed on the workstation must not be a bind
        // conflict this program has to explain.
        assertThat(config.pgPort()).isEqualTo(5433);
        // 53, because a registrar's delegation reaches that port and no other. Both transports are
        // published from it; the service binds 8053 inside the container.
        assertThat(config.dnsPort()).isEqualTo(53);
        // No default, and unset is a supported platform: dns serves no zones and the edge stays on
        // plain HTTP.
        assertThat(config.domain()).isEmpty();
        assertThat(config.pushToken()).isEqualTo("local-dev");
        assertThat(config.machineAuth()).isTrue();
        assertThat(config.skipBuild()).isFalse();
        // The platform's own events are followed by default: free when nothing answers, and for
        // most of a bootstrap nothing does.
        assertThat(config.eventsFeed()).isTrue();
        assertThat(config.deployTimeout()).isEqualTo(Duration.ofHours(1));
        assertThat(config.envName()).isEqualTo("prod");
        assertThat(config.orgUrl()).isEqualTo("https://github.com/QuicklyIterateTheSoftware");
        // No default: unset means "find it by walking up from here" (WrapperDir), not ".".
        assertThat(config.wrapperDir()).isEmpty();
    }

    @Test
    void everyKnobIsSetByItsEnvironmentName() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("QITS_PORT", "9090");
        env.put("QITS_REGISTRY_PORT", "9091");
        env.put("QITS_PG_PORT", "5555");
        env.put("QITS_DNS_PORT", "5353");
        env.put("QITS_DOMAIN", "qits-dev.eu");
        env.put("QITS_PUSH_TOKEN", "not-local-dev");
        env.put("QITS_SKIP_BUILD", "1");
        env.put("QITS_MACHINE_AUTH", "0");
        env.put("QITS_DEPLOY_TIMEOUT", "600");
        env.put("QITS_WRAPPER_DIR", "/home/me/code/qits-qits");
        env.put("QITS_SRC", "/tmp/sources");
        env.put("QITS_ENV_NAME", "preprod");
        env.put("QITS_EVENTS_FEED", "0");

        BootstrapConfig config = from(env);

        assertThat(config.port()).isEqualTo(9090);
        assertThat(config.registryPort()).isEqualTo(9091);
        assertThat(config.pgPort()).isEqualTo(5555);
        assertThat(config.dnsPort()).isEqualTo(5353);
        assertThat(config.domain()).contains("qits-dev.eu");
        assertThat(config.pushToken()).isEqualTo("not-local-dev");
        // The script's knobs are 1 and 0, not true and false.
        assertThat(config.skipBuild()).isTrue();
        assertThat(config.machineAuth()).isFalse();
        assertThat(config.eventsFeed()).isFalse();
        assertThat(config.deployTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(config.wrapperDir()).contains("/home/me/code/qits-qits");
        assertThat(config.src()).isEqualTo("/tmp/sources");
        assertThat(config.envName()).isEqualTo("preprod");
    }

    /**
     * <b>The derived addresses are wire aliases and no longer follow the ports.</b> This CLI runs
     * as a container on qits-net, so it dials what every other member dials; the published ports
     * stay configurable because the generated files and a person's browser still use them.
     */
    @Test
    void theDerivedAddressesAreWireAliasesWhateverThePortsAre() {
        BootstrapConfig config = from(Map.of("QITS_PORT", "9090", "QITS_REGISTRY_PORT", "9091",
                "QITS_ENV_NAME", "preprod"));

        assertThat(config.artifactsUrl()).isEqualTo("http://qits-platform-artifacts:8080/artifacts");
        // Through the EDGE, not straight at prod-qits-ci: the edge and the gateway's route table
        // are the path this run has to keep exercising.
        assertThat(config.ciUrl()).isEqualTo("http://qits-platform-edge:8080/ci");
        assertThat(config.platformDeploymentsUrl())
                .isEqualTo("http://qits-platform-edge:8080/platform-deployments");
        // The bus, through the edge like the two above. It is a seed service now, so the boot waits
        // for it — and it serves everything under one segment, health included.
        assertThat(config.eventsUrl()).isEqualTo("http://qits-platform-edge:8080/events");
        // The ONE deploy ref, on both planes: platform/main is retired.
        assertThat(config.envBranch()).isEqualTo("environment/preprod");
        // The issuer is a value consumers validate as well as an address this program dials.
        assertThat(config.idpIssuer()).isEqualTo("http://qits-platform-idp:8080/idp");
        // The one address NOT behind the edge: there is no gateway route to the nameserver's API and
        // there must not be one, so it is dialled at its own alias. /dns is the service's own
        // non-application root path, which is where health and the zones both hang.
        assertThat(config.dnsUrl()).isEqualTo("http://qits-platform-dns:8080/dns");
    }

    @Test
    void domainIsAnsweredOnTheCommandLineAndABlankOneIsNotAnAnswer() {
        BootstrapConfig base = from(Map.of("QITS_DOMAIN", "from-env.eu"));

        assertThat(new OverridableConfig(base).domain("from-argv.eu").domain())
                .contains("from-argv.eu");
        assertThat(new OverridableConfig(base).domain(null).domain()).contains("from-env.eu");
        assertThat(new OverridableConfig(base).domain("  ").domain()).contains("from-env.eu");
        // Unset stays unset: there is no default domain to fall back on.
        assertThat(new OverridableConfig(from(Map.of())).domain(null).domain()).isEmpty();
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

    /**
     * {@code --platform-env} names the standing environment, and with it the deploy ref every
     * push in the boot goes to. The two move together by construction — {@code envBranch()} is
     * derived rather than configured — and this is what pins that they still do through an
     * override.
     */
    @Test
    void platformEnvNamesTheEnvironmentAndItsDeployRef() {
        BootstrapConfig base = from(Map.of());

        BootstrapConfig effective = new OverridableConfig(base).platformEnv("staging");

        assertThat(effective.envName()).isEqualTo("staging");
        assertThat(effective.envBranch()).isEqualTo("environment/staging");
    }

    @Test
    void platformEnvOutranksTheEnvFileAndABlankOneDoesNot() {
        BootstrapConfig base = from(Map.of("QITS_ENV_NAME", "from-env"));

        assertThat(new OverridableConfig(base).platformEnv("from-argv").envName())
                .isEqualTo("from-argv");
        // picocli hands an absent option through as null, and a shell wrapper can pass "" for one
        // it did not receive. Neither is an answer.
        assertThat(new OverridableConfig(base).platformEnv(null).envName()).isEqualTo("from-env");
        assertThat(new OverridableConfig(base).platformEnv("  ").envName()).isEqualTo("from-env");
    }
}
