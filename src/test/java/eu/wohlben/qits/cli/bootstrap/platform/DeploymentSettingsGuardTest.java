package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>What the bootstrap writes into the deployer's settings store, and what it must never write
 * anywhere else</b> (qits-765). It replaced the guard over the generated extras file, which is gone
 * with qits-configuration: the deployer takes every deployment's environment from its
 * {@code pd_setting} rows, and {@link DeploymentSettings} is the bootstrap's whole contribution to
 * them.
 * <p>
 * The list is asserted EXACTLY. A key the run stops writing is a deployed service that loses a
 * per-installation value at its next deployment — the edge its certificate, postgres its password —
 * and a key that creeps in is a value the image should ship as a default instead.
 */
class DeploymentSettingsGuardTest {

    private static DeploymentSettings.Inputs inputs(int port, List<String> extraSans,
            String hetznerToken) {
        return new DeploymentSettings.Inputs("prod", "qits-dev.eu", port, true, "production",
                "hostmaster@qits-dev.eu", hetznerToken, extraSans, "pg-superuser-secret",
                "push-token-secret", "abc123");
    }

    private static List<String> keys(List<DeploymentSettings.Setting> settings) {
        return settings.stream().map(s -> s.application() + " " + s.key()).toList();
    }

    @Test
    void theSettingsCoverEveryPerInstallationKeyAndNothingElse() {
        List<DeploymentSettings.Setting> settings =
                DeploymentSettings.of(inputs(8080, List.of(), "hetzner-secret"));

        assertThat(keys(settings)).containsExactly(
                "qits-deployments QITS_DOMAIN",
                "qits-deployments QITS_PLATFORM_DEPLOYMENTS_POSTGRES_ADMIN_PASSWORD",
                "qits-deployments QITS_PLATFORM_DEPLOYMENTS_REGISTRY_AUTH",
                "qits-edge QUARKUS_TLS_KEY_STORE_PEM_ACME_CERT",
                "qits-edge QUARKUS_TLS_KEY_STORE_PEM_ACME_KEY",
                "qits-edge QUARKUS_TLS_RELOAD_PERIOD",
                "qits-edge QITS_EDGE_ACME_ENABLED",
                "qits-edge QITS_EDGE_ACME_MODE",
                "qits-edge QITS_EDGE_ACME_EMAIL",
                "qits-edge QITS_EDGE_ACME_HETZNER_TOKEN",
                "qits-ci QITS_CI_DAEMON_VERSION_OVERRIDE",
                "qits-githost QITS_REPOSITORIES_GIT_PUSH_TOKEN",
                "qits-projects QITS_REPOSITORIES_GIT_PUSH_TOKEN",
                "qits-oci-postgresql POSTGRES_PASSWORD");
    }

    @Test
    void theValuesAreThisRunsAndOneSecretIsOneValue() {
        List<DeploymentSettings.Setting> settings =
                DeploymentSettings.of(inputs(8080, List.of(), "hetzner-secret"));

        assertThat(value(settings, "qits-deployments", "QITS_DOMAIN")).isEqualTo("qits-dev.eu");
        assertThat(value(settings, "qits-deployments", "QITS_PLATFORM_DEPLOYMENTS_REGISTRY_AUTH"))
                .isEqualTo("true");
        assertThat(value(settings, "qits-edge", "QITS_EDGE_ACME_MODE")).isEqualTo("production");
        assertThat(value(settings, "qits-edge", "QUARKUS_TLS_KEY_STORE_PEM_ACME_CERT"))
                .isEqualTo("/work/.letsencrypt/current/lets-encrypt.crt");
        assertThat(value(settings, "qits-ci", "QITS_CI_DAEMON_VERSION_OVERRIDE"))
                .isEqualTo("abc123");
        // The admin password the deployer provisions with IS the superuser's postgres password.
        assertThat(value(settings, "qits-deployments",
                "QITS_PLATFORM_DEPLOYMENTS_POSTGRES_ADMIN_PASSWORD"))
                .isEqualTo(value(settings, "qits-oci-postgresql", "POSTGRES_PASSWORD"))
                .isEqualTo("pg-superuser-secret");
        // One push token, checked by the git host and presented by qits-projects.
        assertThat(value(settings, "qits-githost", "QITS_REPOSITORIES_GIT_PUSH_TOKEN"))
                .isEqualTo(value(settings, "qits-projects", "QITS_REPOSITORIES_GIT_PUSH_TOKEN"))
                .isEqualTo("push-token-secret");
    }

    @Test
    void everyCredentialIsMaskedByTheDeployerAndNeverPrinted() {
        List<DeploymentSettings.Setting> settings =
                DeploymentSettings.of(inputs(8080, List.of(), "hetzner-secret"));

        assertThat(settings).filteredOn(DeploymentSettings.Setting::secret)
                .extracting(s -> s.application() + " " + s.key())
                .containsExactlyInAnyOrder(
                        "qits-deployments QITS_PLATFORM_DEPLOYMENTS_POSTGRES_ADMIN_PASSWORD",
                        "qits-edge QITS_EDGE_ACME_HETZNER_TOKEN",
                        "qits-githost QITS_REPOSITORIES_GIT_PUSH_TOKEN",
                        "qits-projects QITS_REPOSITORIES_GIT_PUSH_TOKEN",
                        "qits-oci-postgresql POSTGRES_PASSWORD");
        for (DeploymentSettings.Setting setting : settings) {
            assertThat(setting.toString()).doesNotContain(setting.value());
        }
    }

