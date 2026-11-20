package org.hammer.audio.workflow.editor.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.PresenceState;
import org.hammer.audio.workflow.collaboration.RedoWorkflowCommand;
import org.hammer.audio.workflow.collaboration.UndoWorkflowCommand;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry.SessionWorkflowSnapshot;
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
  private final CollaborationIdentityService identities;

  /** Creates the lifecycle REST controller. */
  public WorkflowSessionHttpAdapter(
      WorkflowSessionRegistry registry, CollaborationIdentityService identities) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.identities = Objects.requireNonNull(identities, "identities");
  }

  /** Creates a session and joins its owner. */
  @PostMapping
  public ResponseEntity<SessionResponse> create(
      @Valid @RequestBody CreateSessionRequest request, HttpServletRequest http) {
    SessionResponse response =
        SessionResponse.from(
            registry.create(
                request.sessionId(),
                request.mode(),
                identities.requireActor(http, request.actor().actorId()),
                request.initialWorkflow()),
            identities);
    URI location =
        UriComponentsBuilder.fromPath("/workflow/sessions/{" + SESSION_ID + "}")
            .buildAndExpand(response.sessionId())
            .encode()
            .toUri();
    return ResponseEntity.created(location).body(response);
  }

  /** Lists all current sessions in stable identifier order. */
  @GetMapping
  public List<SessionResponse> sessions(HttpServletRequest http) {
    OperationActor actor = identities.requireActor(http, null);
    return registry.sessions().stream()
        .filter(session -> session.participants().contains(actor) || session.owner().equals(actor))
        .map(session -> SessionResponse.from(session, identities))
        .toList();
  }

  /** Joins an existing session. */
  @PostMapping("/{sessionId}/join")
  public SessionResponse join(
      @PathVariable(SESSION_ID) String sessionId,
      @Valid @RequestBody JoinSessionRequest request,
      HttpServletRequest http) {
    return SessionResponse.from(
        registry.join(sessionId, identities.requireActor(http, request.actorId())), identities);
  }

  /** Leaves a session while retaining it for reconnect. */
  @PostMapping("/{sessionId}/leave")
  public SessionResponse leave(
      @PathVariable(SESSION_ID) String sessionId,
      @Valid @RequestBody ActorIdRequest request,
      HttpServletRequest http) {
    return SessionResponse.from(
        registry.leave(sessionId, identities.requireActor(http, request.actorId()).actorId()),
        identities);
  }

  /** Applies one joined actor's semantic operation and returns the canonical projection. */
  @PostMapping("/{sessionId}/operations")
  public WorkflowProjection applyOperation(
      @PathVariable(SESSION_ID) String sessionId,
      @Valid @RequestBody SessionOperationRequest request,
      HttpServletRequest http) {
    OperationActor actor = identities.requireActor(http, request.actor().actorId());
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
      @PathVariable(SESSION_ID) String sessionId,
      @Valid @RequestBody HistoryQueryRequest request,
      HttpServletRequest http) {
    return HistoryPageResponse.from(
        registry.history(
            sessionId,
            identities.requireActor(http, request.actor().actorId()),
            request.beforeRevision(),
            request.resolvedLimit()),
        identities::historyActor);
  }

  /** Returns actor-scoped undo, redo and shared-undo capabilities. */
  @PostMapping("/{sessionId}/history/capabilities")
  public HistoryCapabilitiesResponse capabilities(
      @PathVariable(SESSION_ID) String sessionId,
      @Valid @RequestBody HistoryCapabilitiesRequest request,
      HttpServletRequest http) {
    return HistoryCapabilitiesResponse.from(
        registry.capabilities(sessionId, identities.requireActor(http, request.actor().actorId())),
        identities::historyActor);
  }

  /** Computes an immutable timestamp-aware undo preview at the current semantic revision. */
  @PostMapping("/{sessionId}/undo/preview")
  public UndoPreviewResponse previewUndo(
      @PathVariable(SESSION_ID) String sessionId,
      @Valid @RequestBody UndoPreviewRequest request,
      HttpServletRequest http) {
    return UndoPreviewResponse.from(
        registry.previewUndo(
            sessionId,
            identities.requireActor(http, request.actor().actorId()),
            request.targetOperationId()));
  }

  /** Applies one idempotent revision-aware semantic undo command. */
  @PostMapping("/{sessionId}/undo")
  public HistoryCommandResponse undo(
      @PathVariable(SESSION_ID) String sessionId,
      @Valid @RequestBody UndoCommandRequest request,
      HttpServletRequest http) {
    return HistoryCommandResponse.from(
        registry.undo(
            sessionId,
            new UndoWorkflowCommand(
                request.commandId(),
                identities.requireActor(http, request.actor().actorId()),
                request.expectedRevision(),
                request.targetOperationId(),
                request.previewId())));
  }

  /** Computes an immutable timestamp-aware redo preview at the current semantic revision. */
  @PostMapping("/{sessionId}/redo/preview")
  public RedoPreviewResponse previewRedo(
      @PathVariable(SESSION_ID) String sessionId,
      @Valid @RequestBody RedoPreviewRequest request,
      HttpServletRequest http) {
    return RedoPreviewResponse.from(
        registry.previewRedo(
            sessionId,
            identities.requireActor(http, request.actor().actorId()),
            request.targetUndoOperationId()));
  }

  /** Applies one idempotent revision-aware semantic redo command. */
  @PostMapping("/{sessionId}/redo")
  public HistoryCommandResponse redo(
      @PathVariable(SESSION_ID) String sessionId,
      @Valid @RequestBody RedoCommandRequest request,
      HttpServletRequest http) {
    return HistoryCommandResponse.from(
        registry.redo(
            sessionId,
            new RedoWorkflowCommand(
                request.commandId(),
                identities.requireActor(http, request.actor().actorId()),
                request.expectedRevision(),
                request.targetUndoOperationId())));
  }

  /** Updates non-semantic presence and broadcasts it separately from workflow history. */
  @PutMapping("/{sessionId}/presence")
  public PresenceResponse updatePresence(
      @PathVariable(SESSION_ID) String sessionId,
      @Valid @RequestBody PresenceRequest request,
      HttpServletRequest http) {
    OperationActor actor = identities.requireActor(http, request.actor().actorId());
    return PresenceResponse.from(
        registry.updatePresence(
            sessionId,
            actor,
            new PresenceState(
                actor.actorId(),
                Instant.now(),
                CollaborationPresence.allowListed(request.attributes()))));
  }

  /** Returns immutable session metadata. */
  @GetMapping("/{sessionId}")
  public SessionResponse inspect(
      @PathVariable(SESSION_ID) String sessionId, HttpServletRequest http) {
    return SessionResponse.from(requireMember(sessionId, http).session(), identities);
  }

  /** Returns the current server-authoritative workflow projection. */
  @GetMapping("/{sessionId}/projection")
  public WorkflowProjection projection(
      @PathVariable(SESSION_ID) String sessionId, HttpServletRequest http) {
    return WorkflowProjection.fromWorkflow(requireMember(sessionId, http).workflow());
  }

  /** Explicitly closes a session. Only its owner may close it. */
  @DeleteMapping("/{sessionId}")
  public ResponseEntity<Void> close(
      @PathVariable(SESSION_ID) String sessionId,
      @Valid @RequestBody ActorIdRequest request,
      HttpServletRequest http) {
    registry.close(sessionId, identities.requireActor(http, request.actorId()).actorId());
    return ResponseEntity.noContent().build();
  }

  private SessionWorkflowSnapshot requireMember(String sessionId, HttpServletRequest http) {
    OperationActor actor = identities.requireActor(http, null);
    return registry.authorizedWorkflow(sessionId, actor);
  }
}
