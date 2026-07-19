package org.hammer.audio.workflow.collaboration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.collaboration.WorkflowHistoryCapabilities.Action;
import org.hammer.audio.workflow.collaboration.WorkflowHistoryCapabilities.ActionStatus;
import org.hammer.audio.workflow.collaboration.WorkflowHistoryDescriptor.CommandKind;
import org.hammer.audio.workflow.collaboration.WorkflowSessionException.Code;
import org.hammer.audio.workflow.collaboration.WorkflowSessionPersistenceCoordinator.AppendOutcome;
import org.hammer.audio.workflow.collaboration.WorkflowSessionPersistenceCoordinator.OperationIdentity;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationBodyCodec;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationCommandMetadata;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationCommandMetadata.Kind;

/** Owns the synchronized runtime state for one collaboration session. */
final class WorkflowSessionEntry {

  private static final int MAX_HISTORY_PAGE_SIZE = 100;

  private final String sessionId;
  private final CollaborationMode mode;
  private final OperationActor sessionOwner;
  private final Instant createdAt;
  private final WorkflowSessionEventHub eventHub;
  private final WorkflowSessionPersistenceCoordinator persistence;
  private final WorkflowSessionIndex<String, OperationActor> participants =
      new WorkflowSessionIndex<>();
  private final WorkflowSessionIndex<String, OperationIdentity> operationsById =
      new WorkflowSessionIndex<>();
  private final List<OperationIdentity> operationHistory = new ArrayList<>();
  private final ReentrantLock lock = new ReentrantLock();
  private Workflow currentWorkflow;
  private int operationCount;
  private long revision;
  private long sequence;
  private volatile boolean closed;

  private WorkflowSessionEntry(
      SessionDefinition definition, RecoveryState recovery, Services services) {
    this.sessionId = definition.sessionId();
    this.mode = definition.mode();
    this.sessionOwner = definition.owner();
    this.createdAt = definition.createdAt();
    this.currentWorkflow = definition.workflow();
    this.eventHub = services.eventHub();
    this.persistence = services.persistence();
    this.revision = recovery.revision();
    this.sequence = recovery.sequence();
    for (OperationIdentity operation : recovery.operations()) {
      OperationIdentity previous = operationsById.putIfAbsent(operation.operationId(), operation);
      if (previous != null) {
        throw new WorkflowSessionRecoveryException(
            sessionId, "Duplicate durable operation id: " + operation.operationId());
      }
      operationHistory.add(operation);
    }
    this.operationCount = recovery.operations().size();
    if (recovery.ownerConnected()) {
      participants.put(sessionOwner.actorId(), sessionOwner);
    }
  }

  static WorkflowSessionEntry created(
      String sessionId,
      CollaborationMode mode,
      OperationActor owner,
      Instant createdAt,
      Workflow workflow,
      WorkflowSessionEventHub eventHub,
      WorkflowSessionPersistenceCoordinator persistence) {
    SessionDefinition definition =
        new SessionDefinition(sessionId, mode, owner, createdAt, workflow);
    RecoveryState recovery = new RecoveryState(List.of(), true, 0, 2);
    return new WorkflowSessionEntry(definition, recovery, new Services(eventHub, persistence));
  }

  static WorkflowSessionEntry recovered(
      SessionDefinition definition,
      RecoveryState recovery,
      WorkflowSessionEventHub eventHub,
      WorkflowSessionPersistenceCoordinator persistence) {
    return new WorkflowSessionEntry(definition, recovery, new Services(eventHub, persistence));
  }

  void verifyInitialEventStream(long expectedSequence) {
    lock.lock();
    try {
      verifyEventHubState(expectedSequence, 0, "session creation");
    } finally {
      lock.unlock();
    }
  }

