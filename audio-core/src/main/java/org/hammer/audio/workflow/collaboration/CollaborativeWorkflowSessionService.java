package org.hammer.audio.workflow.collaboration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.WorkflowOperationLog;

/**
 * Collaboration/application-service adapter around {@link WorkflowOperationLog}.
 *
 * <p>Keeps semantic workflow operations as the source of truth while adding:
 *
 * <ul>
 *   <li>collaboration mode handling
 *   <li>personal/shared undo scopes
 *   <li>actor metadata envelopes
 *   <li>transactional outbox + event-bus publishing boundary
 *   <li>non-semantic presence state
 * </ul>
 */
public final class CollaborativeWorkflowSessionService {

  private static final Logger LOGGER =
      Logger.getLogger(CollaborativeWorkflowSessionService.class.getName());

  private final String sessionId;
  private final CollaborationMode mode;
  private final WorkflowOperationLog operationLog;
  private final WorkflowEventOutbox eventOutbox;
  private final WorkflowEventBus eventBus;
  private final Map<String, PresenceState> presenceByActor = new ConcurrentHashMap<>();

  public CollaborativeWorkflowSessionService(
      String sessionId,
      CollaborationMode mode,
      WorkflowOperationLog operationLog,
      WorkflowEventOutbox eventOutbox,
      WorkflowEventBus eventBus) {
    this.sessionId = requireNotBlank(sessionId, "sessionId");
    this.mode = Objects.requireNonNull(mode, "mode");
    this.operationLog = Objects.requireNonNull(operationLog, "operationLog");
    this.eventOutbox = Objects.requireNonNull(eventOutbox, "eventOutbox");
    this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
  }

  public Workflow currentWorkflow() {
    return operationLog.currentWorkflow();
  }

  public List<WorkflowOperation> operations() {
    return operationLog.operations();
  }

  public Workflow applyOperation(WorkflowOperationEnvelope envelope) {
    Objects.requireNonNull(envelope, "envelope");
    assertSessionAndMode(envelope.sessionId(), envelope.mode());
    operationLog.apply(envelope.operation());
    appendAndPublish(
        WorkflowCollaborationEvent.operationApplied(
            sessionId, envelope.actor(), envelope.operation()));
    return operationLog.currentWorkflow();
  }

  public UndoResult undo(OperationActor actor) {
    Objects.requireNonNull(actor, "actor");
    if (mode.undoScope() == UndoScope.SHARED) {
      throw new IllegalStateException(
          "Shared undo mode requires explicit target operation id to avoid implicit cross-user"
              + " undo");
    }
    WorkflowOperation target = findLatestOperationByAuthor(actor.actorId());
    assertPersonalUndoSafe(actor.actorId(), target);
    return applyUndo(actor, target, mode.undoScope());
  }

  public UndoResult undo(OperationActor actor, String targetOperationId) {
    Objects.requireNonNull(actor, "actor");
    String requiredOperationId = requireNotBlank(targetOperationId, "targetOperationId");
    if (mode.undoScope() != UndoScope.SHARED) {
      throw new IllegalStateException(
          "Explicit target-operation undo is only allowed in SHARED_SESSION_SHARED_UNDO mode");
    }
    WorkflowOperation target = findOperationById(requiredOperationId);
    return applyUndo(actor, target, UndoScope.SHARED);
  }

  public void updatePresence(PresenceState state) {
    Objects.requireNonNull(state, "state");
    presenceByActor.put(state.actorId(), state);
  }

  public void clearPresence(String actorId) {
    presenceByActor.remove(requireNotBlank(actorId, "actorId"));
  }

  public Map<String, PresenceState> presenceSnapshot() {
    return Map.copyOf(presenceByActor);
  }

  private UndoResult applyUndo(
      OperationActor requestedBy, WorkflowOperation target, UndoScope scope) {
    WorkflowOperation inverse =
        target
            .inverseOperation()
            .orElseThrow(
                () ->
                    new UnsupportedOperationException(
                        "Operation has no inverse: " + target.getClass().getSimpleName()));
    operationLog.apply(inverse);
    appendAndPublish(
        WorkflowCollaborationEvent.undoApplied(
            sessionId,
            requestedBy,
            new WorkflowCollaborationEvent.UndoDetails(
                scope, target.operationId(), target.author(), inverse.operationId())));
    return new UndoResult(
        requestedBy.actorId(), scope, target.operationId(), target.author(), inverse.operationId());
  }

