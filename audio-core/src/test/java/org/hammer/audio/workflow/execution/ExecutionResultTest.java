package org.hammer.audio.workflow.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionResultTest {

  private static final Instant STARTED = Instant.parse("2024-01-01T10:00:00Z");
  private static final Instant COMPLETED = Instant.parse("2024-01-01T10:05:00Z");

  @Test
  void overallStatusIsCompletedWhenAllNodesCompleted() {
    ExecutionResult result =
        new ExecutionResult(
            "exec.1",
            "plan.1",
            Map.of("node.a", ExecutionStatus.COMPLETED, "node.b", ExecutionStatus.COMPLETED),
            STARTED,
            COMPLETED);

    assertEquals(ExecutionStatus.COMPLETED, result.overallStatus());
  }

  @Test
  void overallStatusIsFailedWhenAnyNodeFailed() {
    ExecutionResult result =
        new ExecutionResult(
            "exec.2",
            "plan.2",
            Map.of(
                "node.a",
                ExecutionStatus.COMPLETED,
                "node.b",
                ExecutionStatus.FAILED,
                "node.c",
                ExecutionStatus.SKIPPED),
            STARTED,
            COMPLETED);

    assertEquals(ExecutionStatus.FAILED, result.overallStatus());
  }

  @Test
  void overallStatusIsCancelledWhenAnyNodeCancelledAndNoneFailed() {
    ExecutionResult result =
        new ExecutionResult(
            "exec.3",
            "plan.3",
            Map.of(
                "node.a", ExecutionStatus.COMPLETED,
                "node.b", ExecutionStatus.CANCELLED,
                "node.c", ExecutionStatus.SKIPPED),
            STARTED,
            COMPLETED);

    assertEquals(ExecutionStatus.CANCELLED, result.overallStatus());
  }

  @Test
  void overallStatusIsSkippedWhenNotAllCompletedAndNoFailureOrCancellation() {
    ExecutionResult result =
        new ExecutionResult(
            "exec.4",
            "plan.4",
            Map.of("node.a", ExecutionStatus.COMPLETED, "node.b", ExecutionStatus.SKIPPED),
            STARTED,
            COMPLETED);

    assertEquals(ExecutionStatus.SKIPPED, result.overallStatus());
  }

  @Test
  void nodeStatusesAreImmutable() {
    ExecutionResult result =
        new ExecutionResult(
            "exec.5", "plan.5", Map.of("node.a", ExecutionStatus.COMPLETED), STARTED, COMPLETED);

    assertThrows(
        UnsupportedOperationException.class,
        () -> result.nodeStatuses().put("node.a", ExecutionStatus.FAILED));
  }

  @Test
  void rejectsBlankExecutionId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionResult(
                " ", "plan.6", Map.of("node.a", ExecutionStatus.COMPLETED), STARTED, COMPLETED));
  }

  @Test
  void rejectsNullPlanId() {
    assertThrows(
        NullPointerException.class,
        () ->
            new ExecutionResult(
                "exec.7", null, Map.of("node.a", ExecutionStatus.COMPLETED), STARTED, COMPLETED));
  }

  @Test
  void failedOverridesCancel() {
    ExecutionResult result =
        new ExecutionResult(
            "exec.8",
            "plan.8",
            Map.of(
                "node.a", ExecutionStatus.FAILED,
                "node.b", ExecutionStatus.CANCELLED),
            STARTED,
            COMPLETED);

    assertEquals(ExecutionStatus.FAILED, result.overallStatus());
  }
}