  WorkflowSessionRegistry.SessionSnapshot join(OperationActor actor) {
    lock.lock();
    try {
      requireOpen();
      if (mode == CollaborationMode.PRIVATE_WORKSPACE
          && !sessionOwner.actorId().equals(actor.actorId())) {
        throw error(
            Code.PRIVATE_WORKSPACE_ACCESS_DENIED,
            "Private workspace can only be joined by its owner: " + sessionOwner.actorId());
      }
      OperationActor existing = participants.get(actor.actorId());
      if (existing != null && !existing.equals(actor)) {
        throw error(
            Code.ACTOR_METADATA_MISMATCH,
            "Actor metadata mismatch for already joined actor: " + actor.actorId());
      }
      if (existing == null) {
        long nextSequence = reserveNonSemanticEvent();
        participants.put(actor.actorId(), actor);
        eventHub.actorJoined(sessionId, actor);
        confirmNonSemanticEvent(nextSequence, "actor join");
      }
      return snapshotLocked();
    } finally {
      lock.unlock();
    }
  }

  WorkflowSessionRegistry.SessionSnapshot leave(String actorId) {
    lock.lock();
    try {
      requireOpen();
      OperationActor actor = participants.get(actorId);
      if (actor == null) {
        throw error(Code.ACTOR_NOT_JOINED, "Actor is not joined: " + actorId);
      }
      long nextSequence = reserveNonSemanticEvent();
      participants.remove(actorId);
      eventHub.actorLeft(sessionId, actor);
      confirmNonSemanticEvent(nextSequence, "actor leave");
      return snapshotLocked();
    } finally {
      lock.unlock();
    }
  }

  Workflow apply(
      CollaborationMode requestedMode, OperationActor actor, WorkflowOperation operation) {
    lock.lock();
    try {
      return applyLocked(
              requestedMode,
              actor,
              revision,
              operation,
              WorkflowOperationCommandMetadata.normal(operation.operationId()))
          .workflow();
    } finally {
      lock.unlock();
    }
  }

  Workflow apply(
      CollaborationMode requestedMode,
      OperationActor actor,
      long expectedRevision,
      WorkflowOperation operation) {
    lock.lock();
    try {
      return applyLocked(
              requestedMode,
              actor,
              expectedRevision,
              operation,
              WorkflowOperationCommandMetadata.normal(operation.operationId()))
          .workflow();
    } finally {
      lock.unlock();
    }
  }

  WorkflowUndoPreview previewUndo(OperationActor actor, String targetOperationId) {
    lock.lock();
    try {
      requireOpen();
      assertJoinedActor(actor);
      return previewUndoLocked(actor, targetOperationId);
    } finally {
      lock.unlock();
    }
  }

  WorkflowRedoPreview previewRedo(OperationActor actor, String targetUndoOperationId) {
    lock.lock();
    try {
      requireOpen();
      assertJoinedActor(actor);
      return previewRedoLocked(actor, targetUndoOperationId);
    } finally {
      lock.unlock();
    }
  }

  WorkflowHistoryPage history(OperationActor actor, Long beforeRevision, int limit) {
    lock.lock();
    try {
      requireOpen();
      assertJoinedActor(actor);
      if (limit <= 0 || limit > MAX_HISTORY_PAGE_SIZE) {
        throw new IllegalArgumentException(
            "history limit must be between 1 and " + MAX_HISTORY_PAGE_SIZE);
      }
      long cursor = beforeRevision == null ? Math.addExact(revision, 1) : beforeRevision;
      if (cursor <= 0 || cursor > Math.addExact(revision, 1)) {
        throw new IllegalArgumentException(
            "beforeRevision must be between 1 and current revision plus one");
      }
      List<WorkflowHistoryDescriptor> entries = new ArrayList<>();
      for (int index = operationHistory.size() - 1; index >= 0; index--) {
        OperationIdentity operation = operationHistory.get(index);
        if (operation.revision() >= cursor) {
          continue;
        }
        entries.add(describe(operation));
        if (entries.size() > limit) {
          break;
        }
      }
      Long nextBeforeRevision = null;
      if (entries.size() > limit) {
        entries.remove(entries.size() - 1);
        nextBeforeRevision = entries.get(entries.size() - 1).revision();
      }
      return new WorkflowHistoryPage(entries, nextBeforeRevision, revision);
    } finally {
      lock.unlock();
    }
  }

