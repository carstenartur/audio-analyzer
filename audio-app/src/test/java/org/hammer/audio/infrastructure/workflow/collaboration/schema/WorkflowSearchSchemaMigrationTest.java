package org.hammer.audio.infrastructure.workflow.collaboration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.hammer.audio.app.CollaborationPersistenceConfiguration;
import org.hammer.audio.app.WorkflowPersistenceConfiguration;
import org.hammer.audio.app.WorkflowSearchPersistenceConfiguration;
import org.hammer.audio.app.WorkflowSemanticSearchPersistenceConfiguration;
import org.hammer.audio.infrastructure.workflow.search.WorkflowSemanticIndexEntity;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

class WorkflowSearchSchemaMigrationTest {

  @Test
  void installsSemanticProjectionBetweenGenericSearchAndCollaborationMigrations() throws Exception {
    DataSource dataSource = dataSource();

    WorkflowSchemaMigrationResult result =
        new WorkflowSchemaMigrator(dataSource).migrate(false, false, false);

    assertTrue(
        result.coreMigrationsExecuted() >= 7,
        "An upstream candidate must retain every released core migration");
    assertTrue(
        result.searchMigrationsExecuted() >= 3,
        "An upstream candidate must retain every released search migration");
    assertEquals(1, result.semanticMigrationsExecuted());
    assertEquals(3, result.collaborationMigrationsExecuted());
    assertEquals(1, tableCount(dataSource, "git_commit_index"));
    assertEquals(1, tableCount(dataSource, "workflow_semantic_index"));
    assertEquals(1, tableCount(dataSource, "jgit_storage_hibernate_search_schema_history"));
    assertEquals(1, tableCount(dataSource, "audio_analyzer_workflow_semantic_schema_history"));
  }

  @Test
  void migratedSchemaValidatesSharedStorageSearchSemanticAndCollaborationMappings() {
    DriverManagerDataSource dataSource = dataSource();
    WorkflowSchemaMigrationResult result =
        new WorkflowSchemaMigrator(dataSource).migrate(false, false, false);
    MockEnvironment environment =
        new MockEnvironment().withProperty("spring.datasource.url", dataSource.getUrl());

    try (HibernateSessionFactoryProvider provider =
        new WorkflowPersistenceConfiguration()
            .workflowHibernateSessionFactoryProvider(
                dataSource,
                environment,
                List.of(
                    new WorkflowSearchPersistenceConfiguration()
                        .workflowSearchPersistenceEntities(),
                    new WorkflowSemanticSearchPersistenceConfiguration()
                        .workflowSemanticPersistenceEntities(),
                    new CollaborationPersistenceConfiguration().collaborationPersistenceEntities()),
                result,
                "validate")) {
      assertNotNull(provider.getSessionFactory().getMetamodel().entity(GitCommitIndex.class));
      assertNotNull(
          provider.getSessionFactory().getMetamodel().entity(WorkflowSemanticIndexEntity.class));
    }
  }

  private static DriverManagerDataSource dataSource() {
    return new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
  }

  private static int tableCount(DataSource dataSource, String tableName) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                    + "WHERE UPPER(TABLE_NAME) = UPPER('"
                    + tableName
                    + "')")) {
      resultSet.next();
      return resultSet.getInt(1);
    }
  }
}
