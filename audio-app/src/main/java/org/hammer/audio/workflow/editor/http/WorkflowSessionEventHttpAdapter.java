package org.hammer.audio.workflow.editor.http;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.hammer.audio.workflow.collaboration.WorkflowSessionEvent;
import org.hammer.audio.workflow.collaboration.WorkflowSessionEventHub;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.editor.http.WorkflowSessionApiModels.SessionEventResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Spring MVC SSE adapter for ordered collaboration-session events and bounded replay. */
@RestController
@RequestMapping("/workflow/sessions")
public final class WorkflowSessionEventHttpAdapter {

  private static final long EMITTER_TIMEOUT_MILLIS = 30L * 60L * 1000L;
  private static final String CONNECTED_COMMENT = "connected";

  private final WorkflowSessionRegistry registry;
  private final WorkflowSessionEventHub eventHub;

  /** Creates the SSE adapter with the shared registry and transport-neutral event hub. */
  public WorkflowSessionEventHttpAdapter(
      WorkflowSessionRegistry registry, WorkflowSessionEventHub eventHub) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.eventHub = Objects.requireNonNull(eventHub, "eventHub");
  }

  /**
   * Streams retained and future events after a reconnect cursor.
   *
   * <p>The explicit {@code afterSequence} query parameter takes precedence over {@code
   * Last-Event-ID}. A replay gap is represented by one canonical {@code SNAPSHOT} event. A transport
   * comment is written after subscription so an empty replay still flushes the HTTP response and
   * allows the browser's {@code EventSource} to become open without inventing a domain event.
   */
  @SuppressWarnings("PMD.CloseResource")
  @GetMapping(path = "/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(
      @PathVariable("sessionId") String sessionId,
      @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
      @RequestParam(name = "afterSequence", required = false) Long afterSequence) {
    registry.inspect(sessionId);
    long cursor = resolveCursor(afterSequence, lastEventId);
    SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
    SubscriptionHolder holder = new SubscriptionHolder();
    emitter.onCompletion(holder::close);
    emitter.onTimeout(holder::close);
    emitter.onError(ignored -> holder.close());
    WorkflowSessionEventHub.Subscription subscription =
        eventHub.subscribe(sessionId, cursor, event -> send(emitter, holder, event));
    holder.attach(subscription);
    flushConnection(emitter, holder);
    return emitter;
  }

  private static void flushConnection(SseEmitter emitter, SubscriptionHolder holder) {
    try {
      emitter.send(SseEmitter.event().comment(CONNECTED_COMMENT));
    } catch (IOException | IllegalStateException exception) {
      holder.close();
      emitter.completeWithError(exception);
    }
  }

  private static void send(
      SseEmitter emitter, SubscriptionHolder holder, WorkflowSessionEvent event) {
    try {
      emitter.send(
          SseEmitter.event()
              .id(Long.toString(event.sequence()))
              .name(event.type().name())
              .data(SessionEventResponse.from(event)));
      if (event.type() == WorkflowSessionEvent.Type.SESSION_CLOSED) {
        holder.close();
        emitter.complete();
      }
    } catch (IOException | IllegalStateException exception) {
      holder.close();
      emitter.completeWithError(exception);
    }
  }

  private static long resolveCursor(Long afterSequence, String lastEventId) {
    if (afterSequence != null) {
      if (afterSequence < 0) {
        throw new IllegalArgumentException("afterSequence must be >= 0");
      }
      return afterSequence;
    }
    if (lastEventId == null || lastEventId.isBlank()) {
      return 0;
    }
    String numericId =
        lastEventId.contains(":")
            ? lastEventId.substring(lastEventId.lastIndexOf(':') + 1)
            : lastEventId;
    try {
      long parsed = Long.parseLong(numericId);
      if (parsed < 0) {
        throw new IllegalArgumentException("Last-Event-ID must be >= 0");
      }
      return parsed;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          "Last-Event-ID must contain a numeric sequence", exception);
    }
  }

  // The holder owns the subscription until an emitter completion callback closes it.
  @SuppressWarnings("PMD.CloseResource")
  private static final class SubscriptionHolder {
    private final AtomicReference<WorkflowSessionEventHub.Subscription> subscription =
        new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    void attach(WorkflowSessionEventHub.Subscription attachedSubscription) {
      Objects.requireNonNull(attachedSubscription, "attachedSubscription");
      if (!subscription.compareAndSet(null, attachedSubscription)) {
        attachedSubscription.close();
        throw new IllegalStateException("SSE subscription already attached");
      }
      if (closed.get()) {
        attachedSubscription.close();
      }
    }

    void close() {
      if (closed.compareAndSet(false, true)) {
        WorkflowSessionEventHub.Subscription attachedSubscription = subscription.getAndSet(null);
        if (attachedSubscription != null) {
          attachedSubscription.close();
        }
      }
    }
  }
}