  WorkflowHistoryCapabilities capabilities(OperationActor actor) {
    lock.lock();
    try {
      requireOpen();
      assertJoinedActor(actor);
      boolean personalUndoPermitted = mode != CollaborationMode.SHARED_SESSION_SHARED_UNDO;
      OperationIdentity personalUndo =
          personalUndoPermitted ? findPersonalUndoTarget(actor.actorId()) : null;
      OperationIdentity redo = findRedoTarget(actor.actorId());
      return new WorkflowHistoryCapabilities(
          mode,
          revision,
          personalUndoPermitted,
          historyAction(personalUndo),
          historyAction(redo),
          mode == CollaborationMode.SHARED_SESSION_SHARED_UNDO);
    } finally {
      lock.unlock();
    }
  }

  WorkflowHistoryCommandResult undo(UndoWorkflowCommand command) {
    lock.lock();
    try {
      requireOpen();
      assertJoinedActor(command.actor());
      WorkflowHistoryCommandResult retry =
          historyCommandRetry(
              command.commandId(),
              Kind.UNDO,
              command.targetOperationId(),
              command.actor().actorId());
      if (retry != null) {
        return retry;
      }
      persistence.requireExpectedRevision(sessionId, command.expectedRevision(), revision);
      WorkflowUndoPreview preview = previewUndoLocked(command.actor(), command.targetOperationId());
      if (mode == CollaborationMode.SHARED_SESSION_SHARED_UNDO) {
        if (command.previewId() == null) {
          throw error(Code.UNDO_PREVIEW_REQUIRED, "Shared undo requires a preview id");
        }
        if (!preview.previewId().equals(command.previewId())) {
          throw error(Code.UNDO_PREVIEW_STALE, "Undo preview no longer matches current history");
        }
      }
      if (!preview.safe()) {
        throw new WorkflowUndoConflictException(
            sessionId, preview.targetOperationId(), preview.blockingOperations());
      }
      OperationIdentity target =
          requireOperation(preview.targetOperationId(), Code.UNDO_TARGET_NOT_FOUND);
      WorkflowOperation targetOperation = requireUndoableOperation(target);
      WorkflowOperation inverse =
          targetOperation
              .inverseOperation()
              .orElseThrow(
                  () ->
                      error(
                          Code.OPERATION_NOT_UNDOABLE,
                          "Operation has no semantic inverse: " + target.operationId()));
      String operationId = command.commandId() + ":operation";
      WorkflowOperation undoOperation =
          WorkflowOperationBodyCodec.reidentify(
              inverse,
              operationId,
              Instant.now().truncatedTo(ChronoUnit.MICROS),
              command.actor().actorId());
      WorkflowOperationCommandMetadata metadata =
          WorkflowOperationCommandMetadata.undo(command.commandId(), target.operationId());
      AppendOutcome outcome =
          applyLocked(mode, command.actor(), command.expectedRevision(), undoOperation, metadata);
      return result(outcome, metadata, operationId);
    } finally {
      lock.unlock();
    }
  }

  WorkflowHistoryCommandResult redo(RedoWorkflowCommand command) {
    lock.lock();
    try {
      requireOpen();
      assertJoinedActor(command.actor());
      WorkflowHistoryCommandResult retry =
          historyCommandRetry(
              command.commandId(),
              Kind.REDO,
              command.targetUndoOperationId(),
              command.actor().actorId());
      if (retry != null) {
        return retry;
      }
      persistence.requireExpectedRevision(sessionId, command.expectedRevision(), revision);
      WorkflowRedoPreview preview =
          previewRedoLocked(command.actor(), command.targetUndoOperationId());
      if (!preview.safe()) {
        throw new WorkflowUndoConflictException(
            sessionId, preview.targetUndoOperationId(), preview.blockingOperations());
      }
      OperationIdentity targetUndo =
          requireOperation(preview.targetUndoOperationId(), Code.REDO_TARGET_NOT_FOUND);
      WorkflowOperation targetOperation = requireUndoableOperation(targetUndo);
      WorkflowOperation inverse =
          targetOperation
              .inverseOperation()
              .orElseThrow(
                  () ->
                      error(
                          Code.REDO_TARGET_INVALID,
                          "Undo operation has no semantic inverse: " + targetUndo.operationId()));
      String operationId = command.commandId() + ":operation";
      WorkflowOperation redoOperation =
          WorkflowOperationBodyCodec.reidentify(
              inverse,
              operationId,
              Instant.now().truncatedTo(ChronoUnit.MICROS),
              command.actor().actorId());
      WorkflowOperationCommandMetadata metadata =
          WorkflowOperationCommandMetadata.redo(command.commandId(), targetUndo.operationId());
      AppendOutcome outcome =
          applyLocked(mode, command.actor(), command.expectedRevision(), redoOperation, metadata);
      return result(outcome, metadata, operationId);
    } finally {
      lock.unlock();
    }
  }

