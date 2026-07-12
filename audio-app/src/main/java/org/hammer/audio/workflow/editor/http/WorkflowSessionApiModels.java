package org.hammer.audio.workflow.editor.http;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry.SessionSnapshot;

/** Request and response models for the workflow-session REST API. */
public final class WorkflowSessionApiModels {

  /**
   * Actor identity supplied by the transport/authentication boundary.
   *
   * @param actorId stable actor identifier
   * @param userId stable user identifier
   * @param displayName actor display name
   */
  public record ActorRequest(
      @NotBlank String actorId, @NotBlank String userId, @NotBlank String displayName) {

    OperationActor toDomain() {
      return new OperationActor(actorId, userId, displayName);
    }
  }

  /**
   * Request for creating and initially joining a session.
   *
   * @param sessionId stable unique session identifier
   * @param mode requested collaboration mode
   * @param actor owner actor metadata
   * @param workflowId optional initial workflow id
   * @param workflowName optional initial workflow name
   */
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
          workflowName == null || workflowName.isBlank() ? "Workflow " + sessionId : workflowName;
      return new Workflow(resolvedWorkflowId, resolvedWorkflowName, List.of(), List.of());
    }
  }

  /**
   * Request for joining an existing session.
   *
   * @param actorId stable actor identifier
   * @param userId stable user identifier
   * @param displayName actor display name
   */
  public record JoinSessionRequest(
      @NotBlank String actorId, @NotBlank String userId, @NotBlank String displayName) {

    OperationActor toDomain() {
      return new OperationActor(actorId, userId, displayName);
    }
  }

  /**
   * Request for leaving or closing a session.
   *
   * @param actorId stable actor identifier
   */
  public record ActorIdRequest(@NotBlank String actorId) {
    public ActorIdRequest {
      actorId = Objects.requireNonNull(actorId, "actorId");
    }
  }

  /**
   * Stable actor representation returned by the API.
   *
   * @param actorId stable actor identifier
   * @param userId stable user identifier
   * @param displayName actor display name
   */
  public record ActorResponse(String actorId, String userId, String displayName) {

    static ActorResponse from(OperationActor actor) {
      return new ActorResponse(actor.actorId(), actor.userId(), actor.displayName());
    }
  }

  /**
   * Transport response for collaboration-session metadata.
   *
   * @param sessionId stable session identifier
   * @param mode immutable collaboration mode
   * @param owner owner actor metadata
   * @param createdAt session creation timestamp
   * @param participants currently joined participants
   * @param operationCount number of applied operations
   * @param workflowId canonical workflow identifier
   */
  public record SessionResponse(
      String sessionId,
      CollaborationMode mode,
      ActorResponse owner,
      Instant createdAt,
      List<ActorResponse> participants,
      int operationCount,
      String workflowId) {
    public SessionResponse {
      participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
    }

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
