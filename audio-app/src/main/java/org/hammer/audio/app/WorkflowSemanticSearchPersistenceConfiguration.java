package org.hammer.audio.app;

import org.hammer.audio.infrastructure.workflow.search.WorkflowSemanticPersistenceEntities;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the application-owned semantic workflow-history projection. */
@Configuration
@ConditionalOnProperty(name = "workbench.persistence.mode", havingValue = "hibernate")
public class WorkflowSemanticSearchPersistenceConfiguration {

  /** Contributes semantic projection entities to the one application-managed SessionFactory. */
  @Bean
  public WorkflowPersistenceEntityContributor workflowSemanticPersistenceEntities() {
    return WorkflowSemanticPersistenceEntities::annotatedClasses;
  }
}
