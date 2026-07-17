package org.hammer.audio.workflow.version;

import java.util.Objects;

/** Typed semantic conflict emitted by deterministic three-way merge. */
public record WorkflowMergeConflict(
    String conflictId,
    Type type,
    String objectId,
    String field,
    String baseValue,
    String localValue,
    String remoteValue,
    String message) {

  public WorkflowMergeConflict {
    Objects.requireNonNull(conflictId, "conflictId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(objectId, "objectId");
    Objects.requireNonNull(message, "message");
  }

  public enum Type {
    PROPERTY_CHANGED_DIFFERENTLY,
    NODE_CHANGED_DIFFERENTLY,
    EDGE_CHANGED_DIFFERENTLY,
    DELETE_VS_MODIFY,
    DELETE_VS_CONNECT,
    IDENTIFIER_COLLISION
  }
}
