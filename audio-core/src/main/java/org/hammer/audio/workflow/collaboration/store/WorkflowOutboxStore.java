package org.hammer.audio.workflow.collaboration.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Framework-independent persistence boundary for leased durable outbox delivery. */
public interface WorkflowOutboxStore {

  /** Finds an outbox entry by its stable event identifier. */
  Optional<StoredWorkflowOutboxEntry> find(String eventId);

  /**
   * Atomically leases a bounded due batch in stable retry and event order.
   *
   * <p>Expired leases are reclaimable. Every returned entry carries an opaque lease token required
   * by the acknowledgement methods.
   */
  List<LeasedWorkflowOutboxEntry> claimDue(
      String leaseOwner, Instant claimedAt, Instant leaseExpiresAt, int limit);

  /** Marks a leased event published in a separate transaction and clears its lease. */
  StoredWorkflowOutboxEntry markPublished(String eventId, String leaseToken, Instant publishedAt);

  /** Records a failed publication attempt, schedules its retry and clears its lease. */
  StoredWorkflowOutboxEntry markFailed(String eventId, String leaseToken, Instant nextAttemptAt);
}
