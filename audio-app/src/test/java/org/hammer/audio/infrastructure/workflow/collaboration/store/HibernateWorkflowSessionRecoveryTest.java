package org.hammer.audio.infrastructure.workflow.collaboration.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.PresenceState;
import org.hammer.audio.workflow.collaboration.WorkflowSessionEvent;
import org.hammer.audio.workflow.collaboration.WorkflowSessionEventHub;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRecoveryException;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry.SessionSnapshot;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowSession;
import org.junit.jupiter.api.Test;

class HibernateWorkflowSessionRecoveryTest {

  private static final String SESSION_ID = "session.restart";
  private static final OperationActor OWNER =
      new OperationActor("actor.owner", "user.owner", "Owner");
  private static final Instant FIRST_OPERATION_TIME = Instant.parse("2026-07-18T01:00:00Z");

  @Test
  void sessionOperationsSequenceAndCloseSurviveCompleteRestart() throws IOException {
    Path databaseDirectory = Files.createTempDirectory("audio-analyzer-collaboration-recovery-");
    Properties properties = h2FileProperties(databaseDirectory.resolve("collaboration"));
    SessionSnapshot beforeRestart;
    Workflow canonicalBeforeRestart;

    try {
      try (HibernateSessionFactoryProvider provider = provider(properties)) {
        HibernateWorkflowSessionStateStore store =
            new HibernateWorkflowSessionStateStore(provider.getSessionFactory());
        WorkflowSessionEventHub eventHub = new WorkflowSessionEventHub(16, 8);
        WorkflowSessionRegistry registry = new WorkflowSessionRegistry(eventHub, store);
        registry.create(
            SESSION_ID, CollaborationMode.SHARED_SESSION_PERSONAL_UNDO, OWNER, emptyWorkflow());
        registry.updatePresence(
            SESSION_ID,
            OWNER,
            new PresenceState(
                OWNER.actorId(),
                Instant.parse("2026-07-18T00:30:00Z"),
                java.util.Map.of("cursor.x", "42")));
        canonicalBeforeRestart =
            registry.applyOperation(
                SESSION_ID,
                CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
                OWNER,
                createNode("operation.create", FIRST_OPERATION_TIME));
        beforeRestart = registry.inspect(SESSION_ID);

        assertEquals(1, beforeRestart.revision());
        assertEquals(4, beforeRestart.sequence());
        assertEquals(1, beforeRestart.operationCount());
        assertEquals(4, eventHub.currentSequence(SESSION_ID));
        assertEquals(1, store.operations(SESSION_ID).size());
        assertEquals(1, store.pendingOutbox(10).size());
      }

      try (HibernateSessionFactoryProvider provider = provider(properties)) {
        HibernateWorkflowSessionStateStore store =
            new HibernateWorkflowSessionStateStore(provider.getSessionFactory());
        WorkflowSessionEventHub eventHub = new WorkflowSessionEventHub(16, 8);
        WorkflowSessionRegistry registry = new WorkflowSessionRegistry(eventHub, store);
        SessionSnapshot recovered = registry.inspect(SESSION_ID);

        assertEquals(beforeRestart.mode(), recovered.mode());
        assertEquals(beforeRestart.owner(), recovered.owner());
        assertEquals(beforeRestart.createdAt(), recovered.createdAt());
        assertTrue(recovered.participants().isEmpty());
        assertEquals(1, recovered.operationCount());
        assertEquals(1, recovered.revision());
        assertEquals(4, recovered.sequence());
        assertEquals(canonicalBeforeRestart, registry.workflow(SESSION_ID));
        assertEquals(4, eventHub.currentSequence(SESSION_ID));
        assertEquals(1, eventHub.currentRevision(SESSION_ID));

        List<WorkflowSessionEvent> recoveryEvents = eventHub.replay(SESSION_ID, 0);
        assertEquals(1, recoveryEvents.size());
        assertEquals(WorkflowSessionEvent.Type.SNAPSHOT, recoveryEvents.getFirst().type());
        assertEquals(4, recoveryEvents.getFirst().sequence());
        assertEquals(canonicalBeforeRestart, recoveryEvents.getFirst().workflow());

        SessionSnapshot rejoined = registry.join(SESSION_ID, OWNER);
        assertEquals(List.of(OWNER), rejoined.participants());
        assertEquals(5, rejoined.sequence());

        Workflow retryResult =
            registry.applyOperation(
                SESSION_ID,
                CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
                OWNER,
                createNode("operation.create", FIRST_OPERATION_TIME.plusSeconds(60)));
        assertEquals(canonicalBeforeRestart, retryResult);
        assertEquals(5, eventHub.currentSequence(SESSION_ID));
        assertEquals(1, store.operations(SESSION_ID).size());
        assertEquals(1, store.pendingOutbox(10).size());

        Workflow renamed =
            registry.applyOperation(
                SESSION_ID,
                CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
                OWNER,
                new WorkflowOperation.RenameNode(
                    "operation.rename",
                    FIRST_OPERATION_TIME.plusSeconds(120),
                    OWNER.actorId(),
                    "node.input",
                    "Input",
                    "Renamed input"));
        assertEquals("Renamed input", renamed.nodes().getFirst().label());
        assertEquals(2, registry.inspect(SESSION_ID).revision());
        assertEquals(6, registry.inspect(SESSION_ID).sequence());
        assertEquals(2, store.operations(SESSION_ID).size());
        assertEquals(2, store.pendingOutbox(10).size());

        registry.close(SESSION_ID, OWNER.actorId());
        assertEquals(7, eventHub.currentSequence(SESSION_ID));
        assertTrue(store.openSessions().isEmpty());
      }

      try (HibernateSessionFactoryProvider provider = provider(properties)) {
        HibernateWorkflowSessionStateStore store =
            new HibernateWorkflowSessionStateStore(provider.getSessionFactory());
        WorkflowSessionRegistry registry =
            new WorkflowSessionRegistry(new WorkflowSessionEventHub(16, 8), store);
        assertTrue(registry.sessions().isEmpty());
        assertTrue(store.openSessions().isEmpty());
      }
    } finally {
      deleteRecursively(databaseDirectory);
    }
  }

