package org.hammer.audio.workflow.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowValidator;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry.SessionSnapshot;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionRevisionConflictException;
import org.hammer.audio.workflow.dsl.WorkflowDslParser;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
import org.hammer.audio.workflow.execution.WorkflowRunException.Code;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Command;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Control;
import org.hammer.audio.workflow.execution.WorkflowRunModels.ExecutionBackend;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Input;
import org.hammer.audio.workflow.execution.WorkflowRunModels.LiveSessionSource;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Result;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Snapshot;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Source;
import org.hammer.audio.workflow.execution.WorkflowRunModels.State;
import org.hammer.audio.workflow.execution.WorkflowRunModels.StoredCommitSource;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Violation;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;
import org.hammer.audio.workflow.store.WorkflowSnapshot;

/** Process-local orchestration service for immutable workflow runs. */
public final class WorkflowRunService {

  private static final String VALIDATION_CODE = "WORKFLOW_VALIDATION";
  private static final String CYCLE_CODE = "CYCLIC_WORKFLOW";
  private static final String BACKEND_CODE = "BACKEND_FAILURE";

  private final WorkflowSessionRegistry sessions;
  private final VersionedWorkflowStore store;
  private final ExecutionBackend backend;
  private final Executor executor;
  private final WorkflowDslParser parser;
  private final WorkflowDslSerializer serializer;
  private final WorkflowValidator validator;
  private final Clock clock;
  private final Supplier<String> runIdSupplier;
  private final Object startLock = new Object();
  private final Map<String, RunRecord> runsById = new ConcurrentHashMap<>();
  private final Map<String, RunRecord> runsByCommandId = new ConcurrentHashMap<>();

  /** Creates production orchestration with a process-local registry and generated run ids. */
  public WorkflowRunService(
      WorkflowSessionRegistry sessions,
      VersionedWorkflowStore store,
      ExecutionBackend backend,
      Executor executor) {
    this(sessions, store, backend, executor, Clock.systemUTC(), () -> "run-" + UUID.randomUUID());
  }

  WorkflowRunService(
      WorkflowSessionRegistry sessions,
      VersionedWorkflowStore store,
      ExecutionBackend backend,
      Executor executor,
      Clock clock,
      Supplier<String> runIdSupplier) {
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.store = store;
    this.backend = Objects.requireNonNull(backend, "backend");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.parser = new WorkflowDslParser();
    this.serializer = new WorkflowDslSerializer();
    this.validator = new WorkflowValidator();
    this.clock = Objects.requireNonNull(clock, "clock");
    this.runIdSupplier = Objects.requireNonNull(runIdSupplier, "runIdSupplier");
  }

  /** Starts or idempotently returns the run associated with one start command. */
  public Snapshot start(Command command) {
    Objects.requireNonNull(command, "command");
    RunRecord record;
    synchronized (startLock) {
      RunRecord existing = runsByCommandId.get(command.startCommandId());
      if (existing != null) {
        if (existing.input().source().equals(command.source())) {
          return existing.snapshot();
        }
        throw failure(
            Code.DUPLICATE_START_COMMAND,
            "Start command already targets a different workflow source",
            existing.input().runId(),
            command.startCommandId(),
            List.of());
      }
      Input input = captureAndValidate(command);
      record = new RunRecord(input, backend.mode());
      RunRecord duplicateRun = runsById.putIfAbsent(input.runId(), record);
      if (duplicateRun != null) {
        throw failure(
            Code.DUPLICATE_START_COMMAND,
            "Generated run id already exists: " + input.runId(),
            input.runId(),
            command.startCommandId(),
            List.of());
      }
      runsByCommandId.put(command.startCommandId(), record);
    }
    dispatch(record);
    return record.snapshot();
  }

  /** Returns all process-local runs in stable capture order. */
  public List<Snapshot> runs() {
    return runsById.values().stream()
        .map(RunRecord::snapshot)
        .sorted(Comparator.comparing(Snapshot::capturedAt).thenComparing(Snapshot::runId))
        .toList();
  }

  /** Returns one current run snapshot. */
  public Snapshot inspect(String runId) {
    return requireRun(runId).snapshot();
  }

  /** Requests cooperative cancellation and returns the resulting state. */
  public Snapshot cancel(String runId) {
    RunRecord record = requireRun(runId);
    record.requestCancellation();
    return record.snapshot();
  }

  /** Returns terminal result evidence or a typed not-ready error. */
  public Result result(String runId) {
    return requireRun(runId).result();
  }

