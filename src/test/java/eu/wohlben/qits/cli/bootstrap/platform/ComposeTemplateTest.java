package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ComposeTemplateTest {

    private static Map<String, String> tokens() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ENV_NAME", "dev");
        values.put("COMPOSE_FILE", "docker-compose.qits.yml");
        values.put("PORT", "8080");
        values.put("REGISTRY_PORT", "8081");
        values.put("IDP", "http://qits-idp:8080/idp");
        values.put("PUSH_TOKEN", "local-dev");
        values.put("MACHINE_REQUIRED", "true");
        values.put("MACHINE_CLIENT", "true");
        values.put("DOCKER_GID", "988");
        values.put("DAEMON_SHA", "abc123");
        values.put("IDP_AUDIENCES", PlatformModel.IDP_AUDIENCES);
        for (String client : PlatformModel.IDP_CLIENTS) {
            values.put("IDP_SECRET_" + PlatformModel.clientKey(client), "secret-" + client);
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

    @Test
    void fillsEveryPlaceholderOfTheComposeFile() {
        String compose = ComposeTemplate.compose(tokens());

        assertThat(compose).contains("- \"8080:8080\"");
        assertThat(compose).contains("- \"127.0.0.1:8081:8080\"");
        assertThat(compose).contains("QITS_IDP_ISSUER: http://qits-idp:8080/idp");
        assertThat(compose).contains("QITS_IDP_CLIENT_QITS_CI_SECRET: \"secret-qits-ci\"");
        assertThat(compose).contains("group_add: [\"988\"]");
        assertThat(compose).contains("QITS_CI_DAEMON_VERSION: \"abc123\"");
        assertThat(compose).doesNotContain("${PORT}");
        assertThat(compose).doesNotContain("${IDP_SECRET_");
        assertThat(compose).doesNotContain("${IDP_AUDIENCES}");
    }

    @Test
    void keepsTheWarningAboutUserHomeReadable() {
        // ${user.home} is the failure the comment warns about, not a placeholder to fill.
        assertThat(ComposeTemplate.compose(tokens())).contains("under ${user.home}");
    }

    @Test
    void everySeedServiceIsInTheStack() {
        String compose = ComposeTemplate.compose(tokens());

        for (String name : PlatformModel.CORE) {
            assertThat(compose).contains("container_name: qits-" + name);
        }
        // One component replaced both: neither ancestor is in the seed any more.
        assertThat(compose).doesNotContain("container_name: qits-cd")
                .doesNotContain("container_name: qits-serviceregistry");
    }

    @Test
    void theDeployerCarriesItsDatabaseItsConfigVolumeAndTheSocket() {
        String compose = ComposeTemplate.compose(tokens());
        String block = serviceBlock(compose, "qits-platform-deployments");

        assertThat(compose).contains("image: qits/platform-deployments:latest");
        assertThat(block).contains("QUARKUS_DATASOURCE_PLATFORMDEPLOYMENTS_JDBC_URL: "
                + "jdbc:h2:file:/data/platformdeployments/h2/platformdeployments");
        assertThat(block).contains("- qits-platform-deployments-data:/data");
        assertThat(block).contains("- qits-platform-deployments-config:/work/config");
        assertThat(block).contains("- /var/run/docker.sock:/var/run/docker.sock");
        // Machine auth inbound only: it validates a bearer and mints none, so no oidc-client.
        assertThat(block).contains("QITS_AUTH_MACHINE_REQUIRED: \"true\"")
                .contains("QUARKUS_OIDC_AUTH_SERVER_URL: http://qits-idp:8080/idp")
                .doesNotContain("QUARKUS_OIDC_CLIENT_CLIENT_ENABLED");
    }

    @Test
    void theRouteTableClaimsTheDeployersSegmentAndNotTheRetiredOne() {
        String compose = ComposeTemplate.compose(tokens());

        assertThat(compose).contains(
                "QITS_GATEWAY_PROXY_HOSTS_PLATFORM_DEPLOYMENTS: qits-platform-deployments");
        assertThat(compose).doesNotContain("QITS_GATEWAY_PROXY_HOSTS_CD:");
        assertThat(ComposeTemplate.runArgs(tokens()))
                .contains("QITS_GATEWAY_PROXY_HOSTS_PLATFORM_DEPLOYMENTS=qits-platform-deployments")
                .doesNotContain("QITS_GATEWAY_PROXY_HOSTS_CD=");
    }

    @Test
    void ciIsPointedAtTheIntakeInBothPlaces() {
        // Fire-and-forget and swallowed at debug: a wrong value deploys nothing and says nothing,
        // which is why it is spelled rather than inherited. Move one, move both.
        String intake = "QITS_PLATFORM_DEPLOYMENTS_INTAKE_URL"
                + ": http://qits-platform-deployments:8080"
                + "/platform-deployments/api/events/build-succeeded";

        assertThat(ComposeTemplate.compose(tokens())).contains(intake);
        assertThat(ComposeTemplate.runArgs(tokens())).contains(
                "-e QITS_PLATFORM_DEPLOYMENTS_INTAKE_URL=http://qits-platform-deployments:8080"
                        + "/platform-deployments/api/events/build-succeeded");
    }

    @Test
    void runArgsCoverEveryApplicationThatNeedsMoreThanItsImage() {
        String properties = ComposeTemplate.runArgs(tokens());

        for (String application : new String[]{"qits-gateway", "qits-artifacts", "qits-ci",
                "qits-platform-deployments", "qits-idp", "qits-stt", "qits-projects",
                "qits-workspaces", "qits-events"}) {
            assertThat(properties).contains("qits.platform.deployments.run-args." + application + "=");
        }
        // The retired pair is deployed by nothing, so it configures nothing.
        assertThat(properties).doesNotContain("run-args.qits-cd=")
                .doesNotContain("run-args.qits-serviceregistry=");
        // The namespace moved with the merge-back; the old spelling configures nothing.
        assertThat(properties).doesNotContain("qits.cd.run-args.");
        assertThat(properties).contains("-e QITS_REPOSITORIES_GIT_PUSH_TOKEN=local-dev");
        assertThat(properties).contains("--group-add 988");
        assertThat(properties).doesNotContain("${DOCKER_GID}");
    }

    @Test
    void theDeployersOwnDeploymentInheritsItsConfigVolume() {
        // The self-update handoff: without this mount the successor comes up with no run-args at
        // all and every later deployment loses its volumes and its datasource env.
        String deployer = ComposeTemplate.runArgs(tokens()).lines()
                .filter(line -> line.startsWith(
                        "qits.platform.deployments.run-args.qits-platform-deployments="))
                .findFirst().orElseThrow();

        assertThat(deployer).contains("-v qits-platform-deployments-data:/data");
        assertThat(deployer).contains("-v qits-platform-deployments-config:/work/config");
        assertThat(deployer).contains("-v /var/run/docker.sock:/var/run/docker.sock");
        assertThat(deployer).contains("QUARKUS_DATASOURCE_PLATFORMDEPLOYMENTS_JDBC_URL=");
    }

    @Test
    void theIdpsDeploymentCarriesItsVolumeAndEverySecret() {
        String idp = ComposeTemplate.runArgs(tokens()).lines()
                .filter(line -> line.startsWith("qits.platform.deployments.run-args.qits-idp="))
                .findFirst().orElseThrow();

        assertThat(idp).contains("-v qits-idp-data:/data");
        for (String client : PlatformModel.IDP_CLIENTS) {
            assertThat(idp).contains("secret-" + client);
        }
        // The sender's list, restated in full: the key replaces the shipped one.
        assertThat(idp).contains("QITS_IDP_CLIENT_QITS_CI_AUDIENCES=")
                .contains("qits-platform-deployments");
    }
}
