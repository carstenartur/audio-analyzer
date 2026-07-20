package org.hammer.audio.app;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.hammer.audio.dsp.workflow.DeterministicAudioWorkflowExecutionBackend;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.execution.WorkflowRunModels.ExecutionBackend;
import org.hammer.audio.workflow.execution.WorkflowRunService;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Process-local workflow-run wiring; active jobs are intentionally not resumed after restart. */
@Configuration
public class WorkflowRunConfiguration {

  /** Creates a lightweight process-local executor for independent workflow runs. */
  @Bean(destroyMethod = "close")
  public ExecutorService workflowRunExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  /** Registers the first real deterministic offline audio-computation backend. */
  @Bean
  public ExecutionBackend workflowExecutionBackend() {
    return new DeterministicAudioWorkflowExecutionBackend();
  }

  /** Creates immutable run orchestration over collaboration and optional version history. */
  @Bean
  public WorkflowRunService workflowRunService(
      WorkflowSessionRegistry sessions,
      ObjectProvider<VersionedWorkflowStore> storeProvider,
      ExecutionBackend backend,
      @Qualifier("workflowRunExecutor") ExecutorService executor) {
    return new WorkflowRunService(sessions, storeProvider.getIfAvailable(), backend, executor);
  }
}
