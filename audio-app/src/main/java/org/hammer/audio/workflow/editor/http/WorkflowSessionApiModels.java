package org.hammer.audio.workflow.editor.http;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry.SessionSnapshot;

/** Request and response models for the workflow-session REST API. */
public final class WorkflowSessionApiModels {

  private WorkflowSessionApiModels() {}

  /** Actor identity supplied by the transport/authentication boundary. */
  public record ActorRequest(
      @NotBlank String actorId, @NotBlank String userId, @NotBlank String displayName) {

    OperationActor toDomain() {
      return new OperationActor(actorId, userId, displayName);
    }
  }

  /** Request for creating and initially joining a session. */
  public record CreateSessionRequest(
      @NotBlank String sessionId,
      @NotNull CollaborationMode mode,
      @Valid @NotNull ActorRequest actor,
      String workflowId,
      String workflowName) {

    Workflow initialWorkflow() {
      String resolvedWorkflowId =
          workflowId == null || workflowId.isBlank() ? "workflow." + sessionId : workflowId;
      String resolvedWorkflowName =
          workflowName == null || workflowName.isBlank()
              ? "Workflow " + sessionId
              : workflowName;
      return new Workflow(resolvedWorkflowId, resolvedWorkflowName, List.of(), List.of());
    }
  }

  /** Request for joining an existing session. */
  public record JoinSessionRequest(
      @NotBlank String actorId, @NotBlank String userId, @NotBlank String displayName) {

    OperationActor toDomain() {
      return new OperationActor(actorId, userId, displayName);
    }
  }

  /** Request for leaving or closing a session. */
  public record ActorIdRequest(@NotBlank String actorId) {}

  /** Stable actor representation returned by the API. */
  public record ActorResponse(String actorId, String userId, String displayName) {

    static ActorResponse from(OperationActor actor) {
      return new ActorResponse(actor.actorId(), actor.userId(), actor.displayName());
    }
  }

  /** Transport response for collaboration-session metadata. */
  public record SessionResponse(
      String sessionId,
      CollaborationMode mode,
      ActorResponse owner,
      Instant createdAt,
      List<ActorResponse> participants,
      int operationCount,
      String workflowId) {

    static SessionResponse from(SessionSnapshot snapshot) {
      return new SessionResponse(
          snapshot.sessionId(),
          snapshot.mode(),
          ActorResponse.from(snapshot.owner()),
          snapshot.createdAt(),
          snapshot.participants().stream().map(ActorResponse::from).toList(),
          snapshot.operationCount(),
          snapshot.workflowId());
    }
  }
}
