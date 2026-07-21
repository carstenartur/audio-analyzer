package org.hammer.audio.workflow.history;

import java.io.Serial;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.Conflict;

/** Raised when unresolved semantic conflicts or validator violations prevent a merge checkpoint. */
public final class WorkflowMergeRejectedException extends IllegalArgumentException {

  @Serial private static final long serialVersionUID = 1L;

  private final List<Conflict> conflictDetails;
  private final List<String> validatorMessages;

  /** Creates a structured rejection from the deterministic merge result. */
  public WorkflowMergeRejectedException(
      List<Conflict> unresolvedConflicts, List<String> validationViolations) {
    super(message(unresolvedConflicts, validationViolations));
    conflictDetails =
        List.copyOf(Objects.requireNonNull(unresolvedConflicts, "unresolvedConflicts"));
    validatorMessages =
        List.copyOf(Objects.requireNonNull(validationViolations, "validationViolations"));
    if (conflictDetails.isEmpty() && validatorMessages.isEmpty()) {
      throw new IllegalArgumentException("A merge rejection requires conflicts or violations");
    }
  }

  /** Returns deterministic unresolved semantic conflicts. */
  public List<Conflict> unresolvedConflicts() {
    return conflictDetails;
  }

  /** Returns structural validator diagnostics. */
  public List<String> validationViolations() {
    return validatorMessages;
  }

  private static String message(
      List<Conflict> unresolvedConflicts, List<String> validationViolations) {
    int conflicts = Objects.requireNonNull(unresolvedConflicts, "unresolvedConflicts").size();
    int violations = Objects.requireNonNull(validationViolations, "validationViolations").size();
    return "Workflow merge is not commit-ready: "
        + conflicts
        + " unresolved conflict(s), "
        + violations
        + " validation violation(s)";
  }
}