  PresenceState updatePresence(OperationActor actor, PresenceState presenceState) {
    lock.lock();
    try {
      requireOpen();
      assertJoinedActor(actor);
      long nextSequence = reserveNonSemanticEvent();
      eventHub.presenceUpdated(sessionId, actor, presenceState);
      confirmNonSemanticEvent(nextSequence, "presence update");
      return presenceState;
    } finally {
      lock.unlock();
    }
  }

  Workflow workflow() {
    lock.lock();
    try {
      requireOpen();
      return currentWorkflow;
    } finally {
      lock.unlock();
    }
  }

  WorkflowSessionRegistry.SessionSnapshot snapshot() {
    lock.lock();
    try {
      requireOpen();
      return snapshotLocked();
    } finally {
      lock.unlock();
    }
  }

  long close(String actorId) {
    lock.lock();
    try {
      requireOpen();
      if (!sessionOwner.actorId().equals(actorId)) {
        throw error(
            Code.SESSION_CLOSE_FORBIDDEN,
            "Only the session owner may close it: " + sessionOwner.actorId());
      }
      sequence = persistence.close(sessionId, revision, sequence);
      closed = true;
      return sequence;
    } finally {
      lock.unlock();
    }
  }

  void verifyClosedEvent(long finalSequence) {
    lock.lock();
    try {
      verifyEventHubState(finalSequence, revision, "session close");
    } finally {
      lock.unlock();
    }
  }

  OperationActor owner() {
    return sessionOwner;
  }

  private AppendOutcome applyLocked(
      CollaborationMode requestedMode,
      OperationActor actor,
      long expectedRevision,
      WorkflowOperation operation,
      WorkflowOperationCommandMetadata command) {
    requireOpen();
    assertModeAndActor(requestedMode, actor);
    OperationIdentity candidate = persistence.identity(operation, command);
    OperationIdentity previous = operationsById.get(operation.operationId());
    if (previous != null) {
      if (previous.matchesRetry(candidate)) {
        return new AppendOutcome(currentWorkflow, previous, revision, sequence, true);
      }
      throw duplicateOperation(operation.operationId());
    }
    persistence.requireExpectedRevision(sessionId, expectedRevision, revision);

    Workflow updatedWorkflow = operation.apply(currentWorkflow);
    AppendOutcome outcome =
        persistence.append(sessionId, revision, sequence, operation, updatedWorkflow, command);
    revision = outcome.revision();
    sequence = outcome.sequence();
    currentWorkflow = outcome.workflow();
    if (outcome.duplicate()) {
      operationsById.put(operation.operationId(), outcome.identity());
      if (operationHistory.stream()
          .noneMatch(identity -> identity.operationId().equals(operation.operationId()))) {
        operationHistory.add(outcome.identity());
      }
      operationCount = Math.toIntExact(revision);
      return outcome;
    }

    if (!updatedWorkflow.equals(currentWorkflow)) {
      throw new IllegalStateException(
          "Persisted workflow differs from applied workflow for operation "
              + operation.operationId());
    }
    operationsById.put(operation.operationId(), outcome.identity());
    operationHistory.add(outcome.identity());
    operationCount++;
    eventHub.operationAccepted(sessionId, actor, operation, currentWorkflow, command);
    verifyEventHubState(sequence, revision, "accepted operation");
    return outcome;
  }

  private WorkflowUndoPreview previewUndoLocked(OperationActor actor, String requestedTargetId) {
    OperationIdentity target = selectUndoTarget(actor, requestedTargetId);
    WorkflowOperation targetOperation = requireUndoableOperation(target);
    List<String> affectedObjectIds = sortedAffectedObjectIds(targetOperation);
    List<WorkflowUndoPreview.BlockingOperation> blockers =
        blockingOperations(target, affectedObjectIds);
    return new WorkflowUndoPreview(
        previewId(target.operationId(), blockers),
        target.operationId(),
        target.actorId(),
        target.operationType(),
        target.occurredAt(),
        affectedObjectIds,
        revision,
        blockers);
  }

