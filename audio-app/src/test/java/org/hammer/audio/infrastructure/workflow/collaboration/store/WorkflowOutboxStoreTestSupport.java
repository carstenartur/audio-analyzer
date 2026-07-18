package org.hammer.audio.infrastructure.workflow.collaboration.store;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowSession;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationPersistenceData;
import org.hammer.audio.workflow.collaboration.store.WorkflowOutboxEventData;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionAppendCommand;

/** Shared test fixtures for durable workflow outbox integration coverage. */
final class WorkflowOutboxStoreTestSupport {

  static final Instant BASE_TIME = Instant.parse("2026-07-18T02:00:00Z");
  static final OperationActor OWNER = new OperationActor("actor.owner", "user.owner", "Owner");

  private WorkflowOutboxStoreTestSupport() {
    // Utility class.
  }

  static HibernateSessionFactoryProvider provider(Properties properties) {
    return new HibernateSessionFactoryProvider(
        properties, CollaborationPersistenceEntities.annotatedClasses());
  }

  static Properties inMemoryProperties() {
    Properties properties = baseProperties();
    properties.put(
        "hibernate.connection.url", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    return properties;
  }

  static Properties fileProperties(Path databasePath) {
    Properties properties = baseProperties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:file:"
            + databasePath.toAbsolutePath()
            + ";DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE");
    properties.put("hibernate.hbm2ddl.auto", "update");
    return properties;
  }

  static void appendPendingEvent(
      HibernateWorkflowSessionStateStore sessionStore,
      String sessionId,
      String eventId,
      Instant occurredAt) {
    StoredWorkflowSession session =
        new StoredWorkflowSession(
            sessionId,
            CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
            OWNER,
            BASE_TIME,
            "workflow." + sessionId,
            "workflow-dsl-0",
            0,
            0,
            false);
    sessionStore.create(session);
    sessionStore.append(
        new WorkflowSessionAppendCommand(
            sessionId,
            0,
            new WorkflowOperationPersistenceData(
                "operation." + eventId,
                OWNER.actorId(),
                "CreateNode",
                occurredAt,
                "operation-payload-" + eventId),
            session.workflowId(),
            "workflow-dsl-1",
            new WorkflowOutboxEventData(
                eventId, "WORKFLOW_OPERATION_ACCEPTED", occurredAt, "event-payload-" + eventId)));
  }

  private static Properties baseProperties() {
    Properties properties = new Properties();
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.show_sql", "false");
    return properties;
  }
}
