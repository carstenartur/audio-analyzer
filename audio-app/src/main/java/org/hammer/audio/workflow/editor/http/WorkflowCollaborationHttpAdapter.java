package org.hammer.audio.workflow.editor.http;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.WorkflowPresence;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.collaboration.WorkflowSessionState;
import org.hammer.audio.workflow.editor.WorkflowProjection;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Server-authoritative REST command API for collaborative editing, presence and undo/redo. */
@RestController
@RequestMapping("/workflow/sessions/{sessionId}")
public final class WorkflowCollaborationHttpAdapter {

  private final WorkflowSessionRegistry registry;
  private final WorkflowOperationJsonCodec operationCodec;

  public WorkflowCollaborationHttpAdapter(
      WorkflowSessionRegistry registry, WorkflowOperationJsonCodec operationCodec) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.operationCodec = Objects.requireNonNull(operationCodec, "operationCodec");
  }

  @GetMapping("/state")
  public SessionStateResponse state(@PathVariable String sessionId) {
    return SessionStateResponse.from(registry.state(sessionId));
  }

  @GetMapping("/operations")
  public List<WorkflowOperation> operations(@PathVariable String sessionId) {
    return registry.operations(sessionId);
  }

  @PostMapping("/operations")
  public SessionStateResponse apply(
      @PathVariable String sessionId, @Valid @RequestBody OperationRequest request) {
    WorkflowOperation operation = operationCodec.decodeApi(request.operation());
    return SessionStateResponse.from(
        registry
            .applyOperation(
                sessionId,
                request.mode(),
                request.actor().toDomain(),
                request.expectedRevision(),
                operation)
            .event()
            .state());
  }

  @PutMapping("/presence")
  public SessionStateResponse presence(
      @PathVariable String sessionId, @Valid @RequestBody PresenceRequest request) {
    OperationActor actor = request.actor().toDomain();
    WorkflowPresence presence =
        new WorkflowPresence(
            actor.actorId(),
            request.cursorX(),
            request.cursorY(),
            request.selectedObjectIds(),
            request.viewportX(),
            request.viewportY(),
            request.viewportZoom(),
            Instant.now());
    return SessionStateResponse.from(
        registry.updatePresence(sessionId, actor, presence).event().state());
  }

  @DeleteMapping("/presence")
  public ResponseEntity<Void> clearPresence(
      @PathVariable String sessionId, @Valid @RequestBody ActorRequest actor) {
    registry.clearPresence(sessionId, actor.toDomain());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/undo")
  public SessionStateResponse undo(
      @PathVariable String sessionId, @Valid @RequestBody UndoRequest request) {
    return SessionStateResponse.from(
        registry
            .undo(
                sessionId,
                request.actor().toDomain(),
                request.expectedRevision(),
                request.targetOperationId())
            .event()
            .state());
  }

  @PostMapping("/redo")
  public SessionStateResponse redo(
      @PathVariable String sessionId, @Valid @RequestBody RedoRequest request) {
    return SessionStateResponse.from(
        registry
            .redo(sessionId, request.actor().toDomain(), request.expectedRevision())
            .event()
            .state());
  }

  public record ActorRequest(
      @NotBlank String actorId, @NotBlank String userId, @NotBlank String displayName) {
    OperationActor toDomain() {
      return new OperationActor(actorId, userId, displayName);
    }
  }

  public record OperationRequest(
      @NotNull CollaborationMode mode,
      @Valid @NotNull ActorRequest actor,
      long expectedRevision,
      @NotNull JsonNode operation) {}

  public record PresenceRequest(
      @Valid @NotNull ActorRequest actor,
      double cursorX,
      double cursorY,
      @NotNull List<String> selectedObjectIds,
      double viewportX,
      double viewportY,
      @Positive double viewportZoom) {
    public PresenceRequest {
      selectedObjectIds = List.copyOf(selectedObjectIds);
    }
  }

  public record UndoRequest(
      @Valid @NotNull ActorRequest actor, long expectedRevision, String targetOperationId) {}

  public record RedoRequest(@Valid @NotNull ActorRequest actor, long expectedRevision) {}

  public record SessionStateResponse(
      WorkflowSessionRegistry.SessionSnapshot session,
      WorkflowProjection projection,
      Map<String, WorkflowPresence> presence) {
    static SessionStateResponse from(WorkflowSessionState state) {
      WorkflowSessionRegistry.SessionSnapshot snapshot =
          new WorkflowSessionRegistry.SessionSnapshot(
              state.sessionId(),
              state.mode(),
              state.owner(),
              state.createdAt(),
              state.participants(),
              state.operations().size(),
              state.workflow().id(),
              state.revision(),
              state.sequence());
      return new SessionStateResponse(
          snapshot, WorkflowProjection.fromWorkflow(state.workflow()), state.presence());
    }
  }
}
