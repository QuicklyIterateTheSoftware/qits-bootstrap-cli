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
        values.put("ENV_KEY", PlatformModel.clientKey(ENV));
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
        values.put("PG_CONTAINERS_PASSWORD", "def0def0def0def0");
        values.put("PG_CONTAINERS_EVENTSTREAM_PASSWORD", "0f0f0f0f0f0f0f0f");
        values.put("IDP", "http://qits-platform-idp:8080/idp");
        values.put("PUSH_TOKEN", "local-dev");
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
        // The passkey binding of a platform with no domain: localhost is a secure context by
        // itself, so the ceremony works on the edge's plain HTTP port.
        values.put("WEBAUTHN_RP_ID", "localhost");
        values.put("WEBAUTHN_ORIGINS", "http://localhost:8080");
        values.put("PUBLIC_ORIGIN", "http://localhost:8080");
        values.put("BROWSER_HOSTS", "localhost:8080");
        values.put("SESSION_COOKIE_DOMAIN", "");
        // No domain: every fragment is empty, which is the ordinary platform.
        values.putAll(DomainTokens.of(Optional.empty()));
        return values;
    }

    /** The same values with a domain configured. */
    static Map<String, String> tokens(String domain) {
        Map<String, String> values = tokens();
        // The binding follows the address a browser arrives at, which a domain moves to TLS.
        values.put("WEBAUTHN_RP_ID", domain);
        values.put("WEBAUTHN_ORIGINS", "https://" + domain);
        values.put("PUBLIC_ORIGIN", "https://" + domain);
        values.put("BROWSER_HOSTS", domain + "," + ENV + "." + domain);
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
                .doesNotContain("${PG_CONTAINERS_PASSWORD}")
                .doesNotContain("${PG_CONTAINERS_EVENTSTREAM_PASSWORD}");
        assertThat(compose).doesNotContain("${MIRROR_PORT}").doesNotContain("${GIT_HOST_PORT}");
        // The domain fragments are filled even when they are empty: a leftover placeholder would
        // reach the file as literal text and compose would refuse it.
        assertThat(compose).doesNotContain("${LETSENCRYPT_VOLUME}")
                .doesNotContain("${EDGE_SEED_TLS_PORTS}")
                .doesNotContain("${EDGE_TLS}");
        assertThat(compose).doesNotContain("${ENV_NAME}");
        assertThat(compose).doesNotContain("${ENV_KEY}");
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

    @Test
    void theDeployerCarriesItsDatabaseItsConfigVolumeAndTheSocket() {
        String compose = ComposeTemplate.compose(tokens());
        String block = serviceBlock(compose, ENV + "-qits-deployments");

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
        // The bus, at the name that resolves. The jar's default is the pre-rename qits-events:8080,
        // and this service SUBSCRIBES — a BuildSuccessful never received deploys nothing.
        assertThat(block).contains("QITS_EVENTS_URL: http://prod-qits-events:8080");
        assertThat(block).contains("QITS_ENVIRONMENT: prod");
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
        assertThat(serviceBlock(compose, ENV + "-qits-deployments"))
                .contains("QITS_AUTH_MACHINE_AUDIENCE: prod-qits-deployments");
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
                .contains("env.QITS_AUTH_MACHINE_AUDIENCE=prod-qits-deployments");
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

        assertThat(idp).contains("QITS_IDP_CLIENT_PROD_QITS_DEPLOYMENTS_SECRET: "
                        + "\"secret-prod-qits-deployments\"")
                .contains("QITS_IDP_CLIENT_PROD_QITS_CONTAINERS_SECRET: "
                        + "\"secret-prod-qits-containers\"")
                .contains("QITS_IDP_CLIENT_PROD_QITS_DEPLOYMENTS_AUDIENCES: \"" + audiences + "\"")
                .contains("QITS_IDP_CLIENT_PROD_QITS_CONTAINERS_AUDIENCES: \"" + audiences + "\"");
        assertThat(idpExtras)
                .contains("env.QITS_IDP_CLIENT_PROD_QITS_DEPLOYMENTS_SECRET="
                        + "secret-prod-qits-deployments")
                .contains("env.QITS_IDP_CLIENT_PROD_QITS_CONTAINERS_SECRET="
                        + "secret-prod-qits-containers")
                .contains("env.QITS_IDP_CLIENT_PROD_QITS_DEPLOYMENTS_AUDIENCES=" + audiences)
                .contains("env.QITS_IDP_CLIENT_PROD_QITS_CONTAINERS_AUDIENCES=" + audiences);
        // And both are on the list that says which clients exist: an id not on it is
        // invalid_client, with nothing to say it was a typo.
        assertThat(idp).contains("QITS_IDP_CLIENTS: \"" + String.join(",",
                PlatformModel.idpClients(ENV)) + "\"");
        assertThat(idpExtras).contains("env.QITS_IDP_CLIENTS="
                + String.join(",", PlatformModel.idpClients(ENV)));
        assertThat(PlatformModel.idpClients(ENV))
                .contains("prod-qits-deployments", "prod-qits-containers");
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
     * <b>THE PASSKEY BINDING, in both files and derived from one address.</b> A credential is bound
     * to the rp id and asserts under no other host, so the two values follow the door a browser
     * arrives at: localhost and the edge's port, or the domain over TLS once there is one.
     */
    @Test
    void theIdpIsToldWhichHostAPasskeyIsBoundTo() {
        String idp = serviceBlock(ComposeTemplate.compose(tokens()), "qits-platform-idp");

        assertThat(idp).contains("QITS_IDP_WEBAUTHN_RP_ID: localhost")
                .contains("QITS_IDP_WEBAUTHN_ORIGINS: \"http://localhost:8080\"");
        assertThat(extras("qits-platform-idp"))
                .contains("env.QITS_IDP_WEBAUTHN_RP_ID=localhost")
                .contains("env.QITS_IDP_WEBAUTHN_ORIGINS=http://localhost:8080");

        String withDomain = serviceBlock(ComposeTemplate.compose(tokens(DOMAIN)),
                "qits-platform-idp");
        assertThat(withDomain).contains("QITS_IDP_WEBAUTHN_RP_ID: " + DOMAIN)
                .contains("QITS_IDP_WEBAUTHN_ORIGINS: \"https://" + DOMAIN + "\"");
        assertThat(ComposeTemplate.extras(tokens(DOMAIN)))
                .contains("env.QITS_IDP_WEBAUTHN_RP_ID=" + DOMAIN)
                .contains("env.QITS_IDP_WEBAUTHN_ORIGINS=https://" + DOMAIN);
    }

    @Test
    void browserSsoUsesTheApexForWebauthnAndOnlyNamedBrowserHostsForReturns() {
        String local = ComposeTemplate.compose(tokens());
        assertThat(serviceBlock(local, "qits-platform-idp"))
                .contains("QITS_IDP_BROWSER_SSO_CANONICAL_ORIGIN: http://localhost:8080")
                .contains("QITS_IDP_BROWSER_SSO_BROWSER_HOSTS: \"localhost:8080\"")
                .contains("QITS_IDP_BROWSER_SSO_COOKIE_DOMAIN: \"\"");
        assertThat(serviceBlock(local, "qits-platform-edge"))
                .contains("QITS_EDGE_SESSIONS_CANONICAL_ORIGIN: http://localhost:8080")
                .contains("QITS_EDGE_SESSIONS_BROWSER_HOSTS: \"localhost:8080\"");

        String domain = ComposeTemplate.compose(tokens(DOMAIN));
        assertThat(serviceBlock(domain, "qits-platform-idp"))
                .contains("QITS_IDP_BROWSER_SSO_CANONICAL_ORIGIN: https://" + DOMAIN)
                .contains("QITS_IDP_BROWSER_SSO_BROWSER_HOSTS: \"" + DOMAIN + ",prod." + DOMAIN + "\"")
                .contains("QITS_IDP_BROWSER_SSO_COOKIE_DOMAIN: \"" + DOMAIN + "\"");
        assertThat(serviceBlock(domain, "qits-platform-edge"))
                .contains("QITS_EDGE_SESSIONS_CANONICAL_ORIGIN: https://" + DOMAIN)
                .contains("QITS_EDGE_SESSIONS_BROWSER_HOSTS: \"" + DOMAIN + ",prod." + DOMAIN + "\"");
        assertThat(ComposeTemplate.extras(tokens(DOMAIN)))
                .contains("qits.platform.deployments.extras.qits-platform-idp.env.QITS_IDP_BROWSER_SSO_COOKIE_DOMAIN=" + DOMAIN)
                .contains("qits.platform.deployments.extras.qits-platform-edge.env.QITS_EDGE_SESSIONS_BROWSER_HOSTS=" + DOMAIN + ",prod." + DOMAIN);
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
        String deployer = serviceBlock(compose, ENV + "-qits-deployments");
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
        assertThat(serviceBlock(compose, ENV + "-qits-deployments"))
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
                .contains("env.QITS_EVENTS_URL=http://prod-qits-events:8080")
                .contains("env.QITS_AUTH_MACHINE_REQUIRED=true")
                .contains("env.QITS_AUTH_MACHINE_AUDIENCE=prod-qits-projects")
                .contains("env.QUARKUS_OIDC_AUTH_SERVER_URL=http://qits-platform-idp:8080/idp")
                .doesNotContain("QITS_ARTIFACTS_URL");
        assertThat(extras("qits-workspaces")).contains("env.QITS_GITHOST_URL=" + host)
                .doesNotContain("QITS_ARTIFACTS_URL");
        // The deployer's OWN trusted address for the same host — a plain property, because this
        // file is its configuration. Specs are read before the runtime mutation begins, so this
        // remains available for qits-githost's own cutover without crossing the public edge.
        assertThat(ComposeTemplate.extras(tokens()))
                .contains("\nqits.platform.deployments.git-host-url=" + host + "\n");
        // The KEYS, not the comments: the git host's own block says in prose where its clone url
        // used to be, and that sentence is why the reader knows what moved.
        assertThat(extrasKeys()).allSatisfy(line -> assertThat(line)
                .doesNotContain("/artifacts/git"));
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
        String block = serviceBlock(compose, ENV + "-qits-events");

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
                .contains("env.QITS_EVENTS_URL=http://prod-qits-events:8080")
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
                .doesNotContain("${ENV_KEY}");
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
        assertThat(serviceBlock(compose, ENV + "-qits-deployments"))
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
        assertThat(deployer).contains("env.QITS_ENVIRONMENT=prod");
        assertThat(deployer).contains(
                "env.QITS_PLATFORM_DEPLOYMENTS_POSTGRES_ADMIN_PASSWORD=0123456789abcdef");
        // The bus, and the ONLY thing the deployer's bus membership adds to this line: the wire
        // name. A subscriber pointed at the pre-rename qits-events:8080 receives nothing.
        assertThat(deployer).contains("env.QITS_EVENTS_URL=http://prod-qits-events:8080");
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
                .contains("prod-qits-deployments");
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
        compose = compose.replace("https://" + DOMAIN, "http://localhost:8080")
                .replace("RP_ID: " + DOMAIN, "RP_ID: localhost")
                .replace(DOMAIN + ",prod." + DOMAIN, "localhost:8080")
                .replace("COOKIE_DOMAIN: \"" + DOMAIN + "\"", "COOKIE_DOMAIN: \"\"");
        extras = extras.replace("https://" + DOMAIN, "http://localhost:8080")
                .replace("RP_ID=" + DOMAIN, "RP_ID=localhost")
                .replace(DOMAIN + ",prod." + DOMAIN, "localhost:8080")
                .replace("COOKIE_DOMAIN=" + DOMAIN, "COOKIE_DOMAIN=");

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
                .contains("QITS_EDGE_ACME_ENABLED: true")
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
        other.put("ENV_KEY", "PREPROD");

        assertThat(ComposeTemplate.compose(other))
                .contains("\n  preprod-qits-ci:\n")
                .contains("QITS_EDGE_ENVIRONMENTS: preprod")
                .contains("QITS_IDP_CLIENT_PREPROD_QITS_CI_SECRET")
                // The registry name carries the tier too: one edge, one door, a name per tier.
                .contains("QITS_ARTIFACTS_REGISTRY_HOST: registry.preprod.localhost:8080");
        assertThat(ComposeTemplate.extras(other))
                .contains("env.QITS_ARTIFACTS_REGISTRY_HOST=registry.preprod.localhost:8080")
                .contains("env.QITS_AUTH_MACHINE_AUDIENCE=preprod-qits-ci")
                .contains("env.QITS_EVENTS_URL=http://preprod-qits-events:8080")
                .doesNotContain("QITS_GATEWAY_PROXY_HOSTS")
                .contains("env.QITS_OBSERVABILITY_URL=http://preprod-qits-observability:8080");
    }
}
