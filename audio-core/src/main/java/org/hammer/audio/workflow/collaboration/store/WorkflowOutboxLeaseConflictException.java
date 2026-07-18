package org.hammer.audio.workflow.collaboration.store;

import java.util.Objects;

/** Raised when an outbox acknowledgement does not own the event's current lease. */
public final class WorkflowOutboxLeaseConflictException extends RuntimeException {

  private final String conflictingEventId;
  private final String rejectedLeaseToken;

  /** Creates a machine-readable lease conflict. */
  public WorkflowOutboxLeaseConflictException(String eventId, String leaseToken) {
    this(eventId, leaseToken, null);
  }

  /** Creates a lease conflict while preserving the triggering persistence failure. */
  public WorkflowOutboxLeaseConflictException(String eventId, String leaseToken, Throwable cause) {
    super("Outbox event " + eventId + " is not owned by lease " + leaseToken, cause);
    this.conflictingEventId = Objects.requireNonNull(eventId, "eventId");
    this.rejectedLeaseToken = Objects.requireNonNull(leaseToken, "leaseToken");
  }

  /** Returns the durable event whose lease was rejected. */
  public String eventId() {
    return conflictingEventId;
  }

  /** Returns the rejected opaque lease token. */
  public String leaseToken() {
    return rejectedLeaseToken;
  }
}