  private WorkflowRedoPreview previewRedoLocked(
      OperationActor actor, String targetUndoOperationId) {
    OperationIdentity targetUndo =
        requireOperation(targetUndoOperationId, Code.REDO_TARGET_NOT_FOUND);
    if (targetUndo.command().kind() != Kind.UNDO || !targetUndo.actorId().equals(actor.actorId())) {
      throw error(
          Code.REDO_TARGET_INVALID,
          "Redo target is not an undo command owned by the requesting actor: "
              + targetUndoOperationId);
    }
    if (isTargeted(targetUndo.operationId(), Kind.REDO)) {
      throw error(
          Code.REDO_ALREADY_APPLIED,
          "Undo operation has already been redone: " + targetUndo.operationId());
    }
    WorkflowOperation targetOperation = requireUndoableOperation(targetUndo);
    List<String> affectedObjectIds = sortedAffectedObjectIds(targetOperation);
    List<WorkflowUndoPreview.BlockingOperation> blockers =
        blockingOperations(targetUndo, affectedObjectIds);
    return new WorkflowRedoPreview(
        previewId(targetUndo.operationId(), blockers),
        targetUndo.operationId(),
        targetUndo.actorId(),
        targetUndo.operationType(),
        targetUndo.occurredAt(),
        affectedObjectIds,
        revision,
        blockers);
  }

  private OperationIdentity selectUndoTarget(OperationActor actor, String requestedTargetId) {
    if (mode == CollaborationMode.SHARED_SESSION_SHARED_UNDO) {
      if (requestedTargetId == null) {
        throw error(Code.UNDO_TARGET_REQUIRED, "Shared undo requires an explicit target operation");
      }
      return validateForwardUndoTarget(
          requireOperation(requestedTargetId, Code.UNDO_TARGET_NOT_FOUND), null);
    }
    if (requestedTargetId != null) {
      return validateForwardUndoTarget(
          requireOperation(requestedTargetId, Code.UNDO_TARGET_NOT_FOUND), actor.actorId());
    }
    OperationIdentity target = findPersonalUndoTarget(actor.actorId());
    if (target != null) {
      return target;
    }
    throw error(Code.UNDO_TARGET_NOT_FOUND, "No active operation is available for personal undo");
  }

  private OperationIdentity findPersonalUndoTarget(String actorId) {
    for (int index = operationHistory.size() - 1; index >= 0; index--) {
      OperationIdentity candidate = operationHistory.get(index);
      if (candidate.actorId().equals(actorId)
          && candidate.command().kind() != Kind.UNDO
          && !isTargeted(candidate.operationId(), Kind.UNDO)) {
        return candidate;
      }
    }
    return null;
  }

  private OperationIdentity findRedoTarget(String actorId) {
    for (int index = operationHistory.size() - 1; index >= 0; index--) {
      OperationIdentity candidate = operationHistory.get(index);
      if (candidate.actorId().equals(actorId)
          && candidate.command().kind() == Kind.UNDO
          && !isTargeted(candidate.operationId(), Kind.REDO)) {
        return candidate;
      }
    }
    return null;
  }

  private OperationIdentity validateForwardUndoTarget(
      OperationIdentity target, String requiredActorId) {
    if (target.command().kind() == Kind.UNDO
        || isTargeted(target.operationId(), Kind.UNDO)
        || (requiredActorId != null && !requiredActorId.equals(target.actorId()))) {
      throw error(
          Code.UNDO_TARGET_NOT_FOUND,
          "Operation is not an active undo target: " + target.operationId());
    }
    return target;
  }

  private OperationIdentity requireOperation(String operationId, Code missingCode) {
    OperationIdentity operation = operationsById.get(operationId);
    if (operation == null) {
      throw error(missingCode, "Unknown operation: " + operationId);
    }
    return operation;
  }

