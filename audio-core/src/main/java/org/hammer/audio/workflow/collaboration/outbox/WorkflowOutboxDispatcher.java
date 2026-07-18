package org.hammer.audio.workflow.collaboration.outbox;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.collaboration.store.LeasedWorkflowOutboxEntry;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowOutboxEntry;
import org.hammer.audio.workflow.collaboration.store.WorkflowOutboxStore;

/** Publishes bounded batches from the durable collaboration outbox with leased retries. */
public final class WorkflowOutboxDispatcher {

  private final WorkflowOutboxStore outboxStore;
  private final WorkflowOutboxPublisher publisher;
  private final Clock clock;
  private final WorkflowOutboxDispatcherSettings settings;

  /** Creates a dispatcher whose timing, bounds and publisher are fully injectable. */
  public WorkflowOutboxDispatcher(
      WorkflowOutboxStore outboxStore,
      WorkflowOutboxPublisher publisher,
      Clock clock,
      WorkflowOutboxDispatcherSettings settings) {
    this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore");
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  /** Claims and processes at most one configured batch. */
  public DispatchBatchResult dispatchBatch() {
    Instant claimedAt = clock.instant();
    List<LeasedWorkflowOutboxEntry> leases =
        outboxStore.claimDue(
            settings.dispatcherId(),
            claimedAt,
            claimedAt.plus(settings.leaseDuration()),
            settings.batchSize());
    List<String> published = new ArrayList<>();
    List<String> failed = new ArrayList<>();
    for (LeasedWorkflowOutboxEntry lease : leases) {
      dispatch(lease, published, failed);
    }
    return new DispatchBatchResult(leases.size(), published, failed);
  }

  private void dispatch(
      LeasedWorkflowOutboxEntry lease, List<String> published, List<String> failed) {
    StoredWorkflowOutboxEntry entry = lease.entry();
    try {
      publisher.publish(WorkflowOutboxMessage.from(entry));
    } catch (RuntimeException publicationFailure) {
      markFailed(lease, publicationFailure);
      failed.add(entry.eventId());
      return;
    }
    try {
      outboxStore.markPublished(entry.eventId(), lease.leaseToken(), clock.instant());
    } catch (RuntimeException acknowledgementFailure) {
      throw new WorkflowOutboxDispatchException(
          entry.eventId(),
          "Published outbox event could not be acknowledged durably: " + entry.eventId(),
          acknowledgementFailure);
    }
    published.add(entry.eventId());
  }

  private void markFailed(LeasedWorkflowOutboxEntry lease, RuntimeException publicationFailure) {
    StoredWorkflowOutboxEntry entry = lease.entry();
    Instant failedAt = clock.instant();
    int failedAttempt = Math.addExact(entry.attemptCount(), 1);
    Instant nextAttemptAt =
        failedAt.plus(settings.backoffPolicy().delayAfterFailure(failedAttempt));
    try {
      outboxStore.markFailed(entry.eventId(), lease.leaseToken(), nextAttemptAt);
    } catch (RuntimeException persistenceFailure) {
      WorkflowOutboxDispatchException dispatchFailure =
          new WorkflowOutboxDispatchException(
              entry.eventId(),
              "Failed outbox publication could not be scheduled for retry: " + entry.eventId(),
              persistenceFailure);
      dispatchFailure.addSuppressed(publicationFailure);
      throw dispatchFailure;
    }
  }

  /**
   * Immutable summary of one bounded dispatch pass.
   *
   * @param claimedCount number of entries leased for this pass
   * @param publishedEventIds stable identifiers acknowledged as published
   * @param failedEventIds stable identifiers scheduled for retry
   */
  public record DispatchBatchResult(
      int claimedCount, List<String> publishedEventIds, List<String> failedEventIds) {

    public DispatchBatchResult {
      if (claimedCount < 0) {
        throw new IllegalArgumentException("claimedCount must be >= 0");
      }
      publishedEventIds =
          List.copyOf(Objects.requireNonNull(publishedEventIds, "publishedEventIds"));
      failedEventIds = List.copyOf(Objects.requireNonNull(failedEventIds, "failedEventIds"));
      if (claimedCount != publishedEventIds.size() + failedEventIds.size()) {
        throw new IllegalArgumentException("Every claimed event must have one outcome");
      }
    }
  }
}
