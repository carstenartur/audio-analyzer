package org.hammer.audio.infrastructure.workflow.collaboration.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.hammer.audio.workflow.collaboration.UndoWorkflowCommand;
import org.hammer.audio.workflow.collaboration.WorkflowHistoryCapabilities;
import org.hammer.audio.workflow.collaboration.WorkflowHistoryCommandResult;
import org.hammer.audio.workflow.collaboration.WorkflowHistoryPage;
import org.hammer.audio.workflow.collaboration.WorkflowRedoPreview;
import org.hammer.audio.workflow.collaboration.WorkflowSessionEventHub;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.junit.jupiter.api.Test;

class HibernateWorkflowHistoryRecoveryTest {

  private static final String SESSION_ID = "session.history.restart";
  private static final OperationActor OWNER =
      new OperationActor("actor.owner", "user.owner", "Owner");
  private static final Instant BASE_TIME = Instant.parse("2026-07-19T08:30:00Z");

  @Test
  void historyAndCapabilitiesRemainStableAcrossCompleteRestart() throws IOException {
    Path databaseDirectory = Files.createTempDirectory("audio-analyzer-history-recovery-");
    Properties properties = h2FileProperties(databaseDirectory.resolve("collaboration"));
    WorkflowHistoryPage expectedHistory;
    WorkflowHistoryCapabilities expectedCapabilities;
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
            createNode("operation.create"));
        registry.applyOperation(
            SESSION_ID,
            CollaborationMode.PRIVATE_WORKSPACE,
            OWNER,
            1,
            renameNode("operation.rename", "node.input", "Renamed"));
        WorkflowHistoryCommandResult undone =
            registry.undo(
                SESSION_ID, new UndoWorkflowCommand("command.undo", OWNER, 2, null, null));
        undoOperationId = undone.operationId();

        WorkflowSessionRegistry.SessionSnapshot beforeQueries = registry.inspect(SESSION_ID);
        int pendingOutboxBeforeQueries = store.pendingOutbox(100).size();
        expectedHistory = registry.history(SESSION_ID, OWNER, null, 10);
        expectedCapabilities = registry.capabilities(SESSION_ID, OWNER);
        assertEquals(beforeQueries, registry.inspect(SESSION_ID));
        assertEquals(3, store.operations(SESSION_ID).size());
        assertEquals(pendingOutboxBeforeQueries, store.pendingOutbox(100).size());
        assertEquals(undoOperationId, expectedCapabilities.redo().operation().operationId());
      }

      try (HibernateSessionFactoryProvider provider = provider(properties)) {
        HibernateWorkflowSessionStateStore store =
            new HibernateWorkflowSessionStateStore(provider.getSessionFactory());
        WorkflowSessionRegistry registry =
            new WorkflowSessionRegistry(new WorkflowSessionEventHub(32, 8), store);
        registry.join(SESSION_ID, OWNER);
        WorkflowSessionRegistry.SessionSnapshot beforeQueries = registry.inspect(SESSION_ID);
        int pendingOutboxBeforeQueries = store.pendingOutbox(100).size();

        WorkflowHistoryPage recoveredHistory = registry.history(SESSION_ID, OWNER, null, 10);
        WorkflowHistoryCapabilities recoveredCapabilities =
            registry.capabilities(SESSION_ID, OWNER);
        WorkflowRedoPreview redoPreview = registry.previewRedo(SESSION_ID, OWNER, undoOperationId);

        assertEquals(expectedHistory, recoveredHistory);
        assertEquals(expectedCapabilities, recoveredCapabilities);
        assertEquals(
            recoveredCapabilities.redo().operation().occurredAt(), redoPreview.targetOccurredAt());
        assertTrue(redoPreview.safe());
        assertEquals(beforeQueries, registry.inspect(SESSION_ID));
        assertEquals(3, store.operations(SESSION_ID).size());
        assertEquals(pendingOutboxBeforeQueries, store.pendingOutbox(100).size());
      }
    } finally {
      deleteRecursively(databaseDirectory);
    }
  }

  private static WorkflowOperation.CreateNode createNode(String operationId) {
    return new WorkflowOperation.CreateNode(
        operationId,
        BASE_TIME,
        OWNER.actorId(),
        new Node(
            "node.input", "recording-input", "node.input", List.of(), List.of(), Metadata.empty()));
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

  private static Workflow emptyWorkflow() {
    return new Workflow("workflow.history.restart", "History restart", List.of(), List.of());
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
      paths.sorted(Comparator.reverseOrder()).forEach(HibernateWorkflowHistoryRecoveryTest::delete);
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
