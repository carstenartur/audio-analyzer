package org.hammer.audio.app;

import io.github.carstenartur.jgit.storage.hibernate.search.SearchEntities;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers shared Git-history search projections in the application persistence context. */
@Configuration
@ConditionalOnProperty(name = "workbench.persistence.mode", havingValue = "hibernate")
public class WorkflowSearchPersistenceConfiguration {

  /** Contributes generic search entities to the one application-managed SessionFactory. */
  @Bean
  public WorkflowPersistenceEntityContributor workflowSearchPersistenceEntities() {
    return SearchEntities::annotatedClasses;
  }
}
