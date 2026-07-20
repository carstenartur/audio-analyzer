package org.hammer.audio.workflow.execution;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.execution.WorkflowRunException.Code;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Command;
import org.hammer.audio.workflow.execution.WorkflowRunModels.ExecutionBackend;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Input;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Result;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Snapshot;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Violation;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;

/** Process-local orchestration service for immutable workflow runs. */
public final class WorkflowRunService {

  private static final String UNSUPPORTED_NODE_CODE = "UNSUPPORTED_NODE";

  private final WorkflowRunInputFactory inputFactory;
  private final ExecutionBackend executionBackend;
  private final Executor dispatchExecutor;
  private final Clock wallClock;
  private final ReentrantLock startGuard = new ReentrantLock();
  private final Map<String, WorkflowRunRecord> recordsByRunId = new ConcurrentHashMap<>();
  private final Map<String, WorkflowRunRecord> recordsByCommandId = new ConcurrentHashMap<>();

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
    this.wallClock = Objects.requireNonNull(clock, "clock");
    this.executionBackend = Objects.requireNonNull(backend, "backend");
    this.dispatchExecutor = Objects.requireNonNull(executor, "executor");
    WorkflowRunSourceResolver resolver = new WorkflowRunSourceResolver(sessions, store);
    this.inputFactory = new WorkflowRunInputFactory(resolver, clock, runIdSupplier);
  }

  /** Starts or idempotently returns the run associated with one start command. */
  public Snapshot start(Command command) {
    Objects.requireNonNull(command, "command");
    WorkflowRunRecord record;
    startGuard.lock();
    try {
      WorkflowRunRecord existing = recordsByCommandId.get(command.startCommandId());
      if (existing != null) {
        return handleExistingCommand(command, existing);
      }
      Input input = inputFactory.capture(command);
      validateBackend(input);
      record = new WorkflowRunRecord(input, executionBackend.mode(), wallClock);
      WorkflowRunRecord duplicateRun = recordsByRunId.putIfAbsent(input.runId(), record);
      if (duplicateRun != null) {
        throw new WorkflowRunException(
            Code.DUPLICATE_START_COMMAND,
            "Generated run id already exists: " + input.runId(),
            input.runId(),
            command.startCommandId(),
            List.of());
      }
      recordsByCommandId.put(command.startCommandId(), record);
    } finally {
      startGuard.unlock();
    }
    dispatch(record);
    return record.snapshot();
  }

  /** Returns all process-local runs in stable capture order. */
  public List<Snapshot> runs() {
    return recordsByRunId.values().stream()
        .map(WorkflowRunRecord::snapshot)
        .sorted(Comparator.comparing(Snapshot::capturedAt).thenComparing(Snapshot::runId))
        .toList();
  }

  /** Returns one current run snapshot. */
  public Snapshot inspect(String runId) {
    return requireRun(runId).snapshot();
  }

  /** Requests cooperative cancellation and returns the resulting state. */
  public Snapshot cancel(String runId) {
    WorkflowRunRecord record = requireRun(runId);
    record.requestCancellation();
    return record.snapshot();
  }

  /** Returns terminal result evidence or a typed not-ready error. */
  public Result result(String runId) {
    return requireRun(runId).result();
  }

  private Snapshot handleExistingCommand(Command command, WorkflowRunRecord existing) {
    if (existing.input().source().equals(command.source())) {
      return existing.snapshot();
    }
    throw new WorkflowRunException(
        Code.DUPLICATE_START_COMMAND,
        "Start command already targets a different workflow source",
        existing.input().runId(),
        command.startCommandId(),
        List.of());
  }

  private void validateBackend(Input input) {
    List<Violation> violations =
        List.copyOf(
            Objects.requireNonNull(executionBackend.validate(input), "backend violations"));
    if (violations.isEmpty()) {
      return;
    }
    Code code =
        violations.stream().anyMatch(item -> UNSUPPORTED_NODE_CODE.equals(item.code()))
            ? Code.UNSUPPORTED_NODE
            : Code.VALIDATION_FAILED;
    throw new WorkflowRunException(
        code,
        "Workflow run preflight rejected " + violations.size() + " violation(s)",
        input.runId(),
        input.startCommandId(),
        violations);
  }

  private void dispatch(WorkflowRunRecord record) {
    try {
      dispatchExecutor.execute(() -> execute(record));
    } catch (RuntimeException exception) {
      record.fail(exception);
    }
  }

  private void execute(WorkflowRunRecord record) {
    if (!record.startRunning()) {
      return;
    }
    try {
      Result backendResult = executionBackend.execute(record.input(), record.control());
      record.complete(Objects.requireNonNull(backendResult, "backendResult"));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      record.fail(exception);
    } catch (WorkflowExecutionBackendException | RuntimeException exception) {
      record.fail(exception);
    }
  }

  private WorkflowRunRecord requireRun(String runId) {
    if (runId == null || runId.isBlank()) {
      throw new IllegalArgumentException("runId must not be blank");
    }
    WorkflowRunRecord record = recordsByRunId.get(runId);
    if (record == null) {
      throw new WorkflowRunException(
          Code.UNKNOWN_RUN, "Unknown workflow run: " + runId, runId, null, List.of());
    }
    return record;
  }
}
