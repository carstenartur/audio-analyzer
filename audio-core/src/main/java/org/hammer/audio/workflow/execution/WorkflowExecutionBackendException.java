package org.hammer.audio.workflow.execution;

import java.io.Serial;

/** Checked boundary failure raised by a replaceable workflow execution backend. */
public final class WorkflowExecutionBackendException extends Exception {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates a backend failure with a stable diagnostic and underlying cause. */
  public WorkflowExecutionBackendException(String message, Throwable cause) {
    super(message, cause);
  }
}
