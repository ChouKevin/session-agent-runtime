package com.java.system.sessionagent.support;

import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

/** Owns one disposable database, never an application or administrative database. */
public final class PostgresTestDatabase implements AutoCloseable {

    private static final String MODE = "SESSION_AGENT_TEST_POSTGRES_MODE";
    private static final String ADMIN_URL = "SESSION_AGENT_TEST_POSTGRES_ADMIN_URL";
    private static final String USERNAME = "SESSION_AGENT_TEST_POSTGRES_USERNAME";
    private static final String PASSWORD = "SESSION_AGENT_TEST_POSTGRES_PASSWORD";

    private final String databaseName = "session_agent_test_" + UUID.randomUUID().toString().replace("-", "");
    private final boolean shared;
    private final String adminUrl;
    private final Properties adminProperties;
    private PostgreSQLContainer container;
    private DriverManagerDataSource dataSource;
    private boolean ownsDatabase;
    private boolean started;
    private boolean closed;

    private PostgresTestDatabase(boolean shared, String adminUrl, Properties adminProperties) {
        this.shared = shared;
        this.adminUrl = adminUrl;
        this.adminProperties = adminProperties;
    }

    public static PostgresTestDatabase create() {
        return create(System.getenv());
    }

    static PostgresTestDatabase create(Map<String, String> environment) {
        boolean hasSharedConfiguration = environment.containsKey(ADMIN_URL)
                || environment.containsKey(USERNAME) || environment.containsKey(PASSWORD);
        String mode = environment.getOrDefault(MODE, "container");
        if (!mode.equals("shared") && !mode.equals("container")) {
            throw new IllegalArgumentException(MODE + " must be shared or container");
        }
        if (!mode.equals("shared")) {
            if (hasSharedConfiguration) {
                throw new IllegalArgumentException("Shared PostgreSQL credentials require explicit shared mode");
            }
            return new PostgresTestDatabase(false, "", new Properties());
        }

        String url = required(environment, ADMIN_URL);
        String username = required(environment, USERNAME);
        String password = required(environment, PASSWORD);
        // An exact allowlist excludes URI aliases, multiple hosts and all driver routing parameters.
        if (!url.equals("jdbc:postgresql://127.0.0.1:5432/postgres")
                && !url.equals("jdbc:postgresql://localhost:5432/postgres")) {
            throw new IllegalArgumentException(ADMIN_URL + " must address loopback port 5432 database postgres without parameters");
        }
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "15");
        return new PostgresTestDatabase(true, url, properties);
    }

    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("A closed PostgreSQL test fixture cannot be restarted");
        }
        if (started) {
            return;
        }
        try {
            if (shared) {
                startShared();
            } else {
                container = new PostgreSQLContainer("postgres:17-alpine").withDatabaseName(databaseName);
                container.start();
                dataSource = new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword());
            }
            started = true;
        } catch (SQLException failure) {
            IllegalStateException startupFailure = new IllegalStateException(
                    "Shared PostgreSQL test database startup failed; check the local service and dedicated test-role configuration", failure);
            cleanupFailedStart(startupFailure);
            throw startupFailure;
        } catch (RuntimeException | Error failure) {
            cleanupFailedStart(failure);
            throw failure;
        }
    }

    public synchronized DriverManagerDataSource dataSource() {
        if (!started || closed) {
            throw new IllegalStateException("Start the owned PostgreSQL test database before requesting its datasource");
        }
        return Objects.requireNonNull(dataSource);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        if (shared && ownsDatabase) {
            try (Connection connection = DriverManager.getConnection(adminUrl, adminProperties);
                    Statement statement = connection.createStatement()) {
                // This exact identifier is generated locally and recorded only after CREATE succeeds.
                statement.execute("ALTER DATABASE \"" + databaseName + "\" ALLOW_CONNECTIONS false");
                try (ResultSet clients = statement.executeQuery("""
                        select pg_terminate_backend(pid, 5000)
                        from pg_stat_activity
                        where datname = '%s' and usename = current_user
                          and backend_type = 'client backend'
                        """.formatted(databaseName))) {
                    while (clients.next()) {
                        if (!clients.getBoolean(1)) {
                            throw new SQLException("An owned test database client did not terminate");
                        }
                    }
                }
                // Ordinary DROP handles autovacuum itself; FORCE first requires signaling
                // every backend, including ones this deliberately unprivileged role cannot signal.
                statement.execute("DROP DATABASE \"" + databaseName + "\"");
                ownsDatabase = false;
            } catch (SQLException failure) {
                throw new IllegalStateException("Could not clean up owned test database " + databaseName
                        + "; inspect this exact database manually (no prefix-wide cleanup)", failure);
            }
        }
        if (Objects.nonNull(container)) {
            container.stop();
        }
        closed = true;
        started = false;
        dataSource = null;
    }

    private void startShared() throws SQLException {
        try (Connection connection = DriverManager.getConnection(adminUrl, adminProperties);
                Statement statement = connection.createStatement()) {
            requireIsolatedRole(statement);
            statement.execute("CREATE DATABASE \"" + databaseName + "\" TEMPLATE template0");
            ownsDatabase = true;
        }
        String databaseUrl = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + databaseName;
        dataSource = new DriverManagerDataSource(databaseUrl,
                adminProperties.getProperty("user"), adminProperties.getProperty("password"));
    }

    private static void requireIsolatedRole(Statement statement) throws SQLException {
        try (ResultSet role = statement.executeQuery("""
                select rolcanlogin and rolcreatedb and not rolsuper and not rolcreaterole
                       and not rolreplication and not rolbypassrls as isolated
                from pg_roles where rolname = current_user
                """)) {
            if (!role.next() || !role.getBoolean("isolated")) {
                throw new IllegalStateException("Shared tests require a dedicated LOGIN CREATEDB role without elevated privileges");
            }
        }
        try (ResultSet membership = statement.executeQuery("""
                select 1 from pg_auth_members
                where member = (select oid from pg_roles where rolname = current_user)
                """)) {
            if (membership.next()) {
                throw new IllegalStateException("Shared test role must not belong to other roles");
            }
        }
        try (ResultSet applicationAccess = statement.executeQuery("""
                select 1 from pg_database
                where not datistemplate and datname <> 'postgres'
                  and datname !~ '^session_agent_test_[0-9a-f]{32}$'
                  and (has_database_privilege(current_user, oid, 'CONNECT')
                       or datdba = (select oid from pg_roles where rolname = current_user))
                """)) {
            if (applicationAccess.next()) {
                throw new IllegalStateException("Shared test role can access or owns an application database; ask the local DB owner to isolate it before testing");
            }
        }
    }

    private void cleanupFailedStart(Throwable failure) {
        try {
            close();
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (Objects.isNull(value) || value.isBlank()) {
            throw new IllegalArgumentException("Shared PostgreSQL tests require " + key);
        }
        return value;
    }
}
