package org.hammer.audio.infrastructure.workflow.collaboration.store;

import static org.hammer.audio.infrastructure.workflow.collaboration.store.WorkflowOutboxStoreTestSupport.BASE_TIME;
import static org.hammer.audio.infrastructure.workflow.collaboration.store.WorkflowOutboxStoreTestSupport.appendPendingEvent;
import static org.hammer.audio.infrastructure.workflow.collaboration.store.WorkflowOutboxStoreTestSupport.fileProperties;
import static org.hammer.audio.infrastructure.workflow.collaboration.store.WorkflowOutboxStoreTestSupport.inMemoryProperties;
import static org.hammer.audio.infrastructure.workflow.collaboration.store.WorkflowOutboxStoreTestSupport.provider;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionDeletionResult;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionPlan;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionService;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionSettings;
import org.hammer.audio.workflow.collaboration.store.LeasedWorkflowOutboxEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HibernateWorkflowOutboxRetentionStoreTest {

  private static final Instant PLANNED_AT = BASE_TIME.plus(Duration.ofDays(60));
  private static final Instant CUTOFF = PLANNED_AT.minus(Duration.ofDays(30));

  @Test
  void reportAndDeleteProtectEveryNonPublishedOrUncertainRow() {
    try (HibernateSessionFactoryProvider provider = provider(inMemoryProperties())) {
      HibernateWorkflowSessionStateStore sessionStore =
          new HibernateWorkflowSessionStateStore(provider.getSessionFactory());
      HibernateWorkflowOutboxStore outboxStore =
          new HibernateWorkflowOutboxStore(provider.getSessionFactory());
      HibernateWorkflowOutboxRetentionStore retentionStore =
          new HibernateWorkflowOutboxRetentionStore(provider.getSessionFactory());

      appendAndPublish(
          sessionStore, outboxStore, "session.old", "event.old", BASE_TIME.plusSeconds(1), CUTOFF);
      appendAndPublish(
          sessionStore,
          outboxStore,
          "session.recent",
          "event.recent",
          BASE_TIME.plusSeconds(2),
          CUTOFF.plusSeconds(1));
      appendPendingEvent(sessionStore, "session.leased", "event.leased", BASE_TIME.plusSeconds(3));
      LeasedWorkflowOutboxEntry activeLease =
          outboxStore
              .claimDue(
                  "dispatcher.active", PLANNED_AT.minusSeconds(1), PLANNED_AT.plusSeconds(60), 1)
              .getFirst();
      assertEquals("event.leased", activeLease.entry().eventId());
      appendPendingEvent(
          sessionStore, "session.pending", "event.pending", BASE_TIME.plusSeconds(4));

      WorkflowOutboxRetentionService service =
          new WorkflowOutboxRetentionService(
              retentionStore,
              Clock.fixed(PLANNED_AT, ZoneOffset.UTC),
              new WorkflowOutboxRetentionSettings(Duration.ofDays(30), 10));
      WorkflowOutboxRetentionPlan plan = service.plan();

      assertEquals(2, plan.scannedCount());
      assertEquals(List.of("event.old"), plan.candidateEventIds());
      assertEquals(CUTOFF, plan.oldestPublishedAt().orElseThrow());
      assertEquals(CUTOFF, plan.newestPublishedAt().orElseThrow());

      WorkflowOutboxRetentionDeletionResult deleted = service.delete(plan);

      assertEquals(List.of("event.old"), deleted.deletedEventIds());
      assertTrue(outboxStore.find("event.old").isEmpty());
      assertTrue(outboxStore.find("event.recent").isPresent());
      assertTrue(outboxStore.find("event.leased").isPresent());
      assertTrue(outboxStore.find("event.pending").isPresent());
      assertFalse(outboxStore.find("event.recent").orElseThrow().pending());
      assertTrue(outboxStore.find("event.pending").orElseThrow().pending());
    }
  }

  @Test
  void twoStoresDeletingTheSamePlanConvergeWithoutFailure() {
    try (HibernateSessionFactoryProvider provider = provider(inMemoryProperties())) {
      HibernateWorkflowSessionStateStore sessionStore =
          new HibernateWorkflowSessionStateStore(provider.getSessionFactory());
      HibernateWorkflowOutboxStore outboxStore =
          new HibernateWorkflowOutboxStore(provider.getSessionFactory());
      HibernateWorkflowOutboxRetentionStore firstStore =
          new HibernateWorkflowOutboxRetentionStore(provider.getSessionFactory());
      HibernateWorkflowOutboxRetentionStore secondStore =
          new HibernateWorkflowOutboxRetentionStore(provider.getSessionFactory());
      appendAndPublish(
          sessionStore,
          outboxStore,
          "session.once",
          "event.once",
          BASE_TIME.plusSeconds(1),
          CUTOFF.minusSeconds(1));

      WorkflowOutboxRetentionPlan plan =
          new WorkflowOutboxRetentionService(
                  firstStore,
                  Clock.fixed(PLANNED_AT, ZoneOffset.UTC),
                  new WorkflowOutboxRetentionSettings(Duration.ofDays(30), 10))
              .plan();
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch start = new CountDownLatch(1);
      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        Future<WorkflowOutboxRetentionDeletionResult> first =
            executor.submit(() -> deleteTogether(firstStore, plan, ready, start));
        Future<WorkflowOutboxRetentionDeletionResult> second =
            executor.submit(() -> deleteTogether(secondStore, plan, ready, start));
        assertTrue(ready.await(10, TimeUnit.SECONDS), "Retention stores did not become ready");
        start.countDown();

        List<WorkflowOutboxRetentionDeletionResult> results =
            List.of(get(first), get(second));
        assertEquals(1, results.stream().mapToInt(result -> result.deletedCount()).sum());
        assertEquals(1, results.stream().mapToInt(result -> result.skippedCount()).sum());
        assertTrue(outboxStore.find("event.once").isEmpty());
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while coordinating retention stores", exception);
      } finally {
        start.countDown();
        executor.shutdownNow();
      }
    }
  }

  @Test
  void cleanupPreservesPendingWorkAcrossCompleteRestart(@TempDir Path temporaryDirectory) {
    Path databasePath = temporaryDirectory.resolve("retention-restart");
    try (HibernateSessionFactoryProvider provider = provider(fileProperties(databasePath))) {
      HibernateWorkflowSessionStateStore sessionStore =
          new HibernateWorkflowSessionStateStore(provider.getSessionFactory());
      HibernateWorkflowOutboxStore outboxStore =
          new HibernateWorkflowOutboxStore(provider.getSessionFactory());
      appendAndPublish(
          sessionStore,
          outboxStore,
          "session.restart.old",
          "event.restart.old",
          BASE_TIME.plusSeconds(1),
          CUTOFF.minusSeconds(1));
      appendPendingEvent(
          sessionStore,
          "session.restart.pending",
          "event.restart.pending",
          BASE_TIME.plusSeconds(2));
    }

    try (HibernateSessionFactoryProvider provider = provider(fileProperties(databasePath))) {
      HibernateWorkflowOutboxRetentionStore retentionStore =
          new HibernateWorkflowOutboxRetentionStore(provider.getSessionFactory());
      WorkflowOutboxRetentionService service =
          new WorkflowOutboxRetentionService(
              retentionStore,
              Clock.fixed(PLANNED_AT, ZoneOffset.UTC),
              new WorkflowOutboxRetentionSettings(Duration.ofDays(30), 10));

      WorkflowOutboxRetentionPlan plan = service.plan();
      WorkflowOutboxRetentionDeletionResult deleted = service.delete(plan);

      assertEquals(List.of("event.restart.old"), deleted.deletedEventIds());
    }

    try (HibernateSessionFactoryProvider provider = provider(fileProperties(databasePath))) {
      HibernateWorkflowOutboxStore outboxStore =
          new HibernateWorkflowOutboxStore(provider.getSessionFactory());
      assertTrue(outboxStore.find("event.restart.old").isEmpty());
      assertTrue(outboxStore.find("event.restart.pending").orElseThrow().pending());
    }
  }

  private static WorkflowOutboxRetentionDeletionResult deleteTogether(
      HibernateWorkflowOutboxRetentionStore store,
      WorkflowOutboxRetentionPlan plan,
      CountDownLatch ready,
      CountDownLatch start) {
    ready.countDown();
    await(start);
    return store.deletePublished(plan);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(10, TimeUnit.SECONDS), "Retention coordination timed out");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while coordinating retention cleanup", exception);
    }
  }

  private static WorkflowOutboxRetentionDeletionResult get(
      Future<WorkflowOutboxRetentionDeletionResult> future) {
    try {
      return future.get(20, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for retention cleanup", exception);
    } catch (ExecutionException | TimeoutException exception) {
      throw new AssertionError("Retention cleanup did not complete successfully", exception);
    }
  }

  private static void appendAndPublish(
      HibernateWorkflowSessionStateStore sessionStore,
      HibernateWorkflowOutboxStore outboxStore,
      String sessionId,
      String eventId,
      Instant occurredAt,
      Instant publishedAt) {
    appendPendingEvent(sessionStore, sessionId, eventId, occurredAt);
    Instant claimedAt = occurredAt.plusSeconds(1);
    LeasedWorkflowOutboxEntry lease =
        outboxStore
            .claimDue("dispatcher." + eventId, claimedAt, claimedAt.plusSeconds(30), 1)
            .getFirst();
    assertEquals(eventId, lease.entry().eventId());
    outboxStore.markPublished(eventId, "dispatcher." + eventId, lease.leaseToken(), publishedAt);
  }
}
