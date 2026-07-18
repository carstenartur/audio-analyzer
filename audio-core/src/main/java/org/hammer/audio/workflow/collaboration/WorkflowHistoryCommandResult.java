package org.hammer.audio.workflow.collaboration;

import java.util.Objects;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationCommandMetadata;

/**
 * Result of an accepted undo or redo command.
 *
 * @param workflow resulting canonical workflow
 * @param command accepted durable command relation
 * @param operationId fresh semantic operation id generated from the command
 * @param revision resulting semantic revision
 * @param sequence resulting event sequence
 */
public record WorkflowHistoryCommandResult(
    Workflow workflow,
    WorkflowOperationCommandMetadata command,
    String operationId,
    long revision,
    long sequence) {

  public WorkflowHistoryCommandResult {
    Objects.requireNonNull(workflow, "workflow");
    Objects.requireNonNull(command, "command");
    operationId = requireNotBlank(operationId, "operationId");
    if (revision <= 0 || sequence < revision) {
      throw new IllegalArgumentException("invalid resulting revision/event sequence");
    }
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
