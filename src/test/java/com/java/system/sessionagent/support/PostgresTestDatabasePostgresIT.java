package com.java.system.sessionagent.support;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import org.testcontainers.DockerClientFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresTestDatabasePostgresIT {

    @Test
    void independent_databases_survive_another_fixtures_flyway_clean_and_close() {
        try (PostgresTestDatabase first = PostgresTestDatabase.create();
                PostgresTestDatabase second = PostgresTestDatabase.create()) {
            first.start();
            second.start();
            JdbcTemplate firstJdbc = new JdbcTemplate(first.dataSource());
            JdbcTemplate secondJdbc = new JdbcTemplate(second.dataSource());
            String firstName = firstJdbc.queryForObject("select current_database()", String.class);
            String secondName = secondJdbc.queryForObject("select current_database()", String.class);
            assertThat(firstName).startsWith("session_agent_test_").isNotEqualTo(secondName);
            assertThat(secondName).startsWith("session_agent_test_");
            firstJdbc.execute("create table fixture_sentinel (value text not null)");
            firstJdbc.update("insert into fixture_sentinel values (?)", "first survives repeated start");
            secondJdbc.execute("create table fixture_sentinel (value text not null)");
            secondJdbc.update("insert into fixture_sentinel values (?)", "second remains untouched");

            first.start();
            assertThat(firstJdbc.queryForObject("select value from fixture_sentinel", String.class))
                    .isEqualTo("first survives repeated start");
            Flyway.configure().dataSource(first.dataSource()).cleanDisabled(false).load().clean();
            first.close();
            first.close();

            assertThat(secondJdbc.queryForObject("select value from fixture_sentinel", String.class))
                    .isEqualTo("second remains untouched");
            assertThat(secondJdbc.queryForObject("select count(*) from pg_database where datname = ?",
                    Integer.class, firstName)).isZero();
            assertThat(secondJdbc.queryForObject("select count(*) from pg_database where datname = ?",
                    Integer.class, secondName)).isOne();
            assertThatThrownBy(() -> firstJdbc.execute("select 1")).hasRootCauseInstanceOf(SQLException.class);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SESSION_AGENT_TEST_POSTGRES_MODE", matches = "shared")
    void shared_cleanup_terminates_owned_connections_and_preserves_unrelated_databases() throws SQLException {
        DriverManagerDataSource admin = sharedAdmin();
        JdbcTemplate adminJdbc = new JdbcTemplate(admin);
        List<Map<String, Object>> databasesBefore = databaseInventory(adminJdbc);
        String databaseName;
        try (PostgresTestDatabase database = PostgresTestDatabase.create();
                PostgresTestDatabase overlappingRun = PostgresTestDatabase.create()) {
            // A legitimate overlapping run changes pg_database between our observations.
            overlappingRun.start();
            JdbcTemplate overlappingJdbc = new JdbcTemplate(overlappingRun.dataSource());
            overlappingJdbc.execute("create table fixture_sentinel (value text not null)");
            overlappingJdbc.update("insert into fixture_sentinel values (?)", "overlapping run survives");
            assertThat(databaseInventory(adminJdbc)).isEqualTo(databasesBefore);
            database.start();
            databaseName = new JdbcTemplate(database.dataSource()).queryForObject("select current_database()", String.class);
            try (Connection connection = database.dataSource().getConnection()) {
                database.close();
                assertThat(connection.isValid(1)).isFalse();
            }
            assertThat(overlappingJdbc.queryForObject("select value from fixture_sentinel", String.class))
                    .isEqualTo("overlapping run survives");
            assertThat(databaseInventory(adminJdbc)).isEqualTo(databasesBefore);
            overlappingRun.close();
            assertThat(databaseInventory(adminJdbc)).isEqualTo(databasesBefore);
        }
        assertThat(adminJdbc.queryForObject("select count(*) from pg_database where datname = ?",
                Integer.class, databaseName)).isZero();
        assertThat(databaseInventory(adminJdbc)).isEqualTo(databasesBefore);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SESSION_AGENT_TEST_POSTGRES_MODE", matches = "shared")
    void test_role_cannot_connect_to_application_databases_or_mutate_their_data() {
        JdbcTemplate admin = new JdbcTemplate(sharedAdmin());
        List<String> applicationDatabases = admin.queryForList("""
                select datname from pg_database
                where not datistemplate and datname <> 'postgres'
                  and datname !~ '^session_agent_test_[0-9a-f]{32}$'
                order by datname
                """, String.class);
        assertThat(applicationDatabases).as("local consolidation must retain the application databases").isNotEmpty();
        for (String databaseName : applicationDatabases) {
            assertThat(admin.queryForObject("select has_database_privilege(current_user, ?, 'CONNECT')",
                    Boolean.class, databaseName)).isFalse();
        }
        // Content fingerprints need application-owner access and are recorded by the root verifier.
        // This role intentionally cannot read application data, even to compute fingerprints.
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SESSION_AGENT_TEST_POSTGRES_MODE", matches = "shared")
    void explicit_shared_authentication_failure_does_not_fall_back_to_a_working_container() {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("SESSION_AGENT_TEST_POSTGRES_PASSWORD", UUID.randomUUID().toString());
        JdbcTemplate admin = new JdbcTemplate(sharedAdmin());
        List<Map<String, Object>> before = databaseInventory(admin);

        try (PostgresTestDatabase database = PostgresTestDatabase.create(environment)) {
            assertThatThrownBy(database::start).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(database::dataSource).isInstanceOf(IllegalStateException.class);
        }

        assertThat(databaseInventory(admin)).isEqualTo(before);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SESSION_AGENT_TEST_POSTGRES_MODE", matches = "container")
    void portable_container_is_stopped_when_its_fixture_is_closed() {
        DockerClient docker = DockerClientFactory.instance().client();
        Set<String> before = containerIds(docker);
        Set<String> ownedContainers;
        DriverManagerDataSource source;
        try (PostgresTestDatabase database = PostgresTestDatabase.create()) {
            database.start();
            source = database.dataSource();
            ownedContainers = containerIds(docker);
            ownedContainers.removeAll(before);
            assertThat(ownedContainers).as("fixture starts one disposable PostgreSQL container").hasSize(1);
            assertThat(new JdbcTemplate(source).queryForObject("select 1", Integer.class)).isOne();
            database.close();
            assertThatThrownBy(() -> new JdbcTemplate(source).execute("select 1"))
                    .isInstanceOf(CannotGetJdbcConnectionException.class);
        }
        for (String containerId : ownedContainers) {
            try {
                assertThat(docker.inspectContainerCmd(containerId).exec().getState().getRunning()).isFalse();
            } catch (NotFoundException removed) {
                // Testcontainers normally removes stopped owned containers.
            }
        }
    }

    private static Set<String> containerIds(DockerClient docker) {
        return docker.listContainersCmd().withShowAll(true).exec().stream()
                .filter(container -> container.getImage().equals("postgres:17-alpine"))
                .map(com.github.dockerjava.api.model.Container::getId)
                .collect(Collectors.toSet());
    }
    private static DriverManagerDataSource sharedAdmin() {
        return new DriverManagerDataSource(System.getenv("SESSION_AGENT_TEST_POSTGRES_ADMIN_URL"),
                System.getenv("SESSION_AGENT_TEST_POSTGRES_USERNAME"), System.getenv("SESSION_AGENT_TEST_POSTGRES_PASSWORD"));
    }

    private static List<Map<String, Object>> databaseInventory(JdbcTemplate admin) {
        return admin.queryForList("""
                select oid, datname, datdba, datacl::text from pg_database
                where datname !~ '^session_agent_test_[0-9a-f]{32}$'
                order by datname
                """);
    }
}
