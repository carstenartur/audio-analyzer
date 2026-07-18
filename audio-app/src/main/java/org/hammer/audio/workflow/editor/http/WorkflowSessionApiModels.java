package org.hammer.audio.workflow.editor.http;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.PresenceState;
import org.hammer.audio.workflow.collaboration.RedoWorkflowCommand;
import org.hammer.audio.workflow.collaboration.UndoWorkflowCommand;
import org.hammer.audio.workflow.collaboration.WorkflowHistoryCommandResult;
import org.hammer.audio.workflow.collaboration.WorkflowSessionEvent;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry.SessionSnapshot;
import org.hammer.audio.workflow.collaboration.WorkflowUndoPreview;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationCommandMetadata.Kind;
import org.hammer.audio.workflow.editor.WorkflowProjection;
import tools.jackson.databind.JsonNode;

/** Request and response models for the workflow-session REST API. */
public final class WorkflowSessionApiModels {

  private WorkflowSessionApiModels() {
    // Utility class.
  }

  static String defaultWorkflowId(String sessionId) {
    return "workflow." + sessionId;
  }

  static String defaultWorkflowName(String sessionId) {
    return "Workflow " + sessionId;
  }

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
          workflowId == null || workflowId.isBlank() ? defaultWorkflowId(sessionId) : workflowId;
      String resolvedWorkflowName =
          workflowName == null || workflowName.isBlank()
              ? defaultWorkflowName(sessionId)
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
  public record ActorIdRequest(@NotBlank String actorId) {
    public ActorIdRequest {
      actorId = Objects.requireNonNull(actorId, "actorId");
    }
  }

  /** Request for one server-authoritative semantic session operation. */
  public record SessionOperationRequest(
      @NotNull CollaborationMode mode,
      @Valid @NotNull ActorRequest actor,
      @PositiveOrZero Long expectedRevision,
      @NotNull JsonNode operation) {
    public SessionOperationRequest {
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(actor, "actor");
      Objects.requireNonNull(operation, "operation");
    }
  }

  /** Request for a revision-bound undo preview. */
  public record UndoPreviewRequest(
      @Valid @NotNull ActorRequest actor, String targetOperationId) {
    public UndoPreviewRequest {
      Objects.requireNonNull(actor, "actor");
    }
  }

  /** Request for an idempotent semantic undo command. */
  public record UndoCommandRequest(
      @NotBlank String commandId,
      @Valid @NotNull ActorRequest actor,
      @PositiveOrZero long expectedRevision,
      String targetOperationId,
      String previewId) {
    public UndoCommandRequest {
      Objects.requireNonNull(actor, "actor");
    }

    UndoWorkflowCommand toDomain() {
      return new UndoWorkflowCommand(
          commandId, actor.toDomain(), expectedRevision, targetOperationId, previewId);
    }
  }

  /** Request for an idempotent semantic redo command. */
  public record RedoCommandRequest(
      @NotBlank String commandId,
      @Valid @NotNull ActorRequest actor,
      @PositiveOrZero long expectedRevision,
      @NotBlank String targetUndoOperationId) {
    public RedoCommandRequest {
      Objects.requireNonNull(actor, "actor");
    }

    RedoWorkflowCommand toDomain() {
      return new RedoWorkflowCommand(
          commandId, actor.toDomain(), expectedRevision, targetUndoOperationId);
    }
  }

  /** Stable undo preview response. */
  public record UndoPreviewResponse(
      String previewId,
      String targetOperationId,
      String targetActorId,
      String operationType,
      List<String> affectedObjectIds,
      long revision,
      boolean safe,
      List<BlockingOperationResponse> blockingOperations) {
    public UndoPreviewResponse {
      affectedObjectIds = List.copyOf(affectedObjectIds);
      blockingOperations = List.copyOf(blockingOperations);
    }

    static UndoPreviewResponse from(WorkflowUndoPreview preview) {
      return new UndoPreviewResponse(
          preview.previewId(),
          preview.targetOperationId(),
          preview.targetActorId(),
          preview.operationType(),
          preview.affectedObjectIds(),
          preview.revision(),
          preview.safe(),
          preview.blockingOperations().stream().map(BlockingOperationResponse::from).toList());
    }
  }

  /** Machine-readable later operation blocking undo/redo. */
  public record BlockingOperationResponse(
      String operationId, String actorId, List<String> conflictingObjectIds) {
    public BlockingOperationResponse {
      conflictingObjectIds = List.copyOf(conflictingObjectIds);
    }

    static BlockingOperationResponse from(WorkflowUndoPreview.BlockingOperation blocker) {
      return new BlockingOperationResponse(
          blocker.operationId(), blocker.actorId(), blocker.conflictingObjectIds());
    }
  }

  /** Stable response for accepted undo and redo commands. */
  public record HistoryCommandResponse(
      WorkflowProjection projection,
      Kind commandKind,
      String commandId,
      String targetOperationId,
      String operationId,
      long revision,
      long sequence) {
    static HistoryCommandResponse from(WorkflowHistoryCommandResult result) {
      return new HistoryCommandResponse(
          WorkflowProjection.fromWorkflow(result.workflow()),
          result.command().kind(),
          result.command().commandId(),
          result.command().targetOperationId(),
          result.operationId(),
          result.revision(),
          result.sequence());
    }
  }

  /** Request for non-semantic cursor, selection or viewport presence state. */
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

  /** Stable transport response for accepted presence state. */
  public record PresenceResponse(
      String actorId, Instant observedAt, Map<String, String> attributes) {
    public PresenceResponse {
      attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }

    static PresenceResponse from(PresenceState state) {
      return new PresenceResponse(state.actorId(), state.observedAt(), state.attributes());
    }
  }

  /** Ordered SSE payload derived from a transport-neutral session event. */
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
      String workflowId,
      long revision,
      long sequence) {
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
          snapshot.workflowId(),
          snapshot.revision(),
          snapshot.sequence());
    }
  }
}
