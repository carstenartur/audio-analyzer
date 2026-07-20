package org.hammer.audio.workflow.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowValidator;
import org.hammer.audio.workflow.execution.WorkflowRunException.Code;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Command;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Input;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Violation;
import org.hammer.audio.workflow.execution.WorkflowRunSourceResolver.ResolvedWorkflow;

/** Compiles a resolved immutable workflow source into validated snapshot and execution plan input. */
final class WorkflowRunInputFactory {

  private static final String VALIDATION_CODE = "WORKFLOW_VALIDATION";
  private static final String CYCLE_CODE = "CYCLIC_WORKFLOW";

  private final WorkflowRunSourceResolver sourceResolver;
  private final WorkflowValidator workflowValidator = new WorkflowValidator();
  private final Clock wallClock;
  private final Supplier<String> identifierSupplier;

  WorkflowRunInputFactory(
      WorkflowRunSourceResolver sourceResolver,
      Clock wallClock,
      Supplier<String> identifierSupplier) {
    this.sourceResolver = Objects.requireNonNull(sourceResolver, "sourceResolver");
    this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
    this.identifierSupplier = Objects.requireNonNull(identifierSupplier, "identifierSupplier");
  }

  Input capture(Command command) {
    Objects.requireNonNull(command, "command");
    String runId = requireGeneratedRunId(identifierSupplier.get());
    Instant capturedAt = wallClock.instant();
    ResolvedWorkflow resolved = sourceResolver.resolve(command.source());
    List<Violation> violations = validateWorkflow(resolved.workflow(), resolved.workflowId());
    if (!violations.isEmpty()) {
      throw validationFailure(runId, command.startCommandId(), violations, null);
    }
    ExecutionSnapshot snapshot =
        ExecutionSnapshot.of(runId + ":snapshot", resolved.workflow(), capturedAt);
    ExecutionPlan plan = createPlan(runId, command.startCommandId(), snapshot);
    return new Input(
        runId,
        command.startCommandId(),
        command.source(),
        resolved.dslText(),
        fingerprint(resolved.dslText()),
        snapshot,
        plan,
        resolved.semanticRevision(),
        resolved.commitId(),
        capturedAt);
  }

  private ExecutionPlan createPlan(
      String runId, String startCommandId, ExecutionSnapshot snapshot) {
    try {
      return ExecutionPlan.of(runId + ":plan", snapshot);
    } catch (IllegalArgumentException exception) {
      throw validationFailure(
          runId,
          startCommandId,
          List.of(new Violation(CYCLE_CODE, exception.getMessage(), null)),
          exception);
    }
  }

  private List<Violation> validateWorkflow(Workflow workflow, String expectedWorkflowId) {
    List<Violation> violations = new ArrayList<>();
    if (!workflow.id().equals(expectedWorkflowId)) {
      violations.add(
          new Violation(
              VALIDATION_CODE,
              "Stored workflow id "
                  + expectedWorkflowId
                  + " does not match DSL workflow id "
                  + workflow.id(),
              null));
    }
    for (String message : workflowValidator.validate(workflow)) {
      violations.add(new Violation(VALIDATION_CODE, message, null));
    }
    return List.copyOf(violations);
  }

  private static WorkflowRunException validationFailure(
      String runId,
      String startCommandId,
      List<Violation> violations,
      IllegalArgumentException cause) {
    return new WorkflowRunException(
        Code.VALIDATION_FAILED,
        "Workflow run preflight rejected " + violations.size() + " violation(s)",
        runId,
        startCommandId,
        violations,
        cause);
  }

  private static String requireGeneratedRunId(String runId) {
    StableExecutionIds.requireStable(runId, "runId");
    return runId;
  }

  private static String fingerprint(String dslText) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(dslText.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(digest.length * 2);
      for (byte value : digest) {
        result
            .append(Character.forDigit((value >>> 4) & 0x0f, 16))
            .append(Character.forDigit(value & 0x0f, 16));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
