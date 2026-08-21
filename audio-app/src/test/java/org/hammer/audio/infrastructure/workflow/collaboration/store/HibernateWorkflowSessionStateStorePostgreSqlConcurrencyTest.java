package org.hammer.audio.infrastructure.workflow.collaboration.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import jakarta.persistence.OptimisticLockException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowSession;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationPersistenceData;
import org.hammer.audio.workflow.collaboration.store.WorkflowOutboxEventData;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionAppendCommand;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionAppendResult;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionRevisionConflictException;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class HibernateWorkflowSessionStateStorePostgreSqlConcurrencyTest {

  private static final int REPEATED_RUNS = 25;
  private static final Instant CREATED_AT = Instant.parse("2026-07-17T00:00:00Z");
  private static final OperationActor OWNER =
      new OperationActor("actor.owner", "user.owner", "Owner");

  @Container
  static final PostgreSQLContainer<?> POSTGRESQL =
      new PostgreSQLContainer<>("postgres:17.10-alpine")
          .withDatabaseName("audio_analyzer_session_concurrency")
          .withUsername("postgres")
          .withPassword("postgres");

  @Test
  void recoveryWaitsForWinnerAndConcurrentContractsRemainStable() {
    withStore(
        (store, sessionFactory) -> {
          verifyWinnerVisibilitySynchronization(store, sessionFactory);
          for (int run = 0; run < REPEATED_RUNS; run++) {
            verifyIdenticalConcurrentAppends(store, run);
            verifyDifferentConcurrentAppends(store, run);
          }
        });
  }

  private static void verifyWinnerVisibilitySynchronization(
      HibernateWorkflowSessionStateStore store, SessionFactory sessionFactory) {
    StoredWorkflowSession initial = session("session.visibility");
    store.create(initial);
    WorkflowSessionAppendCommand command =
        command(initial, "operation.visibility", "event.visibility", 0, "visibility");

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Session winnerSession = sessionFactory.openSession()) {
      Transaction winnerTransaction = winnerSession.beginTransaction();
      try {
        persistUncommittedWinner(winnerSession, command);
        CountDownLatch recoveryStarted = new CountDownLatch(1);
        Future<WorkflowSessionAppendResult> recovery =
            executor.submit(
                () -> {
                  recoveryStarted.countDown();
                  return invokeConcurrentAppendRecovery(
                      store, command, new OptimisticLockException("simulated losing append"));
                });

        await(recoveryStarted);
        assertThrows(
            TimeoutException.class,
            () -> recovery.get(250, TimeUnit.MILLISECONDS),
            "Recovery must wait until the transaction that owns the aggregate row commits");

        winnerTransaction.commit();
        WorkflowSessionAppendResult recovered = get(recovery);

        assertTrue(recovered.duplicate());
        assertEquals(1, recovered.session().revision());
        assertEquals("operation.visibility", recovered.operation().operationId());
        assertEquals("event.visibility", recovered.outboxEntry().eventId());
      } finally {
        if (winnerTransaction.isActive()) {
          winnerTransaction.rollback();
        }
      }
    } finally {
      executor.shutdownNow();
    }
  }

  private static WorkflowSessionAppendResult invokeConcurrentAppendRecovery(
      HibernateWorkflowSessionStateStore store,
      WorkflowSessionAppendCommand command,
      RuntimeException failure) {
    try {
      return (WorkflowSessionAppendResult)
          ConcurrentAppendRecoveryHandle.HANDLE.invokeExact(store, command, failure);
    } catch (RuntimeException | Error exception) {
      throw exception;
    } catch (Throwable failureToInvoke) {
      throw new AssertionError(
          "Could not invoke private concurrent append recovery", failureToInvoke);
    }
  }

  private static void persistUncommittedWinner(
      Session session, WorkflowSessionAppendCommand command) {
    WorkflowSessionEntity aggregate =
        session.find(WorkflowSessionEntity.class, command.sessionId());
    aggregate.applyAcceptedOperation(command.workflowId(), command.workflowDsl(), 1, 1);
    session.persist(
        WorkflowOperationEntity.accepted(command.sessionId(), command.operation(), 1, 1));
    session.persist(WorkflowOutboxEntity.pending(command.sessionId(), command.outboxEvent(), 1, 1));
    session.flush();
  }

  private static void verifyIdenticalConcurrentAppends(
      HibernateWorkflowSessionStateStore store, int run) {
    StoredWorkflowSession initial = session("session.identical." + run);
    store.create(initial);
    WorkflowSessionAppendCommand command =
        command(initial, "operation.identical." + run, "event.identical." + run, 0, "same");

    List<AppendAttempt> attempts = runConcurrently(store, command, command);
    List<WorkflowSessionAppendResult> results =
        attempts.stream().filter(AppendAttempt::accepted).map(AppendAttempt::result).toList();

    assertEquals(2, results.size(), "identical append run " + run);
    assertEquals(0, attempts.stream().filter(AppendAttempt::conflicted).count());
    assertEquals(1, results.stream().filter(WorkflowSessionAppendResult::duplicate).count());
    assertEquals(1, results.stream().filter(result -> !result.duplicate()).count());
    assertEquals(1, store.find(initial.sessionId()).orElseThrow().revision());
    assertEquals(1, store.operations(initial.sessionId()).size());
  }

  private static void verifyDifferentConcurrentAppends(
      HibernateWorkflowSessionStateStore store, int run) {
    StoredWorkflowSession initial = session("session.different." + run);
    store.create(initial);
    WorkflowSessionAppendCommand left =
        command(initial, "operation.left." + run, "event.left." + run, 0, "left");
    WorkflowSessionAppendCommand right =
        command(initial, "operation.right." + run, "event.right." + run, 0, "right");

    List<AppendAttempt> attempts = runConcurrently(store, left, right);

    assertEquals(1, attempts.stream().filter(AppendAttempt::accepted).count());
    assertEquals(1, attempts.stream().filter(AppendAttempt::conflicted).count());
    WorkflowSessionRevisionConflictException conflict =
        attempts.stream()
            .filter(AppendAttempt::conflicted)
            .map(AppendAttempt::conflict)
            .findFirst()
            .orElseThrow();
    assertEquals(0, conflict.expectedRevision());
    assertEquals(1, conflict.actualRevision());
    assertEquals(1, store.find(initial.sessionId()).orElseThrow().revision());
    assertEquals(1, store.operations(initial.sessionId()).size());
  }

  private static List<AppendAttempt> runConcurrently(
      HibernateWorkflowSessionStateStore store,
      WorkflowSessionAppendCommand firstCommand,
      WorkflowSessionAppendCommand secondCommand) {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<AppendAttempt> first =
          executor.submit(() -> attempt(store, firstCommand, ready, start));
      Future<AppendAttempt> second =
          executor.submit(() -> attempt(store, secondCommand, ready, start));
      await(ready);
      start.countDown();
      return List.of(get(first), get(second));
    } finally {
      executor.shutdownNow();
    }
  }

  private static AppendAttempt attempt(
      HibernateWorkflowSessionStateStore store,
      WorkflowSessionAppendCommand command,
      CountDownLatch ready,
      CountDownLatch start) {
    ready.countDown();
    await(start);
    try {
      return AppendAttempt.accepted(store.append(command));
    } catch (WorkflowSessionRevisionConflictException conflict) {
      return AppendAttempt.conflicted(conflict);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent append latch timed out");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while coordinating concurrent append", exception);
    }
  }

  private static <T> T get(Future<T> future) {
    try {
      return future.get(20, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for concurrent append", exception);
    } catch (ExecutionException | TimeoutException exception) {
      throw new AssertionError("Concurrent append did not complete successfully", exception);
    }
  }

  private static WorkflowSessionAppendCommand command(
      StoredWorkflowSession session,
      String operationId,
      String eventId,
      long expectedRevision,
      String payload) {
    long ordinal = expectedRevision + 1;
    Instant occurredAt = CREATED_AT.plusSeconds(ordinal);
    return new WorkflowSessionAppendCommand(
        session.sessionId(),
        expectedRevision,
        new WorkflowOperationPersistenceData(
            operationId, OWNER.actorId(), "CreateNode", occurredAt, payload),
        session.workflowId(),
        "workflow-dsl-" + ordinal,
        new WorkflowOutboxEventData(
            eventId, "WORKFLOW_OPERATION_ACCEPTED", occurredAt, "event-" + payload));
  }

  private static StoredWorkflowSession session(String sessionId) {
    return new StoredWorkflowSession(
        sessionId,
        CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
        OWNER,
        CREATED_AT,
        "workflow.shared",
        "workflow-dsl-0",
        0,
        0,
        false);
  }

  private static void withStore(StoreScenario scenario) {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", POSTGRESQL.getJdbcUrl());
    properties.put("hibernate.connection.username", POSTGRESQL.getUsername());
    properties.put("hibernate.connection.password", POSTGRESQL.getPassword());
    properties.put("hibernate.connection.driver_class", "org.postgresql.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    try (HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(
            properties, CollaborationPersistenceEntities.annotatedClasses())) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      scenario.run(new HibernateWorkflowSessionStateStore(sessionFactory), sessionFactory);
    }
  }

  private record AppendAttempt(
      WorkflowSessionAppendResult result, WorkflowSessionRevisionConflictException conflict) {

    static AppendAttempt accepted(WorkflowSessionAppendResult result) {
      return new AppendAttempt(result, null);
    }

    static AppendAttempt conflicted(WorkflowSessionRevisionConflictException conflict) {
      return new AppendAttempt(null, conflict);
    }

    boolean accepted() {
      return result != null;
    }

    boolean conflicted() {
      return conflict != null;
    }
  }

  private static final class ConcurrentAppendRecoveryHandle {
    private static final MethodHandle HANDLE = create();

    private static MethodHandle create() {
      try {
        return MethodHandles.privateLookupIn(
                HibernateWorkflowSessionStateStore.class, MethodHandles.lookup())
            .findVirtual(
                HibernateWorkflowSessionStateStore.class,
                "recoverConcurrentAppend",
                MethodType.methodType(
                    WorkflowSessionAppendResult.class,
                    WorkflowSessionAppendCommand.class,
                    RuntimeException.class));
      } catch (IllegalAccessException | NoSuchMethodException exception) {
        throw new ExceptionInInitializerError(exception);
      }
    }

    private ConcurrentAppendRecoveryHandle() {}
  }

  @FunctionalInterface
  private interface StoreScenario {
    void run(HibernateWorkflowSessionStateStore store, SessionFactory sessionFactory);
  }
}
