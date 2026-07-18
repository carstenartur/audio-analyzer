package org.hammer.audio.app;

import javax.sql.DataSource;
import org.hammer.audio.infrastructure.workflow.collaboration.schema.WorkflowSchemaMigrationResult;
import org.hammer.audio.infrastructure.workflow.collaboration.schema.WorkflowSchemaMigrator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Runs explicitly enabled versioned migrations before Hibernate validates the shared schema. */
@Configuration
@ConditionalOnProperty(name = "workbench.persistence.mode", havingValue = "hibernate")
public class WorkflowSchemaMigrationConfiguration {

  /** Applies ordered migrations or returns an explicit skipped marker. */
  @Bean
  public WorkflowSchemaMigrationResult workflowSchemaMigrationResult(
      DataSource dataSource,
      @Value("${workbench.persistence.migrations.enabled:false}") boolean migrationsEnabled,
      @Value("${workbench.persistence.migrations.adopt-core-0.1.4:false}")
          boolean adoptLegacyCoreSchema,
      @Value("${workbench.persistence.migrations.adopt-collaboration-pre-lease:false}")
          boolean adoptPreLeaseCollaborationSchema) {
    if (!migrationsEnabled) {
      return WorkflowSchemaMigrationResult.skipped();
    }
    return new WorkflowSchemaMigrator(dataSource)
        .migrate(adoptLegacyCoreSchema, adoptPreLeaseCollaborationSchema);
  }
}
