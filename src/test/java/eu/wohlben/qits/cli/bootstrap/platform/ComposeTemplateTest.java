package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
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
        assertThat(block).contains("QUARKUS_DATASOURCE_PLATFORMDEPLOYMENTS_JDBC_URL: "
                + "jdbc:h2:file:/data/platformdeployments/h2/platformdeployments");
        assertThat(block).contains("- qits-deployments-data:/data");
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

    @Test
    void runArgsCoverEveryApplicationThatNeedsMoreThanItsImage() {
        String properties = ComposeTemplate.runArgs(tokens());

        for (String application : new String[]{"qits-platform-edge", "qits-gateway",
                "qits-platform-artifacts", "qits-ci", "qits-deployments", "qits-platform-idp",
                "qits-stt", "qits-projects", "qits-workspaces", "qits-events",
                "qits-platform-docs", "qits-observability"}) {
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
    void theDeployersOwnDeploymentInheritsItsConfigVolume() {
        // The self-update handoff: without this mount the successor comes up with no run-args at
        // all and every later deployment loses its volumes and its datasource env.
        String deployer = runArgsLine("qits-deployments");

        assertThat(deployer).contains("-v qits-deployments-data:/data");
        assertThat(deployer).contains("-v qits-deployments-config:/work/config");
        assertThat(deployer).contains("-v /var/run/docker.sock:/var/run/docker.sock");
        assertThat(deployer).contains("QUARKUS_DATASOURCE_PLATFORMDEPLOYMENTS_JDBC_URL=");
    }

    @Test
    void theIdpsDeploymentCarriesItsVolumeEverySecretAndTheClientListItself() {
        String idp = runArgsLine("qits-platform-idp");

        assertThat(idp).contains("-v qits-platform-idp-data:/data");
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

    @Test
    void everythingGeneratedIsToldWhereTelemetryGoes() {
        // The images ship the bare qits-observability, and the 2026-08-08 rename killed that name.
        // An exporter dialling a name that does not resolve drops every trace and every log AND
        // retries, so a missing line here is a dark platform and a container log full of attempts.
        String url = "http://prod-qits-observability:8080";
        String compose = ComposeTemplate.compose(tokens());

        for (String name : PlatformModel.CORE) {
            assertThat(serviceBlock(compose, PlatformModel.wireAlias(name, ENV)))
                    .as("seed service %s", name)
                    .contains("QITS_OBSERVABILITY_URL: " + url);
        }
        assertThat(ComposeTemplate.runArgs(tokens()).lines()
                .filter(line -> line.startsWith("qits.platform.deployments.run-args."))
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
