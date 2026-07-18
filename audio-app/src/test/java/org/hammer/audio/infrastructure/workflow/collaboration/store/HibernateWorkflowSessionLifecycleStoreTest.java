package org.hammer.audio.infrastructure.workflow.collaboration.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowSession;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationPersistenceData;
import org.hammer.audio.workflow.collaboration.store.WorkflowOutboxEventData;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionAppendCommand;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionRevisionConflictException;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionSequenceConflictException;
import org.junit.jupiter.api.Test;

class HibernateWorkflowSessionLifecycleStoreTest {

  private static final Instant CREATED_AT = Instant.parse("2026-07-18T00:00:00Z");
  private static final OperationActor OWNER =
      new OperationActor("actor.owner", "user.owner", "Owner");

  @Test
  void openSessionsSequenceAdvanceAndCloseAreDurableAndOrdered() {
    withStore(
        store -> {
          store.create(session("session.zeta"));
          store.create(session("session.alpha"));

          assertEquals(
              List.of("session.alpha", "session.zeta"),
              store.openSessions().stream().map(StoredWorkflowSession::sessionId).toList());

          StoredWorkflowSession advanced = store.advanceEventSequence("session.alpha", 2);
          assertEquals(0, advanced.revision());
          assertEquals(3, advanced.sequence());

          StoredWorkflowSession closed = store.close("session.alpha", 0, 3);
          assertTrue(closed.closed());
          assertEquals(4, closed.sequence());
          assertEquals(List.of("session.zeta"), sessionIds(store.openSessions()));

          assertEquals(closed, store.close("session.alpha", 0, 3));
        });
  }

  @Test
  void staleLifecycleSequenceReturnsTypedConflictWithoutMutation() {
    withStore(
        store -> {
          store.create(session("session.sequence"));

          WorkflowSessionSequenceConflictException conflict =
              assertThrows(
                  WorkflowSessionSequenceConflictException.class,
                  () -> store.advanceEventSequence("session.sequence", 1));

          assertEquals("session.sequence", conflict.sessionId());
          assertEquals(1, conflict.expectedSequence());
          assertEquals(2, conflict.actualSequence());
          assertEquals(2, store.find("session.sequence").orElseThrow().sequence());
        });
  }

  @Test
  void closeRejectsStaleSemanticRevision() {
    withStore(
        store -> {
          StoredWorkflowSession initial = session("session.revision");
          store.create(initial);
          store.append(
              new WorkflowSessionAppendCommand(
                  initial.sessionId(),
                  0,
                  new WorkflowOperationPersistenceData(
                      "operation.one", OWNER.actorId(), "CreateNode", CREATED_AT, "payload"),
                  initial.workflowId(),
                  "workflow-dsl-1",
                  new WorkflowOutboxEventData(
                      "event.one", "WORKFLOW_OPERATION_ACCEPTED", CREATED_AT, "event-payload")));

          WorkflowSessionRevisionConflictException conflict =
              assertThrows(
                  WorkflowSessionRevisionConflictException.class,
                  () -> store.close(initial.sessionId(), 0, 3));

          assertEquals(0, conflict.expectedRevision());
          assertEquals(1, conflict.actualRevision());
          assertEquals(1, store.openSessions().size());
        });
  }

  private static List<String> sessionIds(List<StoredWorkflowSession> sessions) {
    return sessions.stream().map(StoredWorkflowSession::sessionId).toList();
  }

  private static StoredWorkflowSession session(String sessionId) {
    return new StoredWorkflowSession(
        sessionId,
        CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
        OWNER,
        CREATED_AT,
        "workflow.shared",
        "workflow\n  id: workflow.shared\n  name: Shared\n  nodes:\n  edges:\n",
        0,
        2,
        false);
  }

  private static void withStore(StoreScenario scenario) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
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
