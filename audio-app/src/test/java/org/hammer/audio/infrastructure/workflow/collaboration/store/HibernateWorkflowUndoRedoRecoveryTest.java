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
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.RedoWorkflowCommand;
import org.hammer.audio.workflow.collaboration.UndoWorkflowCommand;
import org.hammer.audio.workflow.collaboration.WorkflowHistoryCommandResult;
import org.hammer.audio.workflow.collaboration.WorkflowSessionEventHub;
import org.hammer.audio.workflow.collaboration.WorkflowSessionException;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowOperation;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationCommandMetadata.Kind;
import org.junit.jupiter.api.Test;

class HibernateWorkflowUndoRedoRecoveryTest {

  private static final String SESSION_ID = "session.undo.restart";
  private static final OperationActor OWNER =
      new OperationActor("actor.owner", "user.owner", "Owner");
  private static final Instant BASE_TIME = Instant.parse("2026-07-18T21:30:00Z");

  @Test
  void undoRetryAndRedoRemainDurableAcrossCompleteRestarts() throws IOException {
    Path databaseDirectory = Files.createTempDirectory("audio-analyzer-undo-recovery-");
    Properties properties = h2FileProperties(databaseDirectory.resolve("collaboration"));
    String undoOperationId;

    try {
      try (HibernateSessionFactoryProvider provider = provider(properties)) {
        HibernateWorkflowSessionStateStore store =
            new HibernateWorkflowSessionStateStore(provider.getSessionFactory());
        WorkflowSessionRegistry registry =
            new WorkflowSessionRegistry(new WorkflowSessionEventHub(32, 8), store);
        registry.create(SESSION_ID, CollaborationMode.PRIVATE_WORKSPACE, OWNER, emptyWorkflow());
        registry.applyOperation(
            SESSION_ID,
            CollaborationMode.PRIVATE_WORKSPACE,
            OWNER,
            0,
            createNode("operation.create", "Original"));
        registry.applyOperation(
            SESSION_ID,
            CollaborationMode.PRIVATE_WORKSPACE,
            OWNER,
            1,
            renameNode("operation.rename", "Original", "Renamed"));

        WorkflowHistoryCommandResult undone =
            registry.undo(
                SESSION_ID,
                new UndoWorkflowCommand("command.undo", OWNER, 2, null, null));
        undoOperationId = undone.operationId();
        assertEquals("Original", nodeLabel(registry));
        assertEquals(3, undone.revision());
        assertEquals(3, store.operations(SESSION_ID).size());
        assertEquals(Kind.UNDO, store.operations(SESSION_ID).getLast().command().kind());
      }

      try (HibernateSessionFactoryProvider provider = provider(properties)) {
        HibernateWorkflowSessionStateStore store =
            new HibernateWorkflowSessionStateStore(provider.getSessionFactory());
        WorkflowSessionRegistry registry =
            new WorkflowSessionRegistry(new WorkflowSessionEventHub(32, 8), store);
        assertEquals("Original", nodeLabel(registry));
        assertTrue(registry.inspect(SESSION_ID).participants().isEmpty());
        registry.join(SESSION_ID, OWNER);

        WorkflowHistoryCommandResult retry =
            registry.undo(
                SESSION_ID,
                new UndoWorkflowCommand("command.undo", OWNER, 2, null, null));
        assertEquals(undoOperationId, retry.operationId());
        assertEquals(3, retry.revision());
        assertEquals(3, store.operations(SESSION_ID).size());

        WorkflowHistoryCommandResult redone =
            registry.redo(
                SESSION_ID,
                new RedoWorkflowCommand("command.redo", OWNER, 3, undoOperationId));
        assertEquals("Renamed", nodeLabel(registry));
        assertEquals(4, redone.revision());
        List<StoredWorkflowOperation> operations = store.operations(SESSION_ID);
        assertEquals(4, operations.size());
        assertEquals(Kind.REDO, operations.getLast().command().kind());
        assertEquals(undoOperationId, operations.getLast().command().targetOperationId());
      }

      try (HibernateSessionFactoryProvider provider = provider(properties)) {
        HibernateWorkflowSessionStateStore store =
            new HibernateWorkflowSessionStateStore(provider.getSessionFactory());
        WorkflowSessionRegistry registry =
            new WorkflowSessionRegistry(new WorkflowSessionEventHub(32, 8), store);
        assertEquals("Renamed", nodeLabel(registry));
        registry.join(SESSION_ID, OWNER);
        WorkflowSessionException duplicateRedo =
            assertThrows(
                WorkflowSessionException.class,
                () ->
                    registry.redo(
                        SESSION_ID,
                        new RedoWorkflowCommand("command.redo.second", OWNER, 4, undoOperationId)));
        assertEquals(WorkflowSessionException.Code.REDO_ALREADY_APPLIED, duplicateRedo.code());
        assertEquals(4, store.operations(SESSION_ID).size());
      }
    } finally {
      deleteRecursively(databaseDirectory);
    }
  }

  private static WorkflowOperation.CreateNode createNode(String operationId, String label) {
    return new WorkflowOperation.CreateNode(
        operationId,
        BASE_TIME,
        OWNER.actorId(),
        new Node("node.input", "input", label, List.of(), List.of(), Metadata.empty()));
  }

  private static WorkflowOperation.RenameNode renameNode(
      String operationId, String previousLabel, String newLabel) {
    return new WorkflowOperation.RenameNode(
        operationId,
        BASE_TIME.plusSeconds(1),
        OWNER.actorId(),
        "node.input",
        previousLabel,
        newLabel);
  }

  private static String nodeLabel(WorkflowSessionRegistry registry) {
    return registry.workflow(SESSION_ID).nodes().getFirst().label();
  }

  private static Workflow emptyWorkflow() {
    return new Workflow("workflow.undo.restart", "Undo restart", List.of(), List.of());
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
      paths.sorted(Comparator.reverseOrder())
          .forEach(HibernateWorkflowUndoRedoRecoveryTest::delete);
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
