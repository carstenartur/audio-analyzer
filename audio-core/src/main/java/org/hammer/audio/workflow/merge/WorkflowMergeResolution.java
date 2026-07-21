package org.hammer.audio.workflow.merge;

import java.util.Objects;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.Resolution;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.ResolutionChoice;

/**
 * Framework-independent auditable decision for one semantic workflow merge conflict.
 *
 * @param conflictId exact deterministic conflict identity returned by the preview
 * @param choice selected base/local/remote/delete/custom decision
 * @param customValue explicit merged scalar value when {@code choice == CUSTOM}
 */
public record WorkflowMergeResolution(
    String conflictId, ResolutionChoice choice, String customValue) {

  public WorkflowMergeResolution {
    if (conflictId == null || conflictId.isBlank()) {
      throw new IllegalArgumentException("conflictId must not be blank");
    }
    Objects.requireNonNull(choice, "choice");
    if (choice == ResolutionChoice.CUSTOM) {
      Objects.requireNonNull(customValue, "customValue");
    } else if (customValue != null) {
      throw new IllegalArgumentException("customValue is only valid for CUSTOM resolutions");
    }
  }

  /** Creates a decision without a custom scalar value. */
  public WorkflowMergeResolution(String conflictId, ResolutionChoice choice) {
    this(conflictId, choice, null);
  }

  Resolution toDomainResolution() {
    return new Resolution(conflictId, choice, customValue);
  }
}
