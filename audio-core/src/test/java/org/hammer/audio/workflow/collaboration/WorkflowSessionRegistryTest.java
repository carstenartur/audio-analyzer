package org.hammer.audio.workflow.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.junit.jupiter.api.Test;

class WorkflowSessionRegistryTest {

  private static final OperationActor OWNER =
      new OperationActor("actor.owner", "user.owner", "Owner");
  private static final OperationActor GUEST =
      new OperationActor("actor.guest", "user.guest", "Guest");

  @Test
  void sharedSessionSupportsTwoActorsAndCanonicalWorkflow() {
    WorkflowSessionRegistry registry = new WorkflowSessionRegistry();
    registry.create(
        "session.shared", CollaborationMode.SHARED_SESSION_PERSONAL_UNDO, OWNER, emptyWorkflow());

    WorkflowSessionRegistry.SessionSnapshot joined = registry.join("session.shared", GUEST);

    assertEquals(2, joined.participants().size());
    assertEquals("workflow.session", registry.workflow("session.shared").id());
  }

  @Test
  void privateWorkspaceRejectsDifferentActorButAllowsOwnerReconnect() {
    WorkflowSessionRegistry registry = new WorkflowSessionRegistry();
    registry.create("session.private", CollaborationMode.PRIVATE_WORKSPACE, OWNER, emptyWorkflow());
    registry.leave("session.private", OWNER.actorId());

    assertCode(
        WorkflowSessionException.Code.PRIVATE_WORKSPACE_ACCESS_DENIED,
        () -> registry.join("session.private", GUEST));
    WorkflowSessionRegistry.SessionSnapshot rejoined = registry.join("session.private", OWNER);
    assertEquals(List.of(OWNER), rejoined.participants());
  }

  @Test
  void duplicateJoinIsIdempotentButMetadataMismatchIsRejected() {
    WorkflowSessionRegistry registry = new WorkflowSessionRegistry();
    registry.create(
        "session.shared", CollaborationMode.SHARED_SESSION_PERSONAL_UNDO, OWNER, emptyWorkflow());
    registry.join("session.shared", GUEST);

    assertEquals(2, registry.join("session.shared", GUEST).participants().size());
    OperationActor changedGuest =
        new OperationActor(GUEST.actorId(), GUEST.userId(), "Changed display name");
    assertCode(
        WorkflowSessionException.Code.ACTOR_METADATA_MISMATCH,
        () -> registry.join("session.shared", changedGuest));
  }

  @Test
  void operationRequiresJoinedActorMatchingSessionModeAndAuthor() {
    WorkflowSessionRegistry registry = new WorkflowSessionRegistry();
    registry.create(
        "session.shared", CollaborationMode.SHARED_SESSION_PERSONAL_UNDO, OWNER, emptyWorkflow());
    Node node = new Node("node.input", "input", "Input", List.of(), List.of(), Metadata.empty());
    WorkflowOperation operation =
        new WorkflowOperation.CreateNode(
            "operation.create", Instant.parse("2026-07-12T00:00:00Z"), OWNER.actorId(), node);

    assertCode(
        WorkflowSessionException.Code.SESSION_MODE_MISMATCH,
        () ->
            registry.applyOperation(
                "session.shared", CollaborationMode.SHARED_SESSION_SHARED_UNDO, OWNER, operation));
    assertCode(
        WorkflowSessionException.Code.ACTOR_NOT_JOINED,
        () ->
            registry.applyOperation(
                "session.shared",
                CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
                GUEST,
                new WorkflowOperation.CreateNode(
                    "operation.guest",
                    Instant.parse("2026-07-12T00:00:01Z"),
                    GUEST.actorId(),
                    node)));

    assertEquals(
        1,
        registry
            .applyOperation(
                "session.shared", CollaborationMode.SHARED_SESSION_PERSONAL_UNDO, OWNER, operation)
            .nodes()
            .size());
  }

  @Test
  void emptySessionSurvivesUntilOwnerClosesIt() {
    WorkflowSessionRegistry registry = new WorkflowSessionRegistry();
    registry.create(
        "session.shared", CollaborationMode.SHARED_SESSION_PERSONAL_UNDO, OWNER, emptyWorkflow());
    registry.leave("session.shared", OWNER.actorId());

    assertTrue(registry.inspect("session.shared").participants().isEmpty());
    assertCode(
        WorkflowSessionException.Code.SESSION_CLOSE_FORBIDDEN,
        () -> registry.close("session.shared", GUEST.actorId()));
    registry.close("session.shared", OWNER.actorId());
    assertCode(
        WorkflowSessionException.Code.SESSION_NOT_FOUND,
        () -> registry.inspect("session.shared"));
  }

  private static void assertCode(
      WorkflowSessionException.Code expected, org.junit.jupiter.api.function.Executable executable) {
    WorkflowSessionException exception = assertThrows(WorkflowSessionException.class, executable);
    assertEquals(expected, exception.code());
  }

  private static Workflow emptyWorkflow() {
    return new Workflow("workflow.session", "Session workflow", List.of(), List.of());
  }
}
