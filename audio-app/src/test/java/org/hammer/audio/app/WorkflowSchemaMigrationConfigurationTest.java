package org.hammer.audio.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import javax.sql.DataSource;
import org.hammer.audio.infrastructure.workflow.collaboration.schema.WorkflowSchemaMigrationResult;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class WorkflowSchemaMigrationConfigurationTest {

  @Test
  void disabledMigrationDoesNotRequireDatasourceOrValidateMode() {
    DataSource dataSource = mock(DataSource.class);
    WorkflowSchemaMigrationConfiguration configuration =
        new WorkflowSchemaMigrationConfiguration();

    WorkflowSchemaMigrationResult result =
        configuration.workflowSchemaMigrationResult(
            dataSource, new MockEnvironment(), false, false, false, "update");

    assertFalse(result.applied());
    verifyNoInteractions(dataSource);
  }

  @Test
  void enabledMigrationRejectsHibernateMutationBeforeOpeningDatasource() {
    DataSource dataSource = mock(DataSource.class);
    MockEnvironment environment =
        new MockEnvironment().withProperty("spring.datasource.url", "jdbc:h2:mem:migration");
    WorkflowSchemaMigrationConfiguration configuration =
        new WorkflowSchemaMigrationConfiguration();

    assertThrows(
        IllegalStateException.class,
        () ->
            configuration.workflowSchemaMigrationResult(
                dataSource, environment, true, false, false, "update"));
    verifyNoInteractions(dataSource);
  }
}
