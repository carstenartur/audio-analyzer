package org.hammer.audio.infrastructure.workflow.collaboration.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowOperation;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowSession;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationPersistenceCodec;
import org.hammer.audio.workflow.collaboration.store.WorkflowOutboxEventData;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionAppendCommand;
import org.junit.jupiter.api.Test;

class HibernateWorkflowOperationBodyStoreTest {

  private static final Instant CREATED_AT = Instant.parse("2026-07-18T20:45:00Z");
  private static final OperationActor OWNER =
      new OperationActor("actor.owner", "user.owner", "Owner");

  @Test
  void persistsAndReconstructsCompleteOperationBody() {
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
      HibernateWorkflowSessionStateStore store =
          new HibernateWorkflowSessionStateStore(provider.getSessionFactory());
      StoredWorkflowSession session =
          new StoredWorkflowSession(
              "session.body",
              CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
              OWNER,
              CREATED_AT,
              "workflow.body",
              "workflow workflow.body { }",
              0,
              0,
              false);
      store.create(session);
      WorkflowOperation operation =
          new WorkflowOperation.CreateNode(
              "operation.body",
              CREATED_AT.plusSeconds(1),
              OWNER.actorId(),
              new Node("node.input", "input", "Input", List.of(), List.of()));

      store.append(
          new WorkflowSessionAppendCommand(
              session.sessionId(),
              0,
              WorkflowOperationPersistenceCodec.encode(operation),
              session.workflowId(),
              "workflow workflow.body { node node.input type input label \"Input\" { } }",
              new WorkflowOutboxEventData(
                  "event.body",
                  "WORKFLOW_OPERATION_ACCEPTED",
                  operation.timestamp(),
                  "event-body")));

      StoredWorkflowOperation stored = store.operations(session.sessionId()).getFirst();
      assertTrue(stored.hasOperationBody());
      assertEquals(operation, stored.operation().orElseThrow());
      assertEquals(operation.getClass().getSimpleName(), stored.operationType());
    }
  }
}
