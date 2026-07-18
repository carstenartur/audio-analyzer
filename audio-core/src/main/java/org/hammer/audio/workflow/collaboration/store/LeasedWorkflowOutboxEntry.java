package org.hammer.audio.workflow.collaboration.store;

import java.time.Instant;
import java.util.Objects;

/**
 * One pending durable outbox event exclusively leased by a dispatcher instance.
 *
 * @param entry durable event awaiting publication
 * @param leaseOwner stable dispatcher-instance identifier
 * @param leaseToken opaque token required for acknowledgement
 * @param leaseExpiresAt time after which another dispatcher may reclaim the event
 */
public record LeasedWorkflowOutboxEntry(
    StoredWorkflowOutboxEntry entry, String leaseOwner, String leaseToken, Instant leaseExpiresAt) {

  public LeasedWorkflowOutboxEntry {
    Objects.requireNonNull(entry, "entry");
    if (!entry.pending()) {
      throw new IllegalArgumentException("Only pending outbox entries may be leased");
    }
    leaseOwner = requireNotBlank(leaseOwner, "leaseOwner");
    leaseToken = requireNotBlank(leaseToken, "leaseToken");
    Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
