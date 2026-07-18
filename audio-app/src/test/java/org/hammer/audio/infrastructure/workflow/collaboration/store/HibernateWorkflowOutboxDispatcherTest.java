package org.hammer.audio.infrastructure.workflow.collaboration.store;

import static org.hammer.audio.infrastructure.workflow.collaboration.store.WorkflowOutboxStoreTestSupport.BASE_TIME;
import static org.hammer.audio.infrastructure.workflow.collaboration.store.WorkflowOutboxStoreTestSupport.appendPendingEvent;
import static org.hammer.audio.infrastructure.workflow.collaboration.store.WorkflowOutboxStoreTestSupport.inMemoryProperties;
import static org.hammer.audio.infrastructure.workflow.collaboration.store.WorkflowOutboxStoreTestSupport.provider;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.hammer.audio.workflow.collaboration.outbox.WorkflowOutboxBackoffPolicy;
import org.hammer.audio.workflow.collaboration.outbox.WorkflowOutboxDispatchException;
import org.hammer.audio.workflow.collaboration.outbox.WorkflowOutboxDispatcher;
import org.hammer.audio.workflow.collaboration.outbox.WorkflowOutboxDispatcher.DispatchBatchResult;
import org.hammer.audio.workflow.collaboration.outbox.WorkflowOutboxDispatcherSettings;
import org.hammer.audio.workflow.collaboration.outbox.WorkflowOutboxMessage;
import org.hammer.audio.workflow.collaboration.outbox.WorkflowOutboxPublisher;
import org.hammer.audio.workflow.collaboration.store.LeasedWorkflowOutboxEntry;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowOutboxEntry;
import org.hammer.audio.workflow.collaboration.store.WorkflowOutboxStore;
import org.junit.jupiter.api.Test;

class HibernateWorkflowOutboxDispatcherTest {

  @Test
  void publicationFailureRetriesWithTheSameStableEventId() {
    withStores(
        (sessionStore, outboxStore) -> {
          appendPendingEvent(
              sessionStore, "session.dispatch", "event.dispatch", BASE_TIME.plusSeconds(1));
          List<WorkflowOutboxMessage> published = new ArrayList<>();
          WorkflowOutboxPublisher unavailable =
              message -> {
                throw new IllegalStateException("broker unavailable");
              };

          DispatchBatchResult failed =
              dispatcher(outboxStore, unavailable, BASE_TIME.plusSeconds(10), "dispatcher.one")
                  .dispatchBatch();

          assertEquals(List.of("event.dispatch"), failed.failedEventIds());
          StoredWorkflowOutboxEntry afterFailure = outboxStore.find("event.dispatch").orElseThrow();
          assertEquals(1, afterFailure.attemptCount());
          assertEquals(BASE_TIME.plusSeconds(12), afterFailure.nextAttemptAt());

          DispatchBatchResult succeeded =
              dispatcher(outboxStore, published::add, BASE_TIME.plusSeconds(12), "dispatcher.two")
                  .dispatchBatch();

          assertEquals(List.of("event.dispatch"), succeeded.publishedEventIds());
          assertEquals(1, published.size());
          assertEquals("event.dispatch", published.getFirst().eventId());
          assertEquals(2, outboxStore.find("event.dispatch").orElseThrow().attemptCount());
        });
  }

  @Test
  void secondDispatcherCannotPublishAnActivelyLeasedEvent() {
    withStores(
        (sessionStore, outboxStore) -> {
          appendPendingEvent(
              sessionStore, "session.concurrent", "event.concurrent", BASE_TIME.plusSeconds(1));
          CountDownLatch publicationStarted = new CountDownLatch(1);
          CountDownLatch releasePublication = new CountDownLatch(1);
          List<WorkflowOutboxMessage> messages = Collections.synchronizedList(new ArrayList<>());
          WorkflowOutboxPublisher blockingPublisher =
              message -> {
                messages.add(message);
                publicationStarted.countDown();
                await(releasePublication);
              };
          ExecutorService executor = Executors.newSingleThreadExecutor();
          try {
            Future<DispatchBatchResult> first =
                executor.submit(
                    () ->
                        dispatcher(
                                outboxStore,
                                blockingPublisher,
                                BASE_TIME.plusSeconds(10),
                                "dispatcher.first")
                            .dispatchBatch());
            await(publicationStarted);

            DispatchBatchResult competing =
                dispatcher(
                        outboxStore, messages::add, BASE_TIME.plusSeconds(11), "dispatcher.second")
                    .dispatchBatch();

            assertEquals(0, competing.claimedCount());
            releasePublication.countDown();
            assertEquals(List.of("event.concurrent"), get(first).publishedEventIds());
            assertEquals(1, messages.size());
          } finally {
            releasePublication.countDown();
            executor.shutdownNow();
          }
        });
  }

