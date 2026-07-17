package org.hammer.audio.infrastructure.workflow.collaboration.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.store.PendingWorkflowOutboxEntry;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowSession;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationPersistenceConflictException;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationPersistenceData;
import org.hammer.audio.workflow.collaboration.store.WorkflowOutboxEventData;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionAppendCommand;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionAppendResult;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionRevisionConflictException;
import org.junit.jupiter.api.Test;

class HibernateWorkflowSessionStateStoreTest {

  private static final Instant CREATED_AT = Instant.parse("2026-07-17T00:00:00Z");
  private static final OperationActor OWNER =
      new OperationActor("actor.owner", "user.owner", "Owner");

  @Test
  void appendPersistsAggregateOperationAndOutboxAtomically() {
    withStore(
        store -> {
          StoredWorkflowSession initial = session("session.atomic");
          store.create(initial);

          WorkflowSessionAppendResult result =
              store.append(command(initial, "operation.one", "event.one", 0, "payload-one"));

          assertFalse(result.duplicate());
          assertEquals(1, result.session().revision());
          assertEquals(1, result.session().sequence());
          assertEquals("workflow-dsl-1", result.session().workflowDsl());
          assertEquals(1, result.operation().revision());
          assertEquals(1, result.operation().sequence());
          assertEquals("event.one", result.outboxEntry().eventId());
          assertEquals(result.session(), store.find(initial.sessionId()).orElseThrow());
          assertEquals(List.of(result.operation()), store.operations(initial.sessionId()));
          assertEquals(List.of(result.outboxEntry()), store.pendingOutbox(10));
        });
  }

  @Test
  void staleRevisionRollsBackOperationAggregateAndOutbox() {
    withStore(
        store -> {
          StoredWorkflowSession initial = session("session.stale");
          store.create(initial);
          store.append(command(initial, "operation.one", "event.one", 0, "payload-one"));

          WorkflowSessionRevisionConflictException conflict =
              assertThrows(
                  WorkflowSessionRevisionConflictException.class,
                  () ->
                      store.append(
                          command(initial, "operation.two", "event.two", 0, "payload-two")));

          assertEquals(0, conflict.expectedRevision());
          assertEquals(1, conflict.actualRevision());
          assertEquals(1, store.find(initial.sessionId()).orElseThrow().revision());
          assertEquals(1, store.operations(initial.sessionId()).size());
          assertEquals(1, store.pendingOutbox(10).size());
        });
  }

  @Test
  void identicalOperationRetryIsIdempotent() {
    withStore(
        store -> {
          StoredWorkflowSession initial = session("session.retry");
          store.create(initial);
          WorkflowSessionAppendCommand command =
              command(initial, "operation.retry", "event.retry", 0, "payload-retry");

          WorkflowSessionAppendResult accepted = store.append(command);
          WorkflowSessionAppendResult retried = store.append(command);

          assertFalse(accepted.duplicate());
          assertTrue(retried.duplicate());
          assertEquals(accepted.session(), retried.session());
          assertEquals(accepted.operation(), retried.operation());
          assertEquals(accepted.outboxEntry(), retried.outboxEntry());
          assertEquals(1, store.operations(initial.sessionId()).size());
          assertEquals(1, store.pendingOutbox(10).size());
        });
  }

  @Test
  void operationIdCannotBeReusedForDifferentContent() {
    withStore(
        store -> {
          StoredWorkflowSession initial = session("session.operation-conflict");
          store.create(initial);
          store.append(command(initial, "operation.same", "event.one", 0, "payload-one"));

          assertThrows(
              WorkflowOperationPersistenceConflictException.class,
              () ->
                  store.append(
                      command(initial, "operation.same", "event.two", 1, "payload-different")));

          assertEquals(1, store.find(initial.sessionId()).orElseThrow().revision());
          assertEquals(1, store.operations(initial.sessionId()).size());
          assertEquals(1, store.pendingOutbox(10).size());
        });
  }

  @Test
  void outboxConstraintFailureRollsBackAggregateAndOperation() {
    withStore(
        store -> {
          StoredWorkflowSession firstSession = session("session.first");
          StoredWorkflowSession secondSession = session("session.second");
          store.create(firstSession);
          store.create(secondSession);
          store.append(command(firstSession, "operation.first", "event.shared", 0, "first"));

          assertThrows(
              RuntimeException.class,
              () ->
                  store.append(
                      command(secondSession, "operation.second", "event.shared", 0, "second")));

          assertEquals(0, store.find(secondSession.sessionId()).orElseThrow().revision());
          assertTrue(store.operations(secondSession.sessionId()).isEmpty());
          List<PendingWorkflowOutboxEntry> pending = store.pendingOutbox(10);
          assertEquals(1, pending.size());
          assertEquals("session.first", pending.getFirst().sessionId());
        });
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
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    try (HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(
            properties, CollaborationPersistenceEntities.annotatedClasses())) {
      scenario.run(new HibernateWorkflowSessionStateStore(provider.getSessionFactory()));
    }
  }

  @FunctionalInterface
  private interface StoreScenario {
    void run(HibernateWorkflowSessionStateStore store);
  }
}
