package org.hammer.audio.workflow.collaboration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;

/** Complete recoverable state of one collaboration session. */
public record WorkflowSessionState(
    String sessionId,
    CollaborationMode mode,
    OperationActor owner,
    Instant createdAt,
    Workflow initialWorkflow,
    Workflow workflow,
    List<OperationActor> participants,
    List<WorkflowOperation> operations,
    Map<String, WorkflowPresence> presence,
    List<WorkflowUndoEntry> undoEntries,
    long revision,
    long sequence) {

  public WorkflowSessionState {
    requireNotBlank(sessionId, "sessionId");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(initialWorkflow, "initialWorkflow");
    Objects.requireNonNull(workflow, "workflow");
    participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
    operations = List.copyOf(Objects.requireNonNull(operations, "operations"));
    presence = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(presence, "presence")));
    undoEntries = List.copyOf(Objects.requireNonNull(undoEntries, "undoEntries"));
    if (revision < 0 || sequence < 0) {
      throw new IllegalArgumentException("revision and sequence must be >= 0");
    }
  }

  private static String requireNotBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
