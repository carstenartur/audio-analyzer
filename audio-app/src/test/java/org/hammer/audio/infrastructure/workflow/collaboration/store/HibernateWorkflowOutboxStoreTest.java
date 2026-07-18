package org.hammer.audio.infrastructure.workflow.collaboration.store;

import static org.hammer.audio.infrastructure.workflow.collaboration.store.WorkflowOutboxStoreTestSupport.BASE_TIME;
import static org.hammer.audio.infrastructure.workflow.collaboration.store.WorkflowOutboxStoreTestSupport.appendPendingEvent;
import static org.hammer.audio.infrastructure.workflow.collaboration.store.WorkflowOutboxStoreTestSupport.inMemoryProperties;
import static org.hammer.audio.infrastructure.workflow.collaboration.store.WorkflowOutboxStoreTestSupport.provider;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import java.time.Instant;
import java.util.List;
import org.hammer.audio.workflow.collaboration.store.LeasedWorkflowOutboxEntry;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowOutboxEntry;
import org.hammer.audio.workflow.collaboration.store.WorkflowOutboxLeaseConflictException;
import org.junit.jupiter.api.Test;

class HibernateWorkflowOutboxStoreTest {

  @Test
  void claimIsBoundedAndUsesStableRetryEventOrder() {
    withStores(
        (sessionStore, outboxStore) -> {
          appendPendingEvent(sessionStore, "session.c", "event.c", BASE_TIME.plusSeconds(1));
          appendPendingEvent(sessionStore, "session.a", "event.a", BASE_TIME.plusSeconds(1));
          appendPendingEvent(sessionStore, "session.b", "event.b", BASE_TIME.plusSeconds(2));
          Instant claimedAt = BASE_TIME.plusSeconds(10);

          List<LeasedWorkflowOutboxEntry> firstBatch =
              outboxStore.claimDue("dispatcher.first", claimedAt, claimedAt.plusSeconds(30), 2);
          List<LeasedWorkflowOutboxEntry> secondBatch =
              outboxStore.claimDue("dispatcher.second", claimedAt, claimedAt.plusSeconds(30), 2);

          assertEquals(
              List.of("event.a", "event.c"),
              firstBatch.stream().map(lease -> lease.entry().eventId()).toList());
          assertEquals(1, secondBatch.size());
          assertEquals("event.b", secondBatch.getFirst().entry().eventId());
        });
  }

  @Test
  void activeLeaseIsExclusiveAndExpiredLeaseIsReclaimable() {
    withStores(
        (sessionStore, outboxStore) -> {
          appendPendingEvent(
              sessionStore, "session.lease", "event.lease", BASE_TIME.plusSeconds(1));
          Instant claimedAt = BASE_TIME.plusSeconds(10);
          LeasedWorkflowOutboxEntry first =
              outboxStore
                  .claimDue("dispatcher.one", claimedAt, claimedAt.plusSeconds(30), 10)
                  .getFirst();

          assertTrue(
              outboxStore
                  .claimDue(
                      "dispatcher.two", claimedAt.plusSeconds(1), claimedAt.plusSeconds(31), 10)
                  .isEmpty());

          LeasedWorkflowOutboxEntry reclaimed =
              outboxStore
                  .claimDue(
                      "dispatcher.two", claimedAt.plusSeconds(30), claimedAt.plusSeconds(60), 10)
                  .getFirst();
          assertNotEquals(first.leaseToken(), reclaimed.leaseToken());
          assertThrows(
              WorkflowOutboxLeaseConflictException.class,
              () ->
                  outboxStore.markPublished(
                      "event.lease", first.leaseToken(), claimedAt.plusSeconds(31)));
        });
  }

  @Test
  void failureAndSuccessTransitionsRequireCurrentLeaseAndCountAttempts() {
    withStores(
        (sessionStore, outboxStore) -> {
          appendPendingEvent(
              sessionStore, "session.retry", "event.retry", BASE_TIME.plusSeconds(1));
          Instant claimedAt = BASE_TIME.plusSeconds(10);
          LeasedWorkflowOutboxEntry first =
              outboxStore
                  .claimDue("dispatcher.retry", claimedAt, claimedAt.plusSeconds(30), 10)
                  .getFirst();
          Instant retryAt = claimedAt.plusSeconds(4);

          StoredWorkflowOutboxEntry failed =
              outboxStore.markFailed("event.retry", first.leaseToken(), retryAt);

          assertEquals(1, failed.attemptCount());
          assertTrue(failed.pending());
          assertEquals(retryAt, failed.nextAttemptAt());
          assertTrue(
              outboxStore
                  .claimDue("dispatcher.early", retryAt.minusNanos(1), retryAt.plusSeconds(30), 10)
                  .isEmpty());

          LeasedWorkflowOutboxEntry second =
              outboxStore
                  .claimDue("dispatcher.success", retryAt, retryAt.plusSeconds(30), 10)
                  .getFirst();
          StoredWorkflowOutboxEntry published =
              outboxStore.markPublished("event.retry", second.leaseToken(), retryAt.plusSeconds(1));

          assertEquals(2, published.attemptCount());
          assertFalse(published.pending());
          assertEquals(retryAt.plusSeconds(1), published.publishedAt());
          assertTrue(
              outboxStore
                  .claimDue(
                      "dispatcher.after", retryAt.plusSeconds(100), retryAt.plusSeconds(130), 10)
                  .isEmpty());
          assertEquals(published, outboxStore.find("event.retry").orElseThrow());
        });
  }

  private static void withStores(StoreScenario scenario) {
    try (HibernateSessionFactoryProvider provider = provider(inMemoryProperties())) {
      scenario.run(
          new HibernateWorkflowSessionStateStore(provider.getSessionFactory()),
          new HibernateWorkflowOutboxStore(provider.getSessionFactory()));
    }
  }

  @FunctionalInterface
  private interface StoreScenario {
    void run(
        HibernateWorkflowSessionStateStore sessionStore, HibernateWorkflowOutboxStore outboxStore);
  }
}
