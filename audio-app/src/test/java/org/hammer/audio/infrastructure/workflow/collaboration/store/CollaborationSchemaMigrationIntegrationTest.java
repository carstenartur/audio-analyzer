package org.hammer.audio.infrastructure.workflow.collaboration.store;

import static org.hammer.audio.infrastructure.workflow.collaboration.store.WorkflowOutboxStoreTestSupport.BASE_TIME;
import static org.hammer.audio.infrastructure.workflow.collaboration.store.WorkflowOutboxStoreTestSupport.appendPendingEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.hammer.audio.infrastructure.workflow.collaboration.schema.CollaborationSchemaMigrations;
import org.hammer.audio.infrastructure.workflow.collaboration.schema.WorkflowSchemaMigrationResult;
import org.hammer.audio.infrastructure.workflow.collaboration.schema.WorkflowSchemaMigrator;
import org.hammer.audio.workflow.collaboration.store.LeasedWorkflowOutboxEntry;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowOperation;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowOutboxEntry;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowSession;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class CollaborationSchemaMigrationIntegrationTest {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();
  private static final String H2_LEGACY_SCHEMA =
      "/db/legacy/audio-analyzer/collaboration/pre-lease/h2/schema.sql";
  private static final List<String> EXPECTED_CORE_MIGRATIONS =
      List.of("0.1.4", "0.1.5", "0.1.14", "0.1.14.1", "0.1.14.2", "0.1.17", "0.1.18");
  private static final Instant CLAIMED_AT = Instant.parse("2026-07-18T03:00:00Z");

  @Test
  void migratesEmptyH2DatabaseAndRestartsWithValidation() throws Exception {
    try (TestDatabase database = h2Database("empty")) {
      verifyEmptyMigrationAndRestart(database);
    }
  }

  @Test
  void upgradesPopulatedPreLeaseH2SchemaWithoutDataLoss() throws Exception {
    try (TestDatabase database = h2Database("upgrade")) {
      verifyLegacyUpgrade(database);
    }
  }

  static void verifyEmptyMigrationAndRestart(TestDatabase database) throws Exception {
    WorkflowSchemaMigrationResult migration = migrate(database, false);

    assertTrue(migration.applied());
    assertCoreMigrationPrefix(database, migration);
    assertEquals(3, migration.collaborationMigrationsExecuted());
    assertEquals(
        List.of("1", "2", "3"),
        migrationVersions(database, CollaborationSchemaMigrations.SCHEMA_HISTORY_TABLE));

    String sessionId = "migrated.session";
    String eventId = "migrated.event";
    try (HibernateSessionFactoryProvider provider = provider(database)) {
      appendPendingEvent(
          new HibernateWorkflowSessionStateStore(provider.getSessionFactory()),
          sessionId,
          eventId,
          BASE_TIME.plusSeconds(1));
    }

    try (HibernateSessionFactoryProvider provider = provider(database)) {
      HibernateWorkflowSessionStateStore sessionStore =
          new HibernateWorkflowSessionStateStore(provider.getSessionFactory());
      HibernateWorkflowOutboxStore outboxStore =
          new HibernateWorkflowOutboxStore(provider.getSessionFactory());

      StoredWorkflowSession recovered = sessionStore.find(sessionId).orElseThrow();
      assertEquals(1, recovered.revision());
      assertEquals(1, recovered.sequence());
      assertEquals(1, sessionStore.operations(sessionId).size());

      LeasedWorkflowOutboxEntry lease =
          outboxStore
              .claimDue("migration.dispatcher", CLAIMED_AT, CLAIMED_AT.plusSeconds(30), 10)
              .getFirst();
      assertEquals(eventId, lease.entry().eventId());
      StoredWorkflowOutboxEntry published =
          outboxStore.markPublished(
              eventId, "migration.dispatcher", lease.leaseToken(), CLAIMED_AT.plusSeconds(1));
      assertFalse(published.pending());
    }

    try (HibernateSessionFactoryProvider provider = provider(database)) {
      StoredWorkflowOutboxEntry published =
          new HibernateWorkflowOutboxStore(provider.getSessionFactory())
              .find(eventId)
              .orElseThrow();
      assertEquals(CLAIMED_AT.plusSeconds(1), published.publishedAt());
      assertEquals(1, published.attemptCount());
    }
  }

  static void verifyLegacyUpgrade(TestDatabase database) throws Exception {
    installLegacySchema(database);

    WorkflowSchemaMigrationResult migration = migrate(database, true);
    assertTrue(migration.applied());
    assertCoreMigrationPrefix(database, migration);
    assertEquals(2, migration.collaborationMigrationsExecuted());
    assertEquals(
        List.of("1", "2", "3"),
        migrationVersions(database, CollaborationSchemaMigrations.SCHEMA_HISTORY_TABLE));

    try (HibernateSessionFactoryProvider provider = provider(database)) {
      HibernateWorkflowSessionStateStore sessionStore =
          new HibernateWorkflowSessionStateStore(provider.getSessionFactory());
      HibernateWorkflowOutboxStore outboxStore =
          new HibernateWorkflowOutboxStore(provider.getSessionFactory());

      StoredWorkflowSession recovered = sessionStore.find("legacy.session").orElseThrow();
      assertEquals("legacy-workflow-dsl", recovered.workflowDsl());
      assertEquals(1, recovered.revision());
      assertEquals(2, recovered.sequence());

      List<StoredWorkflowOperation> operations = sessionStore.operations("legacy.session");
      assertEquals(1, operations.size());
      assertEquals("legacy.operation", operations.getFirst().operationId());
      assertEquals("legacy-operation-payload", operations.getFirst().payload());
      assertFalse(operations.getFirst().hasOperationBody());
      assertTrue(operations.getFirst().operation().isEmpty());

      StoredWorkflowOutboxEntry pending = outboxStore.find("legacy.event.pending").orElseThrow();
      assertTrue(pending.pending());
      assertEquals(2, pending.attemptCount());
      assertEquals("legacy-pending-event-payload", pending.payload());

      StoredWorkflowOutboxEntry alreadyPublished =
          outboxStore.find("legacy.event.published").orElseThrow();
      assertFalse(alreadyPublished.pending());
      assertNotNull(alreadyPublished.publishedAt());
      assertEquals(1, alreadyPublished.attemptCount());

      List<LeasedWorkflowOutboxEntry> claimed =
          outboxStore.claimDue("migration.upgrade", CLAIMED_AT, CLAIMED_AT.plusSeconds(30), 10);
      assertEquals(1, claimed.size());
      assertEquals("legacy.event.pending", claimed.getFirst().entry().eventId());
      StoredWorkflowOutboxEntry published =
          outboxStore.markPublished(
              "legacy.event.pending",
              "migration.upgrade",
              claimed.getFirst().leaseToken(),
              CLAIMED_AT.plusSeconds(1));
      assertEquals(3, published.attemptCount());
      assertFalse(published.pending());
    }
  }

  private static void assertCoreMigrationPrefix(
      TestDatabase database, WorkflowSchemaMigrationResult migration) throws SQLException {
    List<String> actualCoreMigrations =
        migrationVersions(database, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE);
    assertEquals(actualCoreMigrations.size(), migration.coreMigrationsExecuted());
    assertTrue(
        actualCoreMigrations.size() >= EXPECTED_CORE_MIGRATIONS.size(),
        "An upstream candidate must retain every released Core migration");
    assertEquals(
        EXPECTED_CORE_MIGRATIONS,
        actualCoreMigrations.subList(0, EXPECTED_CORE_MIGRATIONS.size()),
        "Released Core migrations must remain an unchanged prefix");
  }

  private static WorkflowSchemaMigrationResult migrate(
      TestDatabase database, boolean adoptPreLeaseCollaborationSchema) {
    return new WorkflowSchemaMigrator(database.dataSource())
        .migrate(false, adoptPreLeaseCollaborationSchema);
  }

  private static void installLegacySchema(TestDatabase database) throws IOException, SQLException {
    String script = readResource(database.legacySchemaResource());
    try (Connection connection = database.openConnection();
        Statement statement = connection.createStatement()) {
      for (String sql : sqlStatements(script)) {
        statement.execute(sql);
      }
    }
  }

  private static String readResource(String resourceName) throws IOException {
    try (InputStream input =
        CollaborationSchemaMigrationIntegrationTest.class.getResourceAsStream(resourceName)) {
      if (input == null) {
        throw new IOException("Missing test resource " + resourceName);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static List<String> sqlStatements(String script) {
    StringBuilder withoutComments = new StringBuilder();
    for (String line : script.lines().toList()) {
      if (!line.stripLeading().startsWith("--")) {
        withoutComments.append(line).append('\n');
      }
    }
    return java.util.Arrays.stream(withoutComments.toString().split(";"))
        .map(String::trim)
        .filter(statement -> !statement.isEmpty())
        .toList();
  }

  private static List<String> migrationVersions(TestDatabase database, String historyTable)
      throws SQLException {
    List<String> versions = new ArrayList<>();
    String sql =
        "select \"version\" from \""
            + historyTable
            + "\" where \"success\" = true and \"version\" <> '0' "
            + "order by \"installed_rank\"";
    try (Connection connection = database.openConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      while (resultSet.next()) {
        versions.add(resultSet.getString(1));
      }
    }
    return versions;
  }

  private static HibernateSessionFactoryProvider provider(TestDatabase database) {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", database.url());
    properties.put("hibernate.connection.username", database.username());
    properties.put("hibernate.connection.password", database.password());
    properties.put("hibernate.connection.driver_class", database.driverClass());
    properties.put("hibernate.dialect", database.hibernateDialect());
    properties.put("hibernate.hbm2ddl.auto", "validate");
    properties.put("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(
        properties, CollaborationPersistenceEntities.annotatedClasses());
  }

  private static TestDatabase h2Database(String purpose) {
    String name = "collaboration-migration-" + purpose + "-" + TEST_COUNTER.incrementAndGet();
    return new TestDatabase(
        "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1",
        "sa",
        "",
        "org.h2.Driver",
        "org.hibernate.dialect.H2Dialect",
        H2_LEGACY_SCHEMA,
        () -> {});
  }

  @FunctionalInterface
  interface SqlCleanup {
    void run() throws SQLException;
  }

  record TestDatabase(
      String url,
      String username,
      String password,
      String driverClass,
      String hibernateDialect,
      String legacySchemaResource,
      SqlCleanup cleanup)
      implements AutoCloseable {

    private DataSource dataSource() {
      DriverManagerDataSource dataSource = new DriverManagerDataSource();
      dataSource.setDriverClassName(driverClass);
      dataSource.setUrl(url);
      dataSource.setUsername(username);
      dataSource.setPassword(password);
      return dataSource;
    }

    private Connection openConnection() throws SQLException {
      return DriverManager.getConnection(url, username, password);
    }

    @Override
    public void close() throws SQLException {
      cleanup.run();
    }
  }
}
