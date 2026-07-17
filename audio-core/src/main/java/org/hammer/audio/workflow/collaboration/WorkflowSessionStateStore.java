package org.hammer.audio.workflow.collaboration;

import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.WorkflowOperation;

/** Persistence/outbox boundary used by the session aggregate. */
public interface WorkflowSessionStateStore {

  /** Restores all active sessions at application startup. */
  List<WorkflowSessionState> restore();

  /** Atomically persists one state transition and its outbound event. */
  void commit(Transition transition);

  /** Kinds are deliberately storage-neutral and suitable for audit projections. */
  enum Kind {
    CREATE,
    MEMBERSHIP,
    OPERATION,
    PRESENCE,
    UNDO,
    REDO,
    CLOSE
  }

  /** One optimistic state transition. A null previous state is valid only for CREATE. */
  record Transition(
      Kind kind,
      WorkflowSessionState previousState,
      WorkflowSessionState nextState,
      WorkflowOperation operation,
      WorkflowSessionEvent event) {

    public Transition {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(nextState, "nextState");
      Objects.requireNonNull(event, "event");
      if (kind == Kind.CREATE && previousState != null) {
        throw new IllegalArgumentException("CREATE must not have a previous state");
      }
      if (kind != Kind.CREATE && previousState == null) {
        throw new IllegalArgumentException(kind + " requires a previous state");
      }
    }
  }

  static WorkflowSessionStateStore noOp() {
    return new WorkflowSessionStateStore() {
      @Override
      public List<WorkflowSessionState> restore() {
        return List.of();
      }

      @Override
      public void commit(Transition transition) {
        Objects.requireNonNull(transition, "transition");
      }
    };
  }
}
