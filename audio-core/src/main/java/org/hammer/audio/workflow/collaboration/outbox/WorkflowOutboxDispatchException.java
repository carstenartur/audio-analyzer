package org.hammer.audio.workflow.collaboration.outbox;

import java.util.Objects;

/** Raised when a published or failed outbox attempt cannot be acknowledged durably. */
public final class WorkflowOutboxDispatchException extends RuntimeException {

  private final String failedEventId;

  /** Creates an event-specific dispatch failure while preserving its persistence cause. */
  public WorkflowOutboxDispatchException(String eventId, String message, Throwable cause) {
    super(message, cause);
    this.failedEventId = Objects.requireNonNull(eventId, "eventId");
  }

  /** Returns the durable event whose dispatch state could not be recorded. */
  public String eventId() {
    return failedEventId;
  }
}
