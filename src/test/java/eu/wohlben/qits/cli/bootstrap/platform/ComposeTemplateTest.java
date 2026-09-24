package eu.wohlben.qits.cli.bootstrap.platform;

import eu.wohlben.qits.cli.bootstrap.phases.SeedPhases;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>What is left here is what a golden file cannot say.</b> The extras a deployment starts with are
 * content, and one application's block is now checked in whole under
 * {@code src/test/resources/compose-golden/extras} — see {@link ComposeTemplateGoldenTest}, and its
 * README for the arguments the folded tests carried. Three kinds of question stayed:
 * <ul>
 *   <li><b>The two files agree.</b> A deployer setting is spelled on the seed stack AND in the
 *       extras on purpose, because the update argv {@code --env-rm}s what the extras do not state.
 *       A golden of one file cannot say the other file matches it.
 *   <li><b>A second rendering.</b> A domain, a second environment name, a two-build host — the
 *       goldens are one platform, and everything that has to change with the tokens is proved by
 *       rendering twice and comparing.
 *   <li><b>A question asked across every block at once.</b> Which applications publish a port, hold
 *       the socket, pin a store, claim a vhost. A golden proves one block; only a sweep can say
 *       that no OTHER block answers yes.
 * </ul>
 * {@link ExtrasWiringGuardTest} is the fourth kind and lives on its own: not what a block holds, but
 * what a block is allowed to hold at all.
 */
class ComposeTemplateTest {

    private static final String ENV = "prod";
    private static final String DOMAIN = "qits-dev.eu";