    /**
     * <b>Only what applies.</b> The port and the registry names that carry it are stated only when
     * they differ from the images' default; the DNS token only when there is one; the extra
     * certificate names only when there are any.
     */
    @Test
    void aSettingARunHasNothingToSayAboutIsLeftUnwritten() {
        List<String> plain = keys(DeploymentSettings.of(inputs(8080, List.of(), "")));
        assertThat(plain).doesNotContain("qits-deployments QITS_PORT",
                "qits-deployments QITS_ARTIFACTS_REGISTRY_HOST", "qits-ci QITS_ARTIFACTS_REGISTRY_HOST",
                "qits-edge QITS_EDGE_ACME_HETZNER_TOKEN", "qits-edge QITS_EDGE_ACME_ADDITIONAL_NAMES");

        List<DeploymentSettings.Setting> moved = DeploymentSettings.of(
                inputs(9090, List.of("status.support.qits-dev.eu"), "hetzner-secret"));
        assertThat(value(moved, "qits-deployments", "QITS_PORT")).isEqualTo("9090");
        assertThat(value(moved, "qits-deployments", "QITS_ARTIFACTS_REGISTRY_HOST"))
                .isEqualTo("registry.prod.localhost:9090");
        assertThat(value(moved, "qits-ci", "QITS_ARTIFACTS_REGISTRY_HOST"))
                .isEqualTo("registry.prod.localhost:9090");
        assertThat(value(moved, "qits-edge", "QITS_EDGE_ACME_ADDITIONAL_NAMES"))
                .isEqualTo("status.support.qits-dev.eu");
    }

    @Test
    void anEdgeWithoutAcmeIsToldSoAndNothingAboutAnAccount() {
        List<String> keys = keys(DeploymentSettings.of(new DeploymentSettings.Inputs("prod",
                "qits-dev.eu", 8080, false, "off", "hostmaster@qits-dev.eu", "token", List.of(),
                "pg", "push", "")));

        assertThat(keys).contains("qits-edge QITS_EDGE_ACME_ENABLED")
                .doesNotContain("qits-edge QITS_EDGE_ACME_MODE", "qits-edge QITS_EDGE_ACME_EMAIL",
                        "qits-edge QITS_EDGE_ACME_HETZNER_TOKEN",
                        "qits-ci QITS_CI_DAEMON_VERSION_OVERRIDE");
    }

    /** A blank password is a bug in the run, never a row: it would deploy a database nobody opens. */
    @Test
    void aMissingRequiredValueStopsRatherThanWritingAnEmptyRow() {
        assertThatThrownBy(() -> DeploymentSettings.of(new DeploymentSettings.Inputs("prod",
                "qits-dev.eu", 8080, true, "production", "a@b", "", List.of(), null, "push", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QITS_PLATFORM_DEPLOYMENTS_POSTGRES_ADMIN_PASSWORD")
                .hasMessageNotContaining("push");
    }

    /**
     * <b>An application's credential is a resource the deployer injects, never a setting.</b> A
     * row would be written after the injected triple, win, and outlive every rotation of it.
     */
    @Test
    void noSettingCarriesAnIdentity() {
        assertThat(keys(DeploymentSettings.of(inputs(9090, List.of("x.qits-dev.eu"), "t"))))
                .noneMatch(key -> key.contains(" QITS_RESOURCE_") || key.contains(" QITS_IDP_CLIENT")
                        || key.contains(" QITS_OIDC_CLIENT_") || key.contains(" QUARKUS_OIDC_CLIENT_"));
    }

    /**
     * <b>Nothing writes qits-configuration, and nothing points the deployer at it.</b> The service
     * is retired (qits-1396): its import door, the flip variable and its wire alias would each be a
     * boot that waits on, or writes into, a service that is no longer deployed.
     */
    @Test
    void nothingInTheProgramWritesOrDialsQitsConfiguration() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).strip();
                    if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
                        continue;
                    }
                    if (line.contains("/configuration/api") || line.contains("/configuration/q")
                            || line.contains("QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL")
                            || line.contains("\"configuration\"")
                            || line.contains("qits-configuration")) {
                        offenders.add(file + ":" + (i + 1) + ": " + line);
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
        assertThat(PlatformModel.CORE).doesNotContain("configuration");
        assertThat(PlatformModel.DEPLOYABLES).doesNotContain("configuration");
        assertThat(PlatformModel.SEEDED_REPOS).doesNotContain("spa-configuration");
    }

    private static String value(List<DeploymentSettings.Setting> settings, String application,
            String key) {
        return settings.stream()
                .filter(s -> s.application().equals(application) && s.key().equals(key))
                .map(DeploymentSettings.Setting::value)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + application + " " + key));
    }
}
