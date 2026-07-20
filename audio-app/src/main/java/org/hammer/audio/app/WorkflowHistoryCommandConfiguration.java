package org.hammer.audio.app;

import org.hammer.audio.infrastructure.workflow.history.CollaborationWorkflowHistoryAccessPolicy;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.history.WorkflowHistoryAccessPolicy;
import org.hammer.audio.workflow.history.WorkflowHistoryCommandService;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires explicit compare and restore commands when a versioned workflow store is available. */
@Configuration
@ConditionalOnBean(VersionedWorkflowStore.class)
public class WorkflowHistoryCommandConfiguration {

  /** Applies current collaboration membership as the restore access boundary. */
  @Bean
  public WorkflowHistoryAccessPolicy workflowHistoryAccessPolicy(
      WorkflowSessionRegistry sessionRegistry) {
    return new CollaborationWorkflowHistoryAccessPolicy(sessionRegistry);
  }

  /** Creates the repository-scoped compare and non-destructive restore application service. */
  @Bean
  public WorkflowHistoryCommandService workflowHistoryCommandService(
      VersionedWorkflowStore store, WorkflowHistoryAccessPolicy accessPolicy) {
    return new WorkflowHistoryCommandService(store, accessPolicy);
  }
}
