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
        for (String client : PlatformModel.IDP_CLIENTS) {
            values.put("IDP_SECRET_" + PlatformModel.clientKey(client), "secret-" + client);
        }
        return values;
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
    }

    @Test
    void runArgsCoverEveryApplicationThatNeedsMoreThanItsImage() {
        String properties = ComposeTemplate.runArgs(tokens());

        for (String application : new String[]{"qits-gateway", "qits-artifacts", "qits-ci",
                "qits-cd", "qits-idp", "qits-serviceregistry", "qits-stt", "qits-projects",
                "qits-workspaces", "qits-events"}) {
            assertThat(properties).contains("qits.cd.run-args." + application + "=");
        }
        assertThat(properties).contains("-e QITS_REPOSITORIES_GIT_PUSH_TOKEN=local-dev");
        assertThat(properties).contains("--group-add 988");
        assertThat(properties).doesNotContain("${DOCKER_GID}");
    }

    @Test
    void theIdpsDeploymentCarriesItsVolumeAndEverySecret() {
        String idp = ComposeTemplate.runArgs(tokens()).lines()
                .filter(line -> line.startsWith("qits.cd.run-args.qits-idp=")).findFirst().orElseThrow();

        assertThat(idp).contains("-v qits-idp-data:/data");
        for (String client : PlatformModel.IDP_CLIENTS) {
            assertThat(idp).contains("secret-" + client);
        }
        assertThat(idp).contains("QITS_IDP_CLIENT_QITS_CD_AUDIENCES=")
                .contains("qits-serviceregistry");
    }
}