  private WorkflowOperation requireUndoableOperation(OperationIdentity identity) {
    return identity
        .operation()
        .orElseThrow(
            () ->
                error(
                    Code.OPERATION_NOT_UNDOABLE,
                    "Operation predates reconstructible history: " + identity.operationId()));
  }

  private boolean isTargeted(String operationId, Kind commandKind) {
    return operationHistory.stream()
        .anyMatch(
            operation ->
                operation.command().kind() == commandKind
                    && operationId.equals(operation.command().targetOperationId()));
  }

  private List<WorkflowUndoPreview.BlockingOperation> blockingOperations(
      OperationIdentity target, List<String> affectedObjectIds) {
    int targetIndex = operationHistory.indexOf(target);
    Set<String> affected = new LinkedHashSet<>(affectedObjectIds);
    List<WorkflowUndoPreview.BlockingOperation> blockers = new ArrayList<>();
    for (int index = targetIndex + 1; index < operationHistory.size(); index++) {
      OperationIdentity later = operationHistory.get(index);
      List<String> intersection = conflictingObjectIds(later, affected);
      if (!intersection.isEmpty()) {
        blockers.add(
            new WorkflowUndoPreview.BlockingOperation(
                later.operationId(), later.actorId(), intersection));
      }
    }
    return List.copyOf(blockers);
  }

  private static List<String> conflictingObjectIds(
      OperationIdentity operation, Set<String> affectedObjectIds) {
    if (!operation.hasOperationBody()) {
      return affectedObjectIds.stream().sorted().toList();
    }
    return operation.operation().orElseThrow().affectedObjectIds().stream()
        .filter(affectedObjectIds::contains)
        .distinct()
        .sorted()
        .toList();
  }

  private WorkflowHistoryDescriptor describe(OperationIdentity operation) {
    List<String> affectedObjectIds =
        operation.operation().map(WorkflowSessionEntry::sortedAffectedObjectIds).orElse(List.of());
    return new WorkflowHistoryDescriptor(
        operation.operationId(),
        operation.operationType(),
        operation.actorId(),
        operation.occurredAt(),
        operation.revision(),
        operation.sequence(),
        CommandKind.valueOf(operation.command().kind().name()),
        operation.command().commandId(),
        operation.command().targetOperationId(),
        affectedObjectIds,
        operation.hasOperationBody(),
        operation.command().kind() != Kind.UNDO && !isTargeted(operation.operationId(), Kind.UNDO),
        operation.command().kind() == Kind.UNDO && !isTargeted(operation.operationId(), Kind.REDO));
  }

  private Action historyAction(OperationIdentity operation) {
    if (operation == null) {
      return null;
    }
    WorkflowHistoryDescriptor descriptor = describe(operation);
    if (!operation.hasOperationBody()) {
      return new Action(descriptor, ActionStatus.NOT_RECONSTRUCTIBLE, List.of());
    }
    List<WorkflowUndoPreview.BlockingOperation> blockers =
        blockingOperations(operation, descriptor.affectedObjectIds());
    return blockers.isEmpty()
        ? new Action(descriptor, ActionStatus.AVAILABLE, List.of())
        : new Action(descriptor, ActionStatus.BLOCKED, blockers);
  }

  private static List<String> sortedAffectedObjectIds(WorkflowOperation operation) {
    return operation.affectedObjectIds().stream().distinct().sorted().toList();
  }