  @Test
  void corruptWorkflowDslFailsRecoveryWithSessionSpecificDiagnostic() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");

    try (HibernateSessionFactoryProvider provider = provider(properties)) {
      HibernateWorkflowSessionStateStore store =
          new HibernateWorkflowSessionStateStore(provider.getSessionFactory());
      store.create(
          new StoredWorkflowSession(
              "session.corrupt",
              CollaborationMode.PRIVATE_WORKSPACE,
              OWNER,
              Instant.parse("2026-07-18T00:00:00Z"),
              "workflow.corrupt",
              "this is not canonical workflow DSL",
              0,
              2,
              false));

      WorkflowSessionRecoveryException failure =
          assertThrows(
              WorkflowSessionRecoveryException.class,
              () -> new WorkflowSessionRegistry(new WorkflowSessionEventHub(), store));

      assertEquals("session.corrupt", failure.sessionId());
      assertTrue(failure.getMessage().contains("session.corrupt"));
    }
  }

  private static WorkflowOperation.CreateNode createNode(String operationId, Instant timestamp) {
    Node node = new Node("node.input", "input", "Input", List.of(), List.of(), Metadata.empty());
    return new WorkflowOperation.CreateNode(operationId, timestamp, OWNER.actorId(), node);
  }

  private static Workflow emptyWorkflow() {
    return new Workflow("workflow.restart", "Restart workflow", List.of(), List.of());
  }

  private static HibernateSessionFactoryProvider provider(Properties properties) {
    return new HibernateSessionFactoryProvider(
        properties, CollaborationPersistenceEntities.annotatedClasses());
  }

  private static Properties h2FileProperties(Path databasePath) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:file:" + databasePath.toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "update");
    properties.put("hibernate.show_sql", "false");
    return properties;
  }

  private static void deleteRecursively(Path directory) throws IOException {
    if (Files.notExists(directory)) {
      return;
    }
    try (var paths = Files.walk(directory)) {
      paths.sorted(Comparator.reverseOrder()).forEach(HibernateWorkflowSessionRecoveryTest::delete);
    }
  }

  private static void delete(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to delete test database path " + path, exception);
    }
  }
}
