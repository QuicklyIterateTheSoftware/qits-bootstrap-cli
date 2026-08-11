package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ComposeTemplateTest {

    private static final String ENV = "prod";
    private static final String DOMAIN = "qits-dev.eu";

    private static Map<String, String> tokens() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ENV_NAME", ENV);
        values.put("ENV_KEY", PlatformModel.clientKey(ENV));
        values.put("COMPOSE_FILE", "docker-compose.qits.yml");
        values.put("PORT", "8080");
        values.put("REGISTRY_PORT", "8081");
        values.put("MIRROR_PORT", "8082");
        values.put("GIT_HOST_PORT", "8083");
        values.put("PG_PORT", "5433");
        values.put("DNS_PORT", "53");
        values.put("PG_SUPERUSER_PASSWORD", "0123456789abcdef");
        values.put("PG_DEPLOYMENTS_PASSWORD", "fedcba9876543210");
        values.put("PG_DEPLOYMENTS_EVENTSTREAM_PASSWORD", "1111222233334444");
        values.put("PG_CI_PASSWORD", "aaaabbbbccccdddd");
        values.put("PG_CI_EVENTSTREAM_PASSWORD", "eeeeffff00001111");
        values.put("PG_PLATFORM_IDP_PASSWORD", "2222333344445555");
        values.put("PG_PLATFORM_DNS_PASSWORD", "66667777888899aa");
        values.put("PG_EVENTS_PASSWORD", "bbbbccccddddeeee");
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
        // No domain: every fragment is empty, which is the ordinary platform.
        values.putAll(DomainTokens.of(Optional.empty()));
        return values;
    }

    /** The same values with a domain configured. */
    private static Map<String, String> tokens(String domain) {
        Map<String, String> values = tokens();
        values.putAll(DomainTokens.of(Optional.of(domain)));
        return values;
    }

    /** One service's own lines, so an assertion about what it does NOT carry means that service. */
    private static String serviceBlock(String compose, String container) {
        int start = compose.indexOf("container_name: " + container);
        assertThat(start).isNotNegative();
        int next = compose.indexOf("container_name: ", start + 1);
        return next < 0 ? compose.substring(start) : compose.substring(start, next);
    }

    /** Every generated run-args line, without the comments that explain them. */
    private static List<String> runArgsLines() {
        return ComposeTemplate.runArgs(tokens()).lines()
                .filter(line -> line.startsWith("qits.platform.deployments.run-args."))
                .toList();
    }

    private static String runArgsLine(String application) {
        return runArgsLine(application, tokens());
    }

    private static String runArgsLine(String application, Map<String, String> values) {
        return ComposeTemplate.runArgs(values).lines()
                .filter(line -> line.startsWith(
                        "qits.platform.deployments.run-args." + application + "="))
                .findFirst().orElseThrow();
    }

    @Test
    void fillsEveryPlaceholderOfTheComposeFile() {
        String compose = ComposeTemplate.compose(tokens());

        assertThat(compose).contains("- \"8080:8080\"");
        assertThat(compose).contains("- \"127.0.0.1:8081:8080\"");
        assertThat(compose).contains("QITS_IDP_ISSUER: http://qits-platform-idp:8080/idp");
        assertThat(compose).contains(
                "QITS_IDP_CLIENT_PROD_QITS_CI_SECRET: \"secret-prod-qits-ci\"");
        assertThat(compose).contains("group_add: [\"988\"]");
        assertThat(compose).contains("QITS_CI_DAEMON_VERSION: \"abc123\"");
        assertThat(compose).doesNotContain("${PORT}");
        assertThat(compose).doesNotContain("${PG_PORT}");
        assertThat(compose).doesNotContain("${DNS_PORT}");
        assertThat(compose).doesNotContain("${PG_SUPERUSER_PASSWORD}")
                .doesNotContain("${PG_DEPLOYMENTS_PASSWORD}")
                .doesNotContain("${PG_DEPLOYMENTS_EVENTSTREAM_PASSWORD}")
                .doesNotContain("${PG_CI_PASSWORD}")
                .doesNotContain("${PG_CI_EVENTSTREAM_PASSWORD}")
                .doesNotContain("${PG_PLATFORM_IDP_PASSWORD}")
                .doesNotContain("${PG_PLATFORM_DNS_PASSWORD}")
                .doesNotContain("${PG_EVENTS_PASSWORD}")
                .doesNotContain("${PG_PLATFORM_MIRROR_PASSWORD}")
                .doesNotContain("${PG_GITHOST_PASSWORD}")
                .doesNotContain("${PG_GITHOST_EVENTSTREAM_PASSWORD}")
                .doesNotContain("${PG_CONTAINERS_PASSWORD}")
                .doesNotContain("${PG_CONTAINERS_EVENTSTREAM_PASSWORD}");
        assertThat(compose).doesNotContain("${MIRROR_PORT}").doesNotContain("${GIT_HOST_PORT}");
        // The domain fragments are filled even when they are empty: a leftover placeholder would
        // reach the file as literal text and compose would refuse it.
        assertThat(compose).doesNotContain("${DNS_IDENTITY}")
                .doesNotContain("${LETSENCRYPT_VOLUME}")
                .doesNotContain("${EDGE_TLS_PORTS}")
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

        for (String name : PlatformModel.CORE) {
            assertThat(compose)
                    .contains("container_name: " + PlatformModel.wireAlias(name, ENV));
        }
        // One component replaced both: neither ancestor is in the seed any more.
        assertThat(compose).doesNotContain("container_name: qits-cd")
                .doesNotContain("container_name: qits-serviceregistry");
        // And no seed carries a pre-rename name, which nothing would resolve.
        assertThat(compose).doesNotContain("container_name: qits-idp")
                .doesNotContain("container_name: qits-platform-deployments")
                // The byte-plane split retired this one: the store is an environment service, so a
                // seed under the bare name is a container nothing on this platform dials.
                .doesNotContain("container_name: qits-platform-artifacts");
    }

    @Test
    void theEdgeBindsTheHostPortAndTheGatewayNoLongerDoes() {
        String compose = ComposeTemplate.compose(tokens());
        String edge = serviceBlock(compose, "qits-platform-edge");
        String gateway = serviceBlock(compose, ENV + "-qits-gateway");

        assertThat(edge).contains("- \"8080:8080\"");
        assertThat(edge).contains("QITS_EDGE_ENVIRONMENTS: prod")
                .contains("QITS_EDGE_DEFAULT_ENVIRONMENT: prod")
                .contains("QITS_EDGE_UPSTREAM_HOST_PATTERN: \"{env}-qits-gateway\"");
        // Two binders for one port is a conflict that only shows up on the second cutover.
        assertThat(gateway).doesNotContain("ports:");
        assertThat(runArgsLine("qits-platform-edge")).contains("-p 8080:8080");
        assertThat(runArgsLine("qits-gateway")).doesNotContain("-p ");
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

        assertThat(serviceBlock(compose, ENV + "-qits-ci"))
                .contains("QITS_AUTH_MACHINE_AUDIENCE: prod-qits-ci")
                .contains("QUARKUS_OIDC_CLIENT_CLIENT_ID: prod-qits-ci")
                .contains("QUARKUS_OIDC_CLIENT_GRANT_OPTIONS_CLIENT_AUDIENCE: "
                        + "prod-qits-deployments");
        assertThat(serviceBlock(compose, ENV + "-qits-deployments"))
                .contains("QITS_AUTH_MACHINE_AUDIENCE: prod-qits-deployments");
        // The artifacts service validates a tier-qualified audience since the split, and mints
        // nothing at all: the git host's announcement was the only token it ever asked for.
        assertThat(serviceBlock(compose, ENV + "-qits-artifacts"))
                .contains("QITS_AUTH_MACHINE_AUDIENCE: prod-qits-artifacts")
                .doesNotContain("QUARKUS_OIDC_CLIENT_");

        assertThat(runArgsLine("qits-ci"))
                .contains("-e QITS_AUTH_MACHINE_AUDIENCE=prod-qits-ci")
                .contains("-e QUARKUS_OIDC_CLIENT_CLIENT_ID=prod-qits-ci")
                .contains("-e QUARKUS_OIDC_CLIENT_GRANT_OPTIONS_CLIENT_AUDIENCE="
                        + "prod-qits-deployments");
        assertThat(runArgsLine("qits-deployments"))
                .contains("-e QITS_AUTH_MACHINE_AUDIENCE=prod-qits-deployments");
        assertThat(runArgsLine("qits-artifacts"))
                .contains("-e QITS_AUTH_MACHINE_AUDIENCE=prod-qits-artifacts")
                .doesNotContain("QUARKUS_OIDC_CLIENT_");
        // Neither new byte service holds an identity: the mirror has no auth surface at all, and
        // the git host validates a push option rather than a token.
        assertThat(runArgsLine("qits-platform-mirror")).doesNotContain("QITS_AUTH_MACHINE_")
                .doesNotContain("QUARKUS_OIDC_");
        assertThat(runArgsLine("qits-githost")).doesNotContain("QITS_AUTH_MACHINE_")
                .doesNotContain("QUARKUS_OIDC_");
    }

    /**
     * <b>THE BYTE PLANE, in the seed stack.</b> Three services where there was one, each with its
     * own store, its own volume and its own door — and the split runs through every client's
     * configuration, which is what the rest of this class checks one consumer at a time.
     */
    @Test
    void theByteplaneIsThreeServicesWithThreeStoresAndThreeDoors() {
        String compose = ComposeTemplate.compose(tokens());
        String artifacts = serviceBlock(compose, ENV + "-qits-artifacts");
        String mirror = serviceBlock(compose, "qits-platform-mirror");
        String githost = serviceBlock(compose, ENV + "-qits-githost");

        // The hosted store keeps the registry port and the file H2 it always had; only its name
        // and its plane moved.
        assertThat(compose).contains("image: qits/artifacts:latest");
        assertThat(artifacts).contains("- \"127.0.0.1:8081:8080\"")
                .contains("QUARKUS_DATASOURCE_ARTIFACTS_JDBC_URL: "
                        + "jdbc:h2:file:/data/artifacts/h2/artifacts")
                .contains("- qits-artifacts-data:/data")
                // The git host left, and every knob it owned left with it.
                .doesNotContain("QITS_REPOSITORIES_GIT_")
                .doesNotContain("QITS_CI_INTAKE_URL");

        // The caches: a platform service, its own database, its own published door.
        assertThat(compose).contains("image: qits/platform-mirror:latest");
        assertThat(mirror).contains("- \"127.0.0.1:8082:8080\"")
                .contains("QITS_RESOURCE_DB_URL: "
                        + "jdbc:postgresql://prod-qits-oci-postgresql:5432/qits_platform_mirror")
                .contains("QITS_RESOURCE_DB_PASSWORD: \"1234123412341234\"")
                // Without it the process writes its cache where ${user.home} resolves for a
                // passwd-less uid, which is the literal "?".
                .contains("QITS_ARTIFACTS_BLOBS_DIR: /data/mirror/blobs")
                .contains("- qits-platform-mirror-data:/data");

        // The git host: two stores, because the outbox is a lineage of its own.
        assertThat(compose).contains("image: qits/githost:latest");
        assertThat(githost).contains("- \"127.0.0.1:8083:8080\"")
                .contains("QITS_RESOURCE_DB_URL: "
                        + "jdbc:postgresql://prod-qits-oci-postgresql:5432/qits_githost")
                .contains("QITS_RESOURCE_EVENTSTREAM_URL: "
                        + "jdbc:postgresql://prod-qits-oci-postgresql:5432/qits_githost_eventstream")
                .contains("QITS_ARTIFACTS_BLOBS_DIR: /data/githost/blobs")
                .contains("- qits-githost-data:/data")
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
        String runArgs = runArgsLine("qits-ci");

        for (String block : new String[]{ci.replace(": ", "="), runArgs}) {
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
        // platform's own image, and the mirror takes no writes at all.
        assertThat(runArgs).contains("-e QITS_ARTIFACTS_REGISTRY_HOST=localhost:8081");
        // The docs reader is handed the same address ci injects, so the two cannot disagree.
        assertThat(runArgsLine("qits-docs")).contains(
                "-e QITS_DOCS_ARTIFACTS_URL=http://prod-qits-artifacts:8080/artifacts/docs/docs");
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

        // ci appends /git/<repoId> itself, so its two keys are the ROOT with no path.
        assertThat(serviceBlock(compose, ENV + "-qits-ci"))
                .contains("QITS_CI_GIT_HOST_URL: " + host)
                .contains("QITS_CI_CONTAINER_GIT_URL: " + host);
        assertThat(runArgsLine("qits-ci")).contains("-e QITS_CI_GIT_HOST_URL=" + host)
                .contains("-e QITS_CI_CONTAINER_GIT_URL=" + host);
        // The two services that push. Their key was renamed with the split — a deployment still
        // passing qits.artifacts.url configures nothing and silently takes the default.
        assertThat(runArgsLine("qits-projects")).contains("-e QITS_GITHOST_URL=" + host)
                .contains("-e QITS_PROJECTS_AGENT_GIT_BASE=" + host + "/git")
                .doesNotContain("QITS_ARTIFACTS_URL");
        assertThat(runArgsLine("qits-workspaces")).contains("-e QITS_GITHOST_URL=" + host)
                .doesNotContain("QITS_ARTIFACTS_URL");
        // The deployer's OWN address for the same host — a plain property, because this file is its
        // configuration. Its shipped default is the pre-split qits-platform-artifacts, and this read
        // is how a green build becomes a deployment: without the line every build-succeeded event
        // dies on a connect timeout and nothing deploys.
        assertThat(ComposeTemplate.runArgs(tokens()))
                .contains("\nqits.platform.deployments.git-host-url=" + host + "\n");
        // The LINES, not the comments: the git host's own line says in prose where its clone url
        // used to be, and that sentence is why the reader knows what moved.
        assertThat(runArgsLines()).allSatisfy(line -> assertThat(line)
                .doesNotContain("/artifacts/git"));
    }

    @Test
    void theRouteTableClaimsTheDeployersSegmentAndNotTheRetiredOne() {
        String compose = ComposeTemplate.compose(tokens());

        // The route SEGMENT names the component and did not move; only the host did.
        assertThat(compose).contains(
                "QITS_GATEWAY_PROXY_HOSTS_PLATFORM_DEPLOYMENTS: prod-qits-deployments");
        // Both byte-plane hosts carry the tier now, and the docs segment moved with its
        // repository.
        assertThat(compose).contains(
                "QITS_GATEWAY_PROXY_HOSTS_ARTIFACTS: prod-qits-artifacts");
        assertThat(compose).contains("QITS_GATEWAY_PROXY_HOSTS_DOCS: prod-qits-docs");
        // THE SEGMENT IS `githost`, NOT `git`. The gateway composes the key from the segment, and
        // the segment names the service; /git rides the same entry as a second prefix, so every
        // clone url keeps working without a key of its own. The old key is an unknown service and
        // fails the gateway's startup, so this rename and the gateway's land in one release.
        assertThat(compose).contains("QITS_GATEWAY_PROXY_HOSTS_GITHOST: prod-qits-githost");
        // THE MIRROR IS ON THE TABLE, at its own segment only — a platform service, so no tier in
        // the alias. Its share of the store's prefixes is still unrouted: two services cannot share
        // one prefix behind one entry.
        assertThat(compose).contains("QITS_GATEWAY_PROXY_HOSTS_MIRROR: qits-platform-mirror");
        assertThat(compose).doesNotContain("QITS_GATEWAY_PROXY_HOSTS_CD:")
                .doesNotContain("QITS_GATEWAY_PROXY_HOSTS_GIT:");
        assertThat(runArgsLine("qits-gateway"))
                .contains("QITS_GATEWAY_PROXY_HOSTS_PLATFORM_DEPLOYMENTS=prod-qits-deployments")
                .contains("QITS_GATEWAY_PROXY_HOSTS_ARTIFACTS=prod-qits-artifacts")
                .contains("QITS_GATEWAY_PROXY_HOSTS_DOCS=prod-qits-docs")
                .contains("QITS_GATEWAY_PROXY_HOSTS_GITHOST=prod-qits-githost")
                .contains("QITS_GATEWAY_PROXY_HOSTS_MIRROR=qits-platform-mirror")
                .doesNotContain("QITS_GATEWAY_PROXY_HOSTS_CD=")
                .doesNotContain("QITS_GATEWAY_PROXY_HOSTS_GIT=");
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
        assertThat(runArgsLine("qits-ci")).doesNotContain("QITS_PLATFORM_DEPLOYMENTS_INTAKE_URL");
        assertThat(ComposeTemplate.runArgs(tokens()))
                .doesNotContain("-e QITS_PLATFORM_DEPLOYMENTS_INTAKE_URL=");

        assertThat(serviceBlock(compose, ENV + "-qits-ci"))
                .contains("QITS_EVENTS_URL: http://prod-qits-events:8080");
        assertThat(runArgsLine("qits-ci")).contains("-e QITS_EVENTS_URL=http://prod-qits-events:8080");
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
        // enforces no gate — which is why its run-args line carries neither either.
        assertThat(block).doesNotContain("volumes:")
                .doesNotContain("QITS_AUTH_MACHINE_")
                .doesNotContain("QUARKUS_OIDC_");
        // The deployer injects the triple from `resources: postgresql:db`; a pin in the run-args
        // would be appended after that injection and would outlive the next rotation.
        assertThat(runArgsLine("qits-events")).doesNotContain("QITS_RESOURCE_");
    }

    /**
     * <b>The container orchestrator is a seed service, and the SOCKET is what it is.</b> qits-ci runs
     * every pipeline step as a container it asks this service for, and the first pipeline of a cold
     * boot is minutes after the seed comes up — so it is here rather than at its own place in the
     * deploy train.
     * <p>
     * The mount and the group have to be in BOTH files. The compose half is what the seed can do;
     * the run-args half is what the deployer starts every successor with, and a socket missing there
     * is a cutover that leaves a service passing health and able to do nothing.
     */
    @Test
    void theOrchestratorIsInTheSeedWithItsTwoStoresAndKeepsTheSocketAcrossACutover() {
        String compose = ComposeTemplate.compose(tokens());
        String block = serviceBlock(compose, ENV + "-qits-containers");
        String runArgs = runArgsLine("qits-containers");

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
        // The socket and the group, in the seed. No data volume: the store is the postgres beside
        // it and nothing it writes outlives the container.
        assertThat(block).contains("group_add: [\"988\"]")
                .contains("- /var/run/docker.sock:/var/run/docker.sock");
        assertThat(compose).doesNotContain("qits-containers-data:");

        // And in the run-args, which is the half that survives the first cutover.
        assertThat(runArgs).contains("-v /var/run/docker.sock:/var/run/docker.sock")
                .contains("--group-add 988")
                .contains("-e QITS_EVENTS_URL=http://prod-qits-events:8080")
                .contains("-e QITS_AUTH_MACHINE_AUDIENCE=prod-qits-containers")
                .contains("-e QUARKUS_OIDC_AUTH_SERVER_URL=http://qits-platform-idp:8080/idp");
        // Both stores are declared in its deployments.yml, so the deployer injects the six
        // variables — a pin here is appended after that injection and outlives the next rotation.
        assertThat(runArgs).doesNotContain("QITS_RESOURCE_");
        // No gateway route, and there must not be one: every caller is a machine on qits-net, and a
        // route would put a socket-holding orchestrator behind the platform's public door.
        assertThat(compose).doesNotContain("QITS_GATEWAY_PROXY_HOSTS_CONTAINERS");
        assertThat(runArgsLine("qits-gateway")).doesNotContain("CONTAINERS");
    }

    /**
     * qits-workspaces is told where a release lands, and it is this environment's deploy ref.
     * <p>
     * It ships a default of {@code environment/prod}, so a platform bootstrapped under any other
     * name would silently promote every release onto a branch no environment listens to — the
     * release lands, CI never fires, nothing deploys, and no component reports an error. The
     * run-arg is what stops that, and it is why the key belongs beside the env name rather than in
     * the image.
     */
    @Test
    void workspacesIsToldWhereAReleaseLands() {
        assertThat(runArgsLine("qits-workspaces"))
                .contains("-e QITS_WORKSPACES_RELEASE_ENTRY_BRANCH=environment/prod");

        Map<String, String> staging = tokens();
        staging.put("ENV_NAME", "staging");
        assertThat(ComposeTemplate.runArgs(staging))
                .contains("-e QITS_WORKSPACES_RELEASE_ENTRY_BRANCH=environment/staging");
    }

    @Test
    void runArgsCoverEveryApplicationThatNeedsMoreThanItsImage() {
        String properties = ComposeTemplate.runArgs(tokens());

        for (String application : new String[]{"qits-platform-edge", "qits-gateway",
                "qits-artifacts", "qits-platform-mirror", "qits-githost", "qits-containers",
                "qits-ci", "qits-deployments", "qits-platform-idp",
                "qits-platform-dns", "qits-stt", "qits-projects", "qits-workspaces", "qits-events",
                "qits-docs", "qits-observability", "qits-oci-postgresql"}) {
            assertThat(properties).contains("qits.platform.deployments.run-args." + application + "=");
        }
        // The retired pair is deployed by nothing, so it configures nothing.
        assertThat(properties).doesNotContain("run-args.qits-cd=")
                .doesNotContain("run-args.qits-serviceregistry=");
        // A line under a pre-rename application name configures NOTHING: the deployment comes up
        // with no volumes and no env, passes health, and has lost its database.
        assertThat(properties).doesNotContain("run-args.qits-idp=")
                .doesNotContain("run-args.qits-platform-deployments=")
                // The two the byte-plane split retired, on the same terms.
                .doesNotContain("run-args.qits-platform-artifacts=")
                .doesNotContain("run-args.qits-platform-docs=");
        // The namespace moved with the merge-back; the old spelling configures nothing.
        assertThat(properties).doesNotContain("qits.cd.run-args.");
        assertThat(properties).contains("-e QITS_REPOSITORIES_GIT_PUSH_TOKEN=local-dev");
        assertThat(properties).contains("--group-add 988");
        assertThat(properties).doesNotContain("${DOCKER_GID}")
                .doesNotContain("${ENV_NAME}")
                .doesNotContain("${ENV_KEY}");
        // {env} is the edge's own placeholder and is NOT a token of this file: it is read at
        // runtime by the process the line configures.
        assertThat(properties).contains("QITS_EDGE_UPSTREAM_HOST_PATTERN={env}-qits-gateway");
    }

    /**
     * The two qits-projects dials were the GIT HOST's, and they left with it. The name resolver is
     * what turns a name-addressed clone into a repo id; the backup intake is gone entirely, because
     * a push is a durable event both consumers read off the bus.
     */
    @Test
    void theGitHostDialsProjectsForNameResolutionAndNobodyPostsAPushAnyMore() {
        assertThat(runArgsLine("qits-githost")).contains("-e QITS_PROJECTS_NAME_RESOLVER_URL="
                + "http://prod-qits-projects:8080/projects/api/projects");
        assertThat(runArgsLine("qits-artifacts")).doesNotContain("QITS_PROJECTS_");
        assertThat(runArgsLines()).allSatisfy(line -> assertThat(line)
                .doesNotContain("QITS_PROJECTS_INTAKE_URL")
                .doesNotContain("QITS_CI_INTAKE_URL"));
        assertThat(ComposeTemplate.compose(tokens()).lines()
                .filter(line -> line.strip().startsWith("QITS_"))
                .toList())
                .allSatisfy(line -> assertThat(line).doesNotContain("QITS_CI_INTAKE_URL"));
    }

    @Test
    void theDeployersOwnDeploymentInheritsItsConfigVolume() {
        // The self-update handoff: without this mount the successor comes up with no run-args at
        // all and every later deployment loses its volumes and its datasource env.
        String deployer = runArgsLine("qits-deployments");

        assertThat(deployer).contains("-v qits-deployments-config:/work/config");
        assertThat(deployer).contains("-v /var/run/docker.sock:/var/run/docker.sock");
        assertThat(deployer).contains("-e QITS_RESOURCE_DB_URL="
                + "jdbc:postgresql://prod-qits-oci-postgresql:5432/qits_deployments");
        assertThat(deployer).contains("-e QITS_RESOURCE_DB_USERNAME=qits_deployments");
        assertThat(deployer).contains("-e QITS_RESOURCE_DB_PASSWORD=fedcba9876543210");
        assertThat(deployer).contains("-e QITS_ENVIRONMENT=prod");
        assertThat(deployer).contains(
                "-e QITS_PLATFORM_DEPLOYMENTS_POSTGRES_ADMIN_PASSWORD=0123456789abcdef");
        // The bus, and the ONLY thing the deployer's bus membership adds to this line: the wire
        // name. A subscriber pointed at the pre-rename qits-events:8080 receives nothing.
        assertThat(deployer).contains("-e QITS_EVENTS_URL=http://prod-qits-events:8080");
        // ONE TRIPLE, NOT TWO. The outbox is declared in the deployer's own deployments.yml, so a
        // running deployer provisions it for its successor and injects the triple from the registry
        // row that holds the current password — pinning it here would outlive the next rotation.
        assertThat(deployer).doesNotContain("QITS_RESOURCE_EVENTSTREAM_");
        // The store moved off the file H2, and its volume went with it: /data held nothing else.
        assertThat(deployer).doesNotContain("QUARKUS_DATASOURCE_PLATFORMDEPLOYMENTS_JDBC_URL=")
                .doesNotContain("-v qits-deployments-data:/data");
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
        String runArgs = runArgsLine("qits-oci-postgresql");

        assertThat(compose).contains("image: qits/oci-postgresql:latest");
        // The mount, not the comment beside it, which names the wrong path on purpose.
        assertThat(block).contains("- qits-oci-postgresql-data:/var/lib/postgresql\n")
                .doesNotContain("- qits-oci-postgresql-data:/var/lib/postgresql/data");
        assertThat(block).contains("- \"127.0.0.1:5433:5432\"");
        assertThat(block).contains("POSTGRES_PASSWORD: \"0123456789abcdef\"");
        assertThat(block).contains("pg_isready -U postgres");
        assertThat(block).contains("restart: unless-stopped");
        // No depends_on anywhere: a dependency would resurrect a compose sibling beside its
        // deployed replacement. The deployer's refuse-to-boot and restart policy are the retry.
        // The comments that say so are not one.
        assertThat(compose.lines().map(String::strip).filter(line -> line.startsWith("depends_on"))
                .toList()).isEmpty();

        assertThat(runArgs).contains("-v qits-oci-postgresql-data:/var/lib/postgresql ")
                .doesNotContain("-v qits-oci-postgresql-data:/var/lib/postgresql/data");
        // The host publish survives the cutover, so this CLI can reconnect to the same server
        // after the deployer replaces the seed container.
        assertThat(runArgs).contains("-p 127.0.0.1:5433:5432");
        assertThat(runArgs).contains("-e POSTGRES_PASSWORD=0123456789abcdef");
    }

    @Test
    void theIdpsDeploymentCarriesEverySecretAndTheClientListItself() {
        String idp = runArgsLine("qits-platform-idp");

        // No volume and no datasource: the store is a database the deployer provisions from
        // `resources: postgresql:db` and injects. The signing key is in it, so pinning the triple
        // here would outlive a rotation and take every token in flight down with it.
        assertThat(idp).doesNotContain("-v qits-platform-idp-data:/data")
                .doesNotContain("QUARKUS_DATASOURCE_IDP_JDBC_URL")
                .doesNotContain("QITS_RESOURCE_");
        for (String app : PlatformModel.IDP_CLIENT_APPS) {
            assertThat(idp).contains("secret-" + PlatformModel.wireAlias(app, ENV));
        }
        // Each of these keys REPLACES the shipped list rather than extending it, and every id in
        // them carries the environment name, which no shipped default can follow.
        assertThat(idp).contains("QITS_IDP_CLIENTS=prod-qits-ci,prod-qits-artifacts,"
                + "prod-qits-workspaces,prod-qits-gateway");
        assertThat(idp).contains("QITS_IDP_CLIENT_PROD_QITS_CI_AUDIENCES=")
                .contains("QITS_IDP_CLIENT_PROD_QITS_ARTIFACTS_AUDIENCES=")
                .contains("prod-qits-deployments");
        // The wildcard project claim, under the id it moved to when the store became an
        // environment service. It is this bootstrap's own trigger identity now: the git host mints
        // nothing at all.
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
        // no /data at all now; the socket is what it still needs.
        assertThat(ci).doesNotContain("QUARKUS_DATASOURCE_CI_JDBC_URL")
                .doesNotContain("QUARKUS_DATASOURCE_EVENTSTREAM_JDBC_URL")
                .doesNotContain("- qits-ci-data:/data")
                .contains("- /var/run/docker.sock:/var/run/docker.sock");

        assertThat(idp).contains("QITS_RESOURCE_DB_URL: "
                        + "jdbc:postgresql://prod-qits-oci-postgresql:5432/qits_platform_idp")
                .contains("QITS_RESOURCE_DB_USERNAME: qits_platform_idp")
                .contains("QITS_RESOURCE_DB_PASSWORD: \"2222333344445555\"");
        assertThat(idp).doesNotContain("QUARKUS_DATASOURCE_IDP_JDBC_URL")
                .doesNotContain("- qits-platform-idp-data:/data");

        // A volume declaration with no mount is a volume nothing ever fills, and the next reader
        // has to work out which. Exactly the three that lost their only mount are gone.
        assertThat(compose).doesNotContain("qits-ci-data:")
                .doesNotContain("qits-platform-idp-data:")
                .doesNotContain("qits-events-data:")
                .doesNotContain("qits-deployments-data:");
        assertThat(compose).contains("qits-projects-data:")
                .contains("qits-workspaces-data:")
                .contains("qits-stt-data:")
                .contains("qits-oci-postgresql-data:")
                // One volume per byte store, and never a shared one: each service's reclaim sweep
                // counts every file it did not put there as unreferenced.
                .contains("qits-artifacts-data:")
                .contains("qits-platform-mirror-data:")
                .contains("qits-githost-data:");
    }

    /**
     * THE GUARD ON THE WHOLE MOVE. Every service that flipped to postgres declares its store in its
     * own deployments.yml, so the deployer injects the triple and a datasource line here would be an
     * operator pin that outlives the next password rotation.
     * <p>
     * qits-platform-artifacts is the one service still on a file H2, and its line must keep saying
     * so: it is what proves the sweep took the services it was aimed at and not one more.
     */
    @Test
    void onlyArtifactsStillCarriesAFileDatabase() {
        List<String> h2 = ComposeTemplate.runArgs(tokens()).lines()
                .filter(line -> line.startsWith("qits.platform.deployments.run-args."))
                .filter(line -> line.contains("jdbc:h2"))
                .toList();

        assertThat(h2).singleElement().asString()
                .startsWith("qits.platform.deployments.run-args.qits-artifacts=")
                .contains("-e QUARKUS_DATASOURCE_ARTIFACTS_JDBC_URL=jdbc:h2:file:/data/artifacts"
                        + "/h2/artifacts")
                .contains("-v qits-artifacts-data:/data");
        // The two new byte services declare their stores instead, so the deployer injects the
        // triples and a pin here would outlive the next rotation.
        assertThat(runArgsLine("qits-platform-mirror")).doesNotContain("QITS_RESOURCE_")
                .contains("-v qits-platform-mirror-data:/data");
        assertThat(runArgsLine("qits-githost")).doesNotContain("QITS_RESOURCE_")
                .contains("-v qits-githost-data:/data");

        // No run-args line pins a resource triple either: the deployer's injection comes first and
        // docker keeps the last -e, so a line here would win and never be rotated.
        assertThat(ComposeTemplate.runArgs(tokens())).doesNotContain("-e QITS_RESOURCE_DB_URL=jdbc:"
                + "postgresql://prod-qits-oci-postgresql:5432/qits_ci");
        assertThat(runArgsLine("qits-ci")).doesNotContain("QITS_RESOURCE_")
                .doesNotContain("-v qits-ci-data:/data");
        assertThat(runArgsLine("qits-events")).doesNotContain("QITS_RESOURCE_")
                .doesNotContain("-v qits-events-data:/data");
        // The orchestrator declares two stores and keeps no volume: the socket is the only thing
        // its line mounts.
        assertThat(runArgsLine("qits-containers")).doesNotContain("QITS_RESOURCE_")
                .doesNotContain("-v qits-containers-data:/data");
        // The two that keep a volume: /data is their own tree of files, not a database.
        assertThat(runArgsLine("qits-projects")).contains("-v qits-projects-data:/data")
                .contains("-e QITS_PROJECTS_DATA_DIR=/data/mirrors")
                .doesNotContain("QITS_RESOURCE_");
        assertThat(runArgsLine("qits-workspaces")).contains("-v qits-workspaces-data:/data")
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
        assertThat(ComposeTemplate.runArgs(tokens()).lines()
                .filter(line -> line.startsWith("qits.platform.deployments.run-args."))
                .filter(line -> !line.startsWith(
                        "qits.platform.deployments.run-args.qits-oci-postgresql="))
                .toList())
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line)
                        .contains("-e QITS_OBSERVABILITY_URL=" + url));
        // The receiver is an ordinary OTLP client of itself. Consistent rather than clever: the
        // alternative leaves exactly one container spamming retries at a dead name.
        assertThat(runArgsLine("qits-observability"))
                .isEqualTo("qits.platform.deployments.run-args.qits-observability="
                        + "-e QITS_OBSERVABILITY_URL=" + url);
    }

    /**
     * The nameserver, in both files: two publishes because both transports are mandatory, and the
     * database triple because compose starts it before any deployer exists to inject one.
     */
    @Test
    void theNameserverPublishesBothTransportsAndIsHandedItsDatabase() {
        String compose = ComposeTemplate.compose(tokens());
        String block = serviceBlock(compose, "qits-platform-dns");
        String runArgs = runArgsLine("qits-platform-dns");

        assertThat(compose).contains("image: qits/platform-dns:latest");
        // TCP is not the optional half: a truncated UDP answer carries ZERO records, so the client's
        // TCP retry is the only way it ever gets one.
        assertThat(block).contains("- \"53:8053/udp\"").contains("- \"53:8053/tcp\"");
        assertThat(block).contains("QITS_RESOURCE_DB_URL: "
                        + "jdbc:postgresql://prod-qits-oci-postgresql:5432/qits_platform_dns")
                .contains("QITS_RESOURCE_DB_USERNAME: qits_platform_dns")
                .contains("QITS_RESOURCE_DB_PASSWORD: \"66667777888899aa\"");
        // No volume: the store is postgres and this service writes nothing that outlives it.
        assertThat(block).doesNotContain("volumes:");

        assertThat(runArgs).contains("-p 53:8053/udp").contains("-p 53:8053/tcp");
        // The deployer injects the triple from `resources: postgresql:db`; a pin here would outlive
        // a rotation and break the deployment it looks like it is configuring.
        assertThat(runArgs).doesNotContain("QITS_RESOURCE_");
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
        String runArgs = ComposeTemplate.runArgs(tokens(DOMAIN));
        for (String fragment : DomainTokens.of(Optional.of(DOMAIN)).values()) {
            assertThat(fragment).isNotEmpty();
            compose = compose.replace(fragment, "");
            runArgs = runArgs.replace(fragment, "");
        }

        assertThat(compose).isEqualTo(ComposeTemplate.compose(tokens()));
        assertThat(runArgs).isEqualTo(ComposeTemplate.runArgs(tokens()));
    }

    /** With no domain, not one trace of the zone, the TLS ports or the certificate volume. */
    @Test
    void withNoDomainThereIsNoTlsAndNoNameserverIdentity() {
        String compose = ComposeTemplate.compose(tokens());
        String runArgs = ComposeTemplate.runArgs(tokens());

        assertThat(compose).doesNotContain("letsencrypt")
                .doesNotContain("QITS_DNS_NS_NAMES")
                .doesNotContain("QITS_DNS_HOSTMASTER")
                .doesNotContain("QUARKUS_TLS_")
                .doesNotContain("443:8443")
                .doesNotContain("127.0.0.1:9000");
        assertThat(runArgs).doesNotContain("letsencrypt")
                .doesNotContain("QITS_DNS_NS_NAMES")
                .doesNotContain("QUARKUS_TLS_");
        // The edge keeps the one port it always published.
        assertThat(runArgsLine("qits-platform-edge")).contains("-p 8080:8080");
    }

    /**
     * A configured domain, in both files, on both services. <b>Both dns variables or neither</b>: the
     * service turns SOA and NS synthesis off unless it holds the pair, and a half-configured zone
     * answers records while resolvers cannot negative-cache — load and latency, never an alarm.
     */
    @Test
    void aDomainGivesTheNameserverItsIdentityInBothFiles() {
        String block = serviceBlock(ComposeTemplate.compose(tokens(DOMAIN)), "qits-platform-dns");
        String runArgs = runArgsLine("qits-platform-dns", tokens(DOMAIN));

        assertThat(block).contains("QITS_DNS_NS_NAMES: ns1.qits-dev.eu")
                .contains("QITS_DNS_HOSTMASTER: hostmaster.qits-dev.eu");
        assertThat(runArgs).contains("-e QITS_DNS_NS_NAMES=ns1.qits-dev.eu")
                .contains("-e QITS_DNS_HOSTMASTER=hostmaster.qits-dev.eu");
    }

    /**
     * The edge's TLS wiring, in both files. The run-args half is the one that is easy to forget and
     * expensive to: it is what the deployer starts the successor with, so a piece missing there is a
     * cutover that takes 443 and the certificate away while health goes on passing on 8080.
     */
    @Test
    void aDomainGivesTheEdgeItsCertificateSlotInBothFiles() {
        String compose = ComposeTemplate.compose(tokens(DOMAIN));
        String edge = serviceBlock(compose, "qits-platform-edge");
        String runArgs = runArgsLine("qits-platform-edge", tokens(DOMAIN));

        // The host's own port stays; 80 is the ACME challenge, 443 the TLS listener, and 9000 the
        // management interface on LOOPBACK — the challenge-management endpoint is unauthenticated.
        assertThat(edge).contains("- \"8080:8080\"")
                .contains("- \"80:8080\"")
                .contains("- \"443:8443\"")
                .contains("- \"127.0.0.1:9000:9000\"");
        assertThat(edge).contains(
                        "QUARKUS_TLS_KEY_STORE_PEM_ACME_CERT: /work/.letsencrypt/lets-encrypt.crt")
                .contains("QUARKUS_TLS_KEY_STORE_PEM_ACME_KEY: /work/.letsencrypt/lets-encrypt.key")
                .contains("QUARKUS_TLS_RELOAD_PERIOD: 1h")
                .contains("- qits-edge-letsencrypt:/work/.letsencrypt");
        // A mounted volume has to be declared, or compose refuses the file.
        assertThat(compose).contains("qits-edge-letsencrypt:\n    name: qits-edge-letsencrypt");
        // insecure-requests stays at its default: every health poll in the boot speaks plain HTTP.
        assertThat(compose).doesNotContain("INSECURE_REQUESTS");

        assertThat(runArgs).contains("-p 8080:8080")
                .contains("-p 80:8080")
                .contains("-p 443:8443")
                .contains("-p 127.0.0.1:9000:9000")
                .contains("-v qits-edge-letsencrypt:/work/.letsencrypt")
                .contains("-e QUARKUS_TLS_KEY_STORE_PEM_ACME_CERT=/work/.letsencrypt/lets-encrypt.crt")
                .contains("-e QUARKUS_TLS_KEY_STORE_PEM_ACME_KEY=/work/.letsencrypt/lets-encrypt.key")
                .contains("-e QUARKUS_TLS_RELOAD_PERIOD=1h");
    }

    @Test
    void theEnvironmentNameReachesEveryGeneratedAddress() {
        Map<String, String> other = tokens();
        other.put("ENV_NAME", "preprod");
        other.put("ENV_KEY", "PREPROD");

        assertThat(ComposeTemplate.compose(other))
                .contains("container_name: preprod-qits-ci")
                .contains("QITS_EDGE_ENVIRONMENTS: preprod")
                .contains("QITS_IDP_CLIENT_PREPROD_QITS_CI_SECRET");
        assertThat(ComposeTemplate.runArgs(other))
                .contains("-e QITS_AUTH_MACHINE_AUDIENCE=preprod-qits-ci")
                .contains("QITS_GATEWAY_PROXY_HOSTS_CI=preprod-qits-ci")
                .contains("-e QITS_OBSERVABILITY_URL=http://preprod-qits-observability:8080");
    }
}
