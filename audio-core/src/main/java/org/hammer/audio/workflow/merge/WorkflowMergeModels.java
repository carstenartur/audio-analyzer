package org.hammer.audio.workflow.merge;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.hammer.audio.workflow.Workflow;

/** Framework-independent contracts for deterministic three-way workflow merge. */
public interface WorkflowMergeModels {

  /** Semantic workflow element owning a conflicted value. */
  enum ElementKind {
    WORKFLOW,
    NODE,
    EDGE
  }

  /** Typed semantic conflict classification. */
  enum ConflictKind {
    DIVERGENT_VALUE,
    STABLE_ID_COLLISION,
    DELETE_MODIFY,
    DELETE_CONNECT,
    DIVERGENT_EDGE_ENDPOINTS
  }

  /** Explicit user choice resolving one semantic conflict. */
  enum ResolutionChoice {
    BASE,
    LOCAL,
    REMOTE,
    DELETE,
    CUSTOM
  }

  /**
   * One ordered semantic merge conflict.
   *
   * @param conflictId deterministic identity derived from semantic location and kind
   * @param kind typed conflict classification
   * @param elementKind kind of owning workflow element
   * @param elementId stable workflow, node or edge identifier
   * @param fieldPath stable semantic field path
   * @param baseValue canonical base value, if present
   * @param localValue canonical local value, if present
   * @param remoteValue canonical remote value, if present
   * @param allowedChoices immutable supported resolution choices
   */
  record Conflict(
      String conflictId,
      ConflictKind kind,
      ElementKind elementKind,
      String elementId,
      String fieldPath,
      String baseValue,
      String localValue,
      String remoteValue,
      Set<ResolutionChoice> allowedChoices) {

    /** Stable ordering used by APIs, tests and user interfaces. */
    public static final Comparator<Conflict> ORDERING =
        Comparator.comparing(Conflict::elementKind)
            .thenComparing(Conflict::elementId)
            .thenComparing(Conflict::fieldPath)
            .thenComparing(Conflict::kind)
            .thenComparing(Conflict::conflictId);

    public Conflict {
      requireText(conflictId, "conflictId");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(elementKind, "elementKind");
      requireText(elementId, "elementId");
      requireText(fieldPath, "fieldPath");
      allowedChoices = Set.copyOf(Objects.requireNonNull(allowedChoices, "allowedChoices"));
      if (allowedChoices.isEmpty()) {
        throw new IllegalArgumentException("allowedChoices must not be empty");
      }
    }
  }

  /**
   * One explicit conflict resolution.
   *
   * @param conflictId exact conflict identity returned by the preview
   * @param choice selected resolution choice
   * @param customValue explicit merged scalar value for {@link ResolutionChoice#CUSTOM}
   */
  record Resolution(String conflictId, ResolutionChoice choice, String customValue) {
    public Resolution {
      requireText(conflictId, "conflictId");
      Objects.requireNonNull(choice, "choice");
      if (choice == ResolutionChoice.CUSTOM) {
        Objects.requireNonNull(customValue, "customValue");
      } else if (customValue != null) {
        throw new IllegalArgumentException("customValue is only valid for CUSTOM resolutions");
      }
    }

    /** Creates a side- or delete-based resolution without a custom scalar value. */
    public Resolution(String conflictId, ResolutionChoice choice) {
      this(conflictId, choice, null);
    }
  }

  /**
   * Initial deterministic merge preview.
   *
   * @param autoMergedWorkflow workflow containing every non-conflicting automatic decision
   * @param conflicts unresolved semantic conflicts in stable order
   * @param validationViolations structural violations caused by the combined automatic decisions
   */
  record Preview(
      Workflow autoMergedWorkflow, List<Conflict> conflicts, List<String> validationViolations) {
    public Preview {
      Objects.requireNonNull(autoMergedWorkflow, "autoMergedWorkflow");
      conflicts = List.copyOf(Objects.requireNonNull(conflicts, "conflicts"));
      validationViolations =
          List.copyOf(Objects.requireNonNull(validationViolations, "validationViolations"));
      if (!conflicts.equals(conflicts.stream().sorted(Conflict.ORDERING).toList())) {
        throw new IllegalArgumentException("conflicts must use stable semantic ordering");
      }
    }

    /** Returns whether no explicit field resolution remains. */
    public boolean conflictFree() {
      return conflicts.isEmpty();
    }

    /** Returns whether the automatic result may be serialized and committed directly. */
    public boolean readyToCommit() {
      return conflicts.isEmpty() && validationViolations.isEmpty();
    }
  }

  /**
   * Result after applying zero or more explicit resolutions.
   *
   * @param workflow deterministic candidate workflow
   * @param unresolvedConflicts conflicts without a valid explicit decision
   * @param validationViolations structural workflow validation diagnostics
   */
  record Result(
      Workflow workflow, List<Conflict> unresolvedConflicts, List<String> validationViolations) {
    public Result {
      Objects.requireNonNull(workflow, "workflow");
      unresolvedConflicts =
          List.copyOf(Objects.requireNonNull(unresolvedConflicts, "unresolvedConflicts"));
      validationViolations =
          List.copyOf(Objects.requireNonNull(validationViolations, "validationViolations"));
    }

    /** Returns whether the candidate may be serialized and committed. */
    public boolean readyToCommit() {
      return unresolvedConflicts.isEmpty() && validationViolations.isEmpty();
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
