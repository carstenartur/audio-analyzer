package org.hammer.audio.workflow.editor.http;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.WorkflowHistoryCapabilities;
import org.hammer.audio.workflow.collaboration.WorkflowHistoryDescriptor;
import org.hammer.audio.workflow.collaboration.WorkflowHistoryPage;
import org.hammer.audio.workflow.collaboration.WorkflowRedoPreview;
import org.hammer.audio.workflow.collaboration.WorkflowUndoPreview;
import org.hammer.audio.workflow.editor.http.WorkflowSessionApiModels.ActorRequest;

/** Request and response models for durable semantic workflow history discovery. */
public final class WorkflowHistoryApiModels {

  static final int DEFAULT_HISTORY_LIMIT = 50;

  private WorkflowHistoryApiModels() {
    // Utility class.
  }

  /**
   * Request for one stable newest-first history page.
   *
   * @param actor joined actor requesting history
   * @param beforeRevision optional exclusive semantic-revision cursor
   * @param limit optional page size between 1 and 100
   */
  public record HistoryQueryRequest(
      @Valid @NotNull ActorRequest actor,
      @Positive Long beforeRevision,
      @Min(1) @Max(100) Integer limit) {

    public HistoryQueryRequest {
      Objects.requireNonNull(actor, "actor");
    }

    int resolvedLimit() {
      return limit == null ? DEFAULT_HISTORY_LIMIT : limit;
    }
  }

  /**
   * Request for actor-scoped undo and redo capabilities.
   *
   * @param actor joined actor requesting current capabilities
   */
  public record HistoryCapabilitiesRequest(@Valid @NotNull ActorRequest actor) {
    public HistoryCapabilitiesRequest {
      Objects.requireNonNull(actor, "actor");
    }
  }

  /**
   * Request for a revision-bound redo preview.
   *
   * @param actor joined actor requesting the preview
   * @param targetUndoOperationId accepted actor-owned undo operation to preview
   */
  public record RedoPreviewRequest(
      @Valid @NotNull ActorRequest actor, @NotBlank String targetUndoOperationId) {
    public RedoPreviewRequest {
      Objects.requireNonNull(actor, "actor");
    }
  }

  /**
   * Stable transport representation of one accepted semantic operation.
   *
   * @param operationId stable accepted-operation identifier
   * @param operationType semantic operation type
   * @param actorId actor that authored the operation
   * @param occurredAt operation occurrence timestamp
   * @param revision semantic revision produced by the operation
   * @param sequence durable event sequence assigned to the operation
   * @param commandKind normal, undo or redo command category
   * @param commandId stable command idempotency identity
   * @param targetOperationId undo/redo target, otherwise {@code null}
   * @param affectedObjectIds affected semantic object identifiers
   * @param reconstructible whether the complete semantic body is available
   * @param activeUndoTarget whether the operation is currently an active undo target
   * @param activeRedoTarget whether the operation is currently an active redo target
   */
  public record HistoryEntryResponse(
      String operationId,
      String operationType,
      String actorId,
      Instant occurredAt,
      long revision,
      long sequence,
      WorkflowHistoryDescriptor.CommandKind commandKind,
      String commandId,
      String targetOperationId,
      List<String> affectedObjectIds,
      boolean reconstructible,
      boolean activeUndoTarget,
      boolean activeRedoTarget) {

    public HistoryEntryResponse {
      affectedObjectIds =
          List.copyOf(Objects.requireNonNull(affectedObjectIds, "affectedObjectIds"));
    }

    static HistoryEntryResponse from(WorkflowHistoryDescriptor descriptor) {
      return new HistoryEntryResponse(
          descriptor.operationId(),
          descriptor.operationType(),
          descriptor.actorId(),
          descriptor.occurredAt(),
          descriptor.revision(),
          descriptor.sequence(),
          descriptor.commandKind(),
          descriptor.commandId(),
          descriptor.targetOperationId(),
          descriptor.affectedObjectIds(),
          descriptor.reconstructible(),
          descriptor.activeUndoTarget(),
          descriptor.activeRedoTarget());
    }
  }

  /**
   * Stable newest-first durable history page.
   *
   * @param operations immutable history entries
   * @param nextBeforeRevision exclusive cursor for the next older page, or {@code null}
   * @param currentRevision current session revision at query time
   */
  public record HistoryPageResponse(
      List<HistoryEntryResponse> operations, Long nextBeforeRevision, long currentRevision) {

    public HistoryPageResponse {
      operations = List.copyOf(Objects.requireNonNull(operations, "operations"));
    }

    static HistoryPageResponse from(WorkflowHistoryPage page) {
      return new HistoryPageResponse(
          page.operations().stream().map(HistoryEntryResponse::from).toList(),
          page.nextBeforeRevision(),
          page.currentRevision());
    }
  }

  /**
   * Actor-scoped current undo or redo target.
   *
   * @param operation selected immutable history operation
   * @param status authoritative current availability
   * @param available whether the action is currently executable
   * @param blockingOperations later semantic operations blocking execution
   */
  public record HistoryActionResponse(
      HistoryEntryResponse operation,
      WorkflowHistoryCapabilities.ActionStatus status,
      boolean available,
      List<BlockingOperationResponse> blockingOperations) {

    public HistoryActionResponse {
      Objects.requireNonNull(operation, "operation");
      Objects.requireNonNull(status, "status");
      blockingOperations =
          List.copyOf(Objects.requireNonNull(blockingOperations, "blockingOperations"));
    }

    static HistoryActionResponse from(WorkflowHistoryCapabilities.Action action) {
      if (action == null) {
        return null;
      }
      return new HistoryActionResponse(
          HistoryEntryResponse.from(action.operation()),
          action.status(),
          action.available(),
          action.blockingOperations().stream().map(BlockingOperationResponse::from).toList());
    }
  }