  private String previewId(
      String targetOperationId, List<WorkflowUndoPreview.BlockingOperation> blockers) {
    StringBuilder source =
        new StringBuilder(sessionId)
            .append('\0')
            .append(revision)
            .append('\0')
            .append(targetOperationId);
    for (WorkflowUndoPreview.BlockingOperation blocker : blockers) {
      source.append('\0').append(blocker.operationId());
      blocker.conflictingObjectIds().forEach(value -> source.append('\0').append(value));
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(source.toString().getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private WorkflowHistoryCommandResult historyCommandRetry(
      String commandId, Kind kind, String requestedTargetOperationId, String actorId) {
    for (OperationIdentity operation : operationHistory) {
      WorkflowOperationCommandMetadata metadata = operation.command();
      if (metadata.commandId().equals(commandId)) {
        if (!operation.actorId().equals(actorId)
            || metadata.kind() != kind
            || (requestedTargetOperationId != null
                && !requestedTargetOperationId.equals(metadata.targetOperationId()))) {
          throw duplicateOperation(commandId);
        }
        return new WorkflowHistoryCommandResult(
            currentWorkflow, metadata, operation.operationId(), revision, sequence);
      }
    }
    return null;
  }

  private WorkflowHistoryCommandResult result(
      AppendOutcome outcome, WorkflowOperationCommandMetadata command, String operationId) {
    return new WorkflowHistoryCommandResult(
        outcome.workflow(), command, operationId, outcome.revision(), outcome.sequence());
  }

  private long reserveNonSemanticEvent() {
    return persistence.advanceEventSequence(sessionId, revision, sequence);
  }

  private void confirmNonSemanticEvent(long expectedSequence, String eventDescription) {
    verifyEventHubState(expectedSequence, revision, eventDescription);
    sequence = expectedSequence;
  }

  private void verifyEventHubState(
      long expectedSequence, long expectedRevision, String eventDescription) {
    long actualSequence = eventHub.currentSequence(sessionId);
    long actualRevision = eventHub.currentRevision(sessionId);
    if (actualSequence != expectedSequence || actualRevision != expectedRevision) {
      throw new IllegalStateException(
          "Event hub state differs from session state after "
              + eventDescription
              + " for session "
              + sessionId
              + ": expected sequence/revision "
              + expectedSequence
              + "/"
              + expectedRevision
              + " but found "
              + actualSequence
              + "/"
              + actualRevision);
    }
  }

  private void assertModeAndActor(CollaborationMode requestedMode, OperationActor actor) {
    if (mode != requestedMode) {
      throw error(
          Code.SESSION_MODE_MISMATCH,
          "Requested mode '" + requestedMode + "' does not match session mode '" + mode + "'");
    }
    assertJoinedActor(actor);
  }

  private void assertJoinedActor(OperationActor actor) {
    OperationActor joinedActor = participants.get(actor.actorId());
    if (joinedActor == null) {
      throw error(Code.ACTOR_NOT_JOINED, "Actor is not joined: " + actor.actorId());
    }
    if (!joinedActor.equals(actor)) {
      throw error(Code.ACTOR_METADATA_MISMATCH, "Actor metadata mismatch: " + actor.actorId());
    }
  }

  private WorkflowSessionException duplicateOperation(String operationId) {
    return error(
        Code.DUPLICATE_OPERATION_ID,
        "Operation or command id is already associated with different content: " + operationId);
  }

  private WorkflowSessionException error(Code code, String message) {
    return new WorkflowSessionException(code, sessionId, message);
  }

  private void requireOpen() {
    if (closed) {
      throw error(Code.SESSION_NOT_FOUND, "Unknown session: " + sessionId);
    }
  }

  private WorkflowSessionRegistry.SessionSnapshot snapshotLocked() {
    requireOpen();
    List<OperationActor> actors = new ArrayList<>(participants.values());
    actors.sort(Comparator.comparing(OperationActor::actorId));
    return new WorkflowSessionRegistry.SessionSnapshot(
        sessionId,
        mode,
        sessionOwner,
        createdAt,
        actors,
        operationCount,
        currentWorkflow.id(),
        revision,
        sequence);
  }

  record SessionDefinition(
      String sessionId,
      CollaborationMode mode,
      OperationActor owner,
      Instant createdAt,
      Workflow workflow) {

    SessionDefinition {
      Objects.requireNonNull(sessionId, "sessionId");
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(createdAt, "createdAt");
      Objects.requireNonNull(workflow, "workflow");
    }
  }

  record RecoveryState(
      List<OperationIdentity> operations, boolean ownerConnected, long revision, long sequence) {

    RecoveryState {
      operations = List.copyOf(Objects.requireNonNull(operations, "operations"));
      if (revision < 0 || sequence < revision) {
        throw new IllegalArgumentException("Invalid recovery revision/event sequence");
      }
    }
  }

  private record Services(
      WorkflowSessionEventHub eventHub, WorkflowSessionPersistenceCoordinator persistence) {

    Services {
      Objects.requireNonNull(eventHub, "eventHub");
      Objects.requireNonNull(persistence, "persistence");
    }
  }
}
