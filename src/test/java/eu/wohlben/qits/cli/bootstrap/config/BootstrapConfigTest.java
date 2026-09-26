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
                .isEqualTo("http://prod-qits-edge:9000/q/lets-encrypt");
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

        // THE BYTE PLANE'S THREE ADDRESSES, and all three carry the environment name now. The store
        // and the git host have since the split; the cache is still a PLATFORM service — one
        // instance for the estate — and its address gained the qualifier anyway, which is the
        // platform-service retirement seen from the address side.
        assertThat(config.artifactsUrl())
                .isEqualTo("http://preprod-qits-artifacts:8080/artifacts");
        assertThat(config.gitHostUrl()).isEqualTo("http://preprod-qits-githost:8080/git");
        // TWO PREFIXES, one service. /git is the wire protocol, which git holds opaque; /githost is
        // the service's own root, and health lives there. A health poll at /git/q is a 404 now.
        assertThat(config.gitHostHealthUrl())
                .isEqualTo("http://preprod-qits-githost:8080/githost");
        // Scheme, host and port with NO path: this service answers under /mirror/q for health and
        // under the registries' own literals for content, so each caller appends what it wants.
        assertThat(config.mirrorUrl()).isEqualTo("http://preprod-qits-mirror:8080");
        // Seed services are reached at fixed aliases before deployment endpoints are projected.
        assertThat(config.ciUrl()).isEqualTo("http://preprod-qits-ci:8080/ci");
        // AND EVERY ADDRESS CARRIES THE TIER NOW, the platform plane's included. These four were
        // the exceptions until the platform-service concept began to be retired: the deployer, the
        // bus and the store moved to the platform plane and answered to a bare name, and the cache
        // never left it. qits-deployments gives a platform service the <env>-<app> alias BESIDE
        // its bare name and recreates a live service that lacks it, so both spellings resolve —
        // which is what lets these four move without a flag day.
        //
        // They stay DERIVED for the reason they always were, and the reason cuts both ways now: a
        // concatenation here could not follow PLATFORM_SERVICES when an application moved plane,
        // and it cannot follow this either. dialAlias is the one place the qualifier is decided.
        assertThat(config.platformDeploymentsUrl())
                .isEqualTo("http://preprod-qits-deployments:8080/deployments");
        assertThat(config.eventsUrl()).isEqualTo("http://preprod-qits-events:8080/events");
        // The configuration store is the third, since 2026-09-07 — and it was the one whose
        // concatenation cost the most: this exact string is also handed to the deployer as
        // QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL, which REFUSES a deployment it cannot resolve
        // rather than falling back to the file. Still no path, because that reader appends its own.
        assertThat(config.configurationUrl()).isEqualTo("http://preprod-qits-configuration:8080");
        // THE IDP IS TWO VALUES, AND THIS PAIR IS THE WHOLE REASON THEY SPLIT. The address is
        // qualified like every other; the ISSUER is not, because it is not an address — it is the
        // `iss` claim every consumer compares for equality against the issuer it discovered. Both
        // names resolving on qits-net is what makes every other move above safe, and it buys a
        // string comparison nothing. Moving the issuer rejects every token in flight across the
        // estate at once, so it moves in a step of its own, after every consumer is discovering
        // from the qualified address.
        assertThat(config.idpDialUrl()).isEqualTo("http://preprod-qits-idp:8080/idp");
        assertThat(config.idpIssuer()).isEqualTo("http://qits-platform-idp:8080/idp");
    }

    /**
     * <b>Names are read right to left, {@code <app>[.<env>].<project>.<domain>}</b>, every label
     * inside the one to its right — so the parent of each application host is the INNERMOST DOOR of
     * this platform's own project, {@code <env>.qits.<domain>}. The project label is mandatory:
     * there is no unqualified application tier and no top-level {@code <env>.<domain>} tier, and
     * the platform is simply the project called {@code qits}. Local and hosted are the same shape,
     * one with a port and one without, because a domain's browser names carry no port.
     * <p>
     * The environment label is here because the {@code qits} project supports environments today;
     * this is the one method that spells it, so it is the one method that moves when the flag does.
     */
    @Test
    void theInnermostDoorOfThePlatformProjectIsTheParentOfEveryServiceHost() {
        BootstrapConfig plain = from(Map.of("QITS_PORT", "9090", "QITS_ENV_NAME", "dev"));
        assertThat(plain.domainAuthority()).isEqualTo("localhost:9090");
        assertThat(plain.projectAuthority()).isEqualTo("qits.localhost:9090");
        assertThat(plain.envAuthority()).isEqualTo("dev.qits.localhost:9090");

        BootstrapConfig hosted = from(Map.of("QITS_PORT", "9090", "QITS_ENV_NAME", "dev",
                "QITS_DOMAIN", "qits-dev.eu"));
        assertThat(hosted.domainAuthority()).isEqualTo("qits-dev.eu");
        assertThat(hosted.projectAuthority()).isEqualTo("qits.qits-dev.eu");
        assertThat(hosted.envAuthority()).isEqualTo("dev.qits.qits-dev.eu");
    }

    /**
     * <b>Where a BROWSER arrives.</b> The passkey binding follows it and cannot be told anything
     * else: a credential registered under an rp id asserts on that host and its children only, and
     * an origin the ceremony was not told is refused.
     * <p>
     * <b>The local door is the BARE APEX, and that is load-bearing rather than a leftover.</b> With
     * ACME off the edge has no stated domain of its own and takes one from this value's authority,
     * so {@code http://qits.localhost:9090} here would make {@code qits.localhost} the domain and
     * every name would be read one tier out. The names under it still carry the project label —
     * {@code ci.dev.qits.localhost:9090} — and the cookie and the passkey binding hang off
     * {@code qits.localhost}, which is a parent bare {@code localhost} could never be: that name is
     * a public suffix. Every {@code *.localhost} name is still a secure context, so this platform
     * needs no certificate for passkeys; a raw IP is not one, which is why the fallback there is a
     * password rather than another origin in this list.
     */
    @Test
    void theBrowsersDoorIsTheProjectDoorUntilThereIsNoDomainToHangItOn() {
        BootstrapConfig plain = from(Map.of("QITS_PORT", "9090", "QITS_ENV_NAME", "dev"));

        assertThat(plain.publicOrigin()).isEqualTo("http://localhost:9090");
        // The rp id is a HOST and carries no port. Locally it is the PROJECT's door, which parents
        // the login host either way the supportsEnvironments flag stands.
        assertThat(plain.webauthnRpId()).isEqualTo("qits.localhost");
        // THE CEREMONY HAPPENS ON THE IDP'S OWN HOST, not on a door, which serves no /idp path.
        // It is a child of the rp id, so the binding holds.
        assertThat(plain.idpOrigin()).isEqualTo("http://idp.dev.qits.localhost:9090");

        // With a domain the door is TLS on THIS PLATFORM'S PROJECT DOOR. It was the apex, and the
        // apex is not a name any more: every application address carries a project label, so an
        // edge pointed at the apex composes nothing and answers an honest 404 instead of a
        // redirect to a name that 404s one hop later.
        BootstrapConfig hosted = from(Map.of("QITS_PORT", "9090", "QITS_ENV_NAME", "dev",
                "QITS_DOMAIN", "qits-dev.eu"));

        assertThat(hosted.publicOrigin()).isEqualTo("https://qits.qits-dev.eu");
        // AND THE RP ID DOES NOT MOVE WITH IT. It is the bare apex, a credential asserts on the rp
        // id and its children, so idp.dev.qits.qits-dev.eu is covered exactly as idp.qits-dev.eu
        // was — and changing it would invalidate every passkey this platform ever registered.
        assertThat(hosted.webauthnRpId()).isEqualTo("qits-dev.eu");
        assertThat(hosted.idpOrigin()).isEqualTo("https://idp.dev.qits.qits-dev.eu");
    }

    /**
     * <b>The parent a session cookie is shared with, which is the one session value this program
     * still answers.</b> The return-host allow-list used to be derived beside it and rendered into
     * the idp's and the edge's configuration; the two copies drifted apart and sign-in broke, so
     * the allow-list is the services' own business now, derived from the one {@code QITS_DOMAIN}
     * the deployer propagates. What survives here is what the CLOSING REPORT says to a person.
     */
    @Test
    void theSessionCookiesParentIsTheProjectDoorUntilThereIsADomain() {
        BootstrapConfig plain = from(Map.of("QITS_PORT", "9090", "QITS_ENV_NAME", "dev"));

        // A cookie domain is a host: no port, and no leading dot. Locally it is the PROJECT's
        // door, because bare `localhost` is a public suffix and a cookie scoped to it is dropped.
        assertThat(plain.browserSsoCookieDomain()).isEqualTo("qits.localhost");

        BootstrapConfig hosted = from(Map.of("QITS_PORT", "9090", "QITS_ENV_NAME", "dev",
                "QITS_DOMAIN", "qits-dev.eu"));

        // With a domain it is the domain, which parents every project's names and not only this
        // platform's.
        assertThat(hosted.browserSsoCookieDomain()).isEqualTo("qits-dev.eu");
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
     * {@code --platform-env} names the standing environment, and with it every address the run
     * dials. There is no deploy ref beside it any more — {@code envBranch()} is gone with the
     * branch nothing listens to — so what an override has to move is the wire aliases.
     */
    @Test
    void platformEnvNamesTheEnvironmentAndEveryAddressDerivedFromIt() {
        BootstrapConfig base = from(Map.of());

        BootstrapConfig effective = new OverridableConfig(base).platformEnv("staging");

        assertThat(effective.envName()).isEqualTo("staging");
        assertThat(effective.ciUrl()).isEqualTo("http://staging-qits-ci:8080/ci");
        assertThat(effective.artifactsUrl())
                .isEqualTo("http://staging-qits-artifacts:8080/artifacts");
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
