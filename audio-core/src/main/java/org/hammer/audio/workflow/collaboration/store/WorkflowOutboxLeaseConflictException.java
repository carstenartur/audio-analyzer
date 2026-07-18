package org.hammer.audio.workflow.collaboration.store;

import java.util.Objects;

/** Raised when an outbox acknowledgement does not own the event's current lease. */
public final class WorkflowOutboxLeaseConflictException extends RuntimeException {

  private final String conflictingEventId;
  private final String rejectedLeaseOwner;
  private final String rejectedLeaseToken;

  /** Creates a machine-readable lease conflict. */
  public WorkflowOutboxLeaseConflictException(
      String eventId, String leaseOwner, String leaseToken) {
    this(eventId, leaseOwner, leaseToken, null);
  }

  /** Creates a lease conflict while preserving the triggering persistence failure. */
  public WorkflowOutboxLeaseConflictException(
      String eventId, String leaseOwner, String leaseToken, Throwable cause) {
    super(
        "Outbox event "
            + eventId
            + " is not owned by dispatcher "
            + leaseOwner
            + " with lease "
            + leaseToken,
        cause);
    this.conflictingEventId = Objects.requireNonNull(eventId, "eventId");
    this.rejectedLeaseOwner = Objects.requireNonNull(leaseOwner, "leaseOwner");
    this.rejectedLeaseToken = Objects.requireNonNull(leaseToken, "leaseToken");
  }

  /** Returns the durable event whose lease was rejected. */
  public String eventId() {
    return conflictingEventId;
  }

  /** Returns the rejected dispatcher identifier. */
  public String leaseOwner() {
    return rejectedLeaseOwner;
  }

  /** Returns the rejected opaque lease token. */
  public String leaseToken() {
    return rejectedLeaseToken;
  }
}
