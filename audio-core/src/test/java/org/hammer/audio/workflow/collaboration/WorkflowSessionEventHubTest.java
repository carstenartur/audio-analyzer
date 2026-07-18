package org.hammer.audio.workflow.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.junit.jupiter.api.Test;

class WorkflowSessionEventHubTest {

  private static final String SESSION_ID = "session.events";
  private static final OperationActor ALICE = new OperationActor("alice", "user.alice", "Alice");
  private static final OperationActor BOB = new OperationActor("bob", "user.bob", "Bob");

  @Test
  void twoSubscribersReceiveAcceptedOperationsInIdenticalOrder() throws Exception {
    WorkflowSessionEventHub hub = new WorkflowSessionEventHub(16, 8);
    Workflow initial = initialWorkflow();
    hub.openSession(SESSION_ID, ALICE, initial);
    long cursor = hub.currentSequence(SESSION_ID);
    List<WorkflowSessionEvent> first = new CopyOnWriteArrayList<>();
    List<WorkflowSessionEvent> second = new CopyOnWriteArrayList<>();
    CountDownLatch delivered = new CountDownLatch(4);

    try (var firstSubscription =
            hub.subscribe(
                SESSION_ID,
                cursor,
                event -> {
                  first.add(event);
                  delivered.countDown();
                });
        var secondSubscription =
            hub.subscribe(
                SESSION_ID,
                cursor,
                event -> {
                  second.add(event);
                  delivered.countDown();
                })) {
      WorkflowOperation firstOperation = rename("operation.1", "Input", "Input A");
      Workflow firstWorkflow = firstOperation.apply(initial);
      hub.operationAccepted(SESSION_ID, ALICE, firstOperation, firstWorkflow);
      WorkflowOperation secondOperation = rename("operation.2", "Input A", "Input B");
      Workflow secondWorkflow = secondOperation.apply(firstWorkflow);
      hub.operationAccepted(SESSION_ID, BOB, secondOperation, secondWorkflow);

      assertTrue(delivered.await(5, TimeUnit.SECONDS));
      assertEquals(
          first.stream().map(WorkflowSessionEvent::sequence).toList(),
          second.stream().map(WorkflowSessionEvent::sequence).toList());
      assertEquals(
          List.of("operation.1", "operation.2"),
          first.stream().map(WorkflowSessionEvent::operationId).toList());
      assertEquals(2, hub.currentRevision(SESSION_ID));
    }
  }

  @Test
  void reconnectReplaysMissingEventsAndFallsBackToCanonicalSnapshotAfterGap() {
    WorkflowSessionEventHub hub = new WorkflowSessionEventHub(2, 2);
    Workflow workflow = initialWorkflow();
    hub.openSession(SESSION_ID, ALICE, workflow);
    for (int index = 1; index <= 3; index++) {
      String previousLabel = index == 1 ? "Input" : "Input " + (index - 1);
      String nextLabel = "Input " + index;
      WorkflowOperation operation = rename("operation." + index, previousLabel, nextLabel);
      workflow = operation.apply(workflow);
      hub.operationAccepted(SESSION_ID, ALICE, operation, workflow);
    }

    List<WorkflowSessionEvent> retained = hub.replay(SESSION_ID, 3);
    assertEquals(List.of(4L, 5L), retained.stream().map(WorkflowSessionEvent::sequence).toList());

    List<WorkflowSessionEvent> fallback = hub.replay(SESSION_ID, 0);
    assertEquals(1, fallback.size());
    assertEquals(WorkflowSessionEvent.Type.SNAPSHOT, fallback.getFirst().type());
    assertEquals("Input 3", fallback.getFirst().workflow().nodes().getFirst().label());
    assertEquals(5, fallback.getFirst().sequence());
    assertEquals(3, fallback.getFirst().revision());
  }

  @Test
  void restoredStreamStartsAtDurableBoundaryWithoutFabricatingHistoricalEvents() {
    WorkflowSessionEventHub hub = new WorkflowSessionEventHub(8, 4);
    Workflow workflow = initialWorkflow();

    hub.restoreSession(SESSION_ID, workflow, 9, 3);

    assertEquals(9, hub.currentSequence(SESSION_ID));
    assertEquals(3, hub.currentRevision(SESSION_ID));
    List<WorkflowSessionEvent> recovery = hub.replay(SESSION_ID, 9);
    assertEquals(1, recovery.size());
    assertEquals(WorkflowSessionEvent.Type.SNAPSHOT, recovery.getFirst().type());
    assertEquals(9, recovery.getFirst().sequence());
    assertEquals(3, recovery.getFirst().revision());
    assertEquals(workflow, recovery.getFirst().workflow());

    hub.actorJoined(SESSION_ID, BOB);

    assertEquals(10, hub.currentSequence(SESSION_ID));
    assertEquals(3, hub.currentRevision(SESSION_ID));
    assertTrue(hub.replay(SESSION_ID, 10).isEmpty());
  }

  @Test
  void failedAndSlowSubscribersAreRemovedWithoutBlockingPublication() throws Exception {
    WorkflowSessionEventHub hub = new WorkflowSessionEventHub(8, 1);
    hub.openSession(SESSION_ID, ALICE, initialWorkflow());
    long cursor = hub.currentSequence(SESSION_ID);
    CountDownLatch failedCallback = new CountDownLatch(1);
    hub.subscribe(
        SESSION_ID,
        cursor,
        event -> {
          failedCallback.countDown();
          throw new IllegalStateException("transport failed");
        });

    hub.actorJoined(SESSION_ID, BOB);
    assertTrue(failedCallback.await(5, TimeUnit.SECONDS));
    awaitSubscriberCount(hub, 0);

    CountDownLatch slowCallbackStarted = new CountDownLatch(1);
    CountDownLatch releaseSlowCallback = new CountDownLatch(1);
    hub.subscribe(
        SESSION_ID,
        hub.currentSequence(SESSION_ID),
        event -> {
          slowCallbackStarted.countDown();
          try {
            releaseSlowCallback.await();
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          }
        });
    hub.actorJoined(SESSION_ID, new OperationActor("charlie", "user.charlie", "Charlie"));
    assertTrue(slowCallbackStarted.await(5, TimeUnit.SECONDS));
    hub.actorJoined(SESSION_ID, new OperationActor("dora", "user.dora", "Dora"));
    hub.actorJoined(SESSION_ID, new OperationActor("eric", "user.eric", "Eric"));
    awaitSubscriberCount(hub, 0);
    releaseSlowCallback.countDown();
  }

  private static void awaitSubscriberCount(WorkflowSessionEventHub hub, int expected)
      throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
    while (Instant.now().isBefore(deadline) && hub.subscriberCount(SESSION_ID) != expected) {
      Thread.sleep(10);
    }
    assertEquals(expected, hub.subscriberCount(SESSION_ID));
  }

  private static WorkflowOperation.RenameNode rename(
      String operationId, String previousLabel, String newLabel) {
    return new WorkflowOperation.RenameNode(
        operationId,
        Instant.parse("2026-07-17T00:00:00Z"),
        "alice",
        "node.input",
        previousLabel,
        newLabel);
  }

  private static Workflow initialWorkflow() {
    return new Workflow(
        "workflow.events",
        "Events",
        List.of(new Node("node.input", "input", "Input", List.of(), List.of())),
        List.of());
  }
}
