package com.chaplin.roots.examples.workflow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.DriverManager;
import java.util.UUID;

/** Runs the same workflow contracts on PostgreSQL in a unique, owned test schema. */
@EnabledIfEnvironmentVariable(named = "ROOTS_TEST_POSTGRES_URL", matches = ".+")
class CustomerPostgresTest extends CustomerRepositoryTest {
    private String schema;
    private boolean created;

    @BeforeEach void createSchema() throws Exception {
        var target = System.getenv("ROOTS_TEST_POSTGRES_URL");
        var uri = java.net.URI.create(target.substring("jdbc:".length()));
        if (!uri.getPath().equals("/roots_receipts_test") || target.contains("currentSchema"))
            throw new IllegalArgumentException("Use a dedicated roots_receipts_test database without currentSchema");
        schema = "workflow_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            created = true;
        }
    }
    @Override String url() {
        var base = System.getenv("ROOTS_TEST_POSTGRES_URL");
        return base + (base.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }
    @AfterEach void removeOwnedSchema() throws Exception {
        if (created && schema.matches("workflow_test_[a-f0-9]{32}")) {
            try (var connection = connection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
    private java.sql.Connection connection() throws Exception {
        return DriverManager.getConnection(System.getenv("ROOTS_TEST_POSTGRES_URL"),
                System.getenv("ROOTS_TEST_POSTGRES_USER"), System.getenv("ROOTS_TEST_POSTGRES_PASSWORD"));
    }
}