  @Test
  void crashAfterPublishBeforeAcknowledgementMayDuplicateTheSameEventId() {
    withStores(
        (sessionStore, outboxStore) -> {
          appendPendingEvent(
              sessionStore, "session.crash", "event.crash", BASE_TIME.plusSeconds(1));
          List<WorkflowOutboxMessage> messages = new ArrayList<>();
          WorkflowOutboxStore failingAcknowledgement = new FailingAcknowledgementStore(outboxStore);

          WorkflowOutboxDispatchException failure =
              assertThrows(
                  WorkflowOutboxDispatchException.class,
                  () ->
                      dispatcher(
                              failingAcknowledgement,
                              messages::add,
                              BASE_TIME.plusSeconds(10),
                              "dispatcher.crashed")
                          .dispatchBatch());
          assertEquals("event.crash", failure.eventId());
          assertEquals(
              "simulated crash before outbox acknowledgement", failure.getCause().getMessage());
          assertEquals(1, messages.size());
          assertEquals(0, outboxStore.find("event.crash").orElseThrow().attemptCount());

          DispatchBatchResult recovered =
              dispatcher(
                      outboxStore, messages::add, BASE_TIME.plusSeconds(41), "dispatcher.restarted")
                  .dispatchBatch();

          assertEquals(List.of("event.crash"), recovered.publishedEventIds());
          assertEquals(2, messages.size());
          assertTrue(
              messages.stream().allMatch(message -> message.eventId().equals("event.crash")));
        });
  }

  private static WorkflowOutboxDispatcher dispatcher(
      WorkflowOutboxStore outboxStore,
      WorkflowOutboxPublisher publisher,
      Instant now,
      String dispatcherId) {
    return new WorkflowOutboxDispatcher(
        outboxStore,
        publisher,
        Clock.fixed(now, ZoneOffset.UTC),
        new WorkflowOutboxDispatcherSettings(
            dispatcherId,
            10,
            Duration.ofSeconds(30),
            new WorkflowOutboxBackoffPolicy(Duration.ofSeconds(2), Duration.ofSeconds(8))));
  }

  private static void withStores(StoreScenario scenario) {
    try (HibernateSessionFactoryProvider provider = provider(inMemoryProperties())) {
      scenario.run(
          new HibernateWorkflowSessionStateStore(provider.getSessionFactory()),
          new HibernateWorkflowOutboxStore(provider.getSessionFactory()));
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(10, TimeUnit.SECONDS), "Outbox dispatcher latch timed out");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while coordinating outbox dispatch", exception);
    }
  }

  private static DispatchBatchResult get(Future<DispatchBatchResult> future) {
    try {
      return future.get(20, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for outbox dispatch", exception);
    } catch (ExecutionException | TimeoutException exception) {
      throw new AssertionError("Outbox dispatch did not complete successfully", exception);
    }
  }

  private record FailingAcknowledgementStore(WorkflowOutboxStore delegate)
      implements WorkflowOutboxStore {

    FailingAcknowledgementStore {
      java.util.Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Optional<StoredWorkflowOutboxEntry> find(String eventId) {
      return delegate.find(eventId);
    }

    @Override
    public List<LeasedWorkflowOutboxEntry> claimDue(
        String leaseOwner, Instant claimedAt, Instant leaseExpiresAt, int limit) {
      return delegate.claimDue(leaseOwner, claimedAt, leaseExpiresAt, limit);
    }

    @Override
    public StoredWorkflowOutboxEntry markPublished(
        String eventId, String leaseOwner, String leaseToken, Instant publishedAt) {
      throw new IllegalStateException("simulated crash before outbox acknowledgement");
    }

    @Override
    public StoredWorkflowOutboxEntry markFailed(
        String eventId, String leaseOwner, String leaseToken, Instant nextAttemptAt) {
      return delegate.markFailed(eventId, leaseOwner, leaseToken, nextAttemptAt);
    }
  }

  @FunctionalInterface
  private interface StoreScenario {
    void run(
        HibernateWorkflowSessionStateStore sessionStore, HibernateWorkflowOutboxStore outboxStore);
  }
}
