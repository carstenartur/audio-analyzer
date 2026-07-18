package org.hammer.audio.app;

import javax.sql.DataSource;
import org.hammer.audio.infrastructure.workflow.collaboration.schema.WorkflowSchemaMigrationResult;
import org.hammer.audio.infrastructure.workflow.collaboration.schema.WorkflowSchemaMigrator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Runs explicitly enabled versioned migrations before Hibernate validates the shared schema. */
@Configuration
@ConditionalOnProperty(name = "workbench.persistence.mode", havingValue = "hibernate")
public class WorkflowSchemaMigrationConfiguration {

  /** Applies ordered migrations or returns an explicit skipped marker. */
  @Bean
  public WorkflowSchemaMigrationResult workflowSchemaMigrationResult(
      DataSource dataSource,
      Environment environment,
      @Value("${workbench.persistence.migrations.enabled:false}") boolean migrationsEnabled,
      @Value("${workbench.persistence.migrations.adopt-core-0.1.4:false}")
          boolean adoptLegacyCoreSchema,
      @Value("${workbench.persistence.migrations.adopt-collaboration-pre-lease:false}")
          boolean adoptPreLeaseCollaborationSchema) {
    if (!migrationsEnabled) {
      return WorkflowSchemaMigrationResult.skipped();
    }
    requireExplicitDataSource(environment);
    return new WorkflowSchemaMigrator(dataSource)
        .migrate(adoptLegacyCoreSchema, adoptPreLeaseCollaborationSchema);
  }

  private static void requireExplicitDataSource(Environment environment) {
    String jdbcUrl = environment.getProperty("spring.datasource.url");
    String jndiName = environment.getProperty("spring.datasource.jndi-name");
    if (isBlank(jdbcUrl) && isBlank(jndiName)) {
      throw new IllegalStateException(
          "Versioned workflow migrations require spring.datasource.url "
              + "or spring.datasource.jndi-name");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
