package org.hammer.audio.workflow.editor.http;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.PresenceState;
import org.hammer.audio.workflow.collaboration.WorkflowSessionEvent;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry.SessionSnapshot;
import org.hammer.audio.workflow.editor.WorkflowProjection;
import tools.jackson.databind.JsonNode;

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
   * Request for one server-authoritative semantic session operation.
   *
   * @param mode collaboration mode expected by the session
   * @param actor authenticated actor applying the operation
   * @param operation semantic workflow operation payload
   */
  public record SessionOperationRequest(
      @NotNull CollaborationMode mode,
      @Valid @NotNull ActorRequest actor,
      @NotNull JsonNode operation) {
    public SessionOperationRequest {
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(actor, "actor");
      Objects.requireNonNull(operation, "operation");
    }
  }

  /**
   * Request for non-semantic cursor, selection or viewport presence state.
   *
   * @param actor actor publishing presence
   * @param observedAt client observation time; server time is used when omitted
   * @param attributes transport-neutral presence attributes
   */
  public record PresenceRequest(
      @Valid @NotNull ActorRequest actor,
      Instant observedAt,
      @NotNull Map<String, String> attributes) {

    public PresenceRequest {
      Objects.requireNonNull(actor, "actor");
      attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }

    PresenceState toDomain() {
      Instant timestamp = observedAt == null ? Instant.now() : observedAt;
      return new PresenceState(actor.actorId(), timestamp, attributes);
    }
  }

  /**
   * Stable transport response for accepted presence state.
   *
   * @param actorId stable actor identifier
   * @param observedAt accepted observation timestamp
   * @param attributes immutable presence attributes
   */
  public record PresenceResponse(
      String actorId, Instant observedAt, Map<String, String> attributes) {
    public PresenceResponse {
      attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }

    static PresenceResponse from(PresenceState state) {
      return new PresenceResponse(state.actorId(), state.observedAt(), state.attributes());
    }
  }

  /**
   * Ordered SSE payload derived from a transport-neutral session event.
   *
   * @param eventId stable SSE event identifier
   * @param sessionId collaboration session identifier
   * @param sequence monotonically increasing session event sequence
   * @param revision current semantic workflow revision
   * @param occurredAt server event timestamp
   * @param type event type
   * @param actor actor associated with the event, when present
   * @param operationId semantic operation identifier, when present
   * @param projection canonical workflow projection, when present
   * @param attributes immutable event attributes
   */
  public record SessionEventResponse(
      String eventId,
      String sessionId,
      long sequence,
      long revision,
      Instant occurredAt,
      WorkflowSessionEvent.Type type,
      ActorResponse actor,
      String operationId,
      WorkflowProjection projection,
      Map<String, String> attributes) {
    public SessionEventResponse {
      attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }

    static SessionEventResponse from(WorkflowSessionEvent event) {
      ActorResponse actorResponse =
          event.actor() == null ? null : ActorResponse.from(event.actor());
      WorkflowProjection workflowProjection =
          event.workflow() == null ? null : WorkflowProjection.fromWorkflow(event.workflow());
      return new SessionEventResponse(
          event.eventId(),
          event.sessionId(),
          event.sequence(),
          event.revision(),
          event.occurredAt(),
          event.type(),
          actorResponse,
          event.operationId(),
          workflowProjection,
          event.attributes());
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
