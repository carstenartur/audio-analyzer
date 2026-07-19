package org.hammer.audio.infrastructure.workflow.collaboration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class WorkflowSearchSchemaMigrationTest {

  @Test
  void installsSearchProjectionBetweenCoreAndCollaborationMigrations() throws Exception {
    DataSource dataSource =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");

    WorkflowSchemaMigrationResult result =
        new WorkflowSchemaMigrator(dataSource).migrate(false, false, false);

    assertEquals(2, result.coreMigrationsExecuted());
    assertEquals(1, result.searchMigrationsExecuted());
    assertEquals(3, result.collaborationMigrationsExecuted());
    assertEquals(1, tableCount(dataSource, "GIT_COMMIT_INDEX"));
    assertEquals(1, tableCount(dataSource, "JGit_STORAGE_HIBERNATE_SEARCH_SCHEMA_HISTORY"));
  }

  private static int tableCount(DataSource dataSource, String tableName) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = '"
                    + tableName.toUpperCase(java.util.Locale.ROOT)
                    + "'")) {
      resultSet.next();
      return resultSet.getInt(1);
    }
  }
}
