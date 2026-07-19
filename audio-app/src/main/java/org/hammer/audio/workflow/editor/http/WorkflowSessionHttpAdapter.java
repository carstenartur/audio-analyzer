package org.hammer.audio.workflow.editor.http;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.editor.WorkflowProjection;
import org.hammer.audio.workflow.editor.http.WorkflowHistoryApiModels.HistoryCapabilitiesRequest;
import org.hammer.audio.workflow.editor.http.WorkflowHistoryApiModels.HistoryCapabilitiesResponse;
import org.hammer.audio.workflow.editor.http.WorkflowHistoryApiModels.HistoryPageResponse;
import org.hammer.audio.workflow.editor.http.WorkflowHistoryApiModels.HistoryQueryRequest;
import org.hammer.audio.workflow.editor.http.WorkflowHistoryApiModels.RedoPreviewRequest;
import org.hammer.audio.workflow.editor.http.WorkflowHistoryApiModels.RedoPreviewResponse;
import org.hammer.audio.workflow.editor.http.WorkflowHistoryApiModels.UndoPreviewResponse;
import org.hammer.audio.workflow.editor.http.WorkflowSessionApiModels.ActorIdRequest;
import org.hammer.audio.workflow.editor.http.WorkflowSessionApiModels.CreateSessionRequest;
import org.hammer.audio.workflow.editor.http.WorkflowSessionApiModels.HistoryCommandResponse;
import org.hammer.audio.workflow.editor.http.WorkflowSessionApiModels.JoinSessionRequest;
import org.hammer.audio.workflow.editor.http.WorkflowSessionApiModels.PresenceRequest;
import org.hammer.audio.workflow.editor.http.WorkflowSessionApiModels.PresenceResponse;
import org.hammer.audio.workflow.editor.http.WorkflowSessionApiModels.RedoCommandRequest;
import org.hammer.audio.workflow.editor.http.WorkflowSessionApiModels.SessionOperationRequest;
import org.hammer.audio.workflow.editor.http.WorkflowSessionApiModels.SessionResponse;
import org.hammer.audio.workflow.editor.http.WorkflowSessionApiModels.UndoCommandRequest;
import org.hammer.audio.workflow.editor.http.WorkflowSessionApiModels.UndoPreviewRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/** Spring MVC REST controller for collaboration-session lifecycle and semantic commands. */
@RestController
@RequestMapping("/workflow/sessions")
public final class WorkflowSessionHttpAdapter {

  private static final String SESSION_ID = "sessionId";

  private final WorkflowSessionRegistry registry;

