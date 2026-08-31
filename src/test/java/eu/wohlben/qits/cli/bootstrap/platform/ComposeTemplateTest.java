package eu.wohlben.qits.cli.bootstrap.platform;

import eu.wohlben.qits.cli.bootstrap.phases.SeedPhases;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

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
        values.put("IDP", "http://qits-platform-idp:8080/idp");
        values.put("PUSH_TOKEN", "local-dev");
        // A one-build host: the 16 GB VPS the formula exists for.
        values.put("CI_CONCURRENT_BUILDS", "1");
        values.put("MACHINE_REQUIRED", "true");
        values.put("MACHINE_CLIENT", "true");
        values.put("DOCKER_GID", "988");
        values.put("DAEMON_SHA", "abc123");
        values.put("IDP_CLIENTS", String.join(",", PlatformModel.idpClients(ENV)));
        values.put("IDP_AUDIENCES", PlatformModel.idpAudiences(ENV));
        for (String app : PlatformModel.IDP_CLIENT_APPS) {
            values.put("IDP_SECRET_" + PlatformModel.clientKey(app),
                    "secret-" + PlatformModel.wireAlias(app, ENV));
        }
        // The passkey binding of a platform with no domain: every *.localhost name is a secure
        // context by itself, so the ceremony works on the edge's plain HTTP port. The rp id carries
        // the ENVIRONMENT's label, which is what each service's own host is a child of — the idp's
        // included, and that is where the ceremony happens.
        values.put("WEBAUTHN_RP_ID", ENV + ".localhost");
        values.put("WEBAUTHN_ORIGINS", "http://idp." + ENV + ".localhost:8080");
        values.put("PUBLIC_ORIGIN", "http://" + ENV + ".localhost:8080");
        values.put("IDP_ORIGIN", "http://idp." + ENV + ".localhost:8080");
        values.put("BROWSER_HOSTS", ENV + ".localhost:8080,*." + ENV + ".localhost:8080");
        values.put("SESSION_COOKIE_DOMAIN", ENV + ".localhost");
        // No domain: every fragment is empty, which is the ordinary platform.
        values.putAll(DomainTokens.of(Optional.empty()));
        return values;
    }

    /** The same values with a domain configured. */
    static Map<String, String> tokens(String domain) {
        Map<String, String> values = tokens();
        // The binding follows the address a browser arrives at, which a domain moves to TLS. The
        // login is idp. of the APEX, because the environment label is optional for the default tier.
        values.put("WEBAUTHN_RP_ID", domain);
        values.put("WEBAUTHN_ORIGINS", "https://idp." + domain);
        values.put("PUBLIC_ORIGIN", "https://" + domain);
        values.put("IDP_ORIGIN", "https://idp." + domain);
        values.put("BROWSER_HOSTS", domain + "," + ENV + "." + domain
                + ",*." + domain + ",*." + ENV + "." + domain);
        values.put("SESSION_COOKIE_DOMAIN", domain);
        values.putAll(DomainTokens.of(Optional.of(domain)));
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
                "QITS_IDP_CLIENT_PROD_QITS_CI_SECRET: \"secret-prod-qits-ci\"");
        assertThat(compose).contains("user: \"1001:988\"");
        assertThat(compose).contains("QITS_CI_DAEMON_VERSION: \"abc123\"");
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
        // The alias and client-key families, which replaced a single ENV_KEY the template pasted a
        // repository name after. One left unfilled is an address or a config key rendered as text.
        assertThat(compose).doesNotContain("${ALIAS_").doesNotContain("${CLIENT_KEY_")
                .doesNotContain("${TIER_ENV_");
        assertThat(compose).doesNotContain("${IDP_SECRET_");
        assertThat(compose).doesNotContain("${IDP_AUDIENCES}");
        assertThat(compose).doesNotContain("${IDP_CLIENTS}");
    }

    @Test
    void keepsTheWarningAboutUserHomeReadable() {
        // ${user.home} is the failure the comment warns about, not a placeholder to fill.
        assertThat(ComposeTemplate.compose(tokens())).contains("under ${user.home}");
    }

    @Test
    void everySeedServiceIsInTheStackUnderTheNameItsPeersDial() {
        String compose = ComposeTemplate.compose(tokens());

        // The service KEY is the name now: a stack ignores container_name, and what a peer
        // resolves is the service — under qits_<alias> and under the bare alias both.
        for (String name : PlatformModel.CORE) {
            assertThat(compose).contains("\n  " + PlatformModel.wireAlias(name, ENV) + ":\n");
        }
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
                .contains("QITS_EDGE_APPS_MIRROR_HOST_PATTERN: \"qits-platform-mirror\"")
                .contains("QITS_EDGE_APPS_GITHOST_HOST_PATTERN: \"{env}-qits-githost\"");
        assertThat(edgeExtras)
                .contains("env.QITS_EDGE_APPS_REGISTRY_HOST_PATTERN={env}-qits-artifacts")
                .contains("env.QITS_EDGE_APPS_MIRROR_HOST_PATTERN=qits-platform-mirror")
                .contains("env.QITS_EDGE_APPS_GITHOST_HOST_PATTERN={env}-qits-githost");
    }

    /**
     * <b>The web editor is a fourth app alias and nothing more.</b> {@code
     * editor.<project>.<domain>} is one origin per project, but the edge reads two labels: a
     * project slug is not a known environment, so the name takes the {@code <app>.<domain>}
     * reading and lands in the DEFAULT tier. The entry is therefore the same shape the byte plane
     * uses, and {@code {env}} in it always resolves to the default environment.
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
     * <b>The deployer and the bus are PLATFORM services, and every name derived from that is in the
     * two generated files.</b> Both moved on 2026-08-17, and the whole point of the move landing in
     * PlatformModel is that no template spells either alias for itself: an environment-qualified
     * copy left anywhere is a peer dialling a name nothing answers to, or an audience the idp never
     * mints — the silent-401 class, run backwards.
     */
    @Test
    void theDeployerAndTheBusCarryNoTierInEitherGeneratedFile() {
        String compose = ComposeTemplate.compose(tokens());
        String extras = ComposeTemplate.extras(tokens());

        // The seed service keys, which are what every address in both files resolves to.
        assertThat(compose).contains("\n  qits-deployments:\n").contains("\n  qits-events:\n");
        // And nowhere a tier-qualified spelling of either, in either file.
        assertThat(compose).doesNotContain(ENV + "-qits-deployments")
                .doesNotContain(ENV + "-qits-events");
        assertThat(extras).doesNotContain(ENV + "-qits-deployments")
                .doesNotContain(ENV + "-qits-events");
        // The values that flipped with them, each one an address or an identity a peer holds:
        // the fleet-wide bus url, the artifacts GC's pin source — kept, because the jar defaults to
        // the post-rename qits-platform-deployments, which nothing answers to — the deployer's
        // inbound audience, and its idp client id.
        assertThat(extras).contains("env.QITS_EVENTS_URL=http://qits-events:8080")
                .contains("qits-artifacts.env.QITS_ARTIFACTS_GC_PINS_CD_BASE_URL="
                        + "http://qits-deployments:8080/platform-deployments/api")
                .contains("qits-deployments.env.QITS_AUTH_MACHINE_AUDIENCE=qits-deployments")
                .contains("env.QUARKUS_OIDC_CLIENT_CONFIGURATION_CLIENT_ID=qits-deployments");
        // The idp seeds the same bare id, and its per-client keys embed it — so the key loses the
        // tier too, in both files.
        assertThat(compose).contains("QITS_IDP_CLIENT_QITS_DEPLOYMENTS_SECRET")
                .doesNotContain("QITS_IDP_CLIENT_PROD_QITS_DEPLOYMENTS_");
        assertThat(extras).contains("env.QITS_IDP_CLIENT_QITS_DEPLOYMENTS_ROLES=")
                .doesNotContain("QITS_IDP_CLIENT_PROD_QITS_DEPLOYMENTS_");
        assertThat(PlatformModel.idpAudiences(ENV)).contains("qits-deployments");
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
        assertThat(block).contains("QITS_EVENTS_URL: http://qits-events:8080");
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
                .contains("QUARKUS_OIDC_AUTH_SERVER_URL: http://qits-platform-idp:8080/idp")
                .doesNotContain("QUARKUS_OIDC_CLIENT_CLIENT_ENABLED");
    }

    @Test
    void everyServiceValidatesTheAudienceTheIdpActuallyMintsFor() {
        // The images ship their pre-rename ids, and a token minted for prod-qits-ci that ci
        // validates as qits-ci is a 401 on a gate that looks right from both sides.
        String compose = ComposeTemplate.compose(tokens());

        // ci ASKS FOR THE ORCHESTRATOR, and that audience moved with the caller: it asked for the
        // deployer while it posted build-succeeded, and that call was retired in favour of the bus.
        // Its client id is also its OWNER string at qits-containers, which compares the token's
        // `sub` to the owner in the path — so the two lines below are one fact twice.
        assertThat(serviceBlock(compose, ENV + "-qits-ci"))
                .contains("QITS_AUTH_MACHINE_AUDIENCE: prod-qits-ci")
                .contains("QUARKUS_OIDC_CLIENT_CLIENT_ID: prod-qits-ci")
                .contains("QUARKUS_OIDC_CLIENT_GRANT_OPTIONS_CLIENT_AUDIENCE: "
                        + "prod-qits-containers");
        assertThat(serviceBlock(compose, "qits-deployments"))
                .contains("QITS_AUTH_MACHINE_AUDIENCE: qits-deployments");
        // The artifacts service validates a tier-qualified audience since the split, and mints
        // nothing at all: the git host's announcement was the only token it ever asked for.
        assertThat(serviceBlock(compose, ENV + "-qits-artifacts"))
                .contains("QITS_AUTH_MACHINE_AUDIENCE: prod-qits-artifacts")
                .doesNotContain("QUARKUS_OIDC_CLIENT_");

        assertThat(extras("qits-ci"))
                .contains("env.QITS_AUTH_MACHINE_AUDIENCE=prod-qits-ci")
                .contains("env.QUARKUS_OIDC_CLIENT_CLIENT_ID=prod-qits-ci")
                .contains("env.QUARKUS_OIDC_CLIENT_GRANT_OPTIONS_CLIENT_AUDIENCE="
                        + "prod-qits-containers");
        assertThat(extras("qits-deployments"))
                .contains("env.QITS_AUTH_MACHINE_AUDIENCE=qits-deployments");
        assertThat(extras("qits-artifacts"))
                .contains("env.QITS_AUTH_MACHINE_AUDIENCE=prod-qits-artifacts")
                .doesNotContain("QUARKUS_OIDC_CLIENT_");
        // Workspaces is both a machine caller and a machine resource: the commissioned workspace
        // daemon dials its control socket directly, so the inbound audience must be the same
        // environment-qualified service id the IdP minted for that socket.
        assertThat(extras("qits-workspaces"))
                .contains("env.QITS_AUTH_MACHINE_REQUIRED=true")
                .contains("env.QITS_AUTH_MACHINE_AUDIENCE=prod-qits-workspaces")
                .contains("env.QUARKUS_OIDC_AUTH_SERVER_URL=http://qits-platform-idp:8080/idp");
        // The mirror remains anonymous; githost validates machine bearers and user-forwarded roles.
        assertThat(extras("qits-platform-mirror")).doesNotContain("QITS_AUTH_MACHINE_")
                .doesNotContain("QUARKUS_OIDC_");
        assertThat(extras("qits-githost")).contains("env.QITS_AUTH_MACHINE_AUDIENCE=prod-qits-githost")
                .doesNotContain("QUARKUS_OIDC_");
    }

    /**
     * <b>qits-configuration is an ordinary environment application with a gate and nothing else.</b>
     * Its image ships the bare {@code qits-configuration} as the audience, deliberately — an
     * environment-qualified default would bake one tier into an image every tier shares — so the
     * alias has to be spelled here or every bearer the deployer mints is refused.
     * <p>
     * No mount, no publish and no datasource: {@code resources: postgresql:db} in its own
     * deployments.yml is what provisions its store, and a triple here would be an operator pin that
     * outlives the deployer's next rotation.
     */
    @Test
    void theConfigurationServiceIsGatedAndOtherwisePlain() {
        String configuration = extras("qits-configuration");

        assertThat(configuration)
                .contains("env.QITS_AUTH_MACHINE_REQUIRED=true")
                .contains("env.QITS_AUTH_MACHINE_AUDIENCE=prod-qits-configuration")
                .contains("env.QUARKUS_OIDC_AUTH_SERVER_URL=http://qits-platform-idp:8080/idp")
                .contains("env.QITS_OBSERVABILITY_URL=http://prod-qits-observability:8080");
        assertThat(configuration).doesNotContain(".mounts[")
                .doesNotContain(".publishes[")
                .doesNotContain(".groups[")
                .doesNotContain("QITS_RESOURCE_DB_")
                // It validates and mints nothing, so it holds no oidc client of its own.
                .doesNotContain("QUARKUS_OIDC_CLIENT_");
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
        // The credential that read presents: the deployer's OWN client, and a tier-qualified
        // audience the image deliberately does not default.
        assertThat(deployer)
                .contains("env.QUARKUS_OIDC_CLIENT_CONFIGURATION_CLIENT_ENABLED=true")
                .contains("env.QUARKUS_OIDC_CLIENT_CONFIGURATION_AUTH_SERVER_URL="
                        + "http://qits-platform-idp:8080/idp")
                .contains("env.QUARKUS_OIDC_CLIENT_CONFIGURATION_CLIENT_ID=qits-deployments")
                .contains("env.QUARKUS_OIDC_CLIENT_CONFIGURATION_CREDENTIALS_SECRET="
                        + "secret-qits-deployments")
                .contains("env.QUARKUS_OIDC_CLIENT_CONFIGURATION_GRANT_OPTIONS_CLIENT_AUDIENCE="
                        + "prod-qits-configuration");
        // The DEFAULT unnamed client stays off: the extension creates it whether or not anything
        // injects it, and an enabled client with no auth-server-url fails the deployer's boot.
        assertThat(deployer).doesNotContain("env.QUARKUS_OIDC_CLIENT_CLIENT_ENABLED");
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
        // And there is no seed service for it either: it is deployed through the pipeline like every
        // other ordinary environment application.
        assertThat(compose).doesNotContain("\n  " + ENV + "-qits-configuration:\n");
        assertThat(PlatformModel.CORE).doesNotContain("configuration");
    }

    /**
     * <b>The deployer's client needs ROLES now, and it needed none until it had a guarded peer.</b>
     * Every route of qits-configuration is {@code @RolesAllowed({qits:admin, qits:system})} and the
     * idp puts a client's roles in the token's {@code groups} claim — so a deployer client with no
     * roles mints a token that validates and is then refused 403 on the read that decides what a
     * deployment is configured with.
     */
    @Test
    void theDeployersIdpClientCarriesTheSystemRoleInBothFiles() {
        assertThat(serviceBlock(ComposeTemplate.compose(tokens()), "qits-platform-idp"))
                .contains("QITS_IDP_CLIENT_QITS_DEPLOYMENTS_ROLES: "
                        + "\"qits:system,qits-platform:system\"");
        assertThat(extras("qits-platform-idp"))
                .contains("env.QITS_IDP_CLIENT_QITS_DEPLOYMENTS_ROLES="
                        + "qits:system,qits-platform:system");
        // And the audience it may ask for, which is what keeps the mint out of invalid_target.
        assertThat(extras("qits-platform-idp"))
                .contains("env.QITS_IDP_CLIENT_QITS_DEPLOYMENTS_AUDIENCES="
                        + PlatformModel.idpAudiences(ENV));
        assertThat(PlatformModel.idpAudiences(ENV)).contains("prod-qits-configuration");
    }

    /**
     * <b>The technical processes service, and the four credentials one process needs.</b> Its gc
     * run asks the deployer and ci for the pins, hands them to qits-artifacts, and tells
     * qits-containers what to reclaim — four peers behind four different audiences, so four named
     * oidc clients rather than one reused token. All four present this service's own id, and the
     * targets are wire aliases in both shapes: the three environment services carry the tier, the
     * deployer carries none.
     */
    @Test
    void theOrchestratorHoldsAClientPerPeerAndDialsEachOneByItsAlias() {
        String orchestrator = extras("qits-platform-orchestrator");

        // Its own gate. The audience is the bare alias: it is a platform service, so there is no
        // tier in the name a peer would validate against.
        assertThat(orchestrator)
                .contains("env.QITS_AUTH_MACHINE_REQUIRED=true")
                .contains("env.QITS_AUTH_MACHINE_AUDIENCE=qits-platform-orchestrator")
                .contains("env.QUARKUS_OIDC_AUTH_SERVER_URL=http://qits-platform-idp:8080/idp")
                .contains("env.QITS_OBSERVABILITY_URL=http://prod-qits-observability:8080");
        // Where each step goes. The deployer's is the one with no tier in it.
        assertThat(orchestrator)
                .contains("env.QITS_ORCHESTRATOR_TARGETS_ARTIFACTS_URL="
                        + "http://prod-qits-artifacts:8080")
                .contains("env.QITS_ORCHESTRATOR_TARGETS_CONTAINERS_URL="
                        + "http://prod-qits-containers:8080")
                .contains("env.QITS_ORCHESTRATOR_TARGETS_CI_URL=http://prod-qits-ci:8080")
                .contains("env.QITS_ORCHESTRATOR_TARGETS_DEPLOYMENTS_URL="
                        + "http://qits-deployments:8080");
        // Four clients, and the AUDIENCE is what makes them four: each peer validates its own, so
        // one client with one audience would be a run whose every step but one is a 401.
        assertThat(orchestrator)
                .contains("env.QUARKUS_OIDC_CLIENT_ARTIFACTS_GRANT_OPTIONS_CLIENT_AUDIENCE="
                        + "prod-qits-artifacts")
                .contains("env.QUARKUS_OIDC_CLIENT_CONTAINERS_GRANT_OPTIONS_CLIENT_AUDIENCE="
                        + "prod-qits-containers")
                .contains("env.QUARKUS_OIDC_CLIENT_CI_GRANT_OPTIONS_CLIENT_AUDIENCE="
                        + "prod-qits-ci")
                .contains("env.QUARKUS_OIDC_CLIENT_DEPLOYMENTS_GRANT_OPTIONS_CLIENT_AUDIENCE="
                        + "qits-deployments");
        for (String client : List.of("ARTIFACTS", "CONTAINERS", "CI", "DEPLOYMENTS")) {
            assertThat(orchestrator).as("the %s client", client)
                    .contains("env.QUARKUS_OIDC_CLIENT_" + client + "_CLIENT_ENABLED=true")
                    .contains("env.QUARKUS_OIDC_CLIENT_" + client + "_AUTH_SERVER_URL="
                            + "http://qits-platform-idp:8080/idp")
                    // This service's OWN id, never a borrowed one: a refused step has to name the
                    // service that was refused.
                    .contains("env.QUARKUS_OIDC_CLIENT_" + client + "_CLIENT_ID="
                            + "qits-platform-orchestrator")
                    .contains("env.QUARKUS_OIDC_CLIENT_" + client + "_CREDENTIALS_SECRET="
                            + "secret-qits-platform-orchestrator");
        }
        // The DEFAULT unnamed client stays off: the extension creates it whether or not anything
        // injects it, and an enabled client with no auth-server-url fails the boot.
        assertThat(orchestrator).doesNotContain("env.QUARKUS_OIDC_CLIENT_CLIENT_ENABLED");
        // It starts no container and holds no store of anyone else's: no socket, no group, no
        // publish, and its own run history is declared in its deployments.yml rather than pinned.
        assertThat(orchestrator).doesNotContain(".mounts[")
                .doesNotContain(".publishes[")
                .doesNotContain(".groups[")
                .doesNotContain("QITS_RESOURCE_DB_");
        // And no seed service either: it is deployed through the pipeline like every other
        // ordinary application, near the end of the train.
        assertThat(ComposeTemplate.compose(tokens()))
                .doesNotContain("\n  qits-platform-orchestrator:\n");
    }

    /**
     * <b>The orchestrator's client carries BOTH planes' system roles, and its process is why.</b>
     * The gc routes of qits-artifacts, qits-containers and qits-ci are {@code qits:system}; the
     * deployer's pin union at {@code /platform-deployments/api/pins} is {@code qits-platform:system}.
     * The idp puts a client's roles in the token's {@code groups} claim, so one missing role is a
     * step that authenticates and is then refused 403 — and a run that cannot read the pins refuses
     * to delete anything at all.
     */
    @Test
    void theOrchestratorsIdpClientCarriesBothSystemRolesInBothFiles() {
        assertThat(serviceBlock(ComposeTemplate.compose(tokens()), "qits-platform-idp"))
                .contains("QITS_IDP_CLIENT_QITS_PLATFORM_ORCHESTRATOR_SECRET: "
                        + "\"secret-qits-platform-orchestrator\"")
                .contains("QITS_IDP_CLIENT_QITS_PLATFORM_ORCHESTRATOR_ROLES: "
                        + "\"qits:system,qits-platform:system\"")
                .contains("QITS_IDP_CLIENT_QITS_PLATFORM_ORCHESTRATOR_AUDIENCES: \""
                        + PlatformModel.idpAudiences(ENV) + "\"");
        assertThat(extras("qits-platform-idp"))
                .contains("env.QITS_IDP_CLIENT_QITS_PLATFORM_ORCHESTRATOR_SECRET="
                        + "secret-qits-platform-orchestrator")
                .contains("env.QITS_IDP_CLIENT_QITS_PLATFORM_ORCHESTRATOR_ROLES="
                        + "qits:system,qits-platform:system")
                .contains("env.QITS_IDP_CLIENT_QITS_PLATFORM_ORCHESTRATOR_AUDIENCES="
                        + PlatformModel.idpAudiences(ENV));
        // Every peer it mints for is on that list. An audience a client may not ask for is
        // invalid_target, which never reaches the peer's own gate.
        assertThat(PlatformModel.idpAudiences(ENV)).contains("prod-qits-artifacts")
                .contains("prod-qits-containers").contains("prod-qits-ci")
                .contains("qits-deployments");
    }

    /**
     * <b>The dependency inventory, and the three credentials one scan-and-bump needs.</b> It reads
     * the catalog from qits-projects, the manifests from qits-githost and asks qits-ci to apply a
     * bump — three peers behind three different audiences, so three named oidc clients rather than
     * one reused token. The registries it reads versions from are unguarded on qits-net, so it
     * holds no client for them and this file emits none.
     */
    @Test
    void theMaintenanceServiceHoldsAClientPerGuardedPeerAndReadsBothRegistriesBare() {
        String maintenance = extras("qits-platform-maintenance");

        // Its own gate. The audience is the bare alias: it is a platform service, so there is no
        // tier in the name a peer would validate against.
        assertThat(maintenance)
                .contains("env.QITS_AUTH_MACHINE_REQUIRED=true")
                .contains("env.QITS_AUTH_MACHINE_AUDIENCE=qits-platform-maintenance")
                .contains("env.QUARKUS_OIDC_AUTH_SERVER_URL=http://qits-platform-idp:8080/idp")
                .contains("env.QITS_OBSERVABILITY_URL=http://prod-qits-observability:8080");
        // What it reads and what it asks. All three are this tier's wire aliases.
        assertThat(maintenance)
                .contains("env.QITS_MAINTENANCE_TARGETS_PROJECTS_URL="
                        + "http://prod-qits-projects:8080")
                .contains("env.QITS_MAINTENANCE_TARGETS_GITHOST_URL="
                        + "http://prod-qits-githost:8080")
                .contains("env.QITS_MAINTENANCE_TARGETS_CI_URL=http://prod-qits-ci:8080");
        // Where a version comes from: the hosted registries for an internal dependency, the
        // mirror's pull-through caches for an external one. The store carries the tier, the mirror
        // does not — one cache per machine.
        assertThat(maintenance)
                .contains("env.QITS_MAINTENANCE_REGISTRIES_MAVEN_URL="
                        + "http://prod-qits-artifacts:8080/artifacts/maven/maven")
                .contains("env.QITS_MAINTENANCE_REGISTRIES_NPM_URL="
                        + "http://prod-qits-artifacts:8080/artifacts/npm/npm")
                .contains("env.QITS_MAINTENANCE_REGISTRIES_OCI_URL="
                        + "http://prod-qits-artifacts:8080/v2")
                .contains("env.QITS_MAINTENANCE_MIRROR_MAVEN_URL="
                        + "http://qits-platform-mirror:8080/artifacts/maven/central")
                .contains("env.QITS_MAINTENANCE_MIRROR_NPM_URL="
                        + "http://qits-platform-mirror:8080/artifacts/npm/npmjs");
        // WHICH ENVIRONMENT A BUMP RAN IN, recorded on every bump row. It is not QITS_ENVIRONMENT:
        // this service belongs to no tier, and the test below asserts it is told none.
        assertThat(maintenance).contains("env.QITS_MAINTENANCE_ENVIRONMENT=prod");
        // Three clients, and the AUDIENCE is what makes them three: each peer validates its own.
        assertThat(maintenance)
                .contains("env.QUARKUS_OIDC_CLIENT_PROJECTS_GRANT_OPTIONS_CLIENT_AUDIENCE="
                        + "prod-qits-projects")
                .contains("env.QUARKUS_OIDC_CLIENT_GITHOST_GRANT_OPTIONS_CLIENT_AUDIENCE="
                        + "prod-qits-githost")
                .contains("env.QUARKUS_OIDC_CLIENT_CI_GRANT_OPTIONS_CLIENT_AUDIENCE="
                        + "prod-qits-ci");
        for (String client : List.of("PROJECTS", "GITHOST", "CI")) {
            assertThat(maintenance).as("the %s client", client)
                    .contains("env.QUARKUS_OIDC_CLIENT_" + client + "_CLIENT_ENABLED=true")
                    .contains("env.QUARKUS_OIDC_CLIENT_" + client + "_AUTH_SERVER_URL="
                            + "http://qits-platform-idp:8080/idp")
                    // This service's OWN id, never a borrowed one: a refused read has to name the
                    // service that was refused.
                    .contains("env.QUARKUS_OIDC_CLIENT_" + client + "_CLIENT_ID="
                            + "qits-platform-maintenance")
                    .contains("env.QUARKUS_OIDC_CLIENT_" + client + "_CREDENTIALS_SECRET="
                            + "secret-qits-platform-maintenance");
        }
        // The two registry clients stay OFF, and the service ships them disabled: the registry
        // routes and the mirror's proxies are unguarded on qits-net, so enabling them here would
        // be a token nothing asks for against an audience nothing validates.
        assertThat(maintenance).doesNotContain("env.QUARKUS_OIDC_CLIENT_ARTIFACTS_")
                .doesNotContain("env.QUARKUS_OIDC_CLIENT_MIRROR_");
        // The DEFAULT unnamed client stays off for the orchestrator's reason: the extension creates
        // it either way, and an enabled client with no auth-server-url fails the boot.
        assertThat(maintenance).doesNotContain("env.QUARKUS_OIDC_CLIENT_CLIENT_ENABLED");
        // It starts no container and holds no store of anyone else's: no socket, no group, no
        // publish, and its own inventory is declared in its deployments.yml rather than pinned.
        assertThat(maintenance).doesNotContain(".mounts[")
                .doesNotContain(".publishes[")
                .doesNotContain(".groups[")
                .doesNotContain("QITS_RESOURCE_DB_");
        // And no seed service: nothing calls it, so nothing waits on it. It is deployed through
        // the pipeline like every other ordinary application, late in the train.
        assertThat(ComposeTemplate.compose(tokens()))
                .doesNotContain("\n  qits-platform-maintenance:\n");
    }

    /**
     * <b>Its client carries both planes' system roles and the WILDCARD PROJECT CLAIM.</b> The roles
     * are the orchestrator's pair — qits:system for the catalog, the content reads and ci's
     * trigger, qits-platform:system for the platform plane's half — and qits:admin is deliberately
     * absent, because that is the role of a person. The claim is the part that is not optional:
     * qits-ci's trigger calls {@code requireProject("*")}, so a bump naming ONE repository is
     * refused without a token granted every project.
     */
    @Test
    void theMaintenanceIdpClientCarriesBothSystemRolesAndTheWildcardProjectClaim() {
        assertThat(serviceBlock(ComposeTemplate.compose(tokens()), "qits-platform-idp"))
                .contains("QITS_IDP_CLIENT_QITS_PLATFORM_MAINTENANCE_SECRET: "
                        + "\"secret-qits-platform-maintenance\"")
                .contains("QITS_IDP_CLIENT_QITS_PLATFORM_MAINTENANCE_ROLES: "
                        + "\"qits:system,qits-platform:system\"")
                .contains("QITS_IDP_CLIENT_QITS_PLATFORM_MAINTENANCE_AUDIENCES: \""
                        + PlatformModel.idpAudiences(ENV) + "\"")
                .contains("QITS_IDP_CLIENT_QITS_PLATFORM_MAINTENANCE_CLAIMS_PROJECT: \"*\"");
        assertThat(extras("qits-platform-idp"))
                .contains("env.QITS_IDP_CLIENT_QITS_PLATFORM_MAINTENANCE_SECRET="
                        + "secret-qits-platform-maintenance")
                .contains("env.QITS_IDP_CLIENT_QITS_PLATFORM_MAINTENANCE_ROLES="
                        + "qits:system,qits-platform:system")
                .contains("env.QITS_IDP_CLIENT_QITS_PLATFORM_MAINTENANCE_AUDIENCES="
                        + PlatformModel.idpAudiences(ENV))
                .contains("env.QITS_IDP_CLIENT_QITS_PLATFORM_MAINTENANCE_CLAIMS_PROJECT=*");
        // qits:admin is the human role and this service is never a person.
        assertThat(extras("qits-platform-idp"))
                .doesNotContain("QITS_IDP_CLIENT_QITS_PLATFORM_MAINTENANCE_ROLES="
                        + "qits:system,qits-platform:system,qits:admin");
        // Every peer it mints for is on the audience list. An audience a client may not ask for is
        // invalid_target, which never reaches the peer's own gate.
        assertThat(PlatformModel.idpAudiences(ENV)).contains("prod-qits-projects")
                .contains("prod-qits-githost").contains("prod-qits-ci");
        // And the client itself is named, or the idp answers invalid_client to a secret it holds.
        assertThat(PlatformModel.idpClients(ENV)).contains("qits-platform-maintenance");
    }

    /**
     * <b>The base system panels are the THIRD holder of the host's docker socket</b>, after the
     * container orchestrator and the deployer, and this block is the deliberate act of granting it.
     * The alternative was an exec endpoint on qits-containers' machine API, which would put a shell
     * into any container behind a credential every service on qits-net can mint; the power stays in
     * one admin console behind qits:admin instead, and a console that owns the PTYs has to hold the
     * socket itself.
     */
    @Test
    void theSystemConsoleGetsTheSocketTheSocketGroupAndItsMirrorCredential() {
        String system = extras("qits-platform-system");

        // The socket, and the group that makes it usable by a container running as uid 1001. The
        // mount says `bind` rather than leaving the kind to a leading slash: a mistyped path that
        // fell back to a named volume would be a console with no daemon and no error.
        assertThat(system)
                .contains(".mounts[0]=bind:/var/run/docker.sock:/var/run/docker.sock")
                .contains(".groups[0]=988");
        // The credential mount and the variable that makes the docker CLI look at it — the
        // container has no home, so DOCKER_CONFIG is the whole reason the file is read.
        assertThat(system)
                .contains(".mounts[1]=volume:qits-platform-system-config:/work/config")
                .contains("env.DOCKER_CONFIG=/work/config");
        // Its own gate. The audience is the bare alias: it is a platform service, so there is no
        // tier in the name a peer would validate against.
        assertThat(system)
                .contains("env.QITS_AUTH_MACHINE_REQUIRED=true")
                .contains("env.QITS_AUTH_MACHINE_AUDIENCE=qits-platform-system")
                .contains("env.QUARKUS_OIDC_AUTH_SERVER_URL=http://qits-platform-idp:8080/idp")
                .contains("env.QITS_OBSERVABILITY_URL=http://prod-qits-observability:8080");
        // THE GLANCES IMAGE: a repo and a version, so a bump is one value. `hub/` is the mirror's
        // docker.io upstream, the prefix every committed Dockerfile uses, and the tag is pinned to
        // the `-full` variant — the one carrying the docker plugin glances lists containers with.
        assertThat(system)
                .contains("env.QITS_SYSTEM_GLANCES_IMAGE_REPO="
                        + "mirror.prod.localhost:8080/hub/nicolargo/glances")
                .contains("env.QITS_SYSTEM_GLANCES_IMAGE_VERSION=4.5.6-full");
        assertThat(system).doesNotContain("glances:latest");
        // It calls no peer, so it mints nothing: no named oidc client, and not the default one
        // either — the extension creates that regardless, and an enabled client with no
        // auth-server-url fails the boot.
        assertThat(system).doesNotContain("env.QUARKUS_OIDC_CLIENT_");
        // Stateless: every answer is read live from the daemon, and the terminal registry is in
        // memory. No store to inject and no port of its own — it is behind the edge like the rest.
        assertThat(system).doesNotContain("QITS_RESOURCE_").doesNotContain(".publishes[");
        // Not a seed service: nothing calls it, so nothing waits on it.
        assertThat(ComposeTemplate.compose(tokens()))
                .doesNotContain("\n  qits-platform-system:\n");
    }

    /**
     * <b>Its idp client is a docker credential first.</b> The service mints against no peer, but
     * the glances pull it starts goes through the platform mirror and the edge has granted no
     * anonymous read since 2026-08-14 — so the client exists to be half of a {@code config.json}.
     * Its roles are the machine pair; qits:admin is absent, and on this service that absence is
     * what keeps a shell a person's.
     */
    @Test
    void theSystemIdpClientCarriesTheMachineRolesAndNoWildcardClaim() {
        assertThat(serviceBlock(ComposeTemplate.compose(tokens()), "qits-platform-idp"))
                .contains("QITS_IDP_CLIENT_QITS_PLATFORM_SYSTEM_SECRET: "
                        + "\"secret-qits-platform-system\"")
                .contains("QITS_IDP_CLIENT_QITS_PLATFORM_SYSTEM_ROLES: "
                        + "\"qits:system,qits-platform:system\"")
                .contains("QITS_IDP_CLIENT_QITS_PLATFORM_SYSTEM_AUDIENCES: \""
                        + PlatformModel.idpAudiences(ENV) + "\"");
        assertThat(extras("qits-platform-idp"))
                .contains("env.QITS_IDP_CLIENT_QITS_PLATFORM_SYSTEM_SECRET="
                        + "secret-qits-platform-system")
                .contains("env.QITS_IDP_CLIENT_QITS_PLATFORM_SYSTEM_ROLES="
                        + "qits:system,qits-platform:system")
                .contains("env.QITS_IDP_CLIENT_QITS_PLATFORM_SYSTEM_AUDIENCES="
                        + PlatformModel.idpAudiences(ENV));
        // qits:admin is the human role, and it is what opens a terminal. A machine may read the
        // panels; only a person may get a shell.
        assertThat(extras("qits-platform-idp"))
                .doesNotContain("QITS_IDP_CLIENT_QITS_PLATFORM_SYSTEM_ROLES="
                        + "qits:system,qits-platform:system,qits:admin");
        // No wildcard project claim: it triggers no pipeline. The two grants stay artifacts' and
        // maintenance's.
        assertThat(extras("qits-platform-idp"))
                .doesNotContain("QITS_IDP_CLIENT_QITS_PLATFORM_SYSTEM_CLAIMS_PROJECT");
        // And the client itself is named, or the idp answers invalid_client to a secret it holds.
        assertThat(PlatformModel.idpClients(ENV)).contains("qits-platform-system");
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
                .contains("QITS_EVENTS_URL: http://qits-events:8080")
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
                    .contains("QITS_ARTIFACTS_NPM_PROXY_URL=http://qits-platform-mirror:8080"
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
                .contains("env.QITS_WORKSPACE_NPM_PROXY_URL=http://qits-platform-mirror:8080"
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
        // The artifacts secret is still in both files — the idp is told what it is — and it is
        // ci's block that must not carry it.
        assertThat(serviceBlock(compose, ENV + "-qits-ci"))
                .doesNotContain("secret-prod-qits-artifacts");
        assertThat(extras("qits-ci")).doesNotContain("secret-prod-qits-artifacts");
    }

    /**
     * <b>THE TWO PULLERS HOLD A CLIENT NOW, and each holds its OWN.</b> The deployer and the
     * orchestrator both shell {@code docker pull}, and since the flip a pull is authenticated with
     * a client id and a secret out of a config.json. A borrowed identity would make a refused pull
     * unattributable, so each gets a credential of its own — and the same full audience list every
     * other client gets, because a list is restated in full or it is not there.
     */
    @Test
    void thePullersAreIdpClientsWithTheirOwnSecrets() {
        String compose = ComposeTemplate.compose(tokens());
        String idp = serviceBlock(compose, "qits-platform-idp");
        String idpExtras = extras("qits-platform-idp");
        String audiences = PlatformModel.idpAudiences(ENV);

        assertThat(idp).contains("QITS_IDP_CLIENT_QITS_DEPLOYMENTS_SECRET: "
                        + "\"secret-qits-deployments\"")
                .contains("QITS_IDP_CLIENT_PROD_QITS_CONTAINERS_SECRET: "
                        + "\"secret-prod-qits-containers\"")
                .contains("QITS_IDP_CLIENT_QITS_DEPLOYMENTS_AUDIENCES: \"" + audiences + "\"")
                .contains("QITS_IDP_CLIENT_PROD_QITS_CONTAINERS_AUDIENCES: \"" + audiences + "\"");
        assertThat(idpExtras)
                .contains("env.QITS_IDP_CLIENT_QITS_DEPLOYMENTS_SECRET="
                        + "secret-qits-deployments")
                .contains("env.QITS_IDP_CLIENT_PROD_QITS_CONTAINERS_SECRET="
                        + "secret-prod-qits-containers")
                .contains("env.QITS_IDP_CLIENT_QITS_DEPLOYMENTS_AUDIENCES=" + audiences)
                .contains("env.QITS_IDP_CLIENT_PROD_QITS_CONTAINERS_AUDIENCES=" + audiences);
        // And both are on the list that says which clients exist: an id not on it is
        // invalid_client, with nothing to say it was a typo.
        assertThat(idp).contains("QITS_IDP_CLIENTS: \"" + String.join(",",
                PlatformModel.idpClients(ENV)) + "\"");
        assertThat(idpExtras).contains("env.QITS_IDP_CLIENTS="
                + String.join(",", PlatformModel.idpClients(ENV)));
        assertThat(PlatformModel.idpClients(ENV))
                .contains("qits-deployments", "prod-qits-containers");
    }

    /**
     * <b>THE EDGE HOLDS A CLIENT FOR USER SESSIONS, and sessions are enforced by default.</b>
     * <p>
     * The id carries the environment while the service does not: it is the session gate's
     * credential, and a session belongs to a tier.
     */
    @Test
    void theEdgeIsAnIdpClientAndSessionsAreEnabled() {
        String compose = ComposeTemplate.compose(tokens());
        String edge = serviceBlock(compose, "qits-platform-edge");
        String idp = serviceBlock(compose, "qits-platform-idp");

        assertThat(idp).contains(
                "QITS_IDP_CLIENT_PROD_QITS_EDGE_SECRET: \"secret-prod-qits-edge\"")
                .contains("QITS_IDP_CLIENT_PROD_QITS_EDGE_ROLES: \"qits:system,qits-platform:system\"")
                .contains("QITS_IDP_CLIENT_PROD_QITS_CI_ROLES: \"qits:system,qits-platform:system,qits:admin\"");
        assertThat(edge).contains("QITS_EDGE_SESSIONS_ENABLED: \"true\"")
                .contains("QITS_EDGE_SESSIONS_CLIENT_ID: prod-qits-edge")
                .contains("QITS_EDGE_SESSIONS_CLIENT_SECRET: \"secret-prod-qits-edge\"");
        assertThat(extras("qits-platform-idp"))
                .contains("env.QITS_IDP_CLIENT_PROD_QITS_EDGE_SECRET=secret-prod-qits-edge")
                .contains("env.QITS_IDP_CLIENT_PROD_QITS_EDGE_ROLES=qits:system,qits-platform:system")
                .contains("env.QITS_IDP_CLIENT_PROD_QITS_CI_ROLES=qits:system,qits-platform:system,qits:admin");
        assertThat(extras("qits-platform-edge"))
                .contains("env.QITS_EDGE_SESSIONS_ENABLED=true")
                .contains("env.QITS_EDGE_SESSIONS_CLIENT_ID=prod-qits-edge")
                .contains("env.QITS_EDGE_SESSIONS_CLIENT_SECRET=secret-prod-qits-edge");
        // No audience list for it: the edge introspects a session with Basic and asks the idp for
        // no token at all, which is what parts it from the two pullers above.
        assertThat(idp).doesNotContain("QITS_IDP_CLIENT_PROD_QITS_EDGE_AUDIENCES");
        assertThat(extras("qits-platform-idp"))
                .doesNotContain("QITS_IDP_CLIENT_PROD_QITS_EDGE_AUDIENCES");
        // And the gateway is untouched by all of it: which variant it is built as is a pipeline
        // build arg, and neither generated file sets one — the comments that NAME it are not
        // settings, which is why this reads the keys rather than the text.
        assertThat(compose.lines().filter(line -> !line.strip().startsWith("#")))
                .noneMatch(line -> line.contains("QITS_VARIANT"));
        assertThat(extrasKeys()).noneMatch(line -> line.contains("QITS_VARIANT"));
    }

    /**
     * <b>THE PASSKEY BINDING, in both files.</b> A credential is bound to the rp id and asserts on
     * that host and its children: the rp id is {@code <env>.localhost}, or the domain once there is
     * one. The ceremony's origin is the idp's own host, a child of the rp id — so moving the login
     * off the door broke no credential.
     */
    @Test
    void theIdpIsToldWhichHostAPasskeyIsBoundTo() {
        String idp = serviceBlock(ComposeTemplate.compose(tokens()), "qits-platform-idp");

        assertThat(idp).contains("QITS_IDP_WEBAUTHN_RP_ID: prod.localhost")
                .contains("QITS_IDP_WEBAUTHN_ORIGINS: \"http://idp.prod.localhost:8080\"");
        assertThat(extras("qits-platform-idp"))
                .contains("env.QITS_IDP_WEBAUTHN_RP_ID=prod.localhost")
                .contains("env.QITS_IDP_WEBAUTHN_ORIGINS=http://idp.prod.localhost:8080");

        String withDomain = serviceBlock(ComposeTemplate.compose(tokens(DOMAIN)),
                "qits-platform-idp");
        assertThat(withDomain).contains("QITS_IDP_WEBAUTHN_RP_ID: " + DOMAIN)
                .contains("QITS_IDP_WEBAUTHN_ORIGINS: \"https://idp." + DOMAIN + "\"");
        assertThat(ComposeTemplate.extras(tokens(DOMAIN)))
                .contains("env.QITS_IDP_WEBAUTHN_RP_ID=" + DOMAIN)
                .contains("env.QITS_IDP_WEBAUTHN_ORIGINS=https://idp." + DOMAIN);
    }

    /**
     * <b>ONE SESSION, EVERY SERVICE HOST.</b> The allow-list is the environment's door and one label
     * under it — {@code *.<authority>}, same port — because each service serves its own UI at
     * {@code <app>.<env>.<authority>}. The cookie is scoped to the parent both share, which is the
     * environment's name locally and the domain when there is one.
     * <p>
     * <b>The two canonical origins DIFFER.</b> The idp's is its own host, where the login page is;
     * the edge's stays the door, from which it derives the apex and looks the login host up.
     */
    @Test
    void browserSsoCarriesOneSessionOntoEveryServiceHost() {
        String local = ComposeTemplate.compose(tokens());
        assertThat(serviceBlock(local, "qits-platform-idp"))
                .contains("QITS_IDP_BROWSER_SSO_CANONICAL_ORIGIN: http://idp.prod.localhost:8080")
                .contains("QITS_IDP_BROWSER_SSO_BROWSER_HOSTS: "
                        + "\"prod.localhost:8080,*.prod.localhost:8080\"")
                .contains("QITS_IDP_BROWSER_SSO_COOKIE_DOMAIN: \"prod.localhost\"");
        assertThat(serviceBlock(local, "qits-platform-edge"))
                .contains("QITS_EDGE_SESSIONS_CANONICAL_ORIGIN: http://prod.localhost:8080")
                .contains("QITS_EDGE_SESSIONS_BROWSER_HOSTS: "
                        + "\"prod.localhost:8080,*.prod.localhost:8080\"");

        // Four shapes with a domain: the environment label is optional for the default tier, so
        // <app>.<domain> and <app>.<env>.<domain> are one host and both wildcards are named.
        String hosts = DOMAIN + ",prod." + DOMAIN + ",*." + DOMAIN + ",*.prod." + DOMAIN;
        String domain = ComposeTemplate.compose(tokens(DOMAIN));
        assertThat(serviceBlock(domain, "qits-platform-idp"))
                .contains("QITS_IDP_BROWSER_SSO_CANONICAL_ORIGIN: https://idp." + DOMAIN)
                .contains("QITS_IDP_BROWSER_SSO_BROWSER_HOSTS: \"" + hosts + "\"")
                .contains("QITS_IDP_BROWSER_SSO_COOKIE_DOMAIN: \"" + DOMAIN + "\"");
        assertThat(serviceBlock(domain, "qits-platform-edge"))
                .contains("QITS_EDGE_SESSIONS_CANONICAL_ORIGIN: https://" + DOMAIN)
                .contains("QITS_EDGE_SESSIONS_BROWSER_HOSTS: \"" + hosts + "\"");
        assertThat(ComposeTemplate.extras(tokens(DOMAIN)))
                .contains("qits.platform.deployments.extras.qits-platform-idp.env.QITS_IDP_BROWSER_SSO_COOKIE_DOMAIN=" + DOMAIN)
                .contains("qits.platform.deployments.extras.qits-platform-edge.env.QITS_EDGE_SESSIONS_BROWSER_HOSTS=" + hosts);
        // The extras carry the same split: the idp's canonical origin is its own host, the edge's
        // is the door.
        assertThat(ComposeTemplate.extras(tokens()))
                .contains("qits.platform.deployments.extras.qits-platform-idp.env."
                        + "QITS_IDP_BROWSER_SSO_CANONICAL_ORIGIN=http://idp.prod.localhost:8080")
                .contains("qits.platform.deployments.extras.qits-platform-edge.env."
                        + "QITS_EDGE_SESSIONS_CANONICAL_ORIGIN=http://prod.localhost:8080");
        assertThat(ComposeTemplate.extras(tokens(DOMAIN)))
                .contains("qits.platform.deployments.extras.qits-platform-idp.env."
                        + "QITS_IDP_BROWSER_SSO_CANONICAL_ORIGIN=https://idp." + DOMAIN)
                .contains("qits.platform.deployments.extras.qits-platform-edge.env."
                        + "QITS_EDGE_SESSIONS_CANONICAL_ORIGIN=https://" + DOMAIN);
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
     * <b>THE FLIP IS ON, in both files, and it landed on 2026-08-14.</b> Its three values are one
     * absence and two presences: no anonymous-read list anywhere, so every method on all three
     * vhosts needs a bearer; the deployer told to authenticate its pulls; and ci told which
     * registries a step must log in to. Half of it is a platform whose deployer cannot pull, or
     * step containers authenticating against a door that never asks — so all three are asserted
     * together. The rollback is re-adding the anonymous-read env to the two files.
     */
    @Test
    void theFlipIsOn() {
        String compose = ComposeTemplate.compose(tokens());
        String extras = ComposeTemplate.extras(tokens());
        String vhosts = "registry.prod.localhost:8080,mirror.prod.localhost:8080";

        // The absence, and it is the whole of the edge's half: the key is a LIST, and a list
        // nobody sets is the empty one. Asked of both files whole, because a stray copy anywhere
        // reopens the door.
        assertThat(compose.lines().filter(line -> line.strip().startsWith("QITS_")).toList())
                .allSatisfy(line -> assertThat(line)
                        .doesNotContain("QITS_EDGE_AUTH_ANONYMOUS_READ_APPS"));
        assertThat(extrasKeys()).allSatisfy(line -> assertThat(line)
                .doesNotContain("QITS_EDGE_AUTH_ANONYMOUS_READ_APPS"));

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
        // Nobody else claims a vhost: two containers holding one name is a lookup that answers
        // whichever of them the DNS server picked.
        assertThat(extrasKeys()).filteredOn(line -> line.contains(".aliases["))
                .allSatisfy(line -> assertThat(line).startsWith(EXTRAS + "qits-platform-edge."));
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
        assertThat(extras("qits-projects")).contains("env.QITS_GITHOST_URL=" + host)
                .contains("env.QITS_PROJECTS_AGENT_GIT_BASE=" + host + "/git")
                .contains("env.QITS_EVENTS_URL=http://qits-events:8080")
                .contains("env.QITS_AUTH_MACHINE_REQUIRED=true")
                .contains("env.QITS_AUTH_MACHINE_AUDIENCE=prod-qits-projects")
                .contains("env.QUARKUS_OIDC_AUTH_SERVER_URL=http://qits-platform-idp:8080/idp")
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
                .contains("QITS_EVENTS_URL: http://qits-events:8080");
        assertThat(extras("qits-ci")).contains("env.QITS_EVENTS_URL=http://qits-events:8080");
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
        assertThat(block).contains("QITS_EVENTS_URL: http://qits-events:8080");
        // Every route of this service is guarded, so the audience is not decoration: the image ships
        // the bare qits-containers and the idp mints prod-qits-containers. No oidc-client — it
        // validates and mints nothing, exactly like the deployer.
        assertThat(block).contains("QITS_AUTH_MACHINE_AUDIENCE: prod-qits-containers")
                .contains("QITS_AUTH_MACHINE_REQUIRED: \"true\"")
                .contains("QUARKUS_OIDC_AUTH_SERVER_URL: http://qits-platform-idp:8080/idp")
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
                .contains("env.QITS_EVENTS_URL=http://qits-events:8080")
                .contains("env.QITS_AUTH_MACHINE_AUDIENCE=prod-qits-containers")
                .contains("env.QUARKUS_OIDC_AUTH_SERVER_URL=http://qits-platform-idp:8080/idp");
        // Both stores are declared in its deployments.yml, so the deployer injects the six
        // variables — a pin here is written after that injection and outlives the next rotation.
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
     * qits-workspaces is told where a release lands, and it is this environment's deploy ref.
     * <p>
     * It ships a default of {@code environment/prod}, so a platform bootstrapped under any other
     * name would silently promote every release onto a branch no environment listens to — the
     * release lands, CI never fires, nothing deploys, and no component reports an error. The
     * generated key is what stops that, and it is why it belongs beside the env name rather than in
     * the image.
     */
    @Test
    void workspacesIsToldWhereAReleaseLands() {
        assertThat(extras("qits-workspaces"))
                .contains("env.QITS_WORKSPACES_RELEASE_ENTRY_BRANCH=environment/prod");

        Map<String, String> staging = tokens();
        staging.put("ENV_NAME", "staging");
        assertThat(ComposeTemplate.extras(staging))
                .contains("env.QITS_WORKSPACES_RELEASE_ENTRY_BRANCH=environment/staging");
    }

    @Test
    void theExtrasCoverEveryApplicationThatNeedsMoreThanItsImage() {
        String properties = ComposeTemplate.extras(tokens());

        for (String application : new String[]{"qits-platform-edge",
                "qits-artifacts", "qits-platform-mirror", "qits-githost", "qits-containers",
                "qits-ci", "qits-deployments", "qits-platform-idp",
                "qits-stt", "qits-projects", "qits-workspaces", "qits-events",
                "qits-docs", "qits-observability", "qits-oci-postgresql"}) {
            assertThat(properties).contains(EXTRAS + application + ".");
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

    @Test
    void theDeployersOwnDeploymentInheritsItsConfigVolume() {
        // The self-update handoff: without this mount the successor comes up with no configuration
        // at all and every later deployment loses its volumes and its datasource env.
        String deployer = extras("qits-deployments");

        assertThat(deployer).contains(".mounts[0]=volume:qits-deployments-config:/work/config");
        assertThat(deployer).contains(".mounts[1]=bind:/var/run/docker.sock:/var/run/docker.sock");
        assertThat(deployer).contains("env.QITS_RESOURCE_DB_URL="
                + "jdbc:postgresql://prod-qits-oci-postgresql:5432/qits_deployments");
        assertThat(deployer).contains("env.QITS_RESOURCE_DB_USERNAME=qits_deployments");
        assertThat(deployer).contains("env.QITS_RESOURCE_DB_PASSWORD=fedcba9876543210");
        assertThat(deployer).doesNotContain("QITS_ENVIRONMENT");
        assertThat(deployer).contains(
                "env.QITS_PLATFORM_DEPLOYMENTS_POSTGRES_ADMIN_PASSWORD=0123456789abcdef");
        // The bus, and the ONLY thing the deployer's bus membership adds to this line: the wire
        // name. A subscriber pointed at the pre-rename qits-events:8080 receives nothing.
        assertThat(deployer).contains("env.QITS_EVENTS_URL=http://qits-events:8080");
        // ONE TRIPLE, NOT TWO. The outbox is declared in the deployer's own deployments.yml, so a
        // running deployer provisions it for its successor and injects the triple from the registry
        // row that holds the current password — pinning it here would outlive the next rotation.
        assertThat(deployer).doesNotContain("QITS_RESOURCE_EVENTSTREAM_");
        // The store moved off the file H2, and its volume went with it: /data held nothing else.
        assertThat(deployer).doesNotContain("QUARKUS_DATASOURCE_PLATFORMDEPLOYMENTS_JDBC_URL=")
                .doesNotContain("qits-deployments-data:/data");
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
    void theIdpsDeploymentCarriesEverySecretAndTheClientListItself() {
        String idp = extras("qits-platform-idp");

        // No volume and no datasource: the store is a database the deployer provisions from
        // `resources: postgresql:db` and injects. The signing key is in it, so pinning the triple
        // here would outlive a rotation and take every token in flight down with it.
        assertThat(idp).doesNotContain(".mounts[")
                .doesNotContain("QUARKUS_DATASOURCE_IDP_JDBC_URL")
                .doesNotContain("QITS_RESOURCE_");
        for (String app : PlatformModel.IDP_CLIENT_APPS) {
            assertThat(idp).contains("secret-" + PlatformModel.wireAlias(app, ENV));
        }
        // Each of these keys REPLACES the shipped list rather than extending it, and every id in
        // them carries the environment name, which no shipped default can follow.
        assertThat(idp).contains("QITS_IDP_CLIENTS=" + String.join(",", PlatformModel.idpClients(ENV)));
        assertThat(idp).contains("QITS_IDP_CLIENT_PROD_QITS_CI_AUDIENCES=")
                .contains("QITS_IDP_CLIENT_PROD_QITS_ARTIFACTS_AUDIENCES=")
                .contains("qits-deployments");
        // The wildcard project claim, under the id it moved to when the store became an
        // environment service. Nobody in this bootstrap presents it any more — the release replays
        // push a tag — and it stays for the person who triggers a run by hand.
        assertThat(idp).contains("QITS_IDP_CLIENT_PROD_QITS_ARTIFACTS_CLAIMS_PROJECT=*");
        assertThat(idp).doesNotContain("QITS_IDP_CLIENT_QITS_PLATFORM_ARTIFACTS_");
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
        List<String> dialled = ComposeTemplate.compose(tokens()).lines()
                .map(String::strip)
                .filter(line -> line.startsWith("QITS_RESOURCE_") && line.contains("_URL:"))
                .map(line -> line.substring(line.lastIndexOf('/') + 1))
                .distinct()
                .toList();

        assertThat(dialled).contains("qits_artifacts", "qits_platform_mirror");
        assertThat(dialled)
                .containsExactlyInAnyOrderElementsOf(SeedPhases.SEED_DATABASES);
    }

    /**
     * THE GUARD ON THE WHOLE MOVE. Every service that flipped to postgres declares its store in its
     * own deployments.yml, so the deployer injects the triple and a datasource line here would be an
     * operator pin that outlives the next password rotation.
     * <p>
     * NO FILE STORE IS LEFT EITHER. qits-artifacts was the last file database and the git host the
     * last blob directory, so the whole byte plane deploys stateless and the only mounts in this
     * file are qits-projects' and qits-workspaces' trees of files.
     */
    @Test
    void noDeploymentCarriesAFileDatabase() {
        assertThat(extrasKeys().stream().filter(line -> line.contains("jdbc:h2")).toList())
                .isEmpty();

        // The hosted store is stateless: one database holds the catalog and the blob bytes both, so
        // there is no mount and no blobs directory — and no triple either, because the deployer
        // injects it from `resources: postgresql:db`.
        assertThat(extras("qits-artifacts")).doesNotContain("QITS_RESOURCE_")
                .doesNotContain(".mounts[")
                .doesNotContain("QITS_ARTIFACTS_BLOBS_DIR");
        // The cache half, on exactly the same terms.
        assertThat(extras("qits-platform-mirror")).doesNotContain("QITS_RESOURCE_")
                .doesNotContain(".mounts[")
                .doesNotContain("QITS_ARTIFACTS_BLOBS_DIR");
        // The git host, the last of the three to move: packs and reftables are rows in qits_githost,
        // so it has no mount either. Its two triples stay declared rather than pinned.
        assertThat(extras("qits-githost")).doesNotContain("QITS_RESOURCE_")
                .doesNotContain(".mounts[")
                .doesNotContain("QITS_ARTIFACTS_BLOBS_DIR");
        // No application anywhere is told where to put blobs any more: not one store is a directory.
        assertThat(ComposeTemplate.extras(tokens())).doesNotContain("QITS_ARTIFACTS_BLOBS_DIR");

        // No application pins a resource triple either: the deployer's injection comes first and
        // the last assignment of a key wins, so a pin here would win and never be rotated.
        assertThat(ComposeTemplate.extras(tokens())).doesNotContain("env.QITS_RESOURCE_DB_URL=jdbc:"
                + "postgresql://prod-qits-oci-postgresql:5432/qits_ci");
        assertThat(extras("qits-ci")).doesNotContain("QITS_RESOURCE_")
                .doesNotContain(".mounts[");
        assertThat(extras("qits-events")).doesNotContain("QITS_RESOURCE_")
                .doesNotContain(".mounts[");
        // The orchestrator declares two stores and keeps no volume: the socket is the only thing
        // it mounts.
        assertThat(extras("qits-containers")).doesNotContain("QITS_RESOURCE_")
                .doesNotContain("qits-containers-data");
        // THE COUNTER-EXAMPLE, and the only mounts left in this file: /data is these two services'
        // own tree of files, which no database replaced. They are what proves the sweep above took
        // the byte plane on purpose rather than every mount it could reach.
        assertThat(extras("qits-projects")).contains(".mounts[0]=volume:qits-projects-data:/data")
                .contains("env.QITS_PROJECTS_DATA_DIR=/data/mirrors")
                .doesNotContain("QITS_RESOURCE_");
        assertThat(extras("qits-workspaces"))
                .contains(".mounts[0]=volume:qits-workspaces-data:/data")
                .doesNotContain("QITS_RESOURCE_");
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
                .contains("QUARKUS_OIDC_CLIENT_GITHOST_CLIENT_ENABLED: \"true\"")
                .contains("QUARKUS_OIDC_CLIENT_GITHOST_CLIENT_ID: " + ENV + "-qits-projects")
                .contains("QUARKUS_OIDC_CLIENT_GITHOST_GRANT_OPTIONS_CLIENT_AUDIENCE: " + ENV
                        + "-qits-githost");
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
        // The idp's own host first, because it is the longer spelling of the same name.
        compose = compose.replace("https://idp." + DOMAIN, "http://idp.prod.localhost:8080")
                .replace("https://" + DOMAIN, "http://prod.localhost:8080")
                .replace("RP_ID: " + DOMAIN, "RP_ID: prod.localhost")
                .replace(DOMAIN + ",prod." + DOMAIN + ",*." + DOMAIN + ",*.prod." + DOMAIN,
                        "prod.localhost:8080,*.prod.localhost:8080")
                .replace("COOKIE_DOMAIN: \"" + DOMAIN + "\"", "COOKIE_DOMAIN: \"prod.localhost\"");
        extras = extras.replace("https://idp." + DOMAIN, "http://idp.prod.localhost:8080")
                .replace("https://" + DOMAIN, "http://prod.localhost:8080")
                .replace("RP_ID=" + DOMAIN, "RP_ID=prod.localhost")
                .replace(DOMAIN + ",prod." + DOMAIN + ",*." + DOMAIN + ",*.prod." + DOMAIN,
                        "prod.localhost:8080,*.prod.localhost:8080")
                .replace("COOKIE_DOMAIN=" + DOMAIN, "COOKIE_DOMAIN=prod.localhost");

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
                .contains("QITS_IDP_CLIENT_PREPROD_QITS_CI_SECRET")
                // The registry name carries the tier too: one edge, one door, a name per tier.
                .contains("QITS_ARTIFACTS_REGISTRY_HOST: registry.preprod.localhost:8080");
        assertThat(ComposeTemplate.extras(other))
                .contains("env.QITS_ARTIFACTS_REGISTRY_HOST=registry.preprod.localhost:8080")
                .contains("env.QITS_AUTH_MACHINE_AUDIENCE=preprod-qits-ci")
                // The bus is a PLATFORM service, so this one address does NOT carry the tier —
                // which is the whole point of deriving it rather than spelling the environment in.
                .contains("env.QITS_EVENTS_URL=http://qits-events:8080")
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
        assertThat(PlatformModel.idpClients(ENV)).contains(ENV + "-qits-projects");
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
     * <b>ci's catalogue is delivered with ci's own deployment and never on the seed.</b> Configured,
     * it REPLACES the git host's listing — and qits-projects is deployed fourteen phases after the
     * seed ci starts, so a seed holding the key would answer every event of the boot's first half
     * with an empty candidate list. The release replays are exactly those events.
     */
    @Test
    void theProjectsCatalogueIsInCisExtrasAndNotOnTheSeed() {
        assertThat(extras("qits-ci"))
                .contains("env.QITS_CI_PROJECTS_URL=http://" + ENV + "-qits-projects:8080");
        // The KEY, not the word: the block's comment names it to say why it is absent.
        assertThat(serviceBlock(ComposeTemplate.compose(tokens()), ENV + "-qits-ci"))
                .doesNotContain("QITS_CI_PROJECTS_URL:");
    }
}
