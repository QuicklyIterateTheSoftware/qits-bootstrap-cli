package eu.wohlben.qits.cli.bootstrap.platform;

import eu.wohlben.qits.cli.bootstrap.engine.PhaseContext;
import eu.wohlben.qits.cli.bootstrap.engine.Waiter;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * The platform's postgres, as much of it as the bootstrap owns: wait for it to answer, then make
 * sure the roles and databases the seed containers boot against are there.
 * <p>
 * <b>Two arms, and which one a role gets is a decision about who owns its password.</b>
 * {@link #ensureRole} converges an existing role on the recorded value; {@link #ensureRoleIfMissing}
 * creates and then never touches. The second is for every application role, because the deployer's
 * resource registry becomes their authority the moment they are first deployed.
 * <p>
 * <b>Plain JDBC, no Quarkus wiring.</b> There is no datasource and no injected {@code DataSource}
 * — a handful of idempotent statements run once per bootstrap does not need a pool, and a
 * configured datasource would make this CLI refuse to start whenever postgres is down, which is
 * every cold boot until this phase runs.
 * <p>
 * <b>Autocommit, which is not a default this class may lose.</b> {@code CREATE DATABASE} cannot
 * run inside a transaction block, so the connections here stay on JDBC's own autocommit.
 * <p>
 * <b>Nothing here ever prints a password.</b> The identifiers are CLI-owned constants and the
 * passwords are generated hex, so the assertions below are a belt rather than a gate — but DDL
 * cannot be parametrized, so the belt is checked immediately before every string is assembled, and
 * every message that leaves this class has the password taken out of it first.
 */
public final class PgAdmin {

    /** postgres' own unquoted-identifier shape, and the whole charset this CLI ever names. */
    static final Pattern IDENTIFIER = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");

    /** Generated hex. Nothing else is ever assembled into a statement. */
    static final Pattern PASSWORD = Pattern.compile("^[0-9a-f]{16,64}$");

    /** postgres says "already exists" two ways, and both mean another run got there first. */
    private static final String DUPLICATE_DATABASE = "42P04";
    private static final String DUPLICATE_OBJECT = "42710";

    private PgAdmin() {
    }

    /**
     * Whether a value may ever be assembled into a statement. Asked where a password is RESOLVED
     * as well as where it is used, so the refusal can name the key that was set rather than
     * arriving three statements later.
     */
    public static boolean isPassword(String value) {
        return value != null && PASSWORD.matcher(value).matches();
    }

    /**
     * Waits until postgres answers a query, saying what the last attempt saw.
     * <p>
     * A cold boot's first start is an initdb, so the first several polls are refused connections
     * rather than errors — which is exactly what the display shows.
     */
    public static void awaitReady(String jdbcUrl, String user, String password, Duration timeout,
                                  PhaseContext ctx) throws Exception {
        Waiter.await(ctx, "postgres at " + jdbcUrl, timeout, Duration.ofSeconds(2), () -> {
            try (Connection connection = connect(jdbcUrl, user, password);
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("select 1")) {
                return rows.next()
                        ? Waiter.Poll.done("ready", "ready")
                        : Waiter.Poll.pending("connected, no answer to select 1");
            } catch (SQLException e) {
                return Waiter.Poll.pending(redact(shortMessage(e), password));
            }
        });
    }

    /** One connection, autocommit, closed by the caller. */
    public static Connection connect(String jdbcUrl, String user, String password)
            throws SQLException {
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    /**
     * The role, with the password this run recorded.
     * <p>
     * An existing role is ALTERed rather than left alone, every time. The recorded password is the
     * single authority for a CLI-managed role, so a role that drifted — a hand-typed ALTER, a
     * half-finished earlier run — converges on the next bootstrap instead of failing every
     * connection with nothing to say why.
     *
     * @return what happened, for the phase log. Never contains the password.
     */
    public static String ensureRole(Connection admin, String role, String password)
            throws SQLException {
        boolean exists = exists(admin, "select 1 from pg_catalog.pg_roles where rolname = ?", role);
        String sql = exists ? alterRolePassword(role, password) : createRole(role, password);
        try {
            execute(admin, sql, password);
        } catch (SQLException e) {
            if (!exists && DUPLICATE_OBJECT.equals(e.getSQLState())) {
                // Another run created it between the check and the statement.
                execute(admin, alterRolePassword(role, password), password);
                return "already there, password converged";
            }
            throw e;
        }
        return exists ? "password converged" : "created";
    }

    /**
     * The role, created with the password this run recorded — and left ALONE if it is already
     * there.
     * <p>
     * <b>This is the arm for an APPLICATION's role, and the difference from {@link #ensureRole} is
     * the whole point.</b> An application's credential belongs to the deployer's {@code pd_resource}
     * registry from its first deploy onwards: that registry is the single authority, its reconcile
     * arm rotates the password and records the new one, and the container is then started with what
     * the row says. A CLI rerun that ALTERed the role back to the value in
     * {@code .qits-bootstrap.env} would take the running application's database away from it, with
     * nothing anywhere to say why. So the CLI only opens the door — first, before any deployer
     * exists — and never touches it again.
     * <p>
     * {@code qits_deployments} is the one role that keeps {@link #ensureRole}: the deployer records
     * its OWN credential from the environment the bootstrap handed it, so converging on the
     * recorded value is what keeps the two in step rather than what breaks them apart.
     *
     * @return what happened, for the phase log. Never contains the password.
     */
    public static String ensureRoleIfMissing(Connection admin, String role, String password)
            throws SQLException {
        if (exists(admin, "select 1 from pg_catalog.pg_roles where rolname = ?", role)) {
            return "already there, password left as it is";
        }
        try {
            execute(admin, createRole(role, password), password);
        } catch (SQLException e) {
            if (DUPLICATE_OBJECT.equals(e.getSQLState())) {
                // Another run created it between the check and the statement. Still not altered.
                return "already there, password left as it is";
            }
            throw e;
        }
        return "created";
    }

    /**
     * The database, owned by its own role and closed to everyone else.
     *
     * @return what happened, for the phase log
     */
    public static String ensureDatabase(Connection admin, String database, String owner)
            throws SQLException {
        if (exists(admin, "select 1 from pg_catalog.pg_database where datname = ?", database)) {
            return "already there";
        }
        try {
            execute(admin, createDatabase(database, owner), null);
        } catch (SQLException e) {
            if (DUPLICATE_DATABASE.equals(e.getSQLState())) {
                return "already there";
            }
            throw e;
        }
        // The default is that every role may connect. This database holds one application's data
        // and every other application on this instance is a peer, not a guest.
        execute(admin, revokePublic(database), null);
        return "created, owned by " + owner + ", closed to public";
    }

    // --- the statements, assembled after the identifiers are checked ------------------------------

    static String createRole(String role, String password) {
        return "create role " + identifier(role) + " login password '" + secret(password) + "'";
    }

    static String alterRolePassword(String role, String password) {
        return "alter role " + identifier(role) + " password '" + secret(password) + "'";
    }

    static String createDatabase(String database, String owner) {
        return "create database " + identifier(database) + " owner " + identifier(owner);
    }

    static String revokePublic(String database) {
        return "revoke all on database " + identifier(database) + " from public";
    }

    /**
     * The last check before a name reaches a statement. DDL cannot be parametrized, so this is the
     * only thing between a name and the server — which is why it is here, at the assembly, rather
     * than only where the name was chosen.
     */
    static String identifier(String name) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("not a name this program may put in a statement: "
                    + name);
        }
        return name;
    }

    /** The same check for a password, which is a literal in the statement and cannot be bound. */
    static String secret(String password) {
        if (password == null || !PASSWORD.matcher(password).matches()) {
            // The value is deliberately not in the message.
            throw new IllegalArgumentException("the postgres password this run resolved is not "
                    + "16-64 hex characters, and nothing else may be assembled into a statement");
        }
        return password;
    }

    /** Whatever a message says, the password is not in it. */
    static String redact(String text, String password) {
        if (text == null) {
            return "";
        }
        return password == null || password.isBlank() ? text : text.replace(password, "***");
    }

    // --- small helpers ----------------------------------------------------------------------------

    private static boolean exists(Connection admin, String query, String value) throws SQLException {
        try (PreparedStatement statement = admin.prepareStatement(query)) {
            statement.setString(1, value);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /**
     * Runs one statement and rethrows its failure with the password taken out: the driver puts the
     * statement it was given into some of its own messages, and this program's exceptions reach
     * the screen and the run log.
     */
    private static void execute(Connection admin, String sql, String password) throws SQLException {
        try (Statement statement = admin.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new SQLException(redact(shortMessage(e), password), e.getSQLState(),
                    e.getErrorCode());
        }
    }

    private static String shortMessage(SQLException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message.trim();
    }
}
