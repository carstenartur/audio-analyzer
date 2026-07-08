package org.hammer.audio.workflow.dsl;

/**
 * Thrown by {@link WorkflowDslParser} when the DSL text is malformed.
 *
 * <p>Owned by the DSL layer.
 */
public final class WorkflowDslParseException extends RuntimeException {

  public WorkflowDslParseException(String message) {
    super(message);
  }

  public WorkflowDslParseException(String message, Throwable cause) {
    super(message, cause);
  }
}
