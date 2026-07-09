package org.hammer.audio.workflow.editor;

import java.util.List;
import java.util.Objects;

/**
 * Thrown when a {@link org.hammer.audio.workflow.WorkflowOperation} produces a structurally invalid
 * workflow.
 *
 * <p>The {@link #violations()} list contains human-readable descriptions of every constraint
 * violation detected by {@link org.hammer.audio.workflow.WorkflowValidator}. Callers (e.g. an HTTP
 * adapter) should translate this into a 422 response body.
 */
public final class WorkflowOperationRejectedException extends RuntimeException {

  private final List<String> violationMessages;

  /**
   * Creates a new exception with the given validation violations.
   *
   * @param violations non-empty list of violation messages from {@code WorkflowValidator}
   */
  public WorkflowOperationRejectedException(List<String> violations) {
    super("Operation rejected: " + Objects.requireNonNull(violations, "violations"));
    this.violationMessages = List.copyOf(violations);
  }

  /**
   * Returns the list of constraint violations that caused the rejection.
   *
   * @return immutable, non-empty list of violation messages
   */
  public List<String> violations() {
    return violationMessages;
  }
}