    static Map<String, String> tokens() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ENV_NAME", ENV);
        // The same derivation SeedPhases.tokens fills these from, and it is asked here rather than
        // restated: an alias or a client-id key spelled twice is a plane move that lands in the
        // generated file and not in the test that guards it.
        values.putAll(PlatformModel.modelTokens(ENV));
        values.put("COMPOSE_FILE", "docker-compose.qits.yml");
        values.put("PORT", "8080");
        values.put("REGISTRY_PORT", "8081");
        values.put("MIRROR_PORT", "8082");
        values.put("GIT_HOST_PORT", "8083");
        values.put("PG_PORT", "5433");
        values.put("PG_SUPERUSER_PASSWORD", "0123456789abcdef");
        values.put("PG_DEPLOYMENTS_PASSWORD", "fedcba9876543210");
        values.put("PG_DEPLOYMENTS_EVENTSTREAM_PASSWORD", "1111222233334444");
        values.put("PG_CI_PASSWORD", "aaaabbbbccccdddd");
        values.put("PG_CI_EVENTSTREAM_PASSWORD", "eeeeffff00001111");
        values.put("PG_PLATFORM_IDP_PASSWORD", "2222333344445555");
        values.put("PG_EVENTS_PASSWORD", "bbbbccccddddeeee");
        values.put("PG_ARTIFACTS_PASSWORD", "cafecafecafecafe");
        values.put("PG_PLATFORM_MIRROR_PASSWORD", "1234123412341234");
        values.put("PG_GITHOST_PASSWORD", "5678567856785678");
        values.put("PG_GITHOST_EVENTSTREAM_PASSWORD", "9abc9abc9abc9abc");
        values.put("PG_PROJECTS_PASSWORD", "7777888899990000");
        values.put("PG_EPICS_PASSWORD", "3333444455556666");
        values.put("PG_PROJECTS_EVENTSTREAM_PASSWORD", "abcdabcdabcdabcd");
        values.put("PG_CONTAINERS_PASSWORD", "def0def0def0def0");
        values.put("PG_CONTAINERS_EVENTSTREAM_PASSWORD", "0f0f0f0f0f0f0f0f");
        // The issuer and the address, which are two values now. IDP is the `iss` claim, still
        // bare because it is compared and not resolved; IDP_DIAL is what every consumer dials.
        values.put("IDP", "http://qits-platform-idp:8080/idp");
        values.put("IDP_DIAL", "http://" + ENV + "-qits-platform-idp:8080/idp");
        values.put("PUSH_TOKEN", "local-dev");
        // A one-build host: the 16 GB VPS the formula exists for.
        values.put("CI_CONCURRENT_BUILDS", "1");
        values.put("MACHINE_REQUIRED", "true");
        values.put("DOCKER_GID", "988");
        values.put("DAEMON_SHA", "abc123");
        // The idp's own seed pair: the one credential this program records itself, and the whole
        // of the identity bootstrap the generated stack still carries.
        values.put("BOOTSTRAP_CLIENT_ID", PlatformModel.bootstrapClientId(ENV));
        values.put("BOOTSTRAP_CLIENT_SECRET", "secret-" + PlatformModel.bootstrapClientId(ENV));
        // And the five seed services' own, keyed by the APPLICATION the way the registry is.
        // There is no id beside them: a client id is the wire alias, and ALIAS_<APP> renders it.
        for (String app : PlatformModel.SEED_IDP_CLIENT_APPS) {
            values.put("IDP_CLIENT_SECRET_" + PlatformModel.clientKey(app),
                    "secret-" + PlatformModel.wireAlias(app, ENV));
        }
        // The browser names of a local platform, in the grammar the edge reads right to left:
        // <app>[.<env>].<project>.<domain>, with `localhost` as the stated domain and `qits` as
        // this platform's own project. The door is the BARE APEX and must be: with ACME off the
        // edge takes the stated domain from this value's authority, so qits.localhost here would
        // make qits.localhost the domain. The rp id is the PROJECT's door — a parent of every name
        // either way the supportsEnvironments flag stands — and every *.localhost name is a secure
        // context by itself, so the ceremony works on the edge's plain HTTP port.
        values.put("WEBAUTHN_RP_ID", "qits.localhost");
        values.put("WEBAUTHN_ORIGINS", "http://idp." + ENV + ".qits.localhost:8080");
        values.put("PUBLIC_ORIGIN", "http://localhost:8080");
        values.put("IDP_ORIGIN", "http://idp." + ENV + ".qits.localhost:8080");
        values.put("BROWSER_HOSTS", "localhost:8080,qits.localhost:8080,*.qits.localhost:8080,*."
                + ENV + ".qits.localhost:8080");
        values.put("SESSION_COOKIE_DOMAIN", "qits.localhost");
        // No domain: every fragment is empty, which is the ordinary platform.
        values.putAll(DomainTokens.of(Optional.empty()));
        return values;
    }

    /** The same values with a domain configured. */
    static Map<String, String> tokens(String domain) {
        Map<String, String> values = tokens();
        // The binding follows the address a browser arrives at, which a domain moves to TLS. The
        // door is this platform's own PROJECT door, qits.<domain>, because the apex carries no
        // project label and an edge pointed at it can compose no application name. The login is
        // idp. of the environment's door inside that project. The RP ID stays the bare apex — a
        // credential asserts on it and its children, and moving it would invalidate every passkey.
        values.put("WEBAUTHN_RP_ID", domain);
        values.put("WEBAUTHN_ORIGINS", "https://idp." + ENV + ".qits." + domain);
        values.put("PUBLIC_ORIGIN", "https://qits." + domain);
        values.put("IDP_ORIGIN", "https://idp." + ENV + ".qits." + domain);
        values.put("BROWSER_HOSTS", "qits." + domain + ",*.qits." + domain
                + ",*." + ENV + ".qits." + domain);
        values.put("SESSION_COOKIE_DOMAIN", domain);
        values.putAll(DomainTokens.of(Optional.of(domain)));
        return values;
    }

    /** The same values again, with names the derived wildcards cannot reach. */
    static Map<String, String> tokens(String domain, List<String> extraSans) {
        Map<String, String> values = tokens(domain);
        values.putAll(DomainTokens.of(Optional.of(domain), "staging", "hostmaster@" + domain,
                "hetzner-token", Optional.empty(), extraSans));
        return values;
    }

    /**
     * One service's own lines, so an assertion about what it does NOT carry means that service.
     * <p>
     * Keyed by the service NAME, which is all a stack file has: {@code container_name} is gone
     * with the move off compose, and the key is the wire alias every peer dials anyway.
     */
    static String serviceBlock(String stack, String service) {
        StringBuilder block = new StringBuilder();
        boolean inside = false;
        for (String line : stack.lines().toList()) {
            if (isServiceKey(line)) {
                if (inside) {
                    break;
                }
                inside = line.equals("  " + service + ":");
            }
            if (inside) {
                block.append(line).append('\n');
            }
        }
        assertThat(block.toString()).as("the block of %s", service).isNotEmpty();
        return block.toString();
    }

    /** Two spaces, a name, a colon and nothing after it. A comment is not one. */
    private static boolean isServiceKey(String line) {
        return line.startsWith("  ") && !line.startsWith("   ") && !line.startsWith("  #")
                && line.endsWith(":");
    }

    /**
     * Every service the stack file starts, and nothing else. The same two-space shape names a
     * network and a volume as well, so the top-level {@code services:} key is what is followed
     * rather than the indentation alone.
     */
    private static List<String> serviceKeys(String stack) {
        List<String> keys = new ArrayList<>();
        boolean inServices = false;
        for (String line : stack.lines().toList()) {
            if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")) {
                inServices = line.equals("services:");
            } else if (inServices && isServiceKey(line)) {
                keys.add(line.strip().substring(0, line.strip().length() - 1));
            }
        }
        return keys;
    }

    private static final String EXTRAS = "qits.platform.deployments.extras.";

    /** Every generated extras key, without the comments that explain them. */
    private static List<String> extrasKeys() {
        return extrasKeys(tokens());
    }

    private static List<String> extrasKeys(Map<String, String> values) {
        return ComposeTemplate.extras(values).lines()
                .filter(line -> line.startsWith(EXTRAS))
                .toList();
    }

    /**
     * The applications whose own generated lines carry a fragment, in name order.
     * <p>
     * This is the shape of question the goldens deliberately do not answer. A golden says what one
     * block holds; only a sweep over every block at once can say WHICH applications hold a thing
     * and, more to the point, that no other one does.
     */
    private static List<String> applicationsWith(String fragment) {
        return extrasKeys().stream()
                .filter(line -> line.contains(fragment))
                .map(line -> line.substring(EXTRAS.length(), line.indexOf('.', EXTRAS.length())))
                .distinct()
                .sorted()
                .toList();
    }

    /** One application's own keys — the trailing dot is what keeps a sibling's out. */
    private static String extras(String application) {
        return extras(application, tokens());
    }

    private static String extras(String application, Map<String, String> values) {
        String block = extrasKeys(values).stream()
                .filter(line -> line.startsWith(EXTRAS + application + "."))
                .reduce("", (all, line) -> all.isEmpty() ? line : all + "\n" + line);
        assertThat(block).as("extras of %s", application).isNotEmpty();
        return block;
    }

    @Test
    void fillsEveryPlaceholderOfTheComposeFile() {
        String compose = ComposeTemplate.compose(tokens());

        assertThat(compose).contains("published: 8080");
        assertThat(compose).contains("QITS_IDP_ISSUER: http://qits-platform-idp:8080/idp");
        assertThat(compose).contains(
                "QITS_IDP_SEED_CLIENT_ID: \"prod-qits-bootstrap\"")
                .contains("QITS_IDP_SEED_CLIENT_SECRET: \"secret-prod-qits-bootstrap\"");
        assertThat(compose).contains("user: \"1001:988\"");
        assertThat(compose).contains("QITS_CI_DAEMON_VERSION_OVERRIDE: \"abc123\"");
        assertThat(compose).doesNotContain("${PORT}");
        assertThat(compose).doesNotContain("${PG_SUPERUSER_PASSWORD}")
                .doesNotContain("${PG_DEPLOYMENTS_PASSWORD}")
                .doesNotContain("${PG_DEPLOYMENTS_EVENTSTREAM_PASSWORD}")
                .doesNotContain("${PG_CI_PASSWORD}")
                .doesNotContain("${PG_CI_EVENTSTREAM_PASSWORD}")
                .doesNotContain("${PG_PLATFORM_IDP_PASSWORD}")
                .doesNotContain("${PG_EVENTS_PASSWORD}")
                .doesNotContain("${PG_ARTIFACTS_PASSWORD}")
                .doesNotContain("${PG_PLATFORM_MIRROR_PASSWORD}")
                .doesNotContain("${PG_GITHOST_PASSWORD}")
                .doesNotContain("${PG_GITHOST_EVENTSTREAM_PASSWORD}")
                .doesNotContain("${PG_PROJECTS_PASSWORD}")
                .doesNotContain("${PG_EPICS_PASSWORD}")
                .doesNotContain("${PG_PROJECTS_EVENTSTREAM_PASSWORD}")
                .doesNotContain("${PG_CONTAINERS_PASSWORD}")
                .doesNotContain("${PG_CONTAINERS_EVENTSTREAM_PASSWORD}");
        assertThat(compose).doesNotContain("${MIRROR_PORT}").doesNotContain("${GIT_HOST_PORT}");
        // The domain fragments are filled even when they are empty: a leftover placeholder would
        // reach the file as literal text and compose would refuse it.
        assertThat(compose).doesNotContain("${LETSENCRYPT_VOLUME}")
                .doesNotContain("${EDGE_SEED_TLS_PORTS}")
                .doesNotContain("${EDGE_TLS}");
        assertThat(compose).doesNotContain("${ENV_NAME}");
        // The alias family, which replaced a single ENV_KEY the template pasted a repository name
        // after. One left unfilled is an address rendered as text.
        assertThat(compose).doesNotContain("${ALIAS_").doesNotContain("${TIER_ENV_");
        // The credentials, and both families are filled from the run rather than the model.
        assertThat(compose).doesNotContain("${BOOTSTRAP_CLIENT_")
                .doesNotContain("${IDP_CLIENT_SECRET_");
    }

    /**
     * <b>THE IDENTITY THE SEED STACK CARRIES, WHOLE, AND IT IS SIX VALUES.</b> One pair for the idp
     * — the client it seeds its first database service client from — and one triple per seed
     * service, spelled as the resource contract the deployer will inject the same row through.
     * <p>
     * The id in each triple is {@code ${ALIAS_<APP>}} and never a second spelling: a client id IS
     * the wire alias, bare on the platform plane and tier-qualified otherwise, exactly as the
     * deployer's own {@code PdNetworks.alias} derives it.
     * <p>
     * <b>The URL in the triple is NOT that family, and this test is where the two are held apart.</b>
     * The id stays bare for qits-deployments and qits-platform-edge because it names a row in the
     * idp's registry, while the idp's own address is environment-qualified like every other address
     * the platform dials. A change that moved the id with the address would pass a sloppier
     * assertion and mint five clients nobody holds a credential for.
     */
    @Test
    void everySeedServiceIsHandedItsOwnIdpClientAndTheIdpItsSeedPair() {
        String compose = ComposeTemplate.compose(tokens());
        String issuer = "http://" + ENV + "-qits-platform-idp:8080/idp";

        assertThat(serviceBlock(compose, "qits-platform-idp"))
                .contains("QITS_IDP_SEED_CLIENT_ID: \"prod-qits-bootstrap\"")
                .contains("QITS_IDP_SEED_CLIENT_SECRET: \"secret-prod-qits-bootstrap\"");

        Map<String, String> byService = new LinkedHashMap<>();
        byService.put(ENV + "-qits-projects", ENV + "-qits-projects");
        byService.put(ENV + "-qits-ci", ENV + "-qits-ci");
        byService.put(ENV + "-qits-containers", ENV + "-qits-containers");
        byService.put("qits-deployments", "qits-deployments");
        byService.put("qits-platform-edge", "qits-platform-edge");
        byService.forEach((service, client) -> assertThat(serviceBlock(compose, service))
                .as("the idp client of %s", service)
                .contains("QITS_RESOURCE_IDP_URL: " + issuer)
                .contains("QITS_RESOURCE_IDP_CLIENT_ID: " + client)
                .contains("QITS_RESOURCE_IDP_CLIENT_SECRET: \"secret-" + client + "\""));
        // The five are the model's five, so a sixth seed client cannot be added without this list
        // being wrong about it.
        assertThat(PlatformModel.SEED_IDP_CLIENT_APPS.stream()
                .map(app -> PlatformModel.wireAlias(app, ENV)).toList())
                .containsExactlyInAnyOrderElementsOf(byService.values());
    }

    /**
     * <b>And no other identity survives in the stack, under either spelling.</b> A named oidc
     * client is five env lines of one identity the resource triple already carries, and a
     * per-client idp key is the registry the idp holds in its own store now. Read off the KEYS
     * rather than the text: the comments that explain both absences name them.
     */
    @Test
    void theSeedStackNamesNoOidcClientAndNoIdpClientKey() {
        assertThat(ComposeTemplate.compose(tokens()).lines()
                .filter(line -> !line.strip().startsWith("#")))
                .noneMatch(line -> line.contains("QUARKUS_OIDC_CLIENT_")
                        || line.contains("QITS_IDP_CLIENT_"));
    }

    @Test
    void keepsTheWarningAboutUserHomeReadable() {
        // ${user.home} is the failure the comment warns about, not a placeholder to fill.
        assertThat(ComposeTemplate.compose(tokens())).contains("under ${user.home}");
    }

    /**
     * <b>NOT ONE GENERATED ADDRESS DIALS A PLATFORM SERVICE BY ITS BARE NAME, and this is the
     * assertion the platform-service retirement is held to.</b> A platform service was the one
     * process addressed without a tier; qits-deployments gives it the {@code <env>-<app>} alias
     * beside its bare name now, so every reader can be moved to the qualified spelling while both
     * still resolve — and a bare one left behind is a reader that has to be found again by hand
     * when the bare alias is finally withdrawn.
     * <p>
     * It is spelled as a SWEEP over {@link PlatformModel#PLATFORM_SERVICES} rather than as a list,
     * so an application that joins or leaves the plane is covered without anybody remembering to
     * add a line — which is the same reason the addresses are derived rather than concatenated.
     * <p>
     * <b>The one exemption is QITS_IDP_ISSUER, and naming it here is the point.</b> It is not an
     * address: it is the {@code iss} claim consumers compare for equality against the issuer they
     * discovered, so both names resolving on qits-net buys it nothing and it cannot hold two
     * values. It moves in a step of its own once every consumer discovers from the qualified
     * address. When that step lands, this exemption goes and the sweep needs no other change.
     */
    @Test
    void noGeneratedAddressDialsAPlatformServiceByItsBareName() {
        String compose = ComposeTemplate.compose(tokens());
        String extras = ComposeTemplate.extras(tokens());

        for (String service : PlatformModel.PLATFORM_SERVICES) {
            String bare = PlatformModel.application(service);
            for (String file : List.of(compose, extras)) {
                assertThat(file.lines()
                        .filter(line -> !line.strip().startsWith("#"))
                        .filter(line -> line.contains("http://" + bare + ":"))
                        .filter(line -> !line.contains("QITS_IDP_ISSUER"))
                        .toList())
                        .as("lines dialling %s by its bare name", bare)
                        .isEmpty();
            }
        }
    }

    /**
     * <b>And the qualified address is really there — the positive half, one application at a
     * time.</b> The sweep above would pass on a file that dialled nobody at all; this names the
     * address each of the nine is actually reached at, so a token that silently rendered empty
     * fails here rather than becoming a url with a hole in it.
     * <p>
     * Four of the nine are not dialled from these files at all and are absent on purpose:
     * qits-platform-orchestrator and qits-platform-system are reached by a person through the edge,
     * qits-platform-edge is the door rather than a peer behind it, and the idp's own address is
     * asserted beside its issuer in {@link #everySeedServiceIsHandedItsOwnIdpClientAndTheIdpItsSeedPair}.
     */
    @Test
    void everyPlatformServiceIsDialledAtItsQualifiedAddress() {
        String compose = ComposeTemplate.compose(tokens());
        String extras = ComposeTemplate.extras(tokens());

        assertThat(compose).contains("QITS_EVENTS_URL: http://" + ENV + "-qits-events:8080");
        assertThat(extras)
                .contains("env.QITS_EVENTS_URL=http://" + ENV + "-qits-events:8080")
                .contains("http://" + ENV + "-qits-deployments:8080/platform-deployments/api")
                .contains("env.QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL=http://" + ENV
                        + "-qits-configuration:8080")
                .contains("env.QITS_PROJECTS_RELEASE_REQUESTS_MAINTENANCE_URL=http://" + ENV
                        + "-qits-platform-maintenance:8080")
                .contains("env.QITS_MAINTENANCE_MIRROR_MAVEN_URL=http://" + ENV
                        + "-qits-platform-mirror:8080/artifacts/maven/central");
        // The edge's mirror vhost is the one address that is a PATTERN rather than a name: {env} is
        // the edge's own placeholder, expanded at runtime from the host it was asked for. It gained
        // the qualifier with the rest — the one mirror answers every tier's expansion.
        assertThat(compose)
                .contains("QITS_EDGE_APPS_MIRROR_HOST_PATTERN: \"{env}-qits-platform-mirror\"");
        assertThat(extras)
                .contains("env.QITS_EDGE_APPS_MIRROR_HOST_PATTERN={env}-qits-platform-mirror");
    }

    /**
     * <b>Every platform service in the SEED answers to both of its names, and without this the
     * addresses above would resolve to nothing for the first half of a boot.</b>
     * <p>
     * The seed services are the only thing standing before qits-deployments has deployed anything,
     * so the dual alias the deployer grants does not exist yet. A stack file cannot add an alias to
     * the short {@code networks: [qits-net]} form, so the choice of form IS the choice of whether a
     * second name answers — see {@link PlatformModel#seedNetworks}.
     */
    @Test
    void everyPlatformServiceInTheSeedAnswersToBothOfItsNames() {
        String compose = ComposeTemplate.compose(tokens());

        List<String> platformSeeds = PlatformModel.CORE.stream()
                .filter(PlatformModel::isPlatformService)
                .toList();
        // The seed holds platform services at all — a filter that matched nothing would make every
        // assertion below vacuous.
        assertThat(platformSeeds).isNotEmpty();

        for (String name : platformSeeds) {
            String block = serviceBlock(compose, PlatformModel.wireAlias(name, ENV));
            assertThat(block).as("the network aliases of %s", name)
                    .contains(PlatformModel.dialAlias(name, ENV));
        }
        // And an ENVIRONMENT service declares none, because its service key already IS the
        // qualified address: a second alias there would be the same name twice.
        assertThat(serviceBlock(compose, ENV + "-qits-ci")).contains("networks: [qits-net]");
    }

    @Test
    void everySeedServiceIsInTheStackUnderTheNameItsPeersDial() {
        String compose = ComposeTemplate.compose(tokens());

        // The service KEY is the name now: a stack ignores container_name, and what a peer
        // resolves is the service — under qits_<alias> and under the bare alias both.
        for (String name : PlatformModel.CORE) {
            assertThat(compose).contains("\n  " + PlatformModel.wireAlias(name, ENV) + ":\n");
        }
        // THE SEED IS EXACTLY CORE, and the negative half is the rule rather than a list of names.
        // qits-platform-orchestrator, qits-platform-maintenance and qits-platform-system are
        // deployed through the pipeline like every other ordinary application: nothing calls them
        // during the seed window, so nothing waits on them — and a seed block for one of them
        // would be a service standing beside its own deployed container, which no `depends_on`
        // and no rerun ever recovers from.
        assertThat(serviceKeys(compose)).containsExactlyInAnyOrderElementsOf(
                PlatformModel.CORE.stream()
                        .map(name -> PlatformModel.wireAlias(name, ENV))
                        .toList());
        // One component replaced both: neither ancestor is in the seed any more.
        assertThat(compose).doesNotContain("\n  qits-cd:\n")
                .doesNotContain("\n  qits-serviceregistry:\n");
        // And no seed carries a pre-rename name, which nothing would resolve.
        assertThat(compose).doesNotContain("\n  qits-idp:\n")
                .doesNotContain("\n  qits-platform-deployments:\n")
                // The byte-plane split retired this one: the store is an environment service, so a
                // seed under the bare name is a service nothing on this platform dials.
                .doesNotContain("\n  qits-platform-artifacts:\n");
    }

    /**
     * <b>The edge's publish is INGRESS, and the mode is what makes it able to redeploy itself.</b>
     * Under {@code mode: host} a cutover is stop-first — the old task gives the port up before the
     * new one takes it — and the successor's image is pulled through the edge now, so it would have
     * to be down to come up. Ingress is start-first: the predecessor keeps answering.
     */
    @Test
    void theEdgeBindsTheHostPortInIngressModeWithoutAGatewayService() {
        String compose = ComposeTemplate.compose(tokens());
        String edge = serviceBlock(compose, "qits-platform-edge");

        // The publish itself, whole: the comment above it names the mode it is NOT, so the port
        // block is what has to be read rather than the word.
        assertThat(edge).contains("""
                      - target: 8080
                        published: 8080
                        protocol: tcp
                        mode: ingress
                """.stripTrailing());
        assertThat(edge).contains("QITS_EDGE_ENVIRONMENTS: prod")
                .contains("QITS_EDGE_DEFAULT_ENVIRONMENT: prod")
                .doesNotContain("QITS_EDGE_UPSTREAM_HOST_PATTERN");
        assertThat(compose).doesNotContain("\n  " + ENV + "-qits-gateway:\n");
        // The extras name no mode: it is the edge's own deployment spec (publish_mode: ingress),
        // because it is a property of the service rather than of one port.
        assertThat(extras("qits-platform-edge")).contains(".publishes[0]=8080:8080");
        assertThat(ComposeTemplate.extras(tokens())).doesNotContain(EXTRAS + "qits-gateway.");
    }

    /**
     * <b>The three names that closed three host ports.</b> registry, mirror and githost are matched
     * by HOST NAME rather than by path prefix — a docker client and a git client own their own
     * roots. What each name is answered by is here; what a caller must present to be answered at
     * all is {@link #theFlipIsOn()}.
     */
    @Test
    void theEdgeRoutesTheByteplaneByNameInBothFiles() {
        String edge = serviceBlock(ComposeTemplate.compose(tokens()), "qits-platform-edge");
        String edgeExtras = extras("qits-platform-edge");

        assertThat(edge).contains("QITS_EDGE_APPS_REGISTRY_HOST_PATTERN: \"{env}-qits-artifacts\"")
                .contains("QITS_EDGE_APPS_MIRROR_HOST_PATTERN: \"{env}-qits-platform-mirror\"")
                .contains("QITS_EDGE_APPS_GITHOST_HOST_PATTERN: \"{env}-qits-githost\"");
        assertThat(edgeExtras)
                .contains("env.QITS_EDGE_APPS_REGISTRY_HOST_PATTERN={env}-qits-artifacts")
                .contains("env.QITS_EDGE_APPS_MIRROR_HOST_PATTERN={env}-qits-platform-mirror")
                .contains("env.QITS_EDGE_APPS_GITHOST_HOST_PATTERN={env}-qits-githost");
    }

    /**
     * <b>The web editor is a fourth app alias and nothing more.</b> {@code
     * editor.<project>.<env>.<domain>} is one origin per project per environment, and the edge
     * reads three labels: the project sits at position 1 and the environment the editor is served
     * out of is the host's OWN label at position 2 — not a default and not a fallthrough. The entry
     * is therefore the same shape the byte plane uses, and {@code {env}} in it resolves out of the
     * name the browser arrived at.
     * <p>
     * <b>The audience is the assertion that matters.</b> An app entry that names none inherits the
     * REGISTRY audience, so an unspelled editor entry would let a token bought for {@code docker
     * pull} open any project's editor.
     */
    @Test
    void theEditorVhostFrontsWorkspacesOnTheWorkspacesAudienceInBothFiles() {
        String edge = serviceBlock(ComposeTemplate.compose(tokens()), "qits-platform-edge");
        String edgeExtras = extras("qits-platform-edge");

        assertThat(edge).contains("QITS_EDGE_APPS_EDITOR_HOST_PATTERN: \"{env}-qits-workspaces\"")
                .contains("QITS_EDGE_APPS_EDITOR_AUDIENCE_PATTERN: \"{env}-qits-workspaces\"");
        assertThat(edgeExtras)
                .contains("env.QITS_EDGE_APPS_EDITOR_HOST_PATTERN={env}-qits-workspaces")
                .contains("env.QITS_EDGE_APPS_EDITOR_AUDIENCE_PATTERN={env}-qits-workspaces");
        // The port is the edge's own default for an app, and the three byte-plane entries keep the
        // same silence. A key here would be a second place to keep 8080 in step.
        assertThat(edge).doesNotContain("QITS_EDGE_APPS_EDITOR_PORT");
        assertThat(edgeExtras).doesNotContain("QITS_EDGE_APPS_EDITOR_PORT");
        // The host pattern is the WIRE ALIAS of qits-workspaces, spelled with the edge's own
        // runtime placeholder — never this generator's ${ENV_NAME}, which would pin one tier.
        assertThat(edge).doesNotContain("QITS_EDGE_APPS_EDITOR_HOST_PATTERN: \"" + ENV
                + "-qits-workspaces\"");
    }

    /**
     * <b>A project slug sits where the edge reads a tier AND where it reads an app, so both are
     * reserved.</b> Position 1 of {@code editor.<project>.<env>.<domain>} is the label
     * {@code <app>.<env>.<domain>} spells its environment at, and the edge asks "is this an
     * environment" before it asks "is this a project" — a project called after this platform's
     * environment would be read as that environment, over an apex that is not the apex. Position 0
     * is the app label, matched before any project is read, so a project called {@code registry}
     * simply never reaches its own name.
     * <p>
     * The value is the one string in both files, and the assertion is whole rather than a
     * {@code contains} of the environment: a list that gained the labels but not on the extras is
     * a first self-deploy that drops them, since the update argv {@code --env-rm}s what the extras
     * do not state.
     */
    @Test
    void qitsProjectsIsToldWhichSlugsAreReserved() {
        String reserved = PlatformModel.reservedSlugs(ENV);
        String projects = serviceBlock(ComposeTemplate.compose(tokens()),
                ENV + "-qits-projects");

        assertThat(reserved).startsWith(ENV + ",").contains(",registry,").contains(",editor,");
        assertThat(projects).contains("QITS_PROJECTS_RESERVED_SLUGS: " + reserved);
        assertThat(extras("qits-projects"))
                .contains("env.QITS_PROJECTS_RESERVED_SLUGS=" + reserved);
    }

    /**
     * <b>The extra SANs reach the edge as one generic key, in both files.</b> The edge derives the
     * apex, {@code *.<domain>}, {@code *.<project>.<domain>} per project and
     * {@code *.<env>.<project>.<domain>} per environment of a project that has them — every depth
     * the right-to-left grammar has — so this key is for a name at some OTHER shape. It used to
     * carry the editor hosts, one per project; the per-project wildcards are a live read off
     * qits-projects' events now, and the key says nothing about editors either way — it is a list
     * of names.
     */
    @Test
    void theExtraSansReachTheEdgeAsAdditionalCertificateNames() {
        Map<String, String> values = tokens(DOMAIN,
                List.of("status.support." + DOMAIN, "legacy.acme." + DOMAIN));
        String edge = serviceBlock(ComposeTemplate.compose(values), "qits-platform-edge");

        assertThat(edge).contains("QITS_EDGE_ACME_ADDITIONAL_NAMES: status.support." + DOMAIN
                + ",legacy.acme." + DOMAIN);
        // On the extras too, or the edge's first self-deploy orders a certificate without them.
        assertThat(extras("qits-platform-edge", values))
                .contains("env.QITS_EDGE_ACME_ADDITIONAL_NAMES=status.support." + DOMAIN
                        + ",legacy.acme." + DOMAIN);
    }

    /**
     * No extra names spells no key at all. An empty list and an absent one are the same answer, and
     * a key holding nothing is a line for the next reader to wonder about.
     */
    @Test
    void noExtraSansSpellsNoKey() {
        assertThat(ComposeTemplate.compose(tokens(DOMAIN)))
                .doesNotContain("QITS_EDGE_ACME_ADDITIONAL_NAMES");
        assertThat(ComposeTemplate.extras(tokens(DOMAIN)))
                .doesNotContain("QITS_EDGE_ACME_ADDITIONAL_NAMES");
        // And a platform with no domain has no TLS wiring for one to hide in.
        assertThat(ComposeTemplate.compose(tokens()))
                .doesNotContain("QITS_EDGE_ACME_ADDITIONAL_NAMES");
    }

    /**
     * <b>The byte plane publishes nothing, in the seed and in the deployment alike.</b> Three host
     * ports went with unify-ingress: the host reaches all three services through the edge, by name.
     * A publish that came back would be an unauthenticated door beside the authenticated one.
     */
    @Test
    void theByteplanePublishesNoHostPortAnywhere() {
        String compose = ComposeTemplate.compose(tokens());

        for (String service : List.of(ENV + "-qits-artifacts", "qits-platform-mirror",
                ENV + "-qits-githost")) {
            assertThat(serviceBlock(compose, service)).as("ports of %s", service)
                    .doesNotContain("ports:");
        }
        assertThat(compose).doesNotContain("published: 8081")
                .doesNotContain("published: 8082")
                .doesNotContain("published: 8083");
        for (String application : List.of("qits-artifacts", "qits-platform-mirror",
                "qits-githost")) {
            assertThat(extras(application)).as("publishes of %s", application)
                    .doesNotContain(".publishes[");
        }
    }

    /**
     * <b>The deployer and the bus keep their bare IDENTITY and carry a tier in every ADDRESS, and
     * this is where the two halves are held apart.</b> Both moved to the platform plane on
     * 2026-08-17, and the platform-service concept is being retired from the address side first:
     * qits-deployments gives a platform service the {@code <env>-<app>} alias beside its bare name,
     * so both spellings resolve and a dialer can move without an estate-wide flag day.
     * <p>
     * What did NOT move is the pair of things that are compared rather than resolved — the seed
     * stack's service key and the idp client id, which name a row in the idp's registry that every
     * deployed peer already holds a credential for. Qualifying those would not rename a client, it
     * would mint a second one beside the live row: the silent-401 class.
     */
    @Test
    void theDeployerAndTheBusKeepABareIdentityAndAQualifiedAddress() {
        String compose = ComposeTemplate.compose(tokens());
        String extras = ComposeTemplate.extras(tokens());

        // The seed service keys stay bare, because a key is the client id.
        assertThat(compose).contains("\n  qits-deployments:\n").contains("\n  qits-events:\n");
        // And each declares the qualified alias beside it, so the addresses below resolve during
        // the seed window too — before any deployer exists to grant the second name.
        assertThat(serviceBlock(compose, "qits-deployments"))
                .contains("aliases: [" + ENV + "-qits-deployments]");
        assertThat(serviceBlock(compose, "qits-events"))
                .contains("aliases: [" + ENV + "-qits-events]");
        // The values that flipped with them, each one an address or an identity a peer holds:
        // the fleet-wide bus url, the artifacts GC's pin source — kept, because the jar defaults to
        // the post-rename qits-platform-deployments, which nothing answers to — the deployer's
        // inbound audience, and its idp client id.
        assertThat(extras).contains("env.QITS_EVENTS_URL=http://prod-qits-events:8080")
                .contains("qits-artifacts.env.QITS_ARTIFACTS_GC_PINS_CD_BASE_URL="
                        + "http://prod-qits-deployments:8080/platform-deployments/api")
                .contains("qits-deployments.env.QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL="
                        + "http://prod-qits-configuration:8080");
        // And its idp client id is the same bare alias, on the seed stack where the credential is
        // spelled at all. The extras spell no identity for anybody.
        assertThat(ComposeTemplateTest.serviceBlock(compose, "qits-deployments"))
                .contains("QITS_RESOURCE_IDP_CLIENT_ID: qits-deployments");
    }

    /**
     * <b>QITS_ENVIRONMENT states which tier an application belongs to, and a platform service is
     * handed no such line.</b> The deployer records a resource row per application under the
     * environment this variable names, {@code orElse(null)}, and looks a platform-target service's
     * rows up by that null key — so a platform service told it has a tier records rows its own
     * first self-deploy will not find, takes the reconcile arm and rotates the database passwords
     * this bootstrap issued, mid-boot. The rows the bootstrap records exist to prevent that.
     */
    @Test
    void aPlatformServiceIsNeverToldItHasATier() {
        String compose = ComposeTemplate.compose(tokens());
        String extras = ComposeTemplate.extras(tokens());

        // The deployer's own two blocks, which were the only writers of this line in either file.
        assertThat(serviceBlock(compose, "qits-deployments")).doesNotContain("QITS_ENVIRONMENT");
        assertThat(extras("qits-deployments")).doesNotContain("QITS_ENVIRONMENT");
        // And the bus, the other application that moved plane on the same day.
        assertThat(serviceBlock(compose, "qits-events")).doesNotContain("QITS_ENVIRONMENT");
        assertThat(extras("qits-events")).doesNotContain("QITS_ENVIRONMENT");
        // And the configuration store, which moved on 2026-09-07 — the newest member, and the one
        // whose blocks a hand-written QITS_ENVIRONMENT would have been most tempting on: what it
        // stores IS env-keyed. The keying is in the rows, never in the container's own tier.
        assertThat(extras("qits-configuration")).doesNotContain("QITS_ENVIRONMENT");
        // Not a platform service anywhere in either file, which is the rule rather than two names.
        // The stack file is asked only about the seed, because that is all it holds:
        // qits-platform-orchestrator is deployed near the end of the train and has no seed block.
        for (String app : PlatformModel.PLATFORM_SERVICES) {
            if (PlatformModel.CORE.contains(app)) {
                assertThat(serviceBlock(compose, PlatformModel.wireAlias(app, ENV)))
                        .as("the seed block of %s", app)
                        .doesNotContain("QITS_ENVIRONMENT");
            }
            assertThat(extras(PlatformModel.application(app))).as("the extras of %s", app)
                    .doesNotContain("QITS_ENVIRONMENT");
        }
        // QITS_MAINTENANCE_ENVIRONMENT is not this variable and the loop above proves it: it
        // records which environment's CI ran a bump, on a service that belongs to no tier.
        assertThat(extras("qits-platform-maintenance"))
                .contains("env.QITS_MAINTENANCE_ENVIRONMENT=prod");
        // AN ENVIRONMENT APPLICATION STILL GETS IT, and the line is what says which tier it is —
        // so this asserts the fragment renders rather than that the variable is simply gone.
        assertThat(PlatformModel.modelTokens(ENV))
                .containsEntry("TIER_ENV_DEPLOYMENTS", "")
                .containsEntry("TIER_ENV_EXTRAS_DEPLOYMENTS", "");
        assertThat(PlatformModel.modelTokens(ENV).get("TIER_ENV_CI"))
                .endsWith("      QITS_ENVIRONMENT: prod");
        assertThat(PlatformModel.modelTokens(ENV).get("TIER_ENV_EXTRAS_CI"))
                .endsWith("qits.platform.deployments.extras.qits-ci.env.QITS_ENVIRONMENT=prod");
        // An empty fragment leaves no blank line and no orphan comment where it used to render.
        assertThat(compose).doesNotContain("\n\n\n");
        assertThat(extras).doesNotContain("\n\n\n");
    }

    @Test
    void theDeployerCarriesItsDatabaseItsConfigVolumeAndTheSocket() {
        String compose = ComposeTemplate.compose(tokens());
        String block = serviceBlock(compose, "qits-deployments");

        assertThat(compose).contains("image: qits/deployments:latest");
        // Adopter #1 of the generic resource contract: its own store arrives as the same triple it
        // hands every application that declares one.
        assertThat(block).contains("QITS_RESOURCE_DB_URL: "
                + "jdbc:postgresql://prod-qits-oci-postgresql:5432/qits_deployments");
        assertThat(block).contains("QITS_RESOURCE_DB_USERNAME: qits_deployments");
        assertThat(block).contains("QITS_RESOURCE_DB_PASSWORD: \"fedcba9876543210\"");
        // Two stores, two Flyway lineages: its own, and the outbox of the eventstream library it
        // joined on 2026-08-10. Compose starts it before any deployer exists to inject either.
        assertThat(block).contains("QITS_RESOURCE_EVENTSTREAM_URL: jdbc:postgresql://"
                        + "prod-qits-oci-postgresql:5432/qits_deployments_eventstream")
                .contains("QITS_RESOURCE_EVENTSTREAM_USERNAME: qits_deployments_eventstream")
                .contains("QITS_RESOURCE_EVENTSTREAM_PASSWORD: \"1111222233334444\"");
        // The bus, at its wire alias — bare since the bus moved plane, and stated anyway because
        // this service SUBSCRIBES: a BuildSuccessful never received deploys nothing.
        assertThat(block).contains("QITS_EVENTS_URL: http://prod-qits-events:8080");
        assertThat(block).doesNotContain("QITS_ENVIRONMENT");
        // What makes it a provisioner rather than only a consumer.
        assertThat(block).contains("QITS_PLATFORM_DEPLOYMENTS_POSTGRES_ADMIN_PASSWORD: "
                + "\"0123456789abcdef\"");
        // The H2 file store and the volume that held it are gone together.
        assertThat(block).doesNotContain("QUARKUS_DATASOURCE_PLATFORMDEPLOYMENTS_JDBC_URL");
        assertThat(block).doesNotContain("- qits-deployments-data:/data");
        assertThat(compose).doesNotContain("qits-deployments-data:\n");
        assertThat(block).contains("- qits-deployments-config:/work/config");
        assertThat(block).contains("- /var/run/docker.sock:/var/run/docker.sock");
        // Machine auth inbound only: it validates a bearer and mints none, so no oidc-client.
        assertThat(block).contains("QITS_AUTH_MACHINE_REQUIRED: \"true\"")
                .contains("QUARKUS_OIDC_AUTH_SERVER_URL: http://prod-qits-platform-idp:8080/idp")
                .doesNotContain("QUARKUS_OIDC_CLIENT_CLIENT_ENABLED");
    }

    /**
     * <b>NO SERVICE IS TOLD WHICH AUDIENCE TO VALIDATE ANY MORE, and that is one line per service
     * removed rather than a gate weakened.</b> Every image ships
     * {@code quarkus.oidc.token.audience=qits-platform} as one literal name, and the idp puts that
     * name on every token it mints, so a bearer is accepted without either file naming an audience.
     * There is no per-service name left for an env line to say.
     * <p>
     * What stays on both sides is the GATE — whether a bearer is demanded at all — and the ISSUER,
     * which is one address per platform rather than one per service.
     */
    @Test
    void neitherFileNamesAnAudienceAndBothCarryTheGateAndTheIssuer() {
        String compose = ComposeTemplate.compose(tokens());

        // The KEYS rather than the text: the comments that explain the absence name the variable,
        // and a comment is not a setting.
        assertThat(compose.lines().filter(line -> !line.strip().startsWith("#")))
                .noneMatch(line -> line.contains("QITS_AUTH_MACHINE_AUDIENCE"));
        assertThat(extrasKeys()).noneMatch(line -> line.contains("QITS_AUTH_MACHINE_AUDIENCE"));

        assertThat(serviceBlock(compose, ENV + "-qits-ci"))
                .contains("QITS_AUTH_MACHINE_REQUIRED: \"true\"")
                .contains("QUARKUS_OIDC_AUTH_SERVER_URL: http://prod-qits-platform-idp:8080/idp");
        assertThat(extras("qits-ci"))
                .contains("env.QITS_AUTH_MACHINE_REQUIRED=true")
                .contains("env.QUARKUS_OIDC_AUTH_SERVER_URL=http://prod-qits-platform-idp:8080/idp");
        assertThat(extras("qits-workspaces"))
                .contains("env.QITS_AUTH_MACHINE_REQUIRED=true")
                .contains("env.QUARKUS_OIDC_AUTH_SERVER_URL=http://prod-qits-platform-idp:8080/idp");
        // The mirror stays anonymous on both sides: it serves cached third-party bytes to
        // anonymous clients, validates nothing and mints nothing.
        assertThat(extras("qits-platform-mirror")).doesNotContain("QITS_AUTH_MACHINE_")
                .doesNotContain("QUARKUS_OIDC_");
    }

    /**
     * <b>THE FLIP, stated as configuration so it survives.</b> The boot also applies these values to
     * the RUNNING deployer, and that alone would be reverted by the deployer's own next deployment
     * — which is precisely the failure class qits-configuration exists to kill. These lines are what
     * every successor inherits.
     */
    @Test
    void theDeployerIsPointedAtTheConfigurationService() {
        String deployer = extras("qits-deployments");

        assertThat(deployer).contains("env.QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL="
                + "http://prod-qits-configuration:8080");
        // AND THE CREDENTIAL THAT READ PRESENTS IS NOT HERE. The read is behind
        // qits-configuration's machine gate, so it carries a bearer — minted from the deployer's
        // OWN client, which is the idp:client resource it declares and the row it injects into its
        // own successor. The five QUARKUS_OIDC_CLIENT_CONFIGURATION_* lines this block used to
        // carry would shadow that row and survive every rotation of it.
        assertThat(deployer).doesNotContain("QUARKUS_OIDC_CLIENT_")
                .doesNotContain("QITS_RESOURCE_IDP_");
        // The seed deployer is handed it on the stack instead, because it starts before anything
        // could inject one.
        assertThat(serviceBlock(ComposeTemplate.compose(tokens()), "qits-deployments"))
                .contains("QITS_RESOURCE_IDP_CLIENT_SECRET: \"secret-qits-deployments\"");
    }

    /**
     * <b>The seed deployer must start WITHOUT the flip, and the stack file is where that is kept
     * true.</b> A deployer holding the extras url before qits-configuration is deployed and imported
     * refuses every deployment, qits-configuration's own included — so the boot flips the running
     * service after the import phase and the seed spells none of it.
     */
    @Test
    void theSeedDeployerStartsBeforeThereIsAConfigurationServiceToRead() {
        String compose = ComposeTemplate.compose(tokens());

        assertThat(serviceBlock(compose, "qits-deployments"))
                .doesNotContain("QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL")
                .doesNotContain("QUARKUS_OIDC_CLIENT_CONFIGURATION_");
        // And there is no seed service for it either, under EITHER spelling: it is deployed through
        // the pipeline like every other application, and the plane move changed which name a seed
        // block would have carried rather than whether there is one. Both are asserted because the
        // old name is the one a stale block would still be written under.
        assertThat(compose).doesNotContain("\n  " + ENV + "-qits-configuration:\n")
                .doesNotContain("\n  qits-configuration:\n");
        assertThat(PlatformModel.CORE).doesNotContain("configuration");
    }

    /**
     * <b>Every application the deploy train reaches has a block here.</b> Once the flip is on, the
     * deployer reads each one out of qits-configuration — which the boot fills from this very file —
     * so an application with no lines is an application the import never mentions.
     */
    @Test
    void everyDeployableIsConfiguredInThisFile() {
        List<String> keys = extrasKeys();

        for (String application : PlatformModel.DEPLOYABLES) {
            String prefix = EXTRAS + PlatformModel.application(application) + ".";
            assertThat(keys).as("extras of %s", application)
                    .anyMatch(line -> line.startsWith(prefix));
        }
    }

    /**
     * <b>THE BYTE PLANE, in the seed stack.</b> Three services where there was one, each with its
     * own store and none with a door of its own — the edge is the door for all three — and the split
     * runs through every client's configuration, which is what the rest of this class checks one
     * consumer at a time.
     * <p>
     * All three stores are a DATABASE now, so all three services are pinned to the same shape: no
     * {@code volumes:} key and no blobs directory anywhere. The negative is asserted per service
     * rather than once over the file, because a mount that came back would come back under one name.
     */
    @Test
    void theByteplaneIsThreeServicesWithThreeStoresAndThreeDoors() {
        String compose = ComposeTemplate.compose(tokens());
        String artifacts = serviceBlock(compose, ENV + "-qits-artifacts");
        String mirror = serviceBlock(compose, "qits-platform-mirror");
        String githost = serviceBlock(compose, ENV + "-qits-githost");

        // The hosted store, behind the edge like the other two: no port of its own since
        // unify-ingress. Its store is one database — metadata and blob bytes both — so the
        // container mounts nothing either.
        assertThat(compose).contains("image: qits/artifacts:latest");
        assertThat(artifacts).doesNotContain("ports:")
                .contains("QITS_RESOURCE_DB_URL: "
                        + "jdbc:postgresql://prod-qits-oci-postgresql:5432/qits_artifacts")
                .contains("QITS_RESOURCE_DB_USERNAME: qits_artifacts")
                .contains("QITS_RESOURCE_DB_PASSWORD: \"cafecafecafecafe\"")
                .doesNotContain("QUARKUS_DATASOURCE_ARTIFACTS_JDBC_URL")
                .doesNotContain("QITS_ARTIFACTS_BLOBS_DIR")
                .doesNotContain("volumes:")
                // The git host left, and every knob it owned left with it.
                .doesNotContain("QITS_REPOSITORIES_GIT_")
                .doesNotContain("QITS_CI_INTAKE_URL");

        // The caches: a platform service with its own database, reached at mirror.<env>.localhost
        // through the edge — and the cached bytes are rows in that same database, so this container
        // is stateless too.
        assertThat(compose).contains("image: qits/platform-mirror:latest");
        assertThat(mirror).doesNotContain("ports:")
                .contains("QITS_RESOURCE_DB_URL: "
                        + "jdbc:postgresql://prod-qits-oci-postgresql:5432/qits_platform_mirror")
                .contains("QITS_RESOURCE_DB_PASSWORD: \"1234123412341234\"")
                .doesNotContain("QITS_ARTIFACTS_BLOBS_DIR")
                .doesNotContain("volumes:");

        // The git host: two databases, because the outbox is a lineage of its own — and the packs
        // and reftables are rows in the first of them, so this container is stateless as well. It
        // was the last blob store on a volume.
        assertThat(compose).contains("image: qits/githost:latest");
        assertThat(githost).doesNotContain("ports:")
                .contains("QITS_RESOURCE_DB_URL: "
                        + "jdbc:postgresql://prod-qits-oci-postgresql:5432/qits_githost")
                .contains("QITS_RESOURCE_EVENTSTREAM_URL: "
                        + "jdbc:postgresql://prod-qits-oci-postgresql:5432/qits_githost_eventstream")
                .doesNotContain("QITS_ARTIFACTS_BLOBS_DIR")
                .doesNotContain("volumes:")
                // A push is a durable event now, not an HTTP call to two consumers.
                .contains("QITS_EVENTS_URL: http://prod-qits-events:8080")
                .doesNotContain("QITS_CI_INTAKE_URL")
                // Without the resolver the name-addressed scheme 404s and every agent container
                // starts with an empty workspace.
                .contains("QITS_PROJECTS_NAME_RESOLVER_URL: "
                        + "http://prod-qits-projects:8080/projects/api/projects")
                .contains("QITS_REPOSITORIES_GIT_PUSH_TOKEN: \"local-dev\"")
                .contains("QITS_REPOSITORIES_GIT_PROTECT_DEFAULT_BRANCH: \"true\"");
    }

    /**
     * <b>qits-ci's build concurrency is FILLED, in both files, and is never the literal 2.</b>
     * <p>
     * A step's {@code docker build} is served by the host daemon, so it runs outside the 4g step
     * cgroup; two concurrent GraalVM-native ones livelocked a 16 GB host on 2026-08-22. The number
     * is {@link CiConcurrency}'s, and it has to reach the extras as well as the seed — the extras
     * are what the deployed ci gets, and a literal there is what wrote an operator's hand-set 1
     * back to 2 on the next re-bootstrap.
     */
    @Test
    void ciBuildConcurrencyComesFromTheHostAndReachesBothFiles() {
        Map<String, String> twoBuildHost = tokens();
        twoBuildHost.put("CI_CONCURRENT_BUILDS", "2");

        assertThat(serviceBlock(ComposeTemplate.compose(tokens()), ENV + "-qits-ci"))
                .contains("QITS_CI_CONCURRENT_BUILDS: \"1\"");
        assertThat(serviceBlock(ComposeTemplate.compose(twoBuildHost), ENV + "-qits-ci"))
                .contains("QITS_CI_CONCURRENT_BUILDS: \"2\"");
        assertThat(extras("qits-ci")).contains("env.QITS_CI_CONCURRENT_BUILDS=1");
        assertThat(extras("qits-ci", twoBuildHost)).contains("env.QITS_CI_CONCURRENT_BUILDS=2");

        // The step container's own limits are NOT sized by this and must not move with it.
        assertThat(extras("qits-ci")).contains("env.QITS_CI_MEMORY_LIMIT=4g")
                .contains("env.QITS_CI_CPUS=4");
    }

    /**
     * <b>The two-endpoint topology, as every client sees it.</b> Hosted content is this tier's
     * qits-artifacts; third-party content is qits-platform-mirror. The step containers are where it
     * matters most, because ci ships all four roots defaulted to the one service that used to be
     * both.
     */
    @Test
    void hostedContentIsTheStoreAndThirdPartyContentIsTheMirror() {
        String ci = serviceBlock(ComposeTemplate.compose(tokens()), ENV + "-qits-ci");
        String ciExtras = extras("qits-ci");

        for (String block : new String[]{ci.replace(": ", "="), ciExtras}) {
            assertThat(block).contains(
                            "QITS_ARTIFACTS_NPM_HOSTED_URL=http://prod-qits-artifacts:8080"
                                    + "/artifacts/npm/npm/")
                    .contains("QITS_ARTIFACTS_NPM_PROXY_URL=http://prod-qits-platform-mirror:8080"
                            + "/artifacts/npm/npmjs/")
                    .contains("QITS_ARTIFACTS_MAVEN_REGISTRY_URL=http://prod-qits-artifacts:8080"
                            + "/artifacts/maven/maven")
                    .contains("QITS_ARTIFACTS_DOCS_URL=http://prod-qits-artifacts:8080"
                            + "/artifacts/docs/docs");
        }
        // The publish target is the HOSTED registry and never the mirror: a publish step pushes the
        // platform's own image, and the mirror takes no writes at all. It is dialled by the HOST's
        // docker daemon, which resolves the name to the loopback address and arrives at the edge.
        assertThat(ciExtras).contains(
                "env.QITS_ARTIFACTS_REGISTRY_HOST=registry.prod.localhost:8080");
        // The docs reader is handed the same address ci injects, so the two cannot disagree.
        assertThat(extras("qits-docs")).contains(
                "env.QITS_DOCS_ARTIFACTS_URL=http://prod-qits-artifacts:8080/artifacts/docs/docs");
    }

    /**
     * <b>A workspace builds against the same two registries a CI step does.</b> The addresses are
     * asserted against ci's own so the two cannot drift: a workspace that resolved a different
     * npmjs cache, or a different maven store, would build something CI cannot reproduce — and the
     * failure would not look like a configuration difference, it would look like a flaky test.
     *
     * <p>They are wire aliases and never a {@code *.localhost} name: the consumer is a container on
     * qits-net, whose resolver knows no such name. That is the same rule ci's block follows, and
     * the reason the registry HOST (which the host daemon resolves) is absent here — a workspace
     * pushes no image.
     */
    @Test
    void aWorkspaceIsToldTheSameRegistriesCiUses() {
        String workspaces = extras("qits-workspaces");
        String ci = extras("qits-ci");

        assertThat(workspaces)
                .contains("env.QITS_WORKSPACE_MAVEN_REPOSITORY_URL=http://prod-qits-artifacts:8080"
                        + "/artifacts/maven/maven")
                .contains("env.QITS_WORKSPACE_NPM_REGISTRY_URL=http://prod-qits-artifacts:8080"
                        + "/artifacts/npm/npm/")
                .contains("env.QITS_WORKSPACE_NPM_PROXY_URL=http://prod-qits-platform-mirror:8080"
                        + "/artifacts/npm/npmjs/");
        // Same addresses, stated once per consumer: if ci's move and a workspace's do not, this
        // fails rather than leaving one of them pointed at a registry that no longer serves.
        for (String suffix : new String[]{
                "/artifacts/maven/maven", "/artifacts/npm/npm/", "/artifacts/npm/npmjs/"}) {
            assertThat(ci).contains(suffix);
            assertThat(workspaces).contains(suffix);
        }
    }

    /**
     * <b>ci HOLDS NO STATIC REGISTRY CREDENTIAL, in either file.</b> The pair this generator
     * carried for half a day lent the store's own client to every publish step, so one leaked step
     * secret was the identity that may write to every registry on the platform. ci commissions a
     * credential from the idp for the run instead, with its own client id and secret, and nothing
     * takes the pair's place here.
     */
    @Test
    void noStaticRegistryCredentialReachesCi() {
        String compose = ComposeTemplate.compose(tokens());

        assertThat(compose).doesNotContain("QITS_CI_REGISTRY_AUTH_CLIENT_ID")
                .doesNotContain("QITS_CI_REGISTRY_AUTH_CLIENT_SECRET");
        assertThat(ComposeTemplate.extras(tokens()).lines()
                .filter(line -> line.startsWith(EXTRAS))
                .filter(line -> line.contains("QITS_CI_REGISTRY_AUTH"))
                .toList()).isEmpty();
        // And the store's own credential is in neither file now — qits-artifacts is not a seed
        // client, so its client arrives with its own deployment — which makes ci's block carrying
        // it doubly impossible.
        assertThat(compose).doesNotContain("secret-prod-qits-artifacts");
        assertThat(ComposeTemplate.extras(tokens())).doesNotContain("secret-prod-qits-artifacts");
    }

    /**
     * <b>THE TWO PULLERS HOLD A CREDENTIAL, and each holds its OWN.</b> The deployer and the
     * container orchestrator both shell {@code docker pull}, and since the flip a pull is
     * authenticated with a client id and a secret out of a config.json. A borrowed identity would
     * make a refused pull unattributable, so each is handed its own — as the resource triple, on
     * its own seed block, and nowhere in the extras: the deployer injects the same registry row
     * into every successor.
     */
    @Test
    void thePullersAreHandedTheirOwnCredentialOnTheSeedAndNeverInTheExtras() {
        String compose = ComposeTemplate.compose(tokens());

        assertThat(serviceBlock(compose, "qits-deployments"))
                .contains("QITS_RESOURCE_IDP_CLIENT_ID: qits-deployments")
                .contains("QITS_RESOURCE_IDP_CLIENT_SECRET: \"secret-qits-deployments\"");
        assertThat(serviceBlock(compose, ENV + "-qits-containers"))
                .contains("QITS_RESOURCE_IDP_CLIENT_ID: prod-qits-containers")
                .contains("QITS_RESOURCE_IDP_CLIENT_SECRET: \"secret-prod-qits-containers\"");
        assertThat(extras("qits-deployments")).doesNotContain("QITS_RESOURCE_IDP_");
        assertThat(extras("qits-containers")).doesNotContain("QITS_RESOURCE_IDP_");
    }

    /**
     * <b>THE EDGE HOLDS A CLIENT FOR USER SESSIONS, and sessions are enforced by default.</b>
     * <p>
     * The id carries the environment while the service does not: it is the session gate's
     * credential, and a session belongs to a tier.
     */
    @Test
    void theEdgesSessionCredentialIsTheResourceTripleAndSessionsAreEnabled() {
        String compose = ComposeTemplate.compose(tokens());
        String edge = serviceBlock(compose, "qits-platform-edge");
        String idp = serviceBlock(compose, "qits-platform-idp");

        // THE IDP IS TOLD NOTHING ABOUT THE EDGE, or about any other client: the only pair on its
        // block is its own seed client, which it creates its first database service client from.
        assertThat(idp).contains("QITS_IDP_SEED_CLIENT_ID: \"prod-qits-bootstrap\"")
                .doesNotContain("QITS_IDP_CLIENT_");
        // The edge holds its session credential as the resource triple every seed service gets:
        // qits-edge reads QITS_RESOURCE_IDP_CLIENT_ID/_CLIENT_SECRET first and maps them onto
        // qits.edge.sessions.client-id/-secret itself, so the pair it used to be told separately
        // is one row in the deployer's registry now.
        assertThat(edge).contains("QITS_EDGE_SESSIONS_ENABLED: \"true\"")
                .contains("QITS_RESOURCE_IDP_CLIENT_ID: qits-platform-edge")
                .contains("QITS_RESOURCE_IDP_CLIENT_SECRET: \"secret-qits-platform-edge\"")
                .doesNotContain("QITS_EDGE_SESSIONS_CLIENT_SECRET");
        assertThat(extras("qits-platform-idp")).doesNotContain("QITS_IDP_CLIENT")
                .doesNotContain("QITS_IDP_SEED_CLIENT");
        // And the deployed edge carries the switch and no credential at all — the deployer injects
        // the row, and a stored secret would shadow the one it keeps current.
        assertThat(extras("qits-platform-edge"))
                .contains("env.QITS_EDGE_SESSIONS_ENABLED=true")
                .doesNotContain("env.QITS_EDGE_SESSIONS_CLIENT_SECRET=")
                .doesNotContain("QITS_RESOURCE_IDP_");
        // And the gateway is untouched by all of it: which variant it is built as is a pipeline
        // build arg, and neither generated file sets one — the comments that NAME it are not
        // settings, which is why this reads the keys rather than the text.
        assertThat(compose.lines().filter(line -> !line.strip().startsWith("#")))
                .noneMatch(line -> line.contains("QITS_VARIANT"));
        assertThat(extrasKeys()).noneMatch(line -> line.contains("QITS_VARIANT"));
    }

    /**
     * <b>THE PASSKEY BINDING, in both files.</b> A credential is bound to the rp id and asserts on
     * that host and its children: the rp id is the domain where there is one, and this platform's
     * own PROJECT door {@code qits.localhost} locally. The project's door rather than the
     * environment's, so the {@code supportsEnvironments} flag cannot invalidate a passkey by
     * flipping. The ceremony's origin is the idp's own host, a child of the rp id either way.
     */
    @Test
    void theIdpIsToldWhichHostAPasskeyIsBoundTo() {
        String idp = serviceBlock(ComposeTemplate.compose(tokens()), "qits-platform-idp");

        assertThat(idp).contains("QITS_IDP_WEBAUTHN_RP_ID: qits.localhost")
                .contains("QITS_IDP_WEBAUTHN_ORIGINS: \"http://idp.prod.qits.localhost:8080\"");
        assertThat(extras("qits-platform-idp"))
                .contains("env.QITS_IDP_WEBAUTHN_RP_ID=qits.localhost")
                .contains("env.QITS_IDP_WEBAUTHN_ORIGINS=http://idp.prod.qits.localhost:8080");

        String withDomain = serviceBlock(ComposeTemplate.compose(tokens(DOMAIN)),
                "qits-platform-idp");
        assertThat(withDomain).contains("QITS_IDP_WEBAUTHN_RP_ID: " + DOMAIN)
                .contains("QITS_IDP_WEBAUTHN_ORIGINS: \"https://idp." + ENV + ".qits." + DOMAIN
                        + "\"");
        assertThat(ComposeTemplate.extras(tokens(DOMAIN)))
                .contains("env.QITS_IDP_WEBAUTHN_RP_ID=" + DOMAIN)
                .contains("env.QITS_IDP_WEBAUTHN_ORIGINS=https://idp." + ENV + ".qits." + DOMAIN);
    }

    /**
     * <b>ONE SESSION, EVERY SERVICE HOST, IN THE PROJECT-FIRST GRAMMAR.</b> Names are read right to
     * left — {@code <app>[.<env>].<project>.<domain>} — so the allow-list names this platform's own
     * project door and a wildcard in front of each door under it. A wildcard is exactly one label
     * on the same port, which is what carries one session onto every {@code <app>} host.
     * <p>
     * <b>BOTH DEPTHS ARE LISTED, and that is the assertion.</b> Whether an application of
     * {@code qits} is {@code <app>.qits.<domain>} or {@code <app>.<env>.qits.<domain>} is the
     * project's live {@code supportsEnvironments} flag — true today, and due to flip — so the list
     * covers it either way. An allow-list entry for a name the edge does not serve admits nobody.
     * <p>
     * <b>The two canonical origins DIFFER.</b> The idp's is its own host, where the login page is;
     * the edge's is the PROJECT'S DOOR, which is what lets it compose application names at all —
     * the apex carries no project label. Locally the edge's is the bare apex instead, because with
     * ACME off that value is also where the stated domain comes from.
     */
    @Test
    void browserSsoCarriesOneSessionOntoEveryServiceHost() {
        String localHosts = "localhost:8080,qits.localhost:8080,*.qits.localhost:8080,"
                + "*.prod.qits.localhost:8080";
        String local = ComposeTemplate.compose(tokens());
        assertThat(serviceBlock(local, "qits-platform-idp"))
                .contains("QITS_IDP_BROWSER_SSO_CANONICAL_ORIGIN: "
                        + "http://idp.prod.qits.localhost:8080")
                .contains("QITS_IDP_BROWSER_SSO_BROWSER_HOSTS: \"" + localHosts + "\"")
                .contains("QITS_IDP_BROWSER_SSO_COOKIE_DOMAIN: \"qits.localhost\"");
        assertThat(serviceBlock(local, "qits-platform-edge"))
                .contains("QITS_EDGE_SESSIONS_CANONICAL_ORIGIN: http://localhost:8080")
                .contains("QITS_EDGE_SESSIONS_BROWSER_HOSTS: \"" + localHosts + "\"");

        // Three shapes with a domain: the project's door, one label under it — which covers the
        // environment door and, after the flag flips, every application — and one label under the
        // environment's door, which is where an application is today. Neither the apex nor
        // *.<domain> is on it: the top-level environment tier and the unqualified application tier
        // are gone from the grammar, and another project's names are another project's to allow.
        String hosts = "qits." + DOMAIN + ",*.qits." + DOMAIN + ",*.prod.qits." + DOMAIN;
        String domain = ComposeTemplate.compose(tokens(DOMAIN));
        assertThat(serviceBlock(domain, "qits-platform-idp"))
                .contains("QITS_IDP_BROWSER_SSO_CANONICAL_ORIGIN: https://idp." + ENV + ".qits."
                        + DOMAIN)
                .contains("QITS_IDP_BROWSER_SSO_BROWSER_HOSTS: \"" + hosts + "\"")
                .contains("QITS_IDP_BROWSER_SSO_COOKIE_DOMAIN: \"" + DOMAIN + "\"");
        assertThat(serviceBlock(domain, "qits-platform-edge"))
                .contains("QITS_EDGE_SESSIONS_CANONICAL_ORIGIN: https://qits." + DOMAIN)
                .contains("QITS_EDGE_SESSIONS_BROWSER_HOSTS: \"" + hosts + "\"");
        assertThat(ComposeTemplate.extras(tokens(DOMAIN)))
                .contains("qits.platform.deployments.extras.qits-platform-idp.env.QITS_IDP_BROWSER_SSO_COOKIE_DOMAIN=" + DOMAIN)
                .contains("qits.platform.deployments.extras.qits-platform-edge.env.QITS_EDGE_SESSIONS_BROWSER_HOSTS=" + hosts);
        // The extras carry the same split: the idp's canonical origin is its own host, the edge's
        // is the door.
        assertThat(ComposeTemplate.extras(tokens()))
                .contains("qits.platform.deployments.extras.qits-platform-idp.env."
                        + "QITS_IDP_BROWSER_SSO_CANONICAL_ORIGIN=http://idp.prod.qits.localhost:8080")
                .contains("qits.platform.deployments.extras.qits-platform-edge.env."
                        + "QITS_EDGE_SESSIONS_CANONICAL_ORIGIN=http://localhost:8080")
                .contains("qits.platform.deployments.extras.qits-platform-edge.env."
                        + "QITS_EDGE_SESSIONS_BROWSER_HOSTS=" + localHosts);
        assertThat(ComposeTemplate.extras(tokens(DOMAIN)))
                .contains("qits.platform.deployments.extras.qits-platform-idp.env."
                        + "QITS_IDP_BROWSER_SSO_CANONICAL_ORIGIN=https://idp." + ENV + ".qits."
                        + DOMAIN)
                .contains("qits.platform.deployments.extras.qits-platform-edge.env."
                        + "QITS_EDGE_SESSIONS_CANONICAL_ORIGIN=https://qits." + DOMAIN);
        // The apex is not the canonical origin any more, on either file: it carries no project
        // label, so an edge pointed at it composes no application name and 404s the front door.
        assertThat(domain)
                .doesNotContain("QITS_EDGE_SESSIONS_CANONICAL_ORIGIN: https://" + DOMAIN + "\n");
        assertThat(ComposeTemplate.extras(tokens(DOMAIN)))
                .doesNotContain("QITS_EDGE_SESSIONS_CANONICAL_ORIGIN=https://" + DOMAIN + "\n");
    }

    /**
     * <b>WHERE EACH PULLER'S DOCKER CREDENTIAL IS, in both files.</b> Neither container has a home,
     * so the docker CLI reads no {@code ~/.docker/config.json} and every pull would be anonymous —
     * which the edge refuses — unless {@code DOCKER_CONFIG} names a mounted path. The deployer's
     * file goes beside its extras on the volume it already has; the orchestrator gets a volume that
     * holds nothing else.
     * <p>
     * All of it was written and mounted BEFORE the flip for one reason: gaining a credential must
     * not be a redeploy of the two services that pull everything this platform runs.
     */
    @Test
    void bothPullersAreGivenADockerConfigHome() {
        String compose = ComposeTemplate.compose(tokens());
        String deployer = serviceBlock(compose, "qits-deployments");
        String containers = serviceBlock(compose, ENV + "-qits-containers");

        assertThat(deployer).contains("DOCKER_CONFIG: /work/config")
                .contains("- qits-deployments-config:/work/config");
        assertThat(containers).contains("DOCKER_CONFIG: /work/config")
                .contains("- qits-containers-config:/work/config")
                // The socket stays: it is the whole component.
                .contains("- /var/run/docker.sock:/var/run/docker.sock");
        // A mount needs a declaration, and the orchestrator's volume is new.
        assertThat(compose).contains("  qits-containers-config:\n    name: qits-containers-config");

        assertThat(extras("qits-deployments"))
                .contains(".mounts[0]=volume:qits-deployments-config:/work/config")
                .contains("env.DOCKER_CONFIG=/work/config");
        assertThat(extras("qits-containers"))
                .contains(".mounts[0]=bind:/var/run/docker.sock:/var/run/docker.sock")
                .contains(".mounts[1]=volume:qits-containers-config:/work/config")
                .contains("env.DOCKER_CONFIG=/work/config");
    }

    /**
     * <b>THE LANDING PAGE STAYS BEHIND THE LOGIN WALL, by the owner's decision on ticket
     * qits-374.</b> An earlier change carved out an anonymous-read exemption for it; that decision
     * was reversed, so {@code QITS_EDGE_AUTH_ANONYMOUS_READ_APPS} is gone from both the seed stack
     * and the deployer extras. No key means no list means every name this edge serves needs a
     * bearer on every method, reads included — the byte plane stays closed exactly as before, and
     * now so does the landing page.
     * <p>
     * <b>This test is what stops either being reopened by an edit to the template.</b> It asserts
     * the key is ABSENT from both files — not merely that its value is not {@code landing},
     * {@code registry} or {@code mirror}, but that the key does not appear at all — asked of the
     * KEY lines rather than of the file whole, so a comment mentioning the key does not trip it.
     * Beside it stand the flip of 2026-08-14's other two values, kept exactly as they were: the
     * deployer told to authenticate its pulls, and ci told which registries a step must log in to.
     * Half of it is a platform whose deployer cannot pull, or step containers authenticating
     * against a door that never asks — so all three are asserted together.
     */
    @Test
    void nothingIsAnonymousAndTheBytePlaneStaysClosed() {
        String compose = ComposeTemplate.compose(tokens());
        String extras = ComposeTemplate.extras(tokens());
        String vhosts = "registry.prod.localhost:8080,mirror.prod.localhost:8080";
        String key = "QITS_EDGE_AUTH_ANONYMOUS_READ_APPS";

        // No declaration in either file. Asked of the KEY lines rather than of the file whole, so a
        // comment mentioning the key is free to do so without tripping this assertion.
        List<String> composeLines = compose.lines()
                .map(String::strip)
                .filter(line -> line.startsWith(key + ":"))
                .toList();
        assertThat(composeLines).as("seed edge anonymous-read declarations").isEmpty();
        List<String> extrasLines = extrasKeys().stream()
                .filter(line -> line.contains("env." + key + "="))
                .toList();
        assertThat(extrasLines).as("extras anonymous-read declarations").isEmpty();

        // In particular, no KEY LINE — never a comment — names the key with any of the three labels
        // the flip and the landing exemption ever touched. Asked of key lines only, the same
        // discipline as above, so a comment explaining the absence cannot trip this assertion.
        assertThat(compose.lines().map(String::strip)
                .filter(line -> line.startsWith(key + ":")))
                .noneMatch(line -> line.contains("landing") || line.contains("registry")
                        || line.contains("mirror"));
        assertThat(extras.lines().map(String::strip)
                .filter(line -> line.contains("env." + key + "=")))
                .noneMatch(line -> line.contains("landing") || line.contains("registry")
                        || line.contains("mirror"));

        // The deployer authenticates its own pull AND serialises the credential into every service
        // spec it creates. Spelled in the seed too: that deployer pulls before it reads any extras.
        assertThat(serviceBlock(compose, "qits-deployments"))
                .contains("QITS_PLATFORM_DEPLOYMENTS_REGISTRY_AUTH: \"true\"");
        assertThat(extras("qits-deployments"))
                .contains("env.QITS_PLATFORM_DEPLOYMENTS_REGISTRY_AUTH=true");

        // ci's half names BOTH stores: a step pushes to the hosted registry and pulls its base
        // images from the mirror, and neither answers a read anonymously any more. The port is
        // part of each entry — a docker credential is keyed by host:port.
        assertThat(serviceBlock(compose, ENV + "-qits-ci"))
                .contains("QITS_CI_DOCKER_AUTH_HOSTS: \"" + vhosts + "\"");
        assertThat(extras("qits-ci")).contains("env.QITS_CI_DOCKER_AUTH_HOSTS=" + vhosts);

        // The deployer's plain property is NOT how this is said: what starts a successor is the
        // application's extras, so the switch is an env key like every other value there.
        assertThat(extras.lines()
                .filter(line -> line.startsWith("qits.platform.deployments.registry-auth"))
                .toList()).isEmpty();
    }

    /**
     * <b>THE THREE VHOSTS ARE ALIASES OF THE EDGE ON qits-net, in both files.</b> Docker's embedded
     * DNS holds no wildcard, so nothing ON the network resolves a {@code *.localhost} name unless a
     * container claims it — and BuildKit inside a ci step fetches its registry token client-side,
     * which is a lookup made on this network. curl is a misleading probe: it resolves
     * {@code *.localhost} to loopback itself and asks no resolver at all.
     */
    @Test
    void theEdgeAnswersTheThreeVhostsOnTheNetworkToo() {
        String edge = serviceBlock(ComposeTemplate.compose(tokens()), "qits-platform-edge");

        // The long form, whole: the short `networks: [qits-net]` carries no aliases at all.
        assertThat(edge).contains("""
                    networks:
                """.stripTrailing());
        assertThat(edge).contains("""
                      qits-net:
                        aliases:
                          - registry.prod.localhost
                          - mirror.prod.localhost
                          - githost.prod.localhost
                """.stripTrailing());
        assertThat(edge).doesNotContain("networks: [qits-net]");
        // And the deployer's words for the same thing, applied when it creates the container.
        assertThat(extras("qits-platform-edge"))
                .contains(".aliases[0]=registry.prod.localhost")
                .contains(".aliases[1]=mirror.prod.localhost")
                .contains(".aliases[2]=githost.prod.localhost");
        // Nobody else claims a VHOST: two containers holding one name is a lookup that answers
        // whichever of them the DNS server picked. The rule is about the name, not about the key —
        // qits-configuration claims one alias of its own since the 2026-09-07 plane move, and it is
        // the name that service ITSELF answered to the day before, which no other container has
        // ever held. That entry goes at the epic's cutover.
        assertThat(extrasKeys()).filteredOn(line -> line.contains(".aliases["))
                .allSatisfy(line -> assertThat(line).startsWith(EXTRAS)
                        .containsAnyOf(EXTRAS + "qits-platform-edge.",
                                EXTRAS + "qits-configuration.aliases[0]=prod-qits-configuration"));
    }

    /**
     * <b>Every git address is qits-githost's, and none of them carries {@code /artifacts}.</b> The
     * git host is a service of its own since the byte-plane split, so a value still pointing at the
     * store is a clone of nothing.
     */
    @Test
    void everyGitAddressIsTheGitHostsOwn() {
        String compose = ComposeTemplate.compose(tokens());
        String host = "http://prod-qits-githost:8080";

        // ci itself uses the direct bearer route; its untrusted step containers use the edge.
        assertThat(serviceBlock(compose, ENV + "-qits-ci"))
                .contains("QITS_CI_GIT_HOST_URL: " + host)
                .contains("QITS_CI_CONTAINER_GIT_URL: http://githost.prod.internal:8080")
                .contains("QITS_CI_CONTAINER_GIT_AUDIENCE: prod-qits-githost");
        assertThat(extras("qits-ci")).contains("env.QITS_CI_GIT_HOST_URL=" + host)
                .contains("env.QITS_CI_CONTAINER_GIT_URL=http://githost.prod.internal:8080")
                .contains("env.QITS_CI_CONTAINER_GIT_AUDIENCE=prod-qits-githost");
        // The two services that push. Their key was renamed with the split — a deployment still
        // passing qits.artifacts.url configures nothing and silently takes the default.
        // The agent containers projects creates clone over the internal alias like ci's step
        // containers do: their credential helper answers Basic, which only that alias's oauth2
        // transport turns into a Bearer.
        assertThat(extras("qits-projects")).contains("env.QITS_GITHOST_URL=" + host)
                .contains("env.QITS_PROJECTS_CONTAINER_GIT_URL=http://githost.prod.internal:8080")
                .contains("env.QITS_EVENTS_URL=http://prod-qits-events:8080")
                .contains("env.QITS_AUTH_MACHINE_REQUIRED=true")
                .contains("env.QUARKUS_OIDC_AUTH_SERVER_URL=http://prod-qits-platform-idp:8080/idp")
                .doesNotContain("QITS_ARTIFACTS_URL");
        assertThat(extras("qits-workspaces")).contains("env.QITS_GITHOST_URL=" + host)
                .doesNotContain("QITS_ARTIFACTS_URL");
        // The deployer's OWN trusted address for the same host, and it is ENV on both sides of the
        // demotion: the extras file holds extras and nothing else now. Specs are read before the
        // runtime mutation begins, so this stays available for qits-githost's own cutover without
        // crossing the public edge.
        assertThat(ComposeTemplate.extras(tokens()))
                .doesNotContain("\nqits.platform.deployments.git-host-url=")
                .contains(EXTRAS + "qits-deployments.env.QITS_PLATFORM_DEPLOYMENTS_GIT_HOST_URL="
                        + host + "\n");
        assertThat(ComposeTemplate.compose(tokens()))
                .contains("QITS_PLATFORM_DEPLOYMENTS_GIT_HOST_URL: " + host);
        // The KEYS, not the comments: the git host's own block says in prose where its clone url
        // used to be, and that sentence is why the reader knows what moved.
        assertThat(extrasKeys()).allSatisfy(line -> assertThat(line)
                .doesNotContain("/artifacts/git"));
    }

    /**
     * <b>The demoted file states extras and nothing else.</b> Every other line is a comment. A plain
     * {@code qits.platform.deployments.<key>} here would be a setting the deployer still has to read
     * this file for, on a platform where the flip has made the file unread — so it would configure
     * nothing and the failure would be silent.
     */
    @Test
    void theExtrasFileCarriesOnlyExtras() {
        List<String> settings = ComposeTemplate.extras(tokens()).lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .filter(line -> !line.startsWith(EXTRAS))
                .toList();

        assertThat(settings).as("every non-comment line is an extras key").isEmpty();
    }

    /**
     * <b>The deployer's own settings are env, and they are spelled TWICE on purpose.</b> The seed
     * stack starts a deployer that has read no extras at all; the extras are what every self-update's
     * successor inherits — and since the update argv removes what the extras do not state, a variable
     * on the seed service alone would be gone at the deployer's first self-deploy.
     */
    @Test
    void theDeployersOwnSettingsAreEnvOnBothTheSeedServiceAndItsExtras() {
        String deployer = serviceBlock(ComposeTemplate.compose(tokens()), "qits-deployments");
        String extras = extras("qits-deployments");

        for (String pair : List.of(
                "QITS_PLATFORM_DEPLOYMENTS_GIT_HOST_URL: http://prod-qits-githost:8080",
                "QITS_PLATFORM_DEPLOYMENTS_REGISTRY_AUTH: \"true\"")) {
            assertThat(deployer).contains(pair);
        }
        assertThat(extras)
                .contains("env.QITS_PLATFORM_DEPLOYMENTS_GIT_HOST_URL=http://prod-qits-githost:8080")
                .contains("env.QITS_PLATFORM_DEPLOYMENTS_REGISTRY_AUTH=true")
                .contains("env.QITS_PLATFORM_DEPLOYMENTS_POSTGRES_ADMIN_PASSWORD=");
        // The flip's own two stay OFF the seed service: a seed deployer holding the url before
        // qits-configuration is deployed and imported refuses every deployment in the train.
        assertThat(deployer).doesNotContain("QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL")
                .doesNotContain("QUARKUS_OIDC_CLIENT_CONFIGURATION_");
        assertThat(extras)
                .contains("env.QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL=http://prod-qits-configuration:8080");
    }

    @Test
    void theGeneratedSeedAndExtrasContainNoGatewayRouteTable() {
        String compose = ComposeTemplate.compose(tokens());
        assertThat(compose).doesNotContain("\n  " + ENV + "-qits-gateway:\n")
                .doesNotContain("QITS_GATEWAY_PROXY_HOSTS");
        assertThat(ComposeTemplate.extras(tokens())).doesNotContain(EXTRAS + "qits-gateway.")
                .doesNotContain("QITS_GATEWAY_PROXY_HOSTS")
                .doesNotContain("QITS_EDGE_UPSTREAM_HOST_PATTERN");
    }

    /**
     * <b>ci's direct door to the deployer is gone from both files.</b> A green build travels the bus
     * now — ci -&gt; outbox -&gt; the bus -&gt; the deployer's durable subscriber — and qits-ci reads
     * no {@code qits.platform.deployments.intake-url} any more. A generated line naming a key
     * nothing reads outlives its reader and reads like configuration for years.
     * <p>
     * What replaces it is one address, and it has to be in both files for the same reason the intake
     * had to be: the eventstream jar's shipped default is the pre-rename {@code qits-events:8080},
     * which resolves to nothing on this network.
     */
    @Test
    void ciAnnouncesOnTheBusAndTheDirectIntakeIsGone() {
        String compose = ComposeTemplate.compose(tokens());

        assertThat(compose).doesNotContain("QITS_PLATFORM_DEPLOYMENTS_INTAKE_URL:");
        assertThat(extras("qits-ci")).doesNotContain("QITS_PLATFORM_DEPLOYMENTS_INTAKE_URL");
        assertThat(ComposeTemplate.extras(tokens()))
                .doesNotContain("env.QITS_PLATFORM_DEPLOYMENTS_INTAKE_URL=");

        assertThat(serviceBlock(compose, ENV + "-qits-ci"))
                .contains("QITS_EVENTS_URL: http://prod-qits-events:8080");
        assertThat(extras("qits-ci")).contains("env.QITS_EVENTS_URL=http://prod-qits-events:8080");
    }

    /**
     * <b>The bus is a seed service.</b> Every green build of a cold boot travels it, so it has to
     * answer before the first deployment rather than at its own place in the deploy train — which is
     * six deployables later. Its container name is its wire alias, which is both what ci and the
     * deployer dial and what the deployer searches for when it adopts this container's successor.
     */
    @Test
    void theBusIsInTheSeedAndIsHandedItsDatabase() {
        String compose = ComposeTemplate.compose(tokens());
        String block = serviceBlock(compose, "qits-events");

        assertThat(compose).contains("image: qits/events:latest");
        // The deployer's own default derivation of the database name, so the row it registers on the
        // first pipeline deployment is the row the bootstrap created.
        assertThat(block).contains("QITS_RESOURCE_DB_URL: "
                        + "jdbc:postgresql://prod-qits-oci-postgresql:5432/qits_events")
                .contains("QITS_RESOURCE_DB_USERNAME: qits_events")
                .contains("QITS_RESOURCE_DB_PASSWORD: \"bbbbccccddddeeee\"");
        // No volume and no machine auth: the store is the postgres beside it, and this service
        // enforces no gate — which is why its extras carry neither either.
        assertThat(block).doesNotContain("volumes:")
                .doesNotContain("QITS_AUTH_MACHINE_")
                .doesNotContain("QUARKUS_OIDC_");
        // The deployer injects the triple from `resources: postgresql:db`; a pin here would be
        // written after that injection and would outlive the next rotation.
        assertThat(extras("qits-events")).doesNotContain("QITS_RESOURCE_");
    }

    /**
     * <b>The container orchestrator is a seed service, and the SOCKET is what it is.</b> qits-ci runs
     * every pipeline step as a container it asks this service for, and the first pipeline of a cold
     * boot is minutes after the seed comes up — so it is here rather than at its own place in the
     * deploy train.
     * <p>
     * The mount and the group have to be in BOTH files. The compose half is what the seed can do;
     * the extras half is what the deployer starts every successor with, and a socket missing there
     * is a cutover that leaves a service passing health and able to do nothing.
     */
    @Test
    void theOrchestratorIsInTheSeedWithItsTwoStoresAndKeepsTheSocketAcrossACutover() {
        String compose = ComposeTemplate.compose(tokens());
        String block = serviceBlock(compose, ENV + "-qits-containers");
        String orchestrator = extras("qits-containers");

        assertThat(compose).contains("image: qits/containers:latest");
        // Two stores, two Flyway lineages: the registry of rows, and the eventstream outbox. Both
        // names are the deployer's own derivation, so the rows it registers later are these.
        assertThat(block).contains("QITS_RESOURCE_DB_URL: "
                        + "jdbc:postgresql://prod-qits-oci-postgresql:5432/qits_containers")
                .contains("QITS_RESOURCE_DB_USERNAME: qits_containers")
                .contains("QITS_RESOURCE_DB_PASSWORD: \"def0def0def0def0\"")
                .contains("QITS_RESOURCE_EVENTSTREAM_URL: jdbc:postgresql://"
                        + "prod-qits-oci-postgresql:5432/qits_containers_eventstream")
                .contains("QITS_RESOURCE_EVENTSTREAM_USERNAME: qits_containers_eventstream")
                .contains("QITS_RESOURCE_EVENTSTREAM_PASSWORD: \"0f0f0f0f0f0f0f0f\"");
        assertThat(block).contains("QITS_EVENTS_URL: http://prod-qits-events:8080");
        // Every route of this service is guarded, and the gate is what the seed states: the
        // audience is qits-platform, the one name every minted token carries, which no file has
        // to say. No oidc-client either — it validates and mints nothing — but it HOLDS a
        // credential, because its `docker pull` of every workload image presents one.
        assertThat(block).contains("QITS_AUTH_MACHINE_REQUIRED: \"true\"")
                .contains("QUARKUS_OIDC_AUTH_SERVER_URL: http://prod-qits-platform-idp:8080/idp")
                .contains("QITS_RESOURCE_IDP_CLIENT_ID: prod-qits-containers")
                .doesNotContain("QUARKUS_OIDC_CLIENT_");
        // The socket and the group, in the seed — the group as the PRIMARY one, because
        // group_add is a key a stack file refuses. No data volume: the store is the postgres
        // beside it and nothing it writes outlives the container.
        assertThat(block).contains("user: \"1001:988\"")
                .contains("- /var/run/docker.sock:/var/run/docker.sock");
        assertThat(compose).doesNotContain("qits-containers-data:");

        // And in the extras, which is the half that survives the first cutover. The mount says
        // `bind` rather than leaving the kind to a leading slash: a mistyped path that fell back to
        // a named volume would be an orchestrator with no socket and no error.
        assertThat(orchestrator)
                .contains(".mounts[0]=bind:/var/run/docker.sock:/var/run/docker.sock")
                .contains(".groups[0]=988")
                .contains("env.QITS_EVENTS_URL=http://prod-qits-events:8080")
                .contains("env.QUARKUS_OIDC_AUTH_SERVER_URL=http://prod-qits-platform-idp:8080/idp");
        // Both stores are declared in its deployments.yml, so the deployer injects the six
        // variables — a pin here is written after that injection and outlives the next rotation.
        // Its idp client is the same contract and the same reason, which is why QITS_RESOURCE_
        // covers both halves of this assertion.
        assertThat(orchestrator).doesNotContain("QITS_RESOURCE_");
        // No gateway route, and there must not be one: every caller is a machine on qits-net, and a
        // route would put a socket-holding orchestrator behind the platform's public door.
        assertThat(compose).doesNotContain("QITS_GATEWAY_PROXY_HOSTS_CONTAINERS");
        assertThat(ComposeTemplate.extras(tokens())).doesNotContain("QITS_GATEWAY_PROXY_HOSTS_CONTAINERS");
    }

    /**
     * <b>ci asks the orchestrator, and holds no socket at all.</b> This is the cutover's whole
     * observable shape in the generated files, and both halves matter: the address it dials, in
     * both files, and the grant it no longer gets, in both files.
     * <p>
     * The socket half is the one worth a test rather than a comment. ci executes repo-controlled
     * pipelines, so it was the platform's most exposed service AND the holder of root on the host;
     * a mount left in either file would restore that authority silently, because nothing in the
     * image reads it and no build would fail. The address half fails loudly instead — an
     * unreachable orchestrator is LAUNCH_FAILED on the first step — which is exactly why it needs
     * less protection than the mount.
     */
    @Test
    void ciAsksTheOrchestratorForItsStepContainersAndHoldsNoSocket() {
        String compose = ComposeTemplate.compose(tokens());
        String ci = serviceBlock(compose, ENV + "-qits-ci");
        String ciExtras = extras("qits-ci");

        // The wire alias, in both files: the image ships the unqualified qits-containers:8080,
        // which resolves to nothing on this network.
        assertThat(ci).contains("QITS_CONTAINERS_URL: http://prod-qits-containers:8080");
        assertThat(ciExtras).contains("env.QITS_CONTAINERS_URL=http://prod-qits-containers:8080");

        // And the grant that is gone. Not "no mount" alone — the socket group is the other half,
        // and either one without the other is a container that cannot use what it was given.
        assertThat(ci).doesNotContain("docker.sock").doesNotContain("group_add");
        assertThat(ciExtras).doesNotContain("docker.sock").doesNotContain(".groups[");

        // The service that DOES hold it still does. This is a concentration, not a removal.
        assertThat(serviceBlock(compose, ENV + "-qits-containers"))
                .contains("- /var/run/docker.sock:/var/run/docker.sock");
        assertThat(extras("qits-containers"))
                .contains(".mounts[0]=bind:/var/run/docker.sock:/var/run/docker.sock");
    }

    /**
     * <b>Nothing is told where a release lands any more, because a release lands on no branch.</b>
     * qits-workspaces used to be handed {@code QITS_WORKSPACES_RELEASE_ENTRY_BRANCH} so its release
     * door could fast-forward this environment's deploy ref after writing the release commit. The
     * door left that service, the ref is retired, and the key is unread by the image — so spelling
     * it would configure nothing while surviving every self-deploy, which is exactly how a dead
     * key outlives the thing it configured.
     */
    @Test
    void noApplicationIsToldAnEntryBranch() {
        // The KEYS, not the file: the comment above them says what left and why, and it names the
        // retired ref to do it.
        assertThat(String.join("\n", extrasKeys()))
                .doesNotContain("QITS_WORKSPACES_RELEASE_ENTRY_BRANCH")
                .doesNotContain("environment/");
        assertThat(ComposeTemplate.compose(tokens()).lines()
                .filter(line -> !line.strip().startsWith("#")).toList())
                .noneMatch(line -> line.contains("environment/"));
    }

    /**
     * <b>NOTHING RELEASES DURING THE SEED WINDOW, so the seed drives no release.</b> The release
     * executor's four addresses and its three named clients are the DEPLOYED qits-projects' — the
     * pair that lets it tag through the git host and cancel the ci runs it supersedes, and two more
     * that switch nothing on: the maintenance url enriches an announcement with the repositories
     * downstream of the one being released, and the workspaces url resolves the workspace that
     * stood on the branch a release consumed. Both are best-effort by construction — unset,
     * unreachable, refused or 404 and the release lands anyway, saying so in a WARN — so a platform
     * without either releases exactly as well.
     * <p>
     * The seed carries none of it, and this is the side of that fact a golden cannot hold: the
     * blocks are in {@code compose-golden/extras/qits-projects.properties}, the ABSENCE is here,
     * because it is an absence from the other file.
     * <p>
     * The workspaces url is the one address here that ships LOOKING set — the image's own default is
     * {@code http://qits-workspaces:8080}, a bare name no tiered estate answers to — so its bare
     * form is an absence worth stating beside the seed's. The pair above ship unset instead and say
     * so by refusing to release.
     */
    @Test
    void theSeedProjectsServiceDrivesNoRelease() {
        String seeded = serviceBlock(ComposeTemplate.compose(tokens()), ENV + "-qits-projects");

        assertThat(seeded).doesNotContain("RELEASE_REQUESTS_GITHOST_URL")
                .doesNotContain("RELEASE_REQUESTS_CI_URL")
                .doesNotContain("RELEASE_REQUESTS_MAINTENANCE_URL")
                .doesNotContain("RELEASE_REQUESTS_WORKSPACES_URL")
                .doesNotContain("QUARKUS_OIDC_CLIENT_CI_")
                .doesNotContain("QUARKUS_OIDC_CLIENT_MAINTENANCE_")
                .doesNotContain("QUARKUS_OIDC_CLIENT_WORKSPACES_");
        // The TIER's workspaces, never the bare name the image ships: a deployment that took the
        // default would look configured and reap nothing, for ever, in a WARN.
        assertThat(extras("qits-projects")).doesNotContain("WORKSPACES_URL=http://qits-workspaces:8080");
    }

    /**
     * <b>The golden directory is the checked-in list of what this file configures.</b> The array
     * that used to stand here was a second list to keep in step, and it had already drifted — it
     * named fifteen applications where the file renders nineteen. The files under
     * {@code src/test/resources/compose-golden/extras} cannot drift:
     * {@link ComposeTemplateGoldenTest} fails on a file with no application AND on an application
     * with no file, so an application that leaves this file leaves through a DELETED golden,
     * reviewed as the decision it is rather than as a line nobody noticed.
     */
    @Test
    void theExtrasCoverEveryApplicationThatNeedsMoreThanItsImage() {
        String properties = ComposeTemplate.extras(tokens());

        assertThat(ComposeTemplateGoldenTest.goldenApplications()).isNotEmpty();
        for (String application : ComposeTemplateGoldenTest.goldenApplications()) {
            assertThat(properties).as("extras of %s", application)
                    .contains(EXTRAS + application + ".");
        }
        // The free-form predecessor is gone from the KEYS — the header names it once, to say that a
        // deployment still carrying it configures nothing.
        assertThat(extrasKeys()).allSatisfy(line -> assertThat(line).startsWith(EXTRAS));
        assertThat(properties).doesNotContain("run-args.qits-")
                .doesNotContain("qits.cd.run-args.");
        // The retired pair is deployed by nothing, so it configures nothing.
        assertThat(properties).doesNotContain(EXTRAS + "qits-cd.")
                .doesNotContain(EXTRAS + "qits-serviceregistry.");
        // A key under a pre-rename application name configures NOTHING: the deployment comes up
        // with no volumes and no env, passes health, and has lost its database.
        assertThat(properties).doesNotContain(EXTRAS + "qits-idp.")
                .doesNotContain(EXTRAS + "qits-platform-deployments.")
                // The two the byte-plane split retired, on the same terms.
                .doesNotContain(EXTRAS + "qits-platform-artifacts.")
                .doesNotContain(EXTRAS + "qits-platform-docs.");
        assertThat(properties).contains("env.QITS_REPOSITORIES_GIT_PUSH_TOKEN=local-dev");
        assertThat(properties).contains(".groups[0]=988");
        assertThat(properties).doesNotContain("${DOCKER_GID}")
                .doesNotContain("${ENV_NAME}")
                .doesNotContain("${ALIAS_")
                .doesNotContain("${CLIENT_KEY_")
                .doesNotContain("${TIER_ENV_");
        assertThat(properties).doesNotContain("QITS_EDGE_UPSTREAM_HOST_PATTERN")
                .doesNotContain(EXTRAS + "qits-gateway.");
    }

    /**
     * <b>Every generated key parses as the grammar the deployer reads</b>, because an unknown or
     * malformed one is a REFUSED deployment now rather than a dropped flag. A typo in the template
     * is therefore a platform that will not deploy, and this is where it is caught instead.
     */
    @Test
    void everyGeneratedKeyIsOneTheDeployerCanRead() {
        for (String line : extrasKeys(tokens(DOMAIN))) {
            String element = line.substring(EXTRAS.length(), line.indexOf('='));
            String value = line.substring(line.indexOf('=') + 1);
            assertThat(element).as("key %s", line).containsPattern("^[a-z0-9-]+\\.(env\\."
                    + "[A-Za-z_][A-Za-z0-9_]*|(mounts|publishes|groups|aliases)\\[\\d+])$");
            if (element.contains(".mounts[")) {
                // The kind is stated rather than guessed from a leading slash.
                assertThat(value).as("mount %s", line)
                        .containsPattern("^(volume|bind):[^:]+:/[^:]*(:ro)?$");
            }
            if (element.contains(".publishes[")) {
                assertThat(value).as("publish %s", line)
                        .containsPattern("^(\\d+\\.\\d+\\.\\d+\\.\\d+:)?\\d+:\\d+(/(tcp|udp))?$");
            }
            if (element.contains(".aliases[")) {
                // A network alias is a HOSTNAME and nothing else: no port, no scheme, no path.
                // The vhosts carry the edge's port everywhere else, and one copied in here would
                // be a name the embedded DNS never answers.
                assertThat(value).as("alias %s", line)
                        .containsPattern("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9-]+)*$");
            }
        }
    }

    /**
     * <b>WHAT IS PUBLISHED, AND IT IS ONE THING:</b> the edge's HTTP port. Everything else on this
     * platform is reached through it or dialled at a wire alias. A service publish has no ip field
     * in either mode, so a port that must not reach the network cannot be published at all.
     */
    @Test
    void onlyTheEdgePublishesAHostPort() {
        List<String> publishing = extrasKeys().stream()
                .filter(line -> line.contains(".publishes["))
                .map(line -> line.substring(EXTRAS.length(), line.indexOf('.', EXTRAS.length())))
                .distinct()
                .toList();

        assertThat(publishing).containsExactly("qits-platform-edge");
        // The database whose only consumer was this CLI's cold-boot DDL, which dials the wire
        // alias. Neither file publishes it, in the seed or in the deployment.
        assertThat(ComposeTemplate.extras(tokens())).doesNotContain(":5433:5432");
        assertThat(ComposeTemplate.compose(tokens())).doesNotContain("5433");
    }

    /**
     * <b>The one address the HOST's docker daemon dials, in all four places that spell it.</b> Both
     * the seed and the deployment of ci and of the deployer carry it, and it is a NAME behind the
     * edge rather than a loopback port: the HOST's resolver answers {@code *.localhost} with the
     * loopback address, the edge routes on the name, and every method carries a bearer.
     */
    @Test
    void everyRegistryHostIsTheEdgesRegistryName() {
        String compose = ComposeTemplate.compose(tokens());
        String vhost = "registry.prod.localhost:8080";

        assertThat(serviceBlock(compose, ENV + "-qits-ci"))
                .contains("QITS_ARTIFACTS_REGISTRY_HOST: " + vhost);
        assertThat(serviceBlock(compose, "qits-deployments"))
                .contains("QITS_ARTIFACTS_REGISTRY_HOST: " + vhost);
        assertThat(extras("qits-ci")).contains("env.QITS_ARTIFACTS_REGISTRY_HOST=" + vhost);
        assertThat(extras("qits-deployments")).contains("env.QITS_ARTIFACTS_REGISTRY_HOST=" + vhost);
        // The retired spelling is nowhere in either file: a value still naming the closed port is a
        // push to nothing.
        assertThat(compose).doesNotContain("QITS_ARTIFACTS_REGISTRY_HOST: localhost:");
        assertThat(ComposeTemplate.extras(tokens()))
                .doesNotContain("QITS_ARTIFACTS_REGISTRY_HOST=localhost:");
    }

    /**
     * The two qits-projects dials were the GIT HOST's, and they left with it. The name resolver is
     * what turns a name-addressed clone into a repo id; the backup intake is gone entirely, because
     * a push is a durable event both consumers read off the bus.
     */
    @Test
    void theGitHostDialsProjectsForNameResolutionAndNobodyPostsAPushAnyMore() {
        assertThat(extras("qits-githost")).contains("env.QITS_PROJECTS_NAME_RESOLVER_URL="
                + "http://prod-qits-projects:8080/projects/api/projects");
        assertThat(extras("qits-artifacts")).doesNotContain("QITS_PROJECTS_");
        assertThat(extrasKeys()).allSatisfy(line -> assertThat(line)
                .doesNotContain("QITS_PROJECTS_INTAKE_URL")
                .doesNotContain("QITS_CI_INTAKE_URL"));
        assertThat(ComposeTemplate.compose(tokens()).lines()
                .filter(line -> line.strip().startsWith("QITS_"))
                .toList())
                .allSatisfy(line -> assertThat(line).doesNotContain("QITS_CI_INTAKE_URL"));
    }

    /**
     * The database the deployer boots from, in both generated files. The mount path is the whole
     * risk: postgres 18 keeps PGDATA at {@code /var/lib/postgresql/18/docker}, so a volume mounted
     * at the pre-18 {@code /var/lib/postgresql/data} sits BESIDE the cluster — everything works and
     * every byte is written into the container layer, until the next recreate.
     */
    @Test
    void postgresIsSeededAndDeployedWithItsVolumeAtTheOnePathThatKeepsTheData() {
        String compose = ComposeTemplate.compose(tokens());
        String block = serviceBlock(compose, ENV + "-qits-oci-postgresql");
        String postgres = extras("qits-oci-postgresql");

        assertThat(compose).contains("image: qits/oci-postgresql:latest");
        // The mount, not the comment beside it, which names the wrong path on purpose.
        assertThat(block).contains("- qits-oci-postgresql-data:/var/lib/postgresql\n")
                .doesNotContain("- qits-oci-postgresql-data:/var/lib/postgresql/data");
        // It publishes nothing: every consumer dials the alias on 5432, this CLI included.
        assertThat(block).doesNotContain("ports:");
        assertThat(block).contains("POSTGRES_PASSWORD: \"0123456789abcdef\"");
        assertThat(block).contains("pg_isready -U postgres");
        assertThat(block).contains("restart_policy:");
        // No depends_on anywhere: a dependency would resurrect a compose sibling beside its
        // deployed replacement. The deployer's refuse-to-boot and restart policy are the retry.
        // The comments that say so are not one.
        assertThat(compose.lines().map(String::strip).filter(line -> line.startsWith("depends_on"))
                .toList()).isEmpty();

        assertThat(postgres).contains(".mounts[0]=volume:qits-oci-postgresql-data:/var/lib/postgresql")
                .doesNotContain("/var/lib/postgresql/data");
        // The host publish does NOT survive the cutover any more: its only consumer was this CLI's
        // cold-boot DDL, which dials the wire alias, and a swarm service cannot bind it to loopback.
        assertThat(postgres).doesNotContain(".publishes[");
        assertThat(postgres).contains("env.POSTGRES_PASSWORD=0123456789abcdef");
    }

    @Test
    void theIdpsDeploymentCarriesNoClientAtAll() {
        String idp = extras("qits-platform-idp");

        // No volume and no datasource: the store is a database the deployer provisions from
        // `resources: postgresql:db` and injects. The signing key is in it, so pinning the triple
        // here would outlive a rotation and take every token in flight down with it.
        assertThat(idp).doesNotContain(".mounts[")
                .doesNotContain("QUARKUS_DATASOURCE_IDP_JDBC_URL")
                .doesNotContain("QITS_RESOURCE_");
        // AND NOT ONE CLIENT. This block used to mirror the idp's whole registry — the list, a
        // secret per client, the audiences, the roles and the two project claims — because the idp
        // was configured from a file. A client is a ROW it holds now, created against the running
        // service, and a redeployed idp keeps every one of them.
        assertThat(idp).doesNotContain("QITS_IDP_CLIENT")
                .doesNotContain("QITS_IDP_SEED_CLIENT_ID")
                .doesNotContain("QITS_IDP_SEED_CLIENT_SECRET")
                .doesNotContain("secret-");
        // What stays is what a repository cannot know: the issuer, the browser-SSO trio and the
        // passkey binding.
        assertThat(idp).contains("env.QITS_IDP_ISSUER=")
                .contains("env.QITS_IDP_WEBAUTHN_RP_ID=");
    }

    /**
     * ci's and the idp's stores — the bus has a test of its own above. Every one of these containers
     * is started by compose before any deployer exists, so the seed file is the only place their
     * credentials can come from, and the bootstrap created the roles and the databases over JDBC
     * minutes earlier.
     */
    @Test
    void theSeedsDatabaseConsumersAreHandedTheirTriples() {
        String compose = ComposeTemplate.compose(tokens());
        String ci = serviceBlock(compose, ENV + "-qits-ci");
        String idp = serviceBlock(compose, "qits-platform-idp");

        // Two stores, two Flyway lineages, two databases: ci's own and the eventstream outbox's.
        assertThat(ci).contains("QITS_RESOURCE_DB_URL: "
                        + "jdbc:postgresql://prod-qits-oci-postgresql:5432/qits_ci")
                .contains("QITS_RESOURCE_DB_USERNAME: qits_ci")
                .contains("QITS_RESOURCE_DB_PASSWORD: \"aaaabbbbccccdddd\"")
                .contains("QITS_RESOURCE_EVENTSTREAM_URL: "
                        + "jdbc:postgresql://prod-qits-oci-postgresql:5432/qits_ci_eventstream")
                .contains("QITS_RESOURCE_EVENTSTREAM_USERNAME: qits_ci_eventstream")
                .contains("QITS_RESOURCE_EVENTSTREAM_PASSWORD: \"eeeeffff00001111\"");
        // ci's /data held the H2 files and, before that, a git mirror per repository. The image has
        // no /data at all now — and no socket either, since it stopped starting containers itself.
        assertThat(ci).doesNotContain("QUARKUS_DATASOURCE_CI_JDBC_URL")
                .doesNotContain("QUARKUS_DATASOURCE_EVENTSTREAM_JDBC_URL")
                .doesNotContain("- qits-ci-data:/data")
                .doesNotContain("docker.sock");

        assertThat(idp).contains("QITS_RESOURCE_DB_URL: "
                        + "jdbc:postgresql://prod-qits-oci-postgresql:5432/qits_platform_idp")
                .contains("QITS_RESOURCE_DB_USERNAME: qits_platform_idp")
                .contains("QITS_RESOURCE_DB_PASSWORD: \"2222333344445555\"");
        assertThat(idp).doesNotContain("QUARKUS_DATASOURCE_IDP_JDBC_URL")
                .doesNotContain("- qits-platform-idp-data:/data");

        // A volume declaration with no mount is a volume nothing ever fills, and the next reader
        // has to work out which. Every service that lost its only mount lost its declaration too.
        assertThat(compose).doesNotContain("qits-ci-data:")
                .doesNotContain("qits-platform-idp-data:")
                .doesNotContain("qits-events-data:")
                .doesNotContain("qits-deployments-data:")
                // THE WHOLE BYTE PLANE. All three stores are databases now, so none of the three
                // declares or mounts anything.
                .doesNotContain("qits-artifacts-data")
                .doesNotContain("qits-platform-mirror-data")
                .doesNotContain("qits-githost-data");
        // What is left is FILES that no database replaced, plus the postgres those databases are in
        // — which is what proves the sweep took the byte plane and not every volume in the file.
        assertThat(compose).contains("qits-projects-data:")
                .contains("qits-workspaces-data:")
                .contains("qits-stt-data:")
                .contains("qits-oci-postgresql-data:");
    }

    /**
     * <b>THE SEED CANNOT DIAL A DATABASE NOBODY CREATED.</b> Every service in this file is started
     * before any deployer exists, so its credential is spelled here and its role and database are
     * created by the {@code seed-postgres} phase. A store missing from that phase's list is not a
     * misconfiguration a person sees — it is a container that dies at Flyway's first connect, tens
     * of phases into a boot.
     * <p>
     * The two lists are the same SET, in both directions: a database nothing dials would be
     * provisioned forever after the service that wanted it left.
     */
    @Test
    void everyDatabaseTheSeedDialsIsOneTheSeedCreates() {
        // The jdbc ones only: QITS_RESOURCE_IDP_URL is the same contract for a credential rather
        // than a store, and the issuer it names is not a database anybody creates.
        List<String> dialled = ComposeTemplate.compose(tokens()).lines()
                .map(String::strip)
                .filter(line -> line.startsWith("QITS_RESOURCE_") && line.contains("_URL: jdbc:"))
                .map(line -> line.substring(line.lastIndexOf('/') + 1))
                .distinct()
                .toList();

        assertThat(dialled).contains("qits_artifacts", "qits_platform_mirror");
        assertThat(dialled)
                .containsExactlyInAnyOrderElementsOf(SeedPhases.SEED_DATABASES);
    }

    /**
     * THE GUARD ON THE WHOLE MOVE, and it is one rule rather than a negative per application. Every
     * service that flipped to postgres declares its store in its own deployments.yml, so the
     * deployer injects the triple, its injection comes first and the last assignment of a key wins
     * — a triple pinned here would win and never be rotated again.
     * <p>
     * NO FILE STORE IS LEFT EITHER. qits-artifacts was the last file database and the git host the
     * last blob directory, so the whole byte plane deploys stateless.
     * <p>
     * The per-application negatives this used to spell one block at a time are the goldens' now: a
     * block compared whole says what it does not carry as exactly as what it does. What is left
     * here is the part no single block can state — WHICH applications, out of all of them, are
     * allowed to answer yes.
     */
    @Test
    void noDeploymentCarriesAFileDatabase() {
        assertThat(extrasKeys()).allSatisfy(line -> assertThat(line)
                .doesNotContain("jdbc:h2")
                .doesNotContain("QITS_ARTIFACTS_BLOBS_DIR"));

        // The deployer is the one application that may pin its own store, because it is what does
        // the injecting and nothing injects into it. Every other name appearing here would be a
        // password this bootstrap issued outliving the rotation that replaces it.
        assertThat(applicationsWith("env.QITS_RESOURCE_")).containsExactly("qits-deployments");

        // THE COUNTER-EXAMPLE, and it is what proves the sweep took the byte plane on purpose
        // rather than every mount it could reach: a `-data` volume is a service's own tree of files,
        // which no database replaced. qits-containers is absent from it and was not: its registry
        // of rows is in the postgres beside it now.
        assertThat(applicationsWith("-data:/")).containsExactly(
                "qits-oci-postgresql", "qits-projects", "qits-stt", "qits-workspaces");
    }

    /**
     * <b>THE HOST'S DOCKER SOCKET IS GRANTED TO THREE APPLICATIONS, and each grant is a block
     * somebody wrote on purpose.</b> qits-containers starts every workload on the host,
     * qits-deployments is a deployer, and qits-platform-system owns the admin console's terminals.
     * A fourth is a decision and not a mount: it needs the bind AND {@code groups[0]}, which is what
     * makes the socket usable by a container running as uid 1001, plus a comment saying why the
     * power belongs there rather than behind an endpoint on one of the three.
     * <p>
     * This is the assertion the goldens cannot make. Each of the three blocks holds its own mount
     * and its own group, and a golden proves each of them; only a question asked across every block
     * at once can say that there are three of them.
     */
    @Test
    void theHostsSocketIsGrantedToExactlyThreeApplications() {
        assertThat(applicationsWith("/var/run/docker.sock")).containsExactly(
                "qits-containers", "qits-deployments", "qits-platform-system");
        // The group is the other half, and either one without the other is a container that cannot
        // use what it was given — so the two lists are the same three names.
        assertThat(applicationsWith(".groups[")).containsExactly(
                "qits-containers", "qits-deployments", "qits-platform-system");
    }

    /**
     * <b>THE ALIAS TABLE IS IN THE SEED, and this is everything it needs to answer there.</b>
     * A repository's public address is {@code /git/<projectId>/<repoName>} and it resolves through
     * qits-projects or nowhere, so a boot whose seed cannot start this service is a boot with no
     * clone url at all — which is why the ids used to be spelled like the names.
     */
    @Test
    void theSeedCarriesTheAliasTableAndTheThreeStoresItNeeds() {
        String block = serviceBlock(ComposeTemplate.compose(tokens()), ENV + "-qits-projects");

        // THREE STORES, THREE TRIPLES — its own rows, the epics beside them and the eventstream
        // outbox, which its deployments.yml declares as three resources because they are three
        // Flyway lineages. Spelled here and nowhere else, like the git host's six: the seed starts
        // this container before any deployer exists, and the urls have no fallback behind them.
        assertThat(block)
                .contains("QITS_RESOURCE_DB_URL: jdbc:postgresql://" + ENV
                        + "-qits-oci-postgresql:5432/qits_projects")
                .contains("QITS_RESOURCE_DB_PASSWORD: \"7777888899990000\"")
                .contains("QITS_RESOURCE_EPICS_URL: jdbc:postgresql://" + ENV
                        + "-qits-oci-postgresql:5432/qits_epics")
                .contains("QITS_RESOURCE_EPICS_PASSWORD: \"3333444455556666\"")
                .contains("QITS_RESOURCE_EVENTSTREAM_URL: jdbc:postgresql://" + ENV
                        + "-qits-oci-postgresql:5432/qits_projects_eventstream")
                .contains("QITS_RESOURCE_EVENTSTREAM_PASSWORD: \"abcdabcdabcdabcd\"");
        // The git host it creates bares on, and the credential every one of those calls needs:
        // this service refuses to open a socket to the git host without a bearer, so a seed on the
        // shipped client-enabled=false could create no wrapper and therefore no project.
        assertThat(block)
                .contains("QITS_GITHOST_URL: http://" + ENV + "-qits-githost:8080")
                .contains("QITS_RESOURCE_IDP_URL: http://prod-qits-platform-idp:8080/idp")
                .contains("QITS_RESOURCE_IDP_CLIENT_ID: " + ENV + "-qits-projects")
                .contains("QITS_RESOURCE_IDP_CLIENT_SECRET: \"secret-" + ENV
                        + "-qits-projects\"")
                // One credential, and no named client per peer: the image builds those over
                // qits.resource.idp.*, so a quintet here would be a second copy of one identity.
                .doesNotContain("QUARKUS_OIDC_CLIENT_");
        // Its mirrors go on the volume its successor mounts, not into a container layer — and not
        // under ${user.home}, which is the literal "?" for this image's passwd-less uid.
        assertThat(block)
                .contains("QITS_PROJECTS_DATA_DIR: /data/mirrors")
                .contains("- qits-projects-data:/data");
    }

    /**
     * <b>THE SEED'S TWO SWITCHES, and each one is a boot that fails without it.</b>
     * <p>
     * The self-seed is HELD because creating the wrapper origin needs a bearer the idp mints, and
     * the idp is a seed service starting in the same second: a self-seed that fired first would
     * fail, roll its own transaction back and not try again until the container restarted. The
     * {@code qits-project} phase turns it on once the idp has answered.
     * <p>
     * The wrapper RECONCILE stays off for the whole seed window. Under the 2026-08-21 ruling no
     * storage id is a name, so a reconcile against a platform whose repositories do not exist yet
     * matches no entry and takes its remaining arm — mirroring every repository in from the org,
     * minutes before this bootstrap has created a single bare.
     * <p>
     * The DEPLOYED container spells neither, so both are on at their shipped defaults: that
     * reconcile is the first of the platform's life and every entry matches a row by alias.
     */
    @Test
    void theSeedHoldsTheSelfSeedAndTheDeployedContainerDoesNot() {
        String block = serviceBlock(ComposeTemplate.compose(tokens()), ENV + "-qits-projects");

        assertThat(block)
                .contains("QITS_STARTUP_SEED_ENABLED: \"false\"")
                .contains("QITS_STARTUP_SEED_RECONCILE_REPOSITORIES: \"false\"");
        assertThat(extras("qits-projects")).doesNotContain("QITS_STARTUP_SEED");
    }

    @Test
    void everythingGeneratedIsToldWhereTelemetryGoes() {
        // The images ship the bare qits-observability, and the 2026-08-08 rename killed that name.
        // An exporter dialling a name that does not resolve drops every trace and every log AND
        // retries, so a missing line here is a dark platform and a container log full of attempts.
        String url = "http://prod-qits-observability:8080";
        String compose = ComposeTemplate.compose(tokens());

        // qits-oci-postgresql is the one exception, and it is not an omission: it is upstream
        // postgres, which has no exporter to point anywhere.
        for (String name : PlatformModel.CORE) {
            if (name.equals("oci-postgresql")) {
                assertThat(serviceBlock(compose, PlatformModel.wireAlias(name, ENV)))
                        .doesNotContain("QITS_OBSERVABILITY_URL");
                continue;
            }
            assertThat(serviceBlock(compose, PlatformModel.wireAlias(name, ENV)))
                    .as("seed service %s", name)
                    .contains("QITS_OBSERVABILITY_URL: " + url);
        }
        assertThat(extrasKeys().stream()
                .map(line -> line.substring(EXTRAS.length()).split("\\.")[0])
                .distinct()
                .filter(application -> !application.equals("qits-oci-postgresql"))
                .toList())
                .isNotEmpty()
                .allSatisfy(application -> assertThat(extras(application))
                        .as("deployed %s", application)
                        .contains("env.QITS_OBSERVABILITY_URL=" + url));
        // The receiver is an ordinary OTLP client of itself. Consistent rather than clever: the
        // alternative leaves exactly one container spamming retries at a dead name.
        assertThat(extras("qits-observability"))
                .isEqualTo(EXTRAS + "qits-observability.env.QITS_OBSERVABILITY_URL=" + url);
    }

    /**
     * <b>THE NAMESERVER IS GONE, from both files and from the seed's databases.</b> This platform
     * serves no dns: a domain's records live at whatever provider holds it.
     */
    @Test
    void neitherFileCarriesTheRetiredNameserver() {
        assertThat(ComposeTemplate.compose(tokens())).doesNotContain("qits-platform-dns")
                .doesNotContain("qits/platform-dns")
                .doesNotContain("qits_platform_dns")
                .doesNotContain("8053");
        assertThat(ComposeTemplate.extras(tokens())).doesNotContain("qits-platform-dns");
        assertThat(ComposeTemplate.extras(tokens(DOMAIN))).doesNotContain("qits-platform-dns");
    }

    /**
     * <b>THE INVARIANT OF THE WHOLE DOMAIN FEATURE.</b> Every fragment a domain adds is appended to a
     * line the template already had, so taking the fragments back out of the rendered files leaves
     * exactly what a platform with no domain renders — no blank line, no orphan comment about a
     * feature that is off, nothing for the next reader to wonder about.
     */
    @Test
    void aDomainAddsItsFragmentsAndChangesNothingElse() {
        String compose = ComposeTemplate.compose(tokens(DOMAIN));
        String extras = ComposeTemplate.extras(tokens(DOMAIN));
        for (String fragment : DomainTokens.of(Optional.of(DOMAIN)).values()) {
            assertThat(fragment).isNotEmpty();
            compose = compose.replace(fragment, "");
            extras = extras.replace(fragment, "");
        }
        // THE PASSKEY BINDING IS THE ONE THING A DOMAIN MOVES rather than adds, and it cannot be a
        // fragment: an rp id is the HOST a credential is bound to and the origins are the door a
        // browser arrives at, so a domain replaces both values instead of appending to a line.
        // Put back, so that what is left to compare is everything else.
        // The allow-list first, because it holds the other names as substrings, then the idp's own
        // host, which is the longer spelling of the door's.
        String hosts = "qits." + DOMAIN + ",*.qits." + DOMAIN + ",*.prod.qits." + DOMAIN;
        String localHosts = "localhost:8080,qits.localhost:8080,*.qits.localhost:8080,"
                + "*.prod.qits.localhost:8080";
        compose = compose.replace(hosts, localHosts)
                .replace("https://idp." + ENV + ".qits." + DOMAIN, "http://idp.prod.qits.localhost:8080")
                .replace("https://qits." + DOMAIN, "http://localhost:8080")
                .replace("RP_ID: " + DOMAIN, "RP_ID: qits.localhost")
                .replace("COOKIE_DOMAIN: \"" + DOMAIN + "\"", "COOKIE_DOMAIN: \"qits.localhost\"");
        extras = extras.replace(hosts, localHosts)
                .replace("https://idp." + ENV + ".qits." + DOMAIN, "http://idp.prod.qits.localhost:8080")
                .replace("https://qits." + DOMAIN, "http://localhost:8080")
                .replace("RP_ID=" + DOMAIN, "RP_ID=qits.localhost")
                .replace("COOKIE_DOMAIN=" + DOMAIN, "COOKIE_DOMAIN=qits.localhost");

        assertThat(compose).isEqualTo(ComposeTemplate.compose(tokens()));
        assertThat(extras).isEqualTo(ComposeTemplate.extras(tokens()));
    }

    /** With no domain, not one trace of the TLS ports or the certificate volume. */
    @Test
    void withNoDomainThereIsNoTls() {
        String compose = ComposeTemplate.compose(tokens());
        String extras = ComposeTemplate.extras(tokens());

        assertThat(compose).doesNotContain("letsencrypt")
                .doesNotContain("QUARKUS_TLS_")
                .doesNotContain("443:8443")
                .doesNotContain("127.0.0.1:9000");
        assertThat(extras).doesNotContain("letsencrypt")
                .doesNotContain("QUARKUS_TLS_");
        // The edge keeps the one port it always published, and nothing asks for an ip.
        assertThat(extras("qits-platform-edge")).contains(".publishes[0]=8080:8080")
                .doesNotContain(".publishes[1]");
    }

    /**
     * The edge's TLS wiring, in both files. The extras half is the one that is easy to forget and
     * expensive to: it is what the deployer starts the successor with, so a piece missing there is a
     * cutover that takes 443 and the certificate away while health goes on passing on 8080.
     * <p>
     * The management port keeps its loopback ip, which a swarm service cannot express — so a domain
     * on a swarm platform is a REFUSED deployment saying so, rather than an unauthenticated ACME
     * endpoint on every interface. That refusal is the feature, and the generated comment says it.
     */
    @Test
    void aDomainGivesTheEdgeItsCertificateSlotInBothFiles() {
        String compose = ComposeTemplate.compose(tokens(DOMAIN));
        String edge = serviceBlock(compose, "qits-platform-edge");
        String edgeExtras = extras("qits-platform-edge", tokens(DOMAIN));

        // DNS-01 needs neither the old port-80 challenge route nor its management interface.
        assertThat(edge).contains("published: 8080")
                .contains("published: 443")
                .doesNotContain("published: 80\n")
                .doesNotContain("published: 9000");
        assertThat(edge).contains(
                        "QUARKUS_TLS_KEY_STORE_PEM_ACME_CERT: /work/.letsencrypt/current/lets-encrypt.crt")
                .contains("QUARKUS_TLS_KEY_STORE_PEM_ACME_KEY: /work/.letsencrypt/current/lets-encrypt.key")
                .contains("QUARKUS_TLS_RELOAD_PERIOD: 1m")
                .contains("QITS_EDGE_ACME_ENABLED: \"true\"")
                .contains("- qits-edge-letsencrypt:/work/.letsencrypt");
        // A mounted volume has to be declared, or compose refuses the file.
        assertThat(compose).contains("qits-edge-letsencrypt:\n    name: qits-edge-letsencrypt");
        // insecure-requests stays at its default: every health poll in the boot speaks plain HTTP.
        assertThat(compose).doesNotContain("INSECURE_REQUESTS");

        // DNS-01 needs neither a public port 80 listener nor a management listener.
        assertThat(edgeExtras).contains(".publishes[0]=8080:8080")
                .contains(".publishes[1]=443:8443")
                .doesNotContain(".publishes[2]=80:8080")
                .doesNotContain("9000")
                .contains(".mounts[0]=volume:qits-edge-letsencrypt:/work/.letsencrypt")
                .contains("env.QUARKUS_TLS_KEY_STORE_PEM_ACME_CERT=/work/.letsencrypt/current/lets-encrypt.crt")
                .contains("env.QUARKUS_TLS_KEY_STORE_PEM_ACME_KEY=/work/.letsencrypt/current/lets-encrypt.key")
                .contains("env.QUARKUS_TLS_RELOAD_PERIOD=1m")
                .contains("env.QITS_EDGE_ACME_ENABLED=true");
    }

    @Test
    void aDomainCanReuseAnExistingDnsSecret() {
        String existing = "qits-dns-hetzner-token-v1";
        Map<String, String> values = tokens(DOMAIN);
        values.putAll(DomainTokens.of(Optional.of(DOMAIN), "staging",
                "hostmaster@" + DOMAIN, "", Optional.of(existing)));

        String compose = ComposeTemplate.compose(values);
        assertThat(compose).contains("secrets:\n  " + existing + ":\n    external: true")
                .contains("- source: " + existing)
                .doesNotContain("qits-dns-hetzner-token-e3b0c44298fc");
    }

    @Test
    void theEnvironmentNameReachesEveryGeneratedAddress() {
        Map<String, String> other = tokens();
        other.put("ENV_NAME", "preprod");
        other.putAll(PlatformModel.modelTokens("preprod"));

        assertThat(ComposeTemplate.compose(other))
                .contains("\n  preprod-qits-ci:\n")
                .contains("QITS_EDGE_ENVIRONMENTS: preprod")
                // ci's own client id follows the tier, on the seed where it is spelled at all.
                .contains("QITS_RESOURCE_IDP_CLIENT_ID: preprod-qits-ci")
                // The registry name carries the tier too: one edge, one door, a name per tier.
                .contains("QITS_ARTIFACTS_REGISTRY_HOST: registry.preprod.localhost:8080");
        assertThat(ComposeTemplate.extras(other))
                .contains("env.QITS_ARTIFACTS_REGISTRY_HOST=registry.preprod.localhost:8080")
                .contains("env.QITS_CI_CONTAINER_GIT_AUDIENCE=preprod-qits-githost")
                // The bus is still a PLATFORM service — one instance for the estate — and its
                // ADDRESS carries the tier anyway, which is the platform-service retirement seen
                // from the only side that has landed so far. Both spellings resolve to the one
                // container, so the qualifier costs nothing and the address stops being a special
                // case. Deriving it rather than spelling the environment in is what makes this
                // follow the model instead of restating it.
                .contains("env.QITS_EVENTS_URL=http://preprod-qits-events:8080")
                // The configuration store the same way, and its two lines now agree rather than
                // differing: the address the deployer is given and the alias this tier's callers
                // hold are one string.
                .contains("env.QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL="
                        + "http://preprod-qits-configuration:8080")
                .contains("qits-configuration.aliases[0]=preprod-qits-configuration")
                .doesNotContain("QITS_GATEWAY_PROXY_HOSTS")
                .contains("env.QITS_OBSERVABILITY_URL=http://preprod-qits-observability:8080");
    }

    // --- the identity seam: which side of the cutover each key is delivered on ---------------------

    /**
     * <b>THE GUARD IS STAGED, and the staging IS the placement of one key.</b>
     * {@code qits.githost.storage-client} closes {@code /git/<repoId>} to qits-projects' client and
     * to nothing else — right for the platform the boot leaves behind, impossible for the boot
     * itself, which creates every repository over that exact scheme with its own credential before
     * qits-projects has been deployed to hold a client at all. So the SEED serves the compat arm and
     * the guard arrives with the git host's own deployment.
     */
    @Test
    void theStorageClientGuardIsInTheExtrasAndNotOnTheSeed() {
        assertThat(extras("qits-githost"))
                .contains("env.QITS_GITHOST_STORAGE_CLIENT=" + ENV + "-qits-projects");
        assertThat(serviceBlock(ComposeTemplate.compose(tokens()), ENV + "-qits-githost"))
                .doesNotContain("QITS_GITHOST_STORAGE_CLIENT");
        // And it names the projects service's own client id, which is the only one qits-idp ever
        // stamps clients/<that> into. Naming anything else would close the scheme to everybody.
        // That id is the wire alias, which is also what the seed block hands the service as its
        // QITS_RESOURCE_IDP_CLIENT_ID.
        assertThat(PlatformModel.wireAlias("projects", ENV)).isEqualTo(ENV + "-qits-projects");
    }

    /**
     * <b>The resolver is on BOTH sides</b>, unlike the guard: without it the name-addressed scheme
     * 404s, and that is the address every clone url on this platform is. The value stops before
     * {@code /{projectId}} — the path under it is qits-projects' own.
     */
    @Test
    void theNameResolverIsWiredOnTheSeedAndOnTheDeployment() {
        String url = "http://" + ENV + "-qits-projects:8080/projects/api/projects";
        assertThat(serviceBlock(ComposeTemplate.compose(tokens()), ENV + "-qits-githost"))
                .contains("QITS_PROJECTS_NAME_RESOLVER_URL: " + url);
        assertThat(extras("qits-githost")).contains("env.QITS_PROJECTS_NAME_RESOLVER_URL=" + url);
    }

    /**
     * <b>ci's catalogue is the same on the seed and on the deployment.</b> The seed once withheld
     * it: qits-projects was said to arrive fourteen phases later. It is a seed service in the same
     * stack now, and a seed ci without the key builds runs that carry no project — whose
     * {@code QITS_CI_REPOSITORY_URL} is {@code /git/<storage uuid>}, from which the recipes'
     * frontend-submodule clones derive a {@code /git/<name>} that 404s (trial boot 2026-09-05).
     */
    @Test
    void theProjectsCatalogueIsOnTheSeedAndInCisExtras() {
        String url = "http://" + ENV + "-qits-projects:8080";
        assertThat(extras("qits-ci")).contains("env.QITS_CI_PROJECTS_URL=" + url);
        assertThat(serviceBlock(ComposeTemplate.compose(tokens()), ENV + "-qits-ci"))
                .contains("QITS_CI_PROJECTS_URL: " + url);
    }
}
