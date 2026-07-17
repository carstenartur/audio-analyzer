package org.hammer.audio.workflow.execution;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowValidator;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
import org.hammer.audio.workflow.store.WorkflowSnapshot;

/** Executes immutable workflow snapshots without sharing mutable editor state. */
public final class WorkflowRunService implements AutoCloseable {

  private final ExecutorService executor;
  private final Backend backend;
  private final WorkflowValidator validator = new WorkflowValidator();
  private final WorkflowDslSerializer serializer = new WorkflowDslSerializer();
  private final Map<String, MutableRun> runs = new ConcurrentHashMap<>();

  public WorkflowRunService(ExecutorService executor, Backend backend) {
    this.executor = Objects.requireNonNull(executor, "executor");
    this.backend = Objects.requireNonNull(backend, "backend");
  }

  public RunSnapshot start(Workflow workflow, String sourceCommitId) {
    Objects.requireNonNull(workflow, "workflow");
    List<String> violations = validator.validate(workflow);
    if (!violations.isEmpty()) {
      throw new IllegalArgumentException(String.join("; ", violations));
    }
    WorkflowSnapshot immutable =
        new WorkflowSnapshot(workflow.id(), serializer.serialize(workflow));
    String runId = UUID.randomUUID().toString();
    MutableRun run = new MutableRun(runId, sourceCommitId, immutable);
    runs.put(runId, run);
    Future<?> future = executor.submit(() -> execute(run));
    run.future = future;
    return run.snapshot();
  }

  public RunSnapshot get(String runId) {
    MutableRun run = runs.get(requireId(runId));
    if (run == null) {
      throw new IllegalArgumentException("Unknown workflow run: " + runId);
    }
    return run.snapshot();
  }

  public List<RunSnapshot> runs() {
    return runs.values().stream()
        .map(MutableRun::snapshot)
        .sorted(Comparator.comparing(RunSnapshot::createdAt).reversed())
        .toList();
  }

  public RunSnapshot cancel(String runId) {
    MutableRun run = runs.get(requireId(runId));
    if (run == null) {
      throw new IllegalArgumentException("Unknown workflow run: " + runId);
    }
    run.cancelled.set(true);
    Future<?> future = run.future;
    if (future != null) {
      future.cancel(true);
    }
    synchronized (run) {
      if (!run.status.terminal()) {
        run.status = Status.CANCELLED;
        run.finishedAt = Instant.now();
      }
      return run.snapshot();
    }
  }

  private void execute(MutableRun run) {
    synchronized (run) {
      if (run.cancelled.get()) {
        return;
      }
      run.status = Status.RUNNING;
      run.startedAt = Instant.now();
    }
    try {
      Map<String, String> result = backend.execute(run.snapshot, run.cancelled);
      synchronized (run) {
        if (run.cancelled.get()) {
          run.status = Status.CANCELLED;
        } else {
          run.status = Status.SUCCEEDED;
          run.result = Map.copyOf(result);
          run.progress = 1.0;
        }
        run.finishedAt = Instant.now();
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      synchronized (run) {
        run.status = Status.CANCELLED;
        run.finishedAt = Instant.now();
      }
    } catch (RuntimeException ex) {
      synchronized (run) {
        run.status = Status.FAILED;
        run.error = ex.getMessage();
        run.finishedAt = Instant.now();
      }
    }
  }

  @Override
  public void close() {
    executor.close();
  }

  private static String requireId(String value) {
    Objects.requireNonNull(value, "runId");
    if (value.isBlank()) {
      throw new IllegalArgumentException("runId must not be blank");
    }
    return value;
  }

  @FunctionalInterface
  public interface Backend {
    Map<String, String> execute(WorkflowSnapshot snapshot, AtomicBoolean cancelled)
        throws InterruptedException;
  }

  public enum Status {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
      return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
  }

  public record RunSnapshot(
      String runId,
      String sourceCommitId,
      WorkflowSnapshot workflowSnapshot,
      Status status,
      double progress,
      Instant createdAt,
      Instant startedAt,
      Instant finishedAt,
      Map<String, String> result,
      String error) {
    public RunSnapshot {
      result = Map.copyOf(result);
    }
  }

  private static final class MutableRun {
    private final String runId;
    private final String sourceCommitId;
    private final WorkflowSnapshot snapshot;
    private final Instant createdAt = Instant.now();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Future<?> future;
    private Status status = Status.QUEUED;
    private double progress;
    private Instant startedAt;
    private Instant finishedAt;
    private Map<String, String> result = new LinkedHashMap<>();
    private String error;

    MutableRun(String runId, String sourceCommitId, WorkflowSnapshot snapshot) {
      this.runId = runId;
      this.sourceCommitId = sourceCommitId;
      this.snapshot = snapshot;
    }

    synchronized RunSnapshot snapshot() {
      return new RunSnapshot(
          runId,
          sourceCommitId,
          snapshot,
          status,
          progress,
          createdAt,
          startedAt,
          finishedAt,
          result,
          error);
    }
  }
}
