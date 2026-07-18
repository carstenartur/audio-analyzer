package org.hammer.audio.infrastructure.workflow.collaboration.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.hammer.audio.infrastructure.workflow.collaboration.store.CollaborationSchemaMigrationIntegrationTest.TestDatabase;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class CollaborationPostgreSqlSchemaMigrationIntegrationTest {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();
  private static final String POSTGRESQL_LEGACY_SCHEMA =
      "/db/legacy/audio-analyzer/collaboration/pre-lease/postgresql/schema.sql";

  @Container
  static final PostgreSQLContainer<?> POSTGRESQL =
      new PostgreSQLContainer<>("postgres:17.10-alpine")
          .withDatabaseName("audio_analyzer_collaboration")
          .withUsername("postgres")
          .withPassword("postgres");

  @Test
  void migratesEmptyPostgreSqlDatabaseAndRestartsWithValidation() throws Exception {
    try (TestDatabase database = postgresDatabase("empty")) {
      CollaborationSchemaMigrationIntegrationTest.verifyEmptyMigrationAndRestart(database);
    }
  }

  @Test
  void upgradesPopulatedPreLeasePostgreSqlSchemaWithoutDataLoss() throws Exception {
    try (TestDatabase database = postgresDatabase("upgrade")) {
      CollaborationSchemaMigrationIntegrationTest.verifyLegacyUpgrade(database);
    }
  }

  private static TestDatabase postgresDatabase(String purpose) throws SQLException {
    String baseUrl = POSTGRESQL.getJdbcUrl();
    String username = POSTGRESQL.getUsername();
    String password = POSTGRESQL.getPassword();
    String schema = "collaboration_migration_" + purpose + "_" + TEST_COUNTER.incrementAndGet();

    try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
        Statement statement = connection.createStatement()) {
      statement.execute("create schema " + schema);
    }

    String schemaUrl = appendParameter(baseUrl, "currentSchema", schema);
    return new TestDatabase(
        schemaUrl,
        username,
        password,
        "org.postgresql.Driver",
        "org.hibernate.dialect.PostgreSQLDialect",
        POSTGRESQL_LEGACY_SCHEMA,
        () -> {
          try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
              Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
          }
        });
  }

  private static String appendParameter(String url, String key, String value) {
    return url + (url.contains("?") ? "&" : "?") + key + "=" + value;
  }
}
