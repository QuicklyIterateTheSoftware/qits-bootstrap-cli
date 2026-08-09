package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ComposeTemplateTest {

    private static final String ENV = "prod";

    private static Map<String, String> tokens() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ENV_NAME", ENV);
        values.put("ENV_KEY", PlatformModel.clientKey(ENV));
        values.put("COMPOSE_FILE", "docker-compose.qits.yml");
        values.put("PORT", "8080");
        values.put("REGISTRY_PORT", "8081");
        values.put("PG_PORT", "5433");
        values.put("PG_SUPERUSER_PASSWORD", "0123456789abcdef");
        values.put("PG_DEPLOYMENTS_PASSWORD", "fedcba9876543210");
        values.put("PG_CI_PASSWORD", "aaaabbbbccccdddd");
        values.put("PG_CI_EVENTSTREAM_PASSWORD", "eeeeffff00001111");
        values.put("PG_PLATFORM_IDP_PASSWORD", "2222333344445555");
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
        return values;
    }

    /** One service's own lines, so an assertion about what it does NOT carry means that service. */
    private static String serviceBlock(String compose, String container) {
        int start = compose.indexOf("container_name: " + container);
        assertThat(start).isNotNegative();
        int next = compose.indexOf("container_name: ", start + 1);
        return next < 0 ? compose.substring(start) : compose.substring(start, next);
    }

    private static String runArgsLine(String application) {
        return ComposeTemplate.runArgs(tokens()).lines()
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
        assertThat(compose).doesNotContain("${PG_SUPERUSER_PASSWORD}")
                .doesNotContain("${PG_DEPLOYMENTS_PASSWORD}")
                .doesNotContain("${PG_CI_PASSWORD}")
                .doesNotContain("${PG_CI_EVENTSTREAM_PASSWORD}")
                .doesNotContain("${PG_PLATFORM_IDP_PASSWORD}");
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
        assertThat(compose).doesNotContain("container_name: qits-artifacts")
                .doesNotContain("container_name: qits-idp")
                .doesNotContain("container_name: qits-platform-deployments");
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
        // The artifacts service is a platform service, so its alias IS its repository name and the
        // shipped default is already right — but who its outbound token is FOR still moved.
        assertThat(serviceBlock(compose, "qits-platform-artifacts"))
                .doesNotContain("QITS_AUTH_MACHINE_AUDIENCE")
                .contains("QUARKUS_OIDC_CLIENT_GRANT_OPTIONS_CLIENT_AUDIENCE: prod-qits-ci");

        assertThat(runArgsLine("qits-ci"))
                .contains("-e QITS_AUTH_MACHINE_AUDIENCE=prod-qits-ci")
                .contains("-e QUARKUS_OIDC_CLIENT_CLIENT_ID=prod-qits-ci")
                .contains("-e QUARKUS_OIDC_CLIENT_GRANT_OPTIONS_CLIENT_AUDIENCE="
                        + "prod-qits-deployments");
        assertThat(runArgsLine("qits-deployments"))
                .contains("-e QITS_AUTH_MACHINE_AUDIENCE=prod-qits-deployments");
    }

    @Test
    void theRouteTableClaimsTheDeployersSegmentAndNotTheRetiredOne() {
        String compose = ComposeTemplate.compose(tokens());

        // The route SEGMENT names the component and did not move; only the host did.
        assertThat(compose).contains(
                "QITS_GATEWAY_PROXY_HOSTS_PLATFORM_DEPLOYMENTS: prod-qits-deployments");
        assertThat(compose).contains(
                "QITS_GATEWAY_PROXY_HOSTS_ARTIFACTS: qits-platform-artifacts");
        assertThat(compose).doesNotContain("QITS_GATEWAY_PROXY_HOSTS_CD:");
        assertThat(runArgsLine("qits-gateway"))
                .contains("QITS_GATEWAY_PROXY_HOSTS_PLATFORM_DEPLOYMENTS=prod-qits-deployments")
                .contains("QITS_GATEWAY_PROXY_HOSTS_ARTIFACTS=qits-platform-artifacts")
                .contains("QITS_GATEWAY_PROXY_HOSTS_PLATFORM_DOCS=qits-platform-docs")
                .doesNotContain("QITS_GATEWAY_PROXY_HOSTS_CD=");
    }

    @Test
    void ciIsPointedAtTheIntakeInBothPlaces() {
        // Fire-and-forget and swallowed at debug: a wrong value deploys nothing and says nothing,
        // which is why it is spelled rather than inherited. Move one, move both.
        String intake = "QITS_PLATFORM_DEPLOYMENTS_INTAKE_URL"
                + ": http://prod-qits-deployments:8080"
                + "/platform-deployments/api/events/build-succeeded";

        assertThat(ComposeTemplate.compose(tokens())).contains(intake);
        assertThat(runArgsLine("qits-ci")).contains(
                "-e QITS_PLATFORM_DEPLOYMENTS_INTAKE_URL=http://prod-qits-deployments:8080"
                        + "/platform-deployments/api/events/build-succeeded");
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
                "qits-platform-artifacts", "qits-ci", "qits-deployments", "qits-platform-idp",
                "qits-stt", "qits-projects", "qits-workspaces", "qits-events",
                "qits-platform-docs", "qits-observability", "qits-oci-postgresql"}) {
            assertThat(properties).contains("qits.platform.deployments.run-args." + application + "=");
        }
        // The retired pair is deployed by nothing, so it configures nothing.
        assertThat(properties).doesNotContain("run-args.qits-cd=")
                .doesNotContain("run-args.qits-serviceregistry=");
        // A line under a pre-rename application name configures NOTHING: the deployment comes up
        // with no volumes and no env, passes health, and has lost its database.
        assertThat(properties).doesNotContain("run-args.qits-artifacts=")
                .doesNotContain("run-args.qits-idp=")
                .doesNotContain("run-args.qits-platform-deployments=");
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

    @Test
    void artifactsDialsProjectsForBackupsAndForNameResolution() {
        // Both keys default to a monolith-era localhost, so unset means the service calls itself:
        // no backups, and a name-addressed git clone that 404s with an empty agent /workspace.
        String artifacts = runArgsLine("qits-platform-artifacts");

        assertThat(artifacts).contains("-e QITS_PROJECTS_INTAKE_URL=http://prod-qits-projects:8080"
                + "/projects/api/events/post-receive");
        assertThat(artifacts).contains("-e QITS_PROJECTS_NAME_RESOLVER_URL="
                + "http://prod-qits-projects:8080/projects/api/projects");
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
        assertThat(idp).contains("QITS_IDP_CLIENTS=prod-qits-ci,qits-platform-artifacts,"
                + "prod-qits-workspaces,prod-qits-gateway");
        assertThat(idp).contains("QITS_IDP_CLIENT_PROD_QITS_CI_AUDIENCES=")
                .contains("QITS_IDP_CLIENT_QITS_PLATFORM_ARTIFACTS_AUDIENCES=")
                .contains("prod-qits-deployments");
        // The git host's wildcard project claim, under the id the git host now holds.
        assertThat(idp).contains("QITS_IDP_CLIENT_QITS_PLATFORM_ARTIFACTS_CLAIMS_PROJECT=*");
    }

    /**
     * The two CORE SEED SERVICES' stores. Both containers are started by compose before any deployer
     * exists, so the seed file is the only place their credentials can come from — and the bootstrap
     * created the roles and the databases over JDBC minutes earlier.
     */
    @Test
    void theSeedsTwoDatabaseConsumersAreHandedTheirTriples() {
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
                .contains("qits-platform-artifacts-data:")
                .contains("qits-oci-postgresql-data:");
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
                .startsWith("qits.platform.deployments.run-args.qits-platform-artifacts=")
                .contains("-e QUARKUS_DATASOURCE_ARTIFACTS_JDBC_URL=jdbc:h2:file:/data/artifacts"
                        + "/h2/artifacts")
                .contains("-v qits-platform-artifacts-data:/data");

        // No run-args line pins a resource triple either: the deployer's injection comes first and
        // docker keeps the last -e, so a line here would win and never be rotated.
        assertThat(ComposeTemplate.runArgs(tokens())).doesNotContain("-e QITS_RESOURCE_DB_URL=jdbc:"
                + "postgresql://prod-qits-oci-postgresql:5432/qits_ci");
        assertThat(runArgsLine("qits-ci")).doesNotContain("QITS_RESOURCE_")
                .doesNotContain("-v qits-ci-data:/data");
        assertThat(runArgsLine("qits-events")).doesNotContain("QITS_RESOURCE_")
                .doesNotContain("-v qits-events-data:/data");
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
