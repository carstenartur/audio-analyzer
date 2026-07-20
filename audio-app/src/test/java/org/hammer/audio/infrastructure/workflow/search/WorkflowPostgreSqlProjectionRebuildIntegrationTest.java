package org.hammer.audio.infrastructure.workflow.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.hammer.audio.app.CollaborationPersistenceConfiguration;
import org.hammer.audio.app.WorkflowPersistenceConfiguration;
import org.hammer.audio.app.WorkflowSearchPersistenceConfiguration;
import org.hammer.audio.app.WorkflowSemanticSearchPersistenceConfiguration;
import org.hammer.audio.infrastructure.workflow.collaboration.schema.WorkflowSchemaMigrationResult;
import org.hammer.audio.infrastructure.workflow.collaboration.schema.WorkflowSchemaMigrator;
import org.hammer.audio.infrastructure.workflow.store.HibernateJGitVersionedWorkflowStore;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
import org.hammer.audio.workflow.history.WorkflowCombinedHistoryQuery;
import org.hammer.audio.workflow.history.WorkflowCombinedHistoryResult;
import org.hammer.audio.workflow.history.WorkflowHistoryTextQuery;
import org.hammer.audio.workflow.history.WorkflowHistoryTextResult;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryFilter;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryQuery;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryResult;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.WorkflowSnapshot;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies the complete rebuildable history projection set on production-oriented PostgreSQL. */
@Testcontainers(disabledWithoutDocker = true)
class WorkflowPostgreSqlProjectionRebuildIntegrationTest {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();
  private static final String REPOSITORY_NAME = "postgres-complete-history";
  private static final String WORKFLOW_ID = "workflow.postgres-history";
  private static final Instant BASE_TIME = Instant.parse("2026-07-20T00:00:00Z");

  @Container
  static final PostgreSQLContainer<?> POSTGRESQL =
      new PostgreSQLContainer<>("postgres:17.10-alpine")
          .withDatabaseName("audio_analyzer_history")
          .withUsername("postgres")
          .withPassword("postgres");

  @Test
  void migratesValidatesRebuildsAndQueriesGenericSemanticAndCombinedHistory() throws Exception {
    try (PostgreSqlSchema schema = PostgreSqlSchema.create()) {
      DataSource dataSource = schema.dataSource();
      WorkflowSchemaMigrationResult migration =
          new WorkflowSchemaMigrator(dataSource).migrate(false, false, false);
      assertTrue(migration.applied());
      assertEquals(1, migration.semanticMigrationsExecuted());

      CommitId matchingCommit;
      WorkflowSnapshot matchingSnapshot = matchingSnapshot();
      try (HibernateSessionFactoryProvider provider =
              provider(dataSource, schema.jdbcUrl(), migration);
          HibernateJGitVersionedWorkflowStore store =
              new HibernateJGitVersionedWorkflowStore(
                  provider.getSessionFactory(), REPOSITORY_NAME)) {
        store.commit("main", baselineSnapshot(), metadata("Baseline", 1));
        matchingCommit = store.commit("main", matchingSnapshot, metadata("Add classifier", 2));
        removeDerivedRows(provider);
      }

      try (HibernateSessionFactoryProvider provider =
              provider(dataSource, schema.jdbcUrl(), migration);
          HibernateJGitVersionedWorkflowStore store =
              new HibernateJGitVersionedWorkflowStore(
                  provider.getSessionFactory(), REPOSITORY_NAME)) {
        assertEquals(2, store.rebuild("main", -1));

        WorkflowHistoryTextQuery genericQuery =
            new WorkflowHistoryTextQuery(
                "wingbeatneedle",
                "postgres-test@audio-analyzer.invalid",
                "workflow",
                null,
                null,
                10);
        List<WorkflowHistoryTextResult> genericHits = store.search(genericQuery);
        assertEquals(
            List.of(matchingCommit),
            genericHits.stream().map(WorkflowHistoryTextResult::commitId).toList());

        WorkflowSemanticHistoryFilter semanticFilter =
            new WorkflowSemanticHistoryFilter(
                "main", WORKFLOW_ID, "node.classifier", "classifier", "wingbeat", "mode", "safe");
        List<WorkflowSemanticHistoryResult> semanticHits =
            store.searchSemantic(
                new WorkflowSemanticHistoryQuery(
                    semanticFilter.branch(),
                    semanticFilter.workflowId(),
                    semanticFilter.nodeId(),
                    semanticFilter.nodeType(),
                    semanticFilter.labelText(),
                    semanticFilter.propertyKey(),
                    semanticFilter.propertyValue(),
                    10));
        assertEquals(
            List.of(matchingCommit),
            semanticHits.stream().map(WorkflowSemanticHistoryResult::commitId).toList());

        List<WorkflowCombinedHistoryResult> combinedHits =
            store.searchCombined(new WorkflowCombinedHistoryQuery(genericQuery, semanticFilter));
        assertEquals(1, combinedHits.size());
        assertEquals(matchingCommit, combinedHits.getFirst().commit().commitId());
        assertEquals(matchingCommit, combinedHits.getFirst().semantics().commitId());
        assertEquals(matchingSnapshot, store.loadAtCommit(matchingCommit));
      }
    }
  }

