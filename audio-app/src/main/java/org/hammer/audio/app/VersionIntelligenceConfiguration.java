package org.hammer.audio.app;

import java.util.Map;
import java.util.concurrent.Executors;
import org.hammer.audio.workflow.editor.http.WorkflowHistorySearchService;
import org.hammer.audio.workflow.execution.WorkflowRunService;
import org.hammer.audio.workflow.search.InMemoryWorkflowHistorySearchIndex;
import org.hammer.audio.workflow.search.WorkflowHistorySearchIndex;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;
import org.hammer.audio.workflow.store.WorkflowCheckpointListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Replaceable search projection and immutable execution wiring. */
@Configuration
public class VersionIntelligenceConfiguration {

  @Bean
  public WorkflowHistorySearchIndex workflowHistorySearchIndex() {
    return new InMemoryWorkflowHistorySearchIndex();
  }

  @Bean
  public WorkflowHistorySearchService workflowHistorySearchService(
      ObjectProvider<VersionedWorkflowStore> storeProvider, WorkflowHistorySearchIndex index) {
    return new WorkflowHistorySearchService(storeProvider, index);
  }

  @Bean
  public WorkflowCheckpointListener workflowCheckpointListener(
      WorkflowHistorySearchService searchService) {
    return searchService;
  }

  @Bean(destroyMethod = "close")
  public WorkflowRunService workflowRunService() {
    return new WorkflowRunService(
        Executors.newVirtualThreadPerTaskExecutor(),
        (snapshot, cancelled) -> {
          if (cancelled.get()) {
            throw new InterruptedException("Workflow run cancelled");
          }
          return Map.of(
              "workflowId", snapshot.workflowId(),
              "dslBytes",
                  Integer.toString(
                      snapshot.dslText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length),
              "status", "validated-and-snapshotted");
        });
  }
}
