package org.hammer.audio.app;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.hammer.audio.dsp.workflow.DeterministicAudioWorkflowExecutionBackend;
import org.hammer.audio.workflow.execution.WorkflowRunModels.ExecutionBackend;
import org.junit.jupiter.api.Test;

class WorkflowRunConfigurationTest {

  @Test
  void productionWiringUsesRealDeterministicComputationBackend() {
    ExecutionBackend backend = new WorkflowRunConfiguration().workflowExecutionBackend();

    assertInstanceOf(DeterministicAudioWorkflowExecutionBackend.class, backend);
  }
}
