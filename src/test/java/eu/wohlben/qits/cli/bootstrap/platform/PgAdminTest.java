package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

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
    void theSameCheckIsAvailableWhereAPasswordIsResolved() {
        // Asked at resolution too, so a given value that cannot be assembled is refused by the
        // phase that can name the key that was set.
        assertThat(PgAdmin.isPassword(PASSWORD)).isTrue();
        assertThat(PgAdmin.isPassword("hunter2")).isFalse();
        assertThat(PgAdmin.isPassword(null)).isFalse();
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

    /**
     * WHICH ARM A ROLE GETS is the one decision in this class that a rerun can get wrong, so it is
     * the one thing worth a fake server: an application role that is already there must be left
     * exactly as it is.
     */
    @Test
    void anApplicationRoleIsCreatedOnceAndThenLeftAlone() throws Exception {
        FakePostgres empty = new FakePostgres();
        assertThat(PgAdmin.ensureRoleIfMissing(empty.connection(), "qits_ci", PASSWORD))
                .isEqualTo("created");
        assertThat(empty.statements).containsExactly(PgAdmin.createRole("qits_ci", PASSWORD));

        FakePostgres standing = new FakePostgres("qits_ci");
        assertThat(PgAdmin.ensureRoleIfMissing(standing.connection(), "qits_ci", PASSWORD))
                .contains("left as it is");
        // NOT an ALTER. From its first deployment the deployer's resource registry holds this
        // role's password, and a rerun that put the recorded one back would take the running
        // application's own database away from it.
        assertThat(standing.statements).isEmpty();
    }

    @Test
    void theDeployersOwnRoleStillConvergesOnTheRecordedPassword() throws Exception {
        // The other arm, and correct for exactly one role: the deployer registers the credential
        // this CLI issued it, so the recorded value and the row say the same thing.
        FakePostgres standing = new FakePostgres("qits_deployments");
        assertThat(PgAdmin.ensureRole(standing.connection(), "qits_deployments", PASSWORD))
                .isEqualTo("password converged");
        assertThat(standing.statements)
                .containsExactly(PgAdmin.alterRolePassword("qits_deployments", PASSWORD));
    }

    @Test
    void theRegistrysRowsAreReadBackAndTheBeltStillHolds() {
        // A surviving cluster: the deployer rotated these roles and recorded what it set. The
        // seed must use the rows — and the same belt as everywhere: a row that is not a role
        // name and a generated password never steers what a seed container is started with.
        FakeRegistry registry = new FakeRegistry(List.of(
                new String[]{"qits_ci", "feedfacefeedface"},
                new String[]{"qits_platform_mirror", "not-hex-at-all!"},
                new String[]{"Robert'); drop", PASSWORD}));
        assertThat(PgAdmin.readRecordedPasswords(registry.connection()))
                .containsExactly(entry("qits_ci", "feedfacefeedface"));
    }

    @Test
    void aClusterTheDeployerNeverRanAgainstAnswersNothing() {
        // No pd_resource table — a fresh volume, or a pre-registry cluster. Then the recorded
        // values are still the truth, and the caller hears "nothing" rather than an error.
        assertThat(PgAdmin.readRecordedPasswords(new FakeRegistry(null).connection())).isEmpty();
    }

    /**
     * <b>The idp-client half of the same registry, and the same belt.</b> A row that is not an
     * application name, a client id and a plausible secret must never steer what a seed container
     * is started with — the values reach a running service's credentials, not just a log line.
     */
    @Test
    void theIdpClientRowsAreReadBackAndTheBeltStillHolds() {
        FakeRegistry registry = new FakeRegistry(List.of(
                new String[]{"qits-projects", "prod-qits-projects", "s3cr3t-and-long-enough"},
                // No secret worth the name: a truncated or placeholder value would be handed to a
                // container that then cannot authenticate at all.
                new String[]{"qits-ci", "prod-qits-ci", "short"},
                // Not a client id, so not a row this program derived.
                new String[]{"qits-events", "PROD-QITS-EVENTS", "s3cr3t-and-long-enough"},
                new String[]{"Robert'); drop", "prod-qits-x", "s3cr3t-and-long-enough"}));

        assertThat(PgAdmin.readRecordedIdpClients(registry.connection(), "prod"))
                .containsExactly(entry("qits-projects", new PgAdmin.IdpRow(
                        "qits-projects", "prod-qits-projects", "s3cr3t-and-long-enough")));
    }

    /**
     * <b>A cold boot has no registry at all</b> — qits-deployments' Flyway has not run, so there is
     * no database, no table and no column. That answers the same as "nothing recorded", because it
     * is the same answer: the caller creates the clients and records them on the next run.
     */
    @Test
    void aMachineWithNoDeployerRegistryAnswersNothingRatherThanFailing() {
        assertThat(PgAdmin.readRecordedIdpClients(new FakeRegistry(null).connection(), "prod"))
                .isEmpty();
    }

    /**
     * The same belt on the WRITE side, and it throws rather than answering false: a false says
     * "there is no table yet", which is a stage of a cold boot, while a triple this program should
     * never have derived is a bug in the caller.
     */
    @Test
    void aTripleThisProgramMayNotRecordIsRefusedAtAssembly() {
        assertThatThrownBy(() -> PgAdmin.recordIdpClient("jdbc:postgresql://nowhere:5432/x",
                "postgres", PASSWORD, "qits-projects", "prod", "prod-qits-projects", "short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("short");
    }

    /**
     * <b>A row's key is the TIER and there is always one.</b> A null environment is the retired
     * pre-V8 shape, and it is the one bad value here that would not fail loudly: the unique key is
     * {@code nulls not distinct}, so a null and {@code prod} are different keys and the insert
     * would SUCCEED beside the deployer's own row rather than update it — leaving the deployer to
     * find nothing at {@code findOne(app, tier, "idp")}, provision a second credential and rotate
     * the one the seed containers hold. Refused at assembly instead.
     */
    @Test
    void aRowWithNoEnvironmentIsRefusedRatherThanWrittenBesideTheDeployersOwn() {
        assertThatThrownBy(() -> PgAdmin.recordIdpClient("jdbc:postgresql://nowhere:5432/x",
                "postgres", PASSWORD, "qits-deployments", null, "qits-deployments",
                "s3cr3t-and-long-enough"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A postgres that answers the only two things this class asks of one: whether a name is in
     * pg_catalog, and here is a statement. A proxy rather than a server, because the question is
     * which statements are assembled — and these tests run without docker like every other.
     */
    private static final class FakePostgres implements InvocationHandler {

        private final Set<String> existing;
        private final List<String> statements = new ArrayList<>();
        private String asked;

        FakePostgres(String... existing) {
            this.existing = new LinkedHashSet<>(List.of(existing));
        }

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                // A PreparedStatement proxy answers for createStatement() too: it IS a Statement.
                case "prepareStatement", "createStatement" -> statement();
                case "setString" -> {
                    asked = (String) args[1];
                    yield null;
                }
                case "executeQuery" -> rows();
                case "next" -> existing.contains(asked);
                case "execute" -> {
                    statements.add((String) args[0]);
                    yield false;
                }
                case "toString" -> "fake postgres";
                default -> null;
            };
        }

        private Object statement() {
            return Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, this);
        }

        private Object rows() {
            return Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class}, this);
        }
    }

    /**
     * The deployer's registry, as {@code readRecordedPasswords} sees it: a table of
     * (role_name, password) rows — or no table at all, which is what {@code null} means here.
     */
    private static final class FakeRegistry implements InvocationHandler {

        private final List<String[]> rows;
        private int cursor = -1;

        FakeRegistry(List<String[]> rows) {
            this.rows = rows;
        }

        Connection connection() {
            return (Connection) proxy(Connection.class);
        }

        private Object proxy(Class<?> type) {
            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws SQLException {
            return switch (method.getName()) {
                case "createStatement", "prepareStatement" -> {
                    if (rows == null) {
                        throw new SQLException("relation \"pd_resource\" does not exist");
                    }
                    yield proxy(PreparedStatement.class);
                }
                // The idp-client read binds its two filters; this fake holds one table and
                // answers every row of it, so what was bound changes nothing.
                case "setString" -> null;
                case "executeQuery" -> proxy(ResultSet.class);
                case "next" -> ++cursor < rows.size();
                case "getString" -> rows.get(cursor)[(int) args[0] - 1];
                case "toString" -> "fake registry";
                default -> null;
            };
        }
    }
}
