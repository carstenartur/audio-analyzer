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

  private final Code code;
  private final String runId;
  private final String startCommandId;
  private final transient List<Violation> violations;

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
    this.code = java.util.Objects.requireNonNull(code, "code");
    this.runId = runId;
    this.startCommandId = startCommandId;
    this.violations = List.copyOf(violations == null ? List.of() : violations);
  }

  public Code code() {
    return code;
  }

  public String runId() {
    return runId;
  }

  public String startCommandId() {
    return startCommandId;
  }

  public List<Violation> violations() {
    return violations == null ? List.of() : violations;
  }
}
