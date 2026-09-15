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
import java.util.LinkedHashMap;
import java.util.Map;
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
     * What the deployer's registry says the application roles log in with, by role name.
     * <p>
     * <b>From an application's first deploy on, {@code pd_resource} is the single authority for
     * its credential</b> — the deployer's reconcile arm rotates the role and records what it set,
     * and the value this CLI recorded at creation time is what the role WAS, not what it is.
     * Measured on the first volumes-kept re-bootstrap after rotation landed: the seed mirror died
     * on "password authentication failed" while the registry held the working value all along. So
     * the seed phase asks the registry and starts the seed containers with what the rows say,
     * exactly the way the deployer starts every successor.
     * <p>
     * Empty when there is nothing to ask: a fresh volume has no registry database yet, and a
     * cluster the deployer never ran against has no table. Both mean the recorded values are
     * still the truth, and both answer the same way.
     */
    public static Map<String, String> recordedPasswords(String jdbcUrl, String user,
                                                        String password) {
        try (Connection registry = connect(jdbcUrl, user, password)) {
            return readRecordedPasswords(registry);
        } catch (SQLException absent) {
            return Map.of();
        }
    }

    static Map<String, String> readRecordedPasswords(Connection registry) {
        Map<String, String> recorded = new LinkedHashMap<>();
        try (Statement statement = registry.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select role_name, password from pd_resource")) {
            while (rows.next()) {
                String role = rows.getString(1);
                String value = rows.getString(2);
                // The same belt as everywhere in this class: nothing that is not a role name and
                // a generated password may steer what a seed container is started with.
                if (role != null && IDENTIFIER.matcher(role).matches() && isPassword(value)) {
                    recorded.put(role, value);
                }
            }
        } catch (SQLException absent) {
            return Map.of();
        }
        return Map.copyOf(recorded);
    }

    // --- the deployer's idp-client rows -----------------------------------------------------------

    /**
     * What the platform is addressed by and what it presents, as one row of the deployer's
     * registry.
     *
     * @param applicationName the plain application name — {@code qits-projects}, never the alias
     * @param clientId        the WIRE ALIAS, which is what the idp knows the client as
     * @param secret          the idp-issued secret. Never logged, never printed, never recorded in
     *                        {@code .qits-bootstrap.env}
     */
    public record IdpRow(String applicationName, String clientId, String secret) {
    }

    /** {@code pd_resource.resource_type} for an idp client, and the registry's own spelling. */
    private static final String IDP_CLIENT_TYPE = "idp-client";

    /** {@code pd_resource.resource_name} for the one idp resource an application declares. */
    private static final String IDP_RESOURCE = "idp";

    /**
     * A client id and an application name have the same shape, and it is the idp's rather than
     * postgres': {@code [a-z][a-z0-9-]{0,127}}. Dashes, so {@link #IDENTIFIER} cannot answer for it.
     */
    static final Pattern CLIENT_ID = Pattern.compile("^[a-z][a-z0-9-]{0,127}$");

    /**
     * What may be handed to a container as an idp secret. Printable ASCII with no space and no
     * quote, bounded — the idp's generated form, and nothing that could be a truncated row, a
     * placeholder or a line of yaml somebody pasted.
     */
    static final Pattern CLIENT_SECRET = Pattern.compile("^[!-~]{16,512}$");

    /**
     * <b>What qits-deployments' registry says each seed application's idp client presents</b>, by
     * application name.
     * <p>
     * It is the same posture {@link #recordedPasswords} takes for the database half, and for the
     * same reason: {@code pd_resource} is the single authority for an application's credentials
     * from its first deploy on, and a value this CLI remembers is what the credential WAS. The idp
     * says a secret once, at the call that issued it, so a row is the only place a working one can
     * be read back from at all.
     * <p>
     * <b>Empty when there is nothing to ask</b>, and a cold boot is exactly that: qits-deployments'
     * Flyway has not run, so there is no database, no table and no column. A connection that fails,
     * a table that is not there and a table with no rows all mean "nothing recorded", which is one
     * answer and is answered one way.
     * <p>
     * The same belt as everywhere in this class: a row that is not an application name, a client id
     * and a plausible secret never steers what a container is started with.
     *
     * <p>
     * <b>A row's key is the TIER, and there is always one.</b> Every application is deployed into
     * the designated environment — a platform service included, since qits-deployments' V8 rekey —
     * and its {@code ResourceProvisioning} refuses outright a deployment that names no environment.
     * So this asks for the tier, and {@link #recordIdpClient} always writes it. What IS
     * plane-dependent is the CLIENT ID in the row, which is a wire alias and so bare for a platform
     * service ({@code qits-deployments}) and qualified for an environment one
     * ({@code prod-qits-ci}) — {@code PlatformModel.wireAlias} is the one place that decides it.
     * <p>
     * <b>The null arm finds a PRE-V8 row and nothing else.</b> Platform-plane rows used to be
     * written with {@code environment_name} null, and {@code environment_name = 'prod'} is false
     * for a null — so a machine bootstrapped before the rekey would read as "never recorded" and
     * have two live credentials rotated under it. It is a migration courtesy on the READ, never a
     * shape this program writes: a row written null would be one the deployer's own
     * {@code findOne(app, tier, "idp")} cannot see, and the unique key is {@code nulls not
     * distinct}, so it would insert a SECOND row beside ours rather than conflict with it and then
     * rotate a secret the seed containers are holding.
     *
     * @param environmentName the tier the rows are keyed under
     */
    public static Map<String, IdpRow> recordedIdpClients(String jdbcUrl, String user,
                                                         String password, String environmentName) {
        try (Connection registry = connect(jdbcUrl, user, password)) {
            return readRecordedIdpClients(registry, environmentName);
        } catch (SQLException absent) {
            return Map.of();
        }
    }

    static Map<String, IdpRow> readRecordedIdpClients(Connection registry, String environmentName) {
        Map<String, IdpRow> recorded = new LinkedHashMap<>();
        try (PreparedStatement statement = registry.prepareStatement(
                "select application_name, client_id, password from pd_resource"
                        + " where resource_type = ?"
                        + " and (environment_name = ? or environment_name is null)")) {
            statement.setString(1, IDP_CLIENT_TYPE);
            statement.setString(2, environmentName);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String application = rows.getString(1);
                    String clientId = rows.getString(2);
                    String secret = rows.getString(3);
                    if (application != null && CLIENT_ID.matcher(application).matches()
                            && clientId != null && CLIENT_ID.matcher(clientId).matches()
                            && secret != null && CLIENT_SECRET.matcher(secret).matches()) {
                        recorded.put(application, new IdpRow(application, clientId, secret));
                    }
                }
            }
        } catch (SQLException absent) {
            return Map.of();
        }
        return Map.copyOf(recorded);
    }

    /**
     * Records one idp client in the deployer's registry, in the shape the deployer's own
     * {@code ResourceProvisioning.storeIdpRow} writes.
     * <p>
     * <b>It never creates the table, and that is not a convenience it is missing.</b> The table
     * belongs to qits-deployments' Flyway lineage — {@code V1} owns it — and a table this program
     * created would be one Flyway did not, so the deployer's next migration would fail against a
     * relation that already exists. A cold boot simply has no registry yet: this answers
     * {@code false}, the caller says so, and the next run records the row once the deployer has
     * migrated. The credential is not lost by that — the create/409/rotate arm re-issues one.
     * <p>
     * The upsert's conflict target is the registry's own unique key,
     * {@code (application_name, environment_name, resource_name)}.
     * <p>
     * <b>{@code environmentName} is the tier and is never null.</b> A null is the retired pre-V8
     * shape, and writing one would not fail loudly — the key is {@code nulls not distinct}, so a
     * null and {@code prod} are different keys and the insert would SUCCEED beside the deployer's
     * own row rather than update it. The deployer would then find nothing at
     * {@code findOne(app, tier, "idp")}, provision a second credential and rotate the one the seed
     * containers are already holding. Same failure the postgres half of this class learned; the
     * caller passes {@code boot.config.envName()} for every application, whatever plane it is on.
     *
     * @return whether the row is there now. False rather than a throw for the absent table, because
     *         a missing registry is a stage of a cold boot and not a failure of one
     */
    public static boolean recordIdpClient(String jdbcUrl, String user, String password,
                                          String applicationName, String environmentName,
                                          String clientId, String secret) {
        // The environment is checked with the rest, and it is the one of the four that is checked
        // for being THERE rather than for being safe: a null slips past every other guard in this
        // class and lands as a second row nobody will look at again.
        if (applicationName == null || !CLIENT_ID.matcher(applicationName).matches()
                || environmentName == null || !CLIENT_ID.matcher(environmentName).matches()
                || clientId == null || !CLIENT_ID.matcher(clientId).matches()
                || secret == null || !CLIENT_SECRET.matcher(secret).matches()) {
            // The value is deliberately not in the message.
            throw new IllegalArgumentException("not a (application, environment, client, secret) "
                    + "quadruple this program may record: " + applicationName + " / "
                    + environmentName + " / " + clientId);
        }
        try (Connection registry = connect(jdbcUrl, user, password);
             PreparedStatement statement = registry.prepareStatement(
                     "insert into pd_resource (id, application_name, environment_name,"
                             + " resource_name, resource_type, database_name, role_name,"
                             + " client_id, password, created_at, last_provisioned_at)"
                             + " values (?, ?, ?, ?, ?, null, null, ?, ?, now(), now())"
                             + " on conflict (application_name, environment_name, resource_name)"
                             + " do update set resource_type = excluded.resource_type,"
                             + " database_name = null, role_name = null,"
                             + " client_id = excluded.client_id, password = excluded.password,"
                             + " last_provisioned_at = now()")) {
            // created_at is INSERT-ONLY: the update arm leaves it, so a row keeps saying when the
            // credential first existed rather than when it was last touched.
            statement.setString(1, java.util.UUID.randomUUID().toString());
            statement.setString(2, applicationName);
            statement.setString(3, environmentName);
            statement.setString(4, IDP_RESOURCE);
            statement.setString(5, IDP_CLIENT_TYPE);
            statement.setString(6, clientId);
            statement.setString(7, secret);
            statement.executeUpdate();
            return true;
        } catch (SQLException absent) {
            return false;
        }
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
