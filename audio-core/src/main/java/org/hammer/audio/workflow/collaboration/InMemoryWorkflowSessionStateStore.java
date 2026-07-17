package org.hammer.audio.workflow.collaboration;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory state store used by the demo profile and focused tests. */
public final class InMemoryWorkflowSessionStateStore implements WorkflowSessionStateStore {

  private final Map<String, WorkflowSessionState> states = new ConcurrentHashMap<>();
  private final WorkflowSessionEventSink eventSink;

  public InMemoryWorkflowSessionStateStore(WorkflowSessionEventSink eventSink) {
    this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
  }

  @Override
  public List<WorkflowSessionState> restore() {
    return states.values().stream()
        .sorted(Comparator.comparing(WorkflowSessionState::sessionId))
        .toList();
  }

  @Override
  public synchronized void commit(Transition transition) {
    Objects.requireNonNull(transition, "transition");
    String sessionId = transition.nextState().sessionId();
    WorkflowSessionState current = states.get(sessionId);
    if (transition.kind() == Kind.CREATE) {
      if (current != null) {
        throw new WorkflowSessionException(
            WorkflowSessionException.Code.SESSION_ALREADY_EXISTS,
            sessionId,
            "Session already exists: " + sessionId);
      }
    } else {
      WorkflowSessionState expected = transition.previousState();
      if (current == null
          || current.revision() != expected.revision()
          || current.sequence() != expected.sequence()) {
        throw new WorkflowSessionException(
            WorkflowSessionException.Code.REVISION_CONFLICT,
            sessionId,
            "Session revision/sequence changed while applying transition");
      }
    }
    if (transition.kind() == Kind.CLOSE) {
      states.remove(sessionId);
    } else {
      states.put(sessionId, transition.nextState());
    }
    eventSink.publish(transition.event());
  }
}
