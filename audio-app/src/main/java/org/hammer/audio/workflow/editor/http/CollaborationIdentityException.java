package org.hammer.audio.workflow.editor.http;

/** A missing, invalid or incorrectly bound collaboration credential. */
public final class CollaborationIdentityException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public CollaborationIdentityException() {
    this(null);
  }

  public CollaborationIdentityException(Throwable cause) {
    super("A valid server-issued collaboration identity is required for this client.", cause);
  }
}
