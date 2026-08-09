package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What can be tested without a server: the belt that stands between a name and a statement, and
 * the rule that no message ever carries the password. The statements themselves are proved by a
 * real bootstrap, like every other phase that shells out.
 */
class PgAdminTest {

    private static final String PASSWORD = "0123456789abcdef";

    @Test
    void theStatementsSayWhatTheyAreForAndCarryTheNamesGiven() {
        assertThat(PgAdmin.createRole("qits_deployments", PASSWORD))
                .isEqualTo("create role qits_deployments login password '" + PASSWORD + "'");
        assertThat(PgAdmin.alterRolePassword("qits_deployments", PASSWORD))
                .isEqualTo("alter role qits_deployments password '" + PASSWORD + "'");
        assertThat(PgAdmin.createDatabase("qits_deployments", "qits_deployments"))
                .isEqualTo("create database qits_deployments owner qits_deployments");
        assertThat(PgAdmin.revokePublic("qits_deployments"))
                .isEqualTo("revoke all on database qits_deployments from public");
    }

    @Test
    void aNameThatIsNotOneNeverReachesAStatement() {
        // DDL cannot be parametrized, so this check is the only thing between a name and the
        // server. Every one of these is rejected at assembly, not at the server.
        for (String hostile : new String[]{
                "qits_deployments; drop database postgres",
                "qits_deployments\"",
                "qits deployments",
                "QITS_DEPLOYMENTS",
                "1deployments",
                "",
                null}) {
            assertThatThrownBy(() -> PgAdmin.createDatabase(hostile, "qits_deployments"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PgAdmin.createDatabase("qits_deployments", hostile))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PgAdmin.createRole(hostile, PASSWORD))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PgAdmin.revokePublic(hostile))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // 63 bytes is postgres' own limit; one more is silently truncated by the server, which is
        // how two names become one.
        assertThatThrownBy(() -> PgAdmin.createDatabase("q".repeat(64), "qits_deployments"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PgAdmin.createDatabase("q".repeat(63), "qits_deployments")).isNotBlank();
    }

    @Test
    void onlyGeneratedHexIsEverAssembledIntoAStatement() {
        for (String hostile : new String[]{"' or 1=1 --", "short", "not-hex-at-all-really",
                "0123456789ABCDEF", "", null}) {
            assertThatThrownBy(() -> PgAdmin.createRole("qits_deployments", hostile))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theRefusalDoesNotRepeatThePassword() {
        // The value is what must not reach the screen, and a validation message is a screen line.
        assertThatThrownBy(() -> PgAdmin.createRole("qits_deployments", "' or 1=1 --"))
                .hasMessageNotContaining("or 1=1");
    }

    @Test
    void nothingThisClassSaysCarriesThePassword() {
        // The driver puts what it was given into some of its own messages, and those become this
        // program's exceptions and status lines.
        assertThat(PgAdmin.redact("FATAL: password " + PASSWORD + " rejected", PASSWORD))
                .isEqualTo("FATAL: password *** rejected")
                .doesNotContain(PASSWORD);
        assertThat(PgAdmin.redact(null, PASSWORD)).isEmpty();
        assertThat(PgAdmin.redact("connection refused", null)).isEqualTo("connection refused");
    }
}