  private WorkflowOperation findLatestOperationByAuthor(String actorId) {
    List<WorkflowOperation> history = operationLog.operations();
    for (int i = history.size() - 1; i >= 0; i--) {
      WorkflowOperation operation = history.get(i);
      if (operation.author().equals(actorId)) {
        return operation;
      }
    }
    throw new IllegalStateException("No operation available to undo for actor: " + actorId);
  }

  private WorkflowOperation findOperationById(String operationId) {
    List<WorkflowOperation> history = operationLog.operations();
    for (int i = history.size() - 1; i >= 0; i--) {
      WorkflowOperation operation = history.get(i);
      if (operation.operationId().equals(operationId)) {
        return operation;
      }
    }
    throw new IllegalStateException("Operation not found: " + operationId);
  }

  private void assertPersonalUndoSafe(String actorId, WorkflowOperation target) {
    List<WorkflowOperation> history = operationLog.operations();
    int targetIndex = indexOfOperation(history, target.operationId());
    Set<String> affected = new LinkedHashSet<>(target.affectedObjectIds());
    for (int i = targetIndex + 1; i < history.size(); i++) {
      WorkflowOperation later = history.get(i);
      if (!later.author().equals(actorId) && intersects(affected, later.affectedObjectIds())) {
        throw new IllegalStateException(
            "Personal undo would revert operation from actor "
                + later.author()
                + " touching shared object(s): "
                + intersectingIds(affected, later.affectedObjectIds()));
      }
    }
  }

  private static boolean intersects(Set<String> left, List<String> right) {
    for (String value : right) {
      if (left.contains(value)) {
        return true;
      }
    }
    return false;
  }

  private static List<String> intersectingIds(Set<String> left, List<String> right) {
    List<String> matches = new ArrayList<>();
    for (String value : right) {
      if (left.contains(value)) {
        matches.add(value);
      }
    }
    return matches;
  }

  private static int indexOfOperation(List<WorkflowOperation> history, String operationId) {
    for (int i = history.size() - 1; i >= 0; i--) {
      if (history.get(i).operationId().equals(operationId)) {
        return i;
      }
    }
    throw new IllegalStateException("Operation not found in history: " + operationId);
  }

  private void appendAndPublish(WorkflowCollaborationEvent event) {
    eventOutbox.append(event);
    for (WorkflowEventOutbox.OutboxEntry entry : eventOutbox.pending()) {
      try {
        eventBus.publish(entry.event());
        eventOutbox.markPublished(entry.entryId());
      } catch (RuntimeException ex) {
        LOGGER.log(
            Level.WARNING,
            "Failed to publish collaboration event for session "
                + sessionId
                + ", outboxEntryId="
                + entry.entryId()
                + ", eventType="
                + entry.event().type(),
            ex);
        break;
      }
    }
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private void assertSessionAndMode(String envelopeSessionId, CollaborationMode envelopeMode) {
    if (!sessionId.equals(envelopeSessionId)) {
      throw new IllegalArgumentException(
          "envelope sessionId '"
              + envelopeSessionId
              + "' does not match session '"
              + sessionId
              + "'");
    }
    if (mode != envelopeMode) {
      throw new IllegalArgumentException(
          "envelope mode '" + envelopeMode + "' does not match session mode '" + mode + "'");
    }
  }

  /**
   * Result of an undo operation.
   *
   * @param requestedByActor actor requesting undo
   * @param scope effective undo scope
   * @param revertedOperationId operation id that was reverted
   * @param revertedActorId actor whose operation was reverted
   * @param undoOperationId semantic inverse operation id that was applied
   */
  public record UndoResult(
      String requestedByActor,
      UndoScope scope,
      String revertedOperationId,
      String revertedActorId,
      String undoOperationId) {
    public UndoResult {
      requireNotBlank(requestedByActor, "requestedByActor");
      Objects.requireNonNull(scope, "scope");
      requireNotBlank(revertedOperationId, "revertedOperationId");
      requireNotBlank(revertedActorId, "revertedActorId");
      requireNotBlank(undoOperationId, "undoOperationId");
    }
  }
}
