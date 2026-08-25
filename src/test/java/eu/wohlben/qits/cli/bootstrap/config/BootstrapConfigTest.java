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

    /**
     * <b>The build concurrency is unset by default and COMPUTED, and an operator's value wins.</b>
     * The formula is {@link eu.wohlben.qits.cli.bootstrap.platform.CiConcurrency}'s and is tested
     * there; what is proved here is that {@code QITS_CI_CONCURRENT_BUILDS} reaches this knob at all
     * — the override exists because the number that has to be right is the one on the host.
     */
    @Test
    void ciConcurrentBuildsIsComputedUnlessAnOperatorSaysOtherwise() {
        assertThat(from(Map.of()).ciConcurrentBuilds()).isEmpty();
        assertThat(from(Map.of()).ciConcurrentBuildsEffective()).isGreaterThanOrEqualTo(1);

        BootstrapConfig pinned = from(Map.of("QITS_CI_CONCURRENT_BUILDS", "4"));

        assertThat(pinned.ciConcurrentBuilds()).contains(4);
        assertThat(pinned.ciConcurrentBuildsEffective()).isEqualTo(4);
    }

    @Test
    void defaultsMatchTheScriptsDefaults() {
        BootstrapConfig config = from(Map.of());

        assertThat(config.port()).isEqualTo(8080);
        assertThat(config.registryPort()).isEqualTo(8081);
        // The other two byte doors. 8082 is the pull-through mirror — dockerd's registry-mirrors
        // names it, and every committed Dockerfile spells it in its FROM lines. 8083 is the git
        // host, which needs a door of its own since it stopped riding the registry's.
        assertThat(config.mirrorPort()).isEqualTo(8082);
        assertThat(config.gitHostPort()).isEqualTo(8083);
        // NO POSTGRES PORT KNOB. The platform's postgres publishes nothing: every consumer dials
        // the wire alias on 5432, this CLI included.
        // The edge's MANAGEMENT listener, published nowhere: the challenge slot and the certificate
        // reload live on it, both unauthenticated, and this run reaches them because it is on
        // qits-net. /q is the management root path and lets-encrypt is the extension's own segment.
        assertThat(config.edgeLetsEncryptUrl())
                .isEqualTo("http://qits-platform-edge:9000/q/lets-encrypt");
        // No default, and unset is a supported platform: the edge stays on plain HTTP.
        assertThat(config.domain()).isEmpty();
        // Mandatory WITH a domain and refused without one, so there is nothing to default it to.
        assertThat(config.publicIp()).isEmpty();
        // STAGING by default: the first order is the one most likely to meet a delegation the world
        // has not seen yet, and production counts failed orders per domain per week.
        assertThat(config.acmeMode()).isEqualTo("staging");
        // No default value, because the default is DERIVED: hostmaster@<domain>, the same role the
        // zone's SOA already names.
        assertThat(config.acmeEmail()).isEmpty();
        assertThat(config.dnsHetznerToken()).isEmpty();
        assertThat(config.dnsHetznerSecret()).isEmpty();
        assertThat(config.pushToken()).isEqualTo("local-dev");
        assertThat(config.machineAuth()).isTrue();
        assertThat(config.skipBuild()).isFalse();
        // RESTORE IS THE DEFAULT: a boot deploys each deployable's newest release tag, and
        // shipping the local mains takes saying --ship-mains.
        assertThat(config.shipMains()).isFalse();
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
        env.put("QITS_MIRROR_PORT", "9092");
        env.put("QITS_GIT_HOST_PORT", "9093");
        env.put("QITS_DOMAIN", "qits-dev.eu");
        env.put("QITS_PUBLIC_IP", "203.0.113.7");
        env.put("QITS_ACME_MODE", "production");
        env.put("QITS_ACME_EMAIL", "ops@qits-dev.eu");
        env.put("QITS_PUSH_TOKEN", "not-local-dev");
        env.put("QITS_SKIP_BUILD", "1");
        env.put("QITS_SHIP_MAINS", "1");
        env.put("QITS_MACHINE_AUTH", "0");
        env.put("QITS_DEPLOY_TIMEOUT", "600");
        env.put("QITS_WRAPPER_DIR", "/home/me/code/qits-qits");
        env.put("QITS_SRC", "/tmp/sources");
        env.put("QITS_ENV_NAME", "preprod");
        env.put("QITS_EVENTS_FEED", "0");

        BootstrapConfig config = from(env);

        assertThat(config.port()).isEqualTo(9090);
        assertThat(config.registryPort()).isEqualTo(9091);
        assertThat(config.mirrorPort()).isEqualTo(9092);
        assertThat(config.gitHostPort()).isEqualTo(9093);
        assertThat(config.domain()).contains("qits-dev.eu");
        assertThat(config.publicIp()).contains("203.0.113.7");
        assertThat(config.acmeMode()).isEqualTo("production");
        assertThat(config.acmeEmail()).contains("ops@qits-dev.eu");
        assertThat(config.pushToken()).isEqualTo("not-local-dev");
        // The script's knobs are 1 and 0, not true and false.
        assertThat(config.skipBuild()).isTrue();
        assertThat(config.shipMains()).isTrue();
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

        // THE BYTE PLANE'S THREE ADDRESSES, and two of the three carry the environment name: the
        // store and the git host are environment services since the split, the cache is not.
        assertThat(config.artifactsUrl())
                .isEqualTo("http://preprod-qits-artifacts:8080/artifacts");
        assertThat(config.gitHostUrl()).isEqualTo("http://preprod-qits-githost:8080/git");
        // TWO PREFIXES, one service. /git is the wire protocol, which git holds opaque; /githost is
        // the service's own root, and health lives there. A health poll at /git/q is a 404 now.
        assertThat(config.gitHostHealthUrl())
                .isEqualTo("http://preprod-qits-githost:8080/githost");
        // Scheme, host and port with NO path: this service answers under /mirror/q for health and
        // under the registries' own literals for content, so each caller appends what it wants.
        assertThat(config.mirrorUrl()).isEqualTo("http://qits-platform-mirror:8080");
        // Seed services are reached at fixed aliases before deployment endpoints are projected.
        assertThat(config.ciUrl()).isEqualTo("http://preprod-qits-ci:8080/ci");
        // THE TWO THAT DO NOT CARRY THE TIER, and they are the reason both are derived from
        // PlatformModel rather than concatenated here: the deployer and the bus moved to the
        // platform plane on 2026-08-17, and a hardcoded environment prefix would have sent every
        // topology write and every bus health poll of this run to a name nothing answers to.
        assertThat(config.platformDeploymentsUrl())
                .isEqualTo("http://qits-deployments:8080/platform-deployments");
        assertThat(config.eventsUrl()).isEqualTo("http://qits-events:8080/events");
        // The ONE deploy ref, on both planes: platform/main is retired.
        assertThat(config.envBranch()).isEqualTo("environment/preprod");
        // The issuer is a value consumers validate as well as an address this program dials.
        assertThat(config.idpIssuer()).isEqualTo("http://qits-platform-idp:8080/idp");
    }

    /**
     * <b>The authority every browser name of one environment sits under</b>, and the parent of each
     * service's own host: {@code <app>.<env>.<authority>}. Local and hosted are the same shape, one
     * with a port and one without, because a domain's browser names carry no port.
     */
    @Test
    void theEnvironmentAuthorityIsTheParentOfEveryServiceHost() {
        assertThat(from(Map.of("QITS_PORT", "9090", "QITS_ENV_NAME", "dev")).envAuthority())
                .isEqualTo("dev.localhost:9090");

        assertThat(from(Map.of("QITS_PORT", "9090", "QITS_ENV_NAME", "dev",
                "QITS_DOMAIN", "qits-dev.eu")).envAuthority()).isEqualTo("dev.qits-dev.eu");
    }

    /**
     * <b>Where a BROWSER arrives.</b> The passkey binding follows it and cannot be told anything
     * else: a credential registered under an rp id asserts on that host and its children only, and
     * an origin the ceremony was not told is refused.
     * <p>
     * <b>The local door carries the environment's name</b> since each service gained a host of its
     * own. Bare {@code localhost} could parent none of them — it is a public suffix, so a cookie
     * scoped to it is dropped — while {@code dev.localhost} is accepted and reaches
     * {@code ci.dev.localhost}. It is still a secure context, so this platform needs no certificate
     * for passkeys. A raw IP is not one — the browser offers no ceremony there at all — which is why
     * the fallback is a password and not another origin in this list.
     */
    @Test
    void theBrowsersAddressIsTheEnvironmentsOwnNameUntilThereIsADomain() {
        BootstrapConfig plain = from(Map.of("QITS_PORT", "9090", "QITS_ENV_NAME", "dev"));

        assertThat(plain.publicOrigin()).isEqualTo("http://dev.localhost:9090");
        // The rp id is a HOST and carries no port.
        assertThat(plain.webauthnRpId()).isEqualTo("dev.localhost");
        // THE CEREMONY HAPPENS ON THE IDP'S OWN HOST, not on the door, which serves no /idp path.
        // It is a child of the rp id, so the binding is unchanged by the move.
        assertThat(plain.idpOrigin()).isEqualTo("http://idp.dev.localhost:9090");
        assertThat(plain.webauthnOrigins()).isEqualTo("http://idp.dev.localhost:9090");

        // With a domain the door is TLS on the APEX — the name the edge's certificate is issued
        // for — while the environments are its children. The login is idp. of that apex, because
        // the environment label is optional for the default tier.
        BootstrapConfig hosted = from(Map.of("QITS_PORT", "9090", "QITS_ENV_NAME", "dev",
                "QITS_DOMAIN", "qits-dev.eu"));

        assertThat(hosted.publicOrigin()).isEqualTo("https://qits-dev.eu");
        assertThat(hosted.webauthnRpId()).isEqualTo("qits-dev.eu");
        assertThat(hosted.idpOrigin()).isEqualTo("https://idp.qits-dev.eu");
        assertThat(hosted.webauthnOrigins()).isEqualTo("https://idp.qits-dev.eu");
    }

    /**
     * <b>One session covers every service, and the allow-list plus the cookie domain are what do
     * it.</b> The wildcard entry is exactly one extra label on the same port, which the edge and the
     * idp both read that way; the cookie is scoped to the parent both the door and the service hosts
     * share.
     */
    @Test
    void oneSessionReachesEveryServiceHostOfTheEnvironment() {
        BootstrapConfig plain = from(Map.of("QITS_PORT", "9090", "QITS_ENV_NAME", "dev"));

        assertThat(plain.browserSsoHosts()).isEqualTo("dev.localhost:9090,*.dev.localhost:9090");
        // A cookie domain is a host: no port, and no leading dot.
        assertThat(plain.browserSsoCookieDomain()).isEqualTo("dev.localhost");
        // The idp's host needs no entry of its own: the wildcard is one extra label, and that is
        // exactly what idp. of the authority is.
        assertThat(plain.browserSsoHosts().split(","))
                .contains("*." + plain.envAuthority());

        // With a domain the apex leads the list, because that is where the ceremony happens — and
        // there are FOUR shapes, because the environment label is optional for the default tier:
        // ci.qits-dev.eu and ci.dev.qits-dev.eu are the same host, so both wildcards are named.
        BootstrapConfig hosted = from(Map.of("QITS_PORT", "9090", "QITS_ENV_NAME", "dev",
                "QITS_DOMAIN", "qits-dev.eu"));

        assertThat(hosted.browserSsoHosts()).isEqualTo(
                "qits-dev.eu,dev.qits-dev.eu,*.qits-dev.eu,*.dev.qits-dev.eu");
        assertThat(hosted.browserSsoCookieDomain()).isEqualTo("qits-dev.eu");
        // idp.qits-dev.eu is admitted by *.qits-dev.eu, so the login host is on the list already.
        assertThat(hosted.browserSsoHosts().split(",")).contains("*.qits-dev.eu");
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
                .shipMains(Boolean.TRUE)
                .tui(Boolean.FALSE);

        assertThat(effective.wrapperDir()).contains("/from/the/command/line");
        assertThat(effective.skipBuild()).isTrue();
        assertThat(effective.shipMains()).isTrue();
        assertThat(effective.tui()).isFalse();
        // Everything not answered on the command line still comes from the file.
        assertThat(effective.port()).isEqualTo(8080);
    }

    @Test
    void anOverrideThatWasNotGivenChangesNothing() {
        BootstrapConfig base = from(Map.of("QITS_WRAPPER_DIR", "/from/env"));

        BootstrapConfig effective = new OverridableConfig(base).wrapperDir(null).skipBuild(null)
                .shipMains(null);

        assertThat(effective.wrapperDir()).contains("/from/env");
        assertThat(effective.skipBuild()).isFalse();
        // Not given on the command line and not in the file: the boot restores.
        assertThat(effective.shipMains()).isFalse();
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
