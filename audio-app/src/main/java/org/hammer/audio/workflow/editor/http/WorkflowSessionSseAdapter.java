package org.hammer.audio.workflow.editor.http;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.workflow.collaboration.BoundedWorkflowSessionEventHub;
import org.hammer.audio.workflow.collaboration.WorkflowSessionEvent;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.collaboration.WorkflowSessionState;
import org.hammer.audio.workflow.editor.WorkflowProjection;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Spring MVC SSE adapter with bounded replay and canonical snapshot fallback. */
@RestController
@RequestMapping("/workflow/sessions/{sessionId}")
public final class WorkflowSessionSseAdapter {

  private final BoundedWorkflowSessionEventHub eventHub;
  private final WorkflowSessionRegistry registry;

  public WorkflowSessionSseAdapter(
      BoundedWorkflowSessionEventHub eventHub, WorkflowSessionRegistry registry) {
    this.eventHub = Objects.requireNonNull(eventHub, "eventHub");
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(
      @PathVariable String sessionId,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
      @RequestParam(required = false) Long afterSequence) {
    long after = parseAfter(lastEventId, afterSequence);
    registry.inspect(sessionId);
    SseEmitter emitter = new SseEmitter(0L);
    Object sendLock = new Object();
    BoundedWorkflowSessionEventHub.Subscription subscription =
        eventHub.subscribe(sessionId, event -> send(emitter, event, sendLock));
    emitter.onCompletion(subscription::close);
    emitter.onTimeout(subscription::close);
    emitter.onError(ignored -> subscription.close());

    BoundedWorkflowSessionEventHub.Replay replay = eventHub.replay(sessionId, after);
    if (replay.gap()) {
      WorkflowSessionState state = registry.state(sessionId);
      WorkflowSessionEvent snapshot =
          WorkflowSessionEvent.create(
              sessionId,
              state.sequence(),
              state.revision(),
              WorkflowSessionEvent.Type.SNAPSHOT,
              null,
              null,
              state,
              Map.of("reason", "replay-gap"));
      send(emitter, snapshot, sendLock);
    } else {
      replay.events().forEach(event -> send(emitter, event, sendLock));
    }
    return emitter;
  }

  private static void send(SseEmitter emitter, WorkflowSessionEvent event, Object sendLock) {
    try {
      synchronized (sendLock) {
        emitter.send(
            SseEmitter.event()
                .id(Long.toString(event.sequence()))
                .name(event.type().name().toLowerCase(Locale.ROOT))
                .data(EventResponse.from(event)));
      }
    } catch (IOException | IllegalStateException ex) {
      emitter.completeWithError(ex);
      throw new IllegalStateException("SSE client is no longer writable", ex);
    }
  }

  private static long parseAfter(String lastEventId, Long afterSequence) {
    if (afterSequence != null) {
      return Math.max(0L, afterSequence);
    }
    if (lastEventId == null || lastEventId.isBlank()) {
      return 0L;
    }
    try {
      return Math.max(0L, Long.parseLong(lastEventId));
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("Last-Event-ID must be a non-negative sequence", ex);
    }
  }

  public record EventResponse(
      String eventId,
      String sessionId,
      long sequence,
      long revision,
      WorkflowSessionEvent.Type type,
      String actorId,
      String operationId,
      WorkflowProjection projection,
      WorkflowSessionRegistry.SessionSnapshot session,
      Map<String, org.hammer.audio.workflow.collaboration.WorkflowPresence> presence,
      Map<String, String> details) {

    static EventResponse from(WorkflowSessionEvent event) {
      WorkflowSessionState state = event.state();
      WorkflowSessionRegistry.SessionSnapshot snapshot =
          state == null
              ? null
              : new WorkflowSessionRegistry.SessionSnapshot(
                  state.sessionId(),
                  state.mode(),
                  state.owner(),
                  state.createdAt(),
                  state.participants(),
                  state.operations().size(),
                  state.workflow().id(),
                  state.revision(),
                  state.sequence());
      return new EventResponse(
          event.eventId(),
          event.sessionId(),
          event.sequence(),
          event.revision(),
          event.type(),
          event.actor() == null ? null : event.actor().actorId(),
          event.operationId(),
          state == null ? null : WorkflowProjection.fromWorkflow(state.workflow()),
          snapshot,
          state == null ? Map.of() : state.presence(),
          event.details());
    }
  }
}