  /** Creates the lifecycle REST controller. */
  public WorkflowSessionHttpAdapter(WorkflowSessionRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  /** Creates a session and joins its owner. */
  @PostMapping
  public ResponseEntity<SessionResponse> create(@Valid @RequestBody CreateSessionRequest request) {
    SessionResponse response =
        SessionResponse.from(
            registry.create(
                request.sessionId(),
                request.mode(),
                request.actor().toDomain(),
                request.initialWorkflow()));
    URI location =
        UriComponentsBuilder.fromPath("/workflow/sessions/{" + SESSION_ID + "}")
            .buildAndExpand(response.sessionId())
            .encode()
            .toUri();
    return ResponseEntity.created(location).body(response);
  }

  /** Lists all current sessions in stable identifier order. */
  @GetMapping
  public List<SessionResponse> sessions() {
    return registry.sessions().stream().map(SessionResponse::from).toList();
  }

  /** Joins an existing session. */
  @PostMapping("/{sessionId}/join")
  public SessionResponse join(
      @PathVariable(SESSION_ID) String sessionId, @Valid @RequestBody JoinSessionRequest request) {
    return SessionResponse.from(registry.join(sessionId, request.toDomain()));
  }

  /** Leaves a session while retaining it for reconnect. */
  @PostMapping("/{sessionId}/leave")
  public SessionResponse leave(
      @PathVariable(SESSION_ID) String sessionId, @Valid @RequestBody ActorIdRequest request) {
    return SessionResponse.from(registry.leave(sessionId, request.actorId()));
  }

  /** Applies one joined actor's semantic operation and returns the canonical projection. */
  @PostMapping("/{sessionId}/operations")
  public WorkflowProjection applyOperation(
      @PathVariable(SESSION_ID) String sessionId,
      @Valid @RequestBody SessionOperationRequest request) {
    OperationActor actor = request.actor().toDomain();
    WorkflowOperation operation =
        WorkflowOperationHttpParser.parse(request.operation(), actor.actorId());
    Workflow workflow =
        request.expectedRevision() == null
            ? registry.applyOperation(sessionId, request.mode(), actor, operation)
            : registry.applyOperation(
                sessionId, request.mode(), actor, request.expectedRevision(), operation);
    return WorkflowProjection.fromWorkflow(workflow);
  }

  /** Returns one bounded newest-first page of durable semantic history. */
  @PostMapping("/{sessionId}/history/query")
  public HistoryPageResponse history(
      @PathVariable(SESSION_ID) String sessionId, @Valid @RequestBody HistoryQueryRequest request) {
    return HistoryPageResponse.from(
        registry.history(
            sessionId,
            request.actor().toDomain(),
            request.beforeRevision(),
            request.resolvedLimit()));
  }

  /** Returns actor-scoped undo, redo and shared-undo capabilities. */
  @PostMapping("/{sessionId}/history/capabilities")
  public HistoryCapabilitiesResponse capabilities(
      @PathVariable(SESSION_ID) String sessionId,
      @Valid @RequestBody HistoryCapabilitiesRequest request) {
    return HistoryCapabilitiesResponse.from(
        registry.capabilities(sessionId, request.actor().toDomain()));
  }

  /** Computes an immutable timestamp-aware undo preview at the current semantic revision. */
  @PostMapping("/{sessionId}/undo/preview")
  public UndoPreviewResponse previewUndo(
      @PathVariable(SESSION_ID) String sessionId, @Valid @RequestBody UndoPreviewRequest request) {
    return UndoPreviewResponse.from(
        registry.previewUndo(sessionId, request.actor().toDomain(), request.targetOperationId()));
  }

  /** Applies one idempotent revision-aware semantic undo command. */
  @PostMapping("/{sessionId}/undo")
  public HistoryCommandResponse undo(
      @PathVariable(SESSION_ID) String sessionId, @Valid @RequestBody UndoCommandRequest request) {
    return HistoryCommandResponse.from(registry.undo(sessionId, request.toDomain()));
  }

  /** Computes an immutable timestamp-aware redo preview at the current semantic revision. */
  @PostMapping("/{sessionId}/redo/preview")
  public RedoPreviewResponse previewRedo(
      @PathVariable(SESSION_ID) String sessionId, @Valid @RequestBody RedoPreviewRequest request) {
    return RedoPreviewResponse.from(
        registry.previewRedo(
            sessionId, request.actor().toDomain(), request.targetUndoOperationId()));
  }

  /** Applies one idempotent revision-aware semantic redo command. */
  @PostMapping("/{sessionId}/redo")
  public HistoryCommandResponse redo(
      @PathVariable(SESSION_ID) String sessionId, @Valid @RequestBody RedoCommandRequest request) {
    return HistoryCommandResponse.from(registry.redo(sessionId, request.toDomain()));
  }

  /** Updates non-semantic presence and broadcasts it separately from workflow history. */
  @PutMapping("/{sessionId}/presence")
  public PresenceResponse updatePresence(
      @PathVariable(SESSION_ID) String sessionId, @Valid @RequestBody PresenceRequest request) {
    OperationActor actor = request.actor().toDomain();
    return PresenceResponse.from(registry.updatePresence(sessionId, actor, request.toDomain()));
  }

  /** Returns immutable session metadata. */
  @GetMapping("/{sessionId}")
  public SessionResponse inspect(@PathVariable(SESSION_ID) String sessionId) {
    return SessionResponse.from(registry.inspect(sessionId));
  }

  /** Returns the current server-authoritative workflow projection. */
  @GetMapping("/{sessionId}/projection")
  public WorkflowProjection projection(@PathVariable(SESSION_ID) String sessionId) {
    return WorkflowProjection.fromWorkflow(registry.workflow(sessionId));
  }

  /** Explicitly closes a session. Only its owner may close it. */
  @DeleteMapping("/{sessionId}")
  public ResponseEntity<Void> close(
      @PathVariable(SESSION_ID) String sessionId, @Valid @RequestBody ActorIdRequest request) {
    registry.close(sessionId, request.actorId());
    return ResponseEntity.noContent().build();
  }
}
