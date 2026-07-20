package org.hammer.audio.workflow.execution;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.hammer.audio.workflow.execution.WorkflowRunException.Code;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Control;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Input;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Mode;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Result;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Snapshot;
import org.hammer.audio.workflow.execution.WorkflowRunModels.State;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Violation;

/** Thread-safe mutable lifecycle record around one immutable workflow-run input. */
final class WorkflowRunRecord {

  private static final String BACKEND_FAILURE_CODE = "BACKEND_FAILURE";

  private final Input immutableInput;
  private final Mode backendMode;
  private final Clock wallClock;
  private final ReentrantLock stateGuard = new ReentrantLock();
  private State lifecycleState = State.QUEUED;
  private Instant startedAt;
  private Instant finishedAt;
  private int progressPercent;
  private String statusMessage = "Queued";
  private List<Violation> violationDetails = List.of();
  private Result terminalResult;
  private boolean cancellationRequested;

  WorkflowRunRecord(Input input, Mode mode, Clock clock) {
    this.immutableInput = Objects.requireNonNull(input, "input");
    this.backendMode = Objects.requireNonNull(mode, "mode");
    this.wallClock = Objects.requireNonNull(clock, "clock");
  }

  Input input() {
    return immutableInput;
  }

  Snapshot snapshot() {
    stateGuard.lock();
    try {
      return new Snapshot(
          immutableInput.runId(),
          immutableInput.startCommandId(),
          lifecycleState,
          backendMode,
          immutableInput.source(),
          immutableInput.snapshot().workflowId(),
          immutableInput.snapshot().snapshotId(),
          immutableInput.plan().planId(),
          immutableInput.fingerprint(),
          immutableInput.semanticRevision(),
          immutableInput.commitId(),
          immutableInput.capturedAt(),
          startedAt,
          finishedAt,
          progressPercent,
          statusMessage,
          violationDetails);
    } finally {
      stateGuard.unlock();
    }
  }

  boolean startRunning() {
    stateGuard.lock();
    try {
      if (lifecycleState == State.CANCELLED) {
        return false;
      }
      if (lifecycleState == State.CANCEL_REQUESTED) {
        transition(State.CANCELLED);
        finishedAt = wallClock.instant();
        statusMessage = "Cancelled before backend start";
        return false;
      }
      transition(State.RUNNING);
      startedAt = wallClock.instant();
      statusMessage = "Running";
      return true;
    } finally {
      stateGuard.unlock();
    }
  }

  void requestCancellation() {
    stateGuard.lock();
    try {
      cancellationRequested = true;
      if (lifecycleState == State.QUEUED || lifecycleState == State.RUNNING) {
        transition(State.CANCEL_REQUESTED);
        statusMessage = "Cancellation requested";
      }
    } finally {
      stateGuard.unlock();
    }
  }

  void complete(Result backendResult) {
    Objects.requireNonNull(backendResult, "backendResult");
    stateGuard.lock();
    try {
      State target = terminalState(backendResult.reproducibilityBundle().result().overallStatus());
      transition(target);
      terminalResult = backendResult;
      finishedAt = wallClock.instant();
      if (target == State.COMPLETED) {
        progressPercent = 100;
        statusMessage = "Completed";
      } else {
        statusMessage = target.name();
      }
    } finally {
      stateGuard.unlock();
    }
  }

  void fail(Throwable failure) {
    Objects.requireNonNull(failure, "failure");
    stateGuard.lock();
    try {
      if (lifecycleState.terminal()) {
        return;
      }
      transition(State.FAILED);
      finishedAt = wallClock.instant();
      String message =
          failure.getMessage() == null || failure.getMessage().isBlank()
              ? failure.getClass().getSimpleName()
              : failure.getMessage();
      violationDetails = List.of(new Violation(BACKEND_FAILURE_CODE, message, null));
      statusMessage = "Failed";
    } finally {
      stateGuard.unlock();
    }
  }

  Result result() {
    stateGuard.lock();
    try {
      if (terminalResult == null) {
        throw new WorkflowRunException(
            Code.RESULT_NOT_AVAILABLE,
            "Workflow run has no terminal result in state " + lifecycleState,
            immutableInput.runId(),
            immutableInput.startCommandId(),
            violationDetails);
      }
      return terminalResult;
    } finally {
      stateGuard.unlock();
    }
  }

  Control control() {
    return new BackendControl();
  }

  private boolean isCancellationRequested() {
    stateGuard.lock();
    try {
      return cancellationRequested;
    } finally {
      stateGuard.unlock();
    }
  }

  private void updateProgress(int percentage, String message) {
    if (percentage < 0 || percentage > 100) {
      throw new IllegalArgumentException("progress percentage must be between 0 and 100");
    }
    stateGuard.lock();
    try {
      if (lifecycleState.terminal()) {
        return;
      }
      progressPercent = Math.max(progressPercent, percentage);
      statusMessage = message == null ? "" : message;
    } finally {
      stateGuard.unlock();
    }
  }

  private State terminalState(ExecutionStatus executionStatus) {
    return switch (executionStatus) {
      case COMPLETED -> State.COMPLETED;
      case CANCELLED -> State.CANCELLED;
      case FAILED, SKIPPED -> State.FAILED;
      case IDLE, QUEUED, RUNNING ->
          throw new IllegalArgumentException(
              "Backend returned non-terminal execution status " + executionStatus);
    };
  }

  private void transition(State target) {
    if (!lifecycleState.canTransitionTo(target)) {
      throw new WorkflowRunException(
          Code.ILLEGAL_TRANSITION,
          "Illegal workflow run transition " + lifecycleState + " -> " + target,
          immutableInput.runId(),
          immutableInput.startCommandId(),
          violationDetails);
    }
    lifecycleState = target;
  }

  private final class BackendControl implements Control {
    @Override
    public boolean cancellationRequested() {
      return isCancellationRequested();
    }

    @Override
    public void progress(int percentage, String message) {
      updateProgress(percentage, message);
    }
  }
}
