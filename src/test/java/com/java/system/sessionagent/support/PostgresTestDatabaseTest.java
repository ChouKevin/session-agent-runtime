package com.java.system.sessionagent.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresTestDatabaseTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://example.com:5432/postgres",
            "jdbc:postgresql://127.0.0.2:5432/postgres",
            "jdbc:postgresql://127.0.0.1:5433/postgres",
            "jdbc:postgresql://127.0.0.1:5432/session_agent",
            "jdbc:postgresql://127.0.0.1:5432/postgres?host=example.com",
            "jdbc:postgresql://localhost:5432/postgres?service=application",
            "jdbc:postgresql://localhost,example.com:5432/postgres",
            "jdbc:postgresql:postgres"
    })
    void rejects_unsafe_administrative_targets_before_starting_any_database(String url) {
        Map<String, String> environment = sharedEnvironment();
        environment.put("SESSION_AGENT_TEST_POSTGRES_ADMIN_URL", url);

        assertThatThrownBy(() -> PostgresTestDatabase.create(environment))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SESSION_AGENT_TEST_POSTGRES_ADMIN_URL", "SESSION_AGENT_TEST_POSTGRES_USERNAME",
            "SESSION_AGENT_TEST_POSTGRES_PASSWORD"})
    void rejects_partial_shared_configuration_before_starting_any_database(String missing) {
        Map<String, String> environment = sharedEnvironment();
        environment.remove(missing);

        assertThatThrownBy(() -> PostgresTestDatabase.create(environment))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "docker", "SHARED"})
    void rejects_unrecognized_explicit_modes_instead_of_falling_back(String mode) {
        assertThatThrownBy(() -> PostgresTestDatabase.create(Map.of("SESSION_AGENT_TEST_POSTGRES_MODE", mode)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void credentials_without_a_mode_do_not_silently_select_a_container() {
        Map<String, String> environment = sharedEnvironment();
        environment.remove("SESSION_AGENT_TEST_POSTGRES_MODE");

        assertThatThrownBy(() -> PostgresTestDatabase.create(environment))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void container_mode_rejects_leftover_shared_credentials() {
        Map<String, String> environment = sharedEnvironment();
        environment.put("SESSION_AGENT_TEST_POSTGRES_MODE", "container");

        assertThatThrownBy(() -> PostgresTestDatabase.create(environment))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void never_exposes_an_administrative_datasource_before_start_or_after_close() {
        try (PostgresTestDatabase database = PostgresTestDatabase.create(sharedEnvironment())) {
            assertThatThrownBy(database::dataSource).isInstanceOf(IllegalStateException.class);
            database.close();
            database.close();
            assertThatThrownBy(database::dataSource).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(database::start).isInstanceOf(IllegalStateException.class);
        }
    }

    private static Map<String, String> sharedEnvironment() {
        return new HashMap<>(Map.of(
                "SESSION_AGENT_TEST_POSTGRES_MODE", "shared",
                "SESSION_AGENT_TEST_POSTGRES_ADMIN_URL", "jdbc:postgresql://127.0.0.1:5432/postgres",
                "SESSION_AGENT_TEST_POSTGRES_USERNAME", "test_admin",
                "SESSION_AGENT_TEST_POSTGRES_PASSWORD", "test-only-not-a-real-secret"));
    }
}
