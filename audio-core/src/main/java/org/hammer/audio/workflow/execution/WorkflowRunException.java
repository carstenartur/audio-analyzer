package org.hammer.audio.workflow.execution;

import java.io.Serial;
import java.util.List;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Violation;

/** Typed workflow-run failure suitable for stable HTTP problem mapping. */
public final class WorkflowRunException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Stable machine-readable failure code. */
  public enum Code {
    DUPLICATE_START_COMMAND,
    SOURCE_UNAVAILABLE,
    UNKNOWN_RUN,
    VALIDATION_FAILED,
    UNSUPPORTED_NODE,
    RESULT_NOT_AVAILABLE,
    ILLEGAL_TRANSITION,
    BACKEND_FAILURE
  }

  private final Code errorCode;
  private final String affectedRunId;
  private final String commandId;
  private final Violation[] violationDetails;

  /** Creates a typed failure without nested cause. */
  public WorkflowRunException(
      Code code, String message, String runId, String startCommandId, List<Violation> violations) {
    this(code, message, runId, startCommandId, violations, null);
  }

  /** Creates a typed failure preserving a backend or source cause. */
  public WorkflowRunException(
      Code code,
      String message,
      String runId,
      String startCommandId,
      List<Violation> violations,
      Throwable cause) {
    super(message, cause);
    this.errorCode = java.util.Objects.requireNonNull(code, "code");
    this.affectedRunId = runId;
    this.commandId = startCommandId;
    this.violationDetails =
        (violations == null ? List.<Violation>of() : violations).toArray(Violation[]::new);
  }

  public Code code() {
    return errorCode;
  }

  public String runId() {
    return affectedRunId;
  }

  public String startCommandId() {
    return commandId;
  }

  public List<Violation> violations() {
    return List.of(violationDetails.clone());
  }
}
