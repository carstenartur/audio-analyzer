package org.hammer.audio.workflow.editor.http;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.editor.WorkflowProjection;
import org.hammer.audio.workflow.editor.http.WorkflowSessionApiModels.ActorIdRequest;
import org.hammer.audio.workflow.editor.http.WorkflowSessionApiModels.CreateSessionRequest;
import org.hammer.audio.workflow.editor.http.WorkflowSessionApiModels.JoinSessionRequest;
import org.hammer.audio.workflow.editor.http.WorkflowSessionApiModels.SessionResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/** Spring MVC REST controller for collaboration-session lifecycle from issue #241. */
@RestController
@RequestMapping("/workflow/sessions")
public final class WorkflowSessionHttpAdapter {

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
        UriComponentsBuilder.fromPath("/workflow/sessions/{sessionId}")
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
      @PathVariable("sessionId") String sessionId, @Valid @RequestBody JoinSessionRequest request) {
    return SessionResponse.from(registry.join(sessionId, request.toDomain()));
  }

  /** Leaves a session while retaining it for reconnect. */
  @PostMapping("/{sessionId}/leave")
  public SessionResponse leave(
      @PathVariable("sessionId") String sessionId, @Valid @RequestBody ActorIdRequest request) {
    return SessionResponse.from(registry.leave(sessionId, request.actorId()));
  }

  /** Returns immutable session metadata. */
  @GetMapping("/{sessionId}")
  public SessionResponse inspect(@PathVariable("sessionId") String sessionId) {
    return SessionResponse.from(registry.inspect(sessionId));
  }

  /** Returns the current server-authoritative workflow projection. */
  @GetMapping("/{sessionId}/projection")
  public WorkflowProjection projection(@PathVariable("sessionId") String sessionId) {
    return WorkflowProjection.fromWorkflow(registry.workflow(sessionId));
  }

  /** Explicitly closes a session. Only the owner may close it. */
  @DeleteMapping("/{sessionId}")
  public ResponseEntity<Void> close(
      @PathVariable("sessionId") String sessionId, @Valid @RequestBody ActorIdRequest request) {
    registry.close(sessionId, request.actorId());
    return ResponseEntity.noContent().build();
  }
}