  private static HibernateSessionFactoryProvider provider(
      DataSource dataSource, String jdbcUrl, WorkflowSchemaMigrationResult migration) {
    MockEnvironment environment =
        new MockEnvironment().withProperty("spring.datasource.url", jdbcUrl);
    return new WorkflowPersistenceConfiguration()
        .workflowHibernateSessionFactoryProvider(
            dataSource,
            environment,
            List.of(
                new WorkflowSearchPersistenceConfiguration().workflowSearchPersistenceEntities(),
                new WorkflowSemanticSearchPersistenceConfiguration()
                    .workflowSemanticPersistenceEntities(),
                new CollaborationPersistenceConfiguration().collaborationPersistenceEntities()),
            migration,
            "validate");
  }

  private static void removeDerivedRows(HibernateSessionFactoryProvider provider) {
    try (Session session = provider.getSessionFactory().openSession()) {
      session.beginTransaction();
      session
          .createQuery("FROM GitCommitIndex", GitCommitIndex.class)
          .getResultList()
          .forEach(session::remove);
      session
          .createQuery("FROM WorkflowSemanticIndexEntity", WorkflowSemanticIndexEntity.class)
          .getResultList()
          .forEach(session::remove);
      session.getTransaction().commit();
    }
  }

  private static WorkflowSnapshot baselineSnapshot() {
    return snapshot("Baseline workflow", "node.source", "source", "Microphone source", "observe");
  }

  private static WorkflowSnapshot matchingSnapshot() {
    return snapshot(
        "Wingbeat classifier workflow",
        "node.classifier",
        "classifier",
        "Wingbeat wingbeatneedle classifier",
        "safe");
  }

  private static WorkflowSnapshot snapshot(
      String name, String nodeId, String nodeType, String nodeLabel, String mode) {
    Workflow workflow =
        new Workflow(
            WORKFLOW_ID,
            name,
            List.of(
                new Node(
                    nodeId,
                    nodeType,
                    nodeLabel,
                    List.of(),
                    List.of(),
                    new Metadata(Map.of("mode", mode)))),
            List.of(),
            Metadata.empty());
    return new WorkflowSnapshot(workflow.id(), new WorkflowDslSerializer().serialize(workflow));
  }

  private static CommitMetadata metadata(String message, long seconds) {
    return new CommitMetadata("postgres-test", message, BASE_TIME.plusSeconds(seconds));
  }

  private record PostgreSqlSchema(
      String baseUrl, String schemaName, String username, String password)
      implements AutoCloseable {

    static PostgreSqlSchema create() throws SQLException {
      String baseUrl = POSTGRESQL.getJdbcUrl();
      String username = POSTGRESQL.getUsername();
      String password = POSTGRESQL.getPassword();
      String schemaName = "workflow_history_" + TEST_COUNTER.incrementAndGet();
      try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
          Statement statement = connection.createStatement()) {
        statement.execute("create schema " + schemaName);
      }
      return new PostgreSqlSchema(baseUrl, schemaName, username, password);
    }

    String jdbcUrl() {
      return baseUrl + "?currentSchema=" + schemaName;
    }

    DataSource dataSource() {
      DriverManagerDataSource dataSource = new DriverManagerDataSource();
      dataSource.setDriverClassName("org.postgresql.Driver");
      dataSource.setUrl(jdbcUrl());
      dataSource.setUsername(username);
      dataSource.setPassword(password);
      return dataSource;
    }

    @Override
    public void close() throws SQLException {
      try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
          Statement statement = connection.createStatement()) {
        statement.execute("drop schema if exists " + schemaName + " cascade");
      }
    }
  }
}
