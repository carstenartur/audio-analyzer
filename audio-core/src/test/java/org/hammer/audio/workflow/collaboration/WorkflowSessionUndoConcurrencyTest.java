package org.hammer.audio.workflow.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionRevisionConflictException;
import org.junit.jupiter.api.Test;

class WorkflowSessionUndoConcurrencyTest {

  private static final OperationActor OWNER =
      new OperationActor("actor.owner", "user.owner", "Owner");

  @Test
  void onlyOneUndoAtTheSameExpectedRevisionCanCommit() throws Exception {
    WorkflowSessionRegistry registry = new WorkflowSessionRegistry();
    registry.create(
        "session.concurrent",
        CollaborationMode.PRIVATE_WORKSPACE,
        OWNER,
        new Workflow("workflow.concurrent", "Concurrent", List.of(), List.of()));
    registry.applyOperation(
        "session.concurrent",
        CollaborationMode.PRIVATE_WORKSPACE,
        OWNER,
        0,
        new WorkflowOperation.CreateNode(
            "operation.create",
            Instant.parse("2026-07-18T22:00:00Z"),
            OWNER.actorId(),
            new Node("node.one", "test", "One", List.of(), List.of(), Metadata.empty())));

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Object>> futures = new ArrayList<>();
      for (int index = 0; index < 2; index++) {
        String commandId = "undo.concurrent." + index;
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  try {
                    return registry.undo(
                        "session.concurrent",
                        new UndoWorkflowCommand(commandId, OWNER, 1, null, null));
                  } catch (RuntimeException failure) {
                    return failure;
                  }
                }));
      }
      ready.await();
      start.countDown();

      List<Object> results = futures.stream().map(WorkflowSessionUndoConcurrencyTest::get).toList();
      assertEquals(
          1, results.stream().filter(WorkflowHistoryCommandResult.class::isInstance).count());
      Object rejected =
          results.stream()
              .filter(WorkflowSessionRevisionConflictException.class::isInstance)
              .findFirst()
              .orElseThrow();
      WorkflowSessionRevisionConflictException conflict =
          assertInstanceOf(WorkflowSessionRevisionConflictException.class, rejected);
      assertEquals(1, conflict.expectedRevision());
      assertEquals(2, conflict.actualRevision());
    }

    assertEquals(2, registry.inspect("session.concurrent").revision());
    assertEquals(2, registry.inspect("session.concurrent").operationCount());
    assertTrue(registry.workflow("session.concurrent").nodes().isEmpty());
  }

  private static Object get(Future<Object> future) {
    try {
      return future.get();
    } catch (Exception exception) {
      throw new IllegalStateException("Concurrent undo task failed", exception);
    }
  }
}