  private Input captureAndValidate(Command command) {
    String runId = requireGeneratedRunId(runIdSupplier.get());
    Instant capturedAt = clock.instant();
    CapturedSource captured = capture(command.source());
    List<Violation> violations = validateWorkflow(captured.workflow(), captured.workflowId());
    if (!violations.isEmpty()) {
      throw validationFailure(runId, command.startCommandId(), violations);
    }
    ExecutionSnapshot snapshot =
        ExecutionSnapshot.of(runId + ":snapshot", captured.workflow(), capturedAt);
    ExecutionPlan plan;
    try {
      plan = ExecutionPlan.of(runId + ":plan", snapshot);
    } catch (IllegalArgumentException exception) {
      throw validationFailure(
          runId,
          command.startCommandId(),
          List.of(new Violation(CYCLE_CODE, exception.getMessage(), null)));
    }
    Input input =
        new Input(
            runId,
            command.startCommandId(),
            command.source(),
            captured.dslText(),
            fingerprint(captured.dslText()),
            snapshot,
            plan,
            captured.semanticRevision(),
            captured.commitId(),
            capturedAt);
    List<Violation> backendViolations =
        List.copyOf(Objects.requireNonNull(backend.validate(input), "backend violations"));
    if (!backendViolations.isEmpty()) {
      throw validationFailure(runId, command.startCommandId(), backendViolations);
    }
    return input;
  }

  private CapturedSource capture(Source source) {
    if (source instanceof LiveSessionSource live) {
      SessionSnapshot before = sessions.inspect(live.sessionId());
      requireExpectedRevision(live, before.revision());
      Workflow immutableWorkflow = sessions.workflow(live.sessionId());
      SessionSnapshot after = sessions.inspect(live.sessionId());
      requireExpectedRevision(live, after.revision());
      String dslText = serializer.serialize(immutableWorkflow);
      Workflow parsed = parser.parse(dslText);
      return new CapturedSource(parsed.id(), parsed, dslText, after.revision(), null);
    }
    StoredCommitSource stored = (StoredCommitSource) source;
    if (store == null) {
      throw failure(
          Code.SOURCE_UNAVAILABLE,
          "Stored workflow execution requires a configured VersionedWorkflowStore",
          null,
          null,
          List.of());
    }
    try {
      WorkflowSnapshot workflowSnapshot = store.loadAtCommit(stored.commitId());
      Workflow workflow = parser.parse(workflowSnapshot.dslText());
      return new CapturedSource(
          workflowSnapshot.workflowId(),
          workflow,
          workflowSnapshot.dslText(),
          null,
          stored.commitId());
    } catch (NoSuchElementException exception) {
      throw new WorkflowRunException(
          Code.SOURCE_UNAVAILABLE,
          "Unknown workflow commit: " + stored.commitId().value(),
          null,
          null,
          List.of(),
          exception);
    }
  }

  private static void requireExpectedRevision(LiveSessionSource source, long actualRevision) {
    if (source.expectedRevision() != actualRevision) {
      throw new WorkflowSessionRevisionConflictException(
          source.sessionId(), source.expectedRevision(), actualRevision);
    }
  }

  private List<Violation> validateWorkflow(Workflow workflow, String expectedWorkflowId) {
    List<Violation> violations = new ArrayList<>();
    if (!workflow.id().equals(expectedWorkflowId)) {
      violations.add(
          new Violation(
              VALIDATION_CODE,
              "Stored workflow id "
                  + expectedWorkflowId
                  + " does not match DSL workflow id "
                  + workflow.id(),
              null));
    }
    for (String message : validator.validate(workflow)) {
      violations.add(new Violation(VALIDATION_CODE, message, null));
    }
    return List.copyOf(violations);
  }

  private void dispatch(RunRecord record) {
    try {
      executor.execute(() -> execute(record));
    } catch (RuntimeException exception) {
      record.fail(exception);
    }
  }

  private void execute(RunRecord record) {
    if (!record.startRunning()) {
      return;
    }
    try {
      Result result = backend.execute(record.input(), record.control());
      record.complete(Objects.requireNonNull(result, "backend result"));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      record.fail(exception);
    } catch (WorkflowExecutionBackendException | RuntimeException exception) {
      record.fail(exception);
    }
  }

  private RunRecord requireRun(String runId) {
    if (runId == null || runId.isBlank()) {
      throw new IllegalArgumentException("runId must not be blank");
    }
    RunRecord record = runsById.get(runId);
    if (record == null) {
      throw failure(Code.UNKNOWN_RUN, "Unknown workflow run: " + runId, runId, null, List.of());
    }
    return record;
  }

  private WorkflowRunException validationFailure(
      String runId, String startCommandId, List<Violation> violations) {
    Code code =
        violations.stream().anyMatch(violation -> "UNSUPPORTED_NODE".equals(violation.code()))
            ? Code.UNSUPPORTED_NODE
            : Code.VALIDATION_FAILED;
    return failure(
        code,
        "Workflow run preflight rejected " + violations.size() + " violation(s)",
        runId,
        startCommandId,
        violations);
  }

  private static WorkflowRunException failure(
      Code code, String message, String runId, String startCommandId, List<Violation> violations) {
    return new WorkflowRunException(code, message, runId, startCommandId, violations);
  }