  /**
   * Actor-scoped current history capabilities.
   *
   * @param mode immutable collaboration mode
   * @param revision semantic revision at query time
   * @param personalUndoPermitted whether the mode permits personal undo
   * @param personalUndo current personal-undo target, or {@code null}
   * @param redo current actor-owned redo target, or {@code null}
   * @param sharedUndoPermitted whether explicit shared-target undo is permitted
   */
  public record HistoryCapabilitiesResponse(
      CollaborationMode mode,
      long revision,
      boolean personalUndoPermitted,
      HistoryActionResponse personalUndo,
      HistoryActionResponse redo,
      boolean sharedUndoPermitted) {

    static HistoryCapabilitiesResponse from(WorkflowHistoryCapabilities capabilities) {
      return new HistoryCapabilitiesResponse(
          capabilities.mode(),
          capabilities.revision(),
          capabilities.personalUndoPermitted(),
          HistoryActionResponse.from(capabilities.personalUndo()),
          HistoryActionResponse.from(capabilities.redo()),
          capabilities.sharedUndoPermitted());
    }
  }

  /**
   * Machine-readable later operation blocking undo or redo.
   *
   * @param operationId stable accepted-operation identifier
   * @param actorId actor that authored the blocker
   * @param conflictingObjectIds semantic object identifiers shared with the target
   */
  public record BlockingOperationResponse(
      String operationId, String actorId, List<String> conflictingObjectIds) {

    public BlockingOperationResponse {
      conflictingObjectIds =
          List.copyOf(Objects.requireNonNull(conflictingObjectIds, "conflictingObjectIds"));
    }

    static BlockingOperationResponse from(WorkflowUndoPreview.BlockingOperation blocker) {
      return new BlockingOperationResponse(
          blocker.operationId(), blocker.actorId(), blocker.conflictingObjectIds());
    }
  }

  /**
   * Stable timestamp-aware undo preview response.
   *
   * @param previewId identity bound to revision, target and blockers
   * @param targetOperationId selected accepted operation
   * @param targetActorId actor that authored the target
   * @param operationType semantic operation type
   * @param targetOccurredAt target operation occurrence timestamp
   * @param affectedObjectIds affected semantic object identifiers
   * @param revision revision at which the preview is valid
   * @param safe whether no later operation blocks execution
   * @param blockingOperations later semantic blockers
   */
  public record UndoPreviewResponse(
      String previewId,
      String targetOperationId,
      String targetActorId,
      String operationType,
      Instant targetOccurredAt,
      List<String> affectedObjectIds,
      long revision,
      boolean safe,
      List<BlockingOperationResponse> blockingOperations) {

    public UndoPreviewResponse {
      affectedObjectIds =
          List.copyOf(Objects.requireNonNull(affectedObjectIds, "affectedObjectIds"));
      blockingOperations =
          List.copyOf(Objects.requireNonNull(blockingOperations, "blockingOperations"));
    }

    static UndoPreviewResponse from(WorkflowUndoPreview preview) {
      return new UndoPreviewResponse(
          preview.previewId(),
          preview.targetOperationId(),
          preview.targetActorId(),
          preview.operationType(),
          preview.targetOccurredAt(),
          preview.affectedObjectIds(),
          preview.revision(),
          preview.safe(),
          preview.blockingOperations().stream().map(BlockingOperationResponse::from).toList());
    }
  }

  /**
   * Stable timestamp-aware redo preview response.
   *
   * @param previewId identity bound to revision, target and blockers
   * @param targetUndoOperationId selected accepted undo operation
   * @param targetActorId actor that authored the undo operation
   * @param operationType semantic operation type of the undo operation
   * @param targetOccurredAt undo operation occurrence timestamp
   * @param affectedObjectIds affected semantic object identifiers
   * @param revision revision at which the preview is valid
   * @param safe whether no later operation blocks execution
   * @param blockingOperations later semantic blockers
   */
  public record RedoPreviewResponse(
      String previewId,
      String targetUndoOperationId,
      String targetActorId,
      String operationType,
      Instant targetOccurredAt,
      List<String> affectedObjectIds,
      long revision,
      boolean safe,
      List<BlockingOperationResponse> blockingOperations) {

    public RedoPreviewResponse {
      affectedObjectIds =
          List.copyOf(Objects.requireNonNull(affectedObjectIds, "affectedObjectIds"));
      blockingOperations =
          List.copyOf(Objects.requireNonNull(blockingOperations, "blockingOperations"));
    }

    static RedoPreviewResponse from(WorkflowRedoPreview preview) {
      return new RedoPreviewResponse(
          preview.previewId(),
          preview.targetUndoOperationId(),
          preview.targetActorId(),
          preview.operationType(),
          preview.targetOccurredAt(),
          preview.affectedObjectIds(),
          preview.revision(),
          preview.safe(),
          preview.blockingOperations().stream().map(BlockingOperationResponse::from).toList());
    }
  }
}