  private static String requireGeneratedRunId(String runId) {
    StableExecutionIds.requireStable(runId, "runId");
    return runId;
  }

  private static String fingerprint(String dslText) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(dslText.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(digest.length * 2);
      for (byte value : digest) {
        result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
        result.append(Character.forDigit(value & 0x0f, 16));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private record CapturedSource(
      String workflowId,
      Workflow workflow,
      String dslText,
      Long semanticRevision,
      CommitId commitId) {
    private CapturedSource {
      StableExecutionIds.requireStable(workflowId, "workflowId");
      Objects.requireNonNull(workflow, "workflow");
      Objects.requireNonNull(dslText, "dslText");
      if (semanticRevision != null && semanticRevision < 0) {
        throw new IllegalArgumentException("semanticRevision must be null or >= 0");
      }
      if ((semanticRevision == null) == (commitId == null)) {
        throw new IllegalArgumentException(
            "Captured source requires exactly one of semanticRevision or commitId");
      }
    }
  }

  private final class RunRecord {
    private final Input input;
    private final WorkflowRunModels.Mode mode;
    private State state = State.QUEUED;
    private Instant startedAt;
    private Instant finishedAt;
    private int progressPercent;
    private String statusMessage = "Queued";
    private List<Violation> violations = List.of();
    private Result terminalResult;
    private boolean cancellationRequested;

    private RunRecord(Input input, WorkflowRunModels.Mode mode) {
      this.input = Objects.requireNonNull(input, "input");
      this.mode = Objects.requireNonNull(mode, "mode");
    }

    private Input input() {
      return input;
    }

    private synchronized Snapshot snapshot() {
      return new Snapshot(
          input.runId(),
          input.startCommandId(),
          state,
          mode,
          input.source(),
          input.snapshot().workflowId(),
          input.snapshot().snapshotId(),
          input.plan().planId(),
          input.fingerprint(),
          input.semanticRevision(),
          input.commitId(),
          input.capturedAt(),
          startedAt,
          finishedAt,
          progressPercent,
          statusMessage,
          violations);
    }

    private synchronized boolean startRunning() {
      if (state == State.CANCELLED) {
        return false;
      }
      if (state == State.CANCEL_REQUESTED) {
        transition(State.CANCELLED);
        finishedAt = clock.instant();
        statusMessage = "Cancelled before backend start";
        return false;
      }
      transition(State.RUNNING);
      startedAt = clock.instant();
      statusMessage = "Running";
      return true;
    }

    private synchronized void requestCancellation() {
      cancellationRequested = true;
      if (state == State.QUEUED || state == State.RUNNING) {
        transition(State.CANCEL_REQUESTED);
        statusMessage = "Cancellation requested";
      }
    }

    private synchronized boolean isCancellationRequested() {
      return cancellationRequested;
    }

    private synchronized void complete(Result result) {
      terminalResult = result;
      ExecutionStatus overall = result.reproducibilityBundle().result().overallStatus();
      State target =
          switch (overall) {
            case CANCELLED -> State.CANCELLED;
            case FAILED -> State.FAILED;
            default -> State.COMPLETED;
          };
      transition(target);
      finishedAt = clock.instant();
      progressPercent = target == State.COMPLETED ? 100 : progressPercent;
      statusMessage = target == State.COMPLETED ? "Completed" : target.name();
    }

    private synchronized void fail(Throwable failure) {
      if (state.terminal()) {
        return;
      }
      transition(State.FAILED);
      finishedAt = clock.instant();
      String message =
          failure.getMessage() == null || failure.getMessage().isBlank()
              ? failure.getClass().getSimpleName()
              : failure.getMessage();
      violations = List.of(new Violation(BACKEND_CODE, message, null));
      statusMessage = "Failed";
    }

    private synchronized Result result() {
      if (terminalResult == null) {
        throw failure(
            Code.RESULT_NOT_AVAILABLE,
            "Workflow run has no terminal result in state " + state,
            input.runId(),
            input.startCommandId(),
            violations);
      }
      return terminalResult;
    }

    private Control control() {
      return new Control() {
        @Override
        public boolean cancellationRequested() {
          return RunRecord.this.isCancellationRequested();
        }

        @Override
        public void progress(int percentage, String message) {
          updateProgress(percentage, message);
        }
      };
    }

    private synchronized void updateProgress(int percentage, String message) {
      if (percentage < 0 || percentage > 100) {
        throw new IllegalArgumentException("progress percentage must be between 0 and 100");
      }
      if (state.terminal()) {
        return;
      }
      progressPercent = Math.max(progressPercent, percentage);
      statusMessage = message == null ? "" : message;
    }

    private void transition(State target) {
      if (!state.canTransitionTo(target)) {
        throw failure(
            Code.ILLEGAL_TRANSITION,
            "Illegal workflow run transition " + state + " -> " + target,
            input.runId(),
            input.startCommandId(),
            violations);
      }
      state = target;
    }
  }
}
