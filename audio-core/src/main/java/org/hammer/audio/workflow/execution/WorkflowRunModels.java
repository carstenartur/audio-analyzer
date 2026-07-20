package org.hammer.audio.workflow.execution;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.workflow.store.CommitId;

/** Framework-independent command, lifecycle and backend contracts for immutable workflow runs. */
public final class WorkflowRunModels {

  private WorkflowRunModels() {
    throw new UnsupportedOperationException("Utility class");
  }

  /** Truthful execution capability exposed to clients. */
  public enum Mode {
    /** Topological lifecycle simulation without audio computation. */
    SIMULATION,
    /** A backend that performs real workflow computation. */
    COMPUTATION
  }

  /** Process-local lifecycle of one immutable run. */
  public enum State {
    QUEUED,
    RUNNING,
    CANCEL_REQUESTED,
    CANCELLED,
    COMPLETED,
    FAILED;

    /** Returns whether no further lifecycle transition is possible. */
    public boolean terminal() {
      return this == CANCELLED || this == COMPLETED || this == FAILED;
    }

    /** Returns whether this state may transition to {@code target}. */
    public boolean canTransitionTo(State target) {
      Objects.requireNonNull(target, "target");
      return switch (this) {
        case QUEUED ->
            target == RUNNING
                || target == CANCEL_REQUESTED
                || target == CANCELLED
                || target == FAILED;
        case RUNNING ->
            target == CANCEL_REQUESTED
                || target == CANCELLED
                || target == COMPLETED
                || target == FAILED;
        case CANCEL_REQUESTED -> target == CANCELLED || target == COMPLETED || target == FAILED;
        case CANCELLED, COMPLETED, FAILED -> false;
      };
    }
  }

  /** Kind of immutable source captured before dispatch. */
  public enum SourceKind {
    LIVE_SESSION,
    STORED_COMMIT
  }

  /** Transport-neutral selector for the workflow version to execute. */
  public sealed interface Source permits LiveSessionSource, StoredCommitSource {
    /** Returns the source discriminator. */
    SourceKind kind();
  }

  /** Selects one exact server-authoritative collaboration-session revision. */
  public record LiveSessionSource(String sessionId, long expectedRevision) implements Source {
    public LiveSessionSource {
      requireNotBlank(sessionId, "sessionId");
      if (expectedRevision < 0) {
        throw new IllegalArgumentException("expectedRevision must be >= 0");
      }
    }

    @Override
    public SourceKind kind() {
      return SourceKind.LIVE_SESSION;
    }
  }

  /** Selects one exact stored workflow commit. */
  public record StoredCommitSource(CommitId commitId) implements Source {
    public StoredCommitSource {
      Objects.requireNonNull(commitId, "commitId");
    }

    @Override
    public SourceKind kind() {
      return SourceKind.STORED_COMMIT;
    }
  }

  /** Idempotent start command for one immutable source. */
  public record Command(String startCommandId, Source source) {
    public Command {
      StableExecutionIds.requireStable(startCommandId, "startCommandId");
      Objects.requireNonNull(source, "source");
    }
  }

  /** Machine-readable preflight or backend-capability violation. */
  public record Violation(String code, String message, String nodeId) {
    public Violation {
      requireNotBlank(code, "code");
      requireNotBlank(message, "message");
      if (nodeId != null && nodeId.isBlank()) {
        throw new IllegalArgumentException("nodeId must be null or non-blank");
      }
    }
  }

  /** Immutable backend input derived from exact canonical DSL before dispatch. */
  public record Input(
      String runId,
      String startCommandId,
      Source source,
      String dslText,
      String fingerprint,
      ExecutionSnapshot snapshot,
      ExecutionPlan plan,
      Long semanticRevision,
      CommitId commitId,
      Instant capturedAt) {
    public Input {
      StableExecutionIds.requireStable(runId, "runId");
      StableExecutionIds.requireStable(startCommandId, "startCommandId");
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(dslText, "dslText");
      requireNotBlank(fingerprint, "fingerprint");
      Objects.requireNonNull(snapshot, "snapshot");
      Objects.requireNonNull(plan, "plan");
      Objects.requireNonNull(capturedAt, "capturedAt");
      if (semanticRevision != null && semanticRevision < 0) {
        throw new IllegalArgumentException("semanticRevision must be null or >= 0");
      }
      if (source.kind() == SourceKind.LIVE_SESSION) {
        if (semanticRevision == null || commitId != null) {
          throw new IllegalArgumentException(
              "Live-session inputs require semanticRevision and no commitId");
        }
      } else if (semanticRevision != null || commitId == null) {
        throw new IllegalArgumentException(
            "Stored-commit inputs require commitId and no semanticRevision");
      }
    }
  }

  /** Immutable result and optional backend-specific textual artifacts. */
  public record Result(ReproducibilityBundle reproducibilityBundle, Map<String, String> artifacts) {
    public Result {
      Objects.requireNonNull(reproducibilityBundle, "reproducibilityBundle");
      artifacts = Map.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
    }
  }

  /** Immutable public view of one process-local run. */
  public record Snapshot(
      String runId,
      String startCommandId,
      State state,
      Mode mode,
      Source source,
      String workflowId,
      String snapshotId,
      String planId,
      String fingerprint,
      Long semanticRevision,
      CommitId commitId,
      Instant capturedAt,
      Instant startedAt,
      Instant finishedAt,
      int progressPercent,
      String statusMessage,
      List<Violation> violations) {
    public Snapshot {
      StableExecutionIds.requireStable(runId, "runId");
      StableExecutionIds.requireStable(startCommandId, "startCommandId");
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(source, "source");
      StableExecutionIds.requireStable(workflowId, "workflowId");
      StableExecutionIds.requireStable(snapshotId, "snapshotId");
      StableExecutionIds.requireStable(planId, "planId");
      requireNotBlank(fingerprint, "fingerprint");
      Objects.requireNonNull(capturedAt, "capturedAt");
      if (progressPercent < 0 || progressPercent > 100) {
        throw new IllegalArgumentException("progressPercent must be between 0 and 100");
      }
      statusMessage = statusMessage == null ? "" : statusMessage;
      violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
    }
  }

  /** Replaceable execution adapter. */
  public interface ExecutionBackend {
    /** Returns whether the adapter simulates or performs computation. */
    Mode mode();

    /** Returns capability violations before any backend work is dispatched. */
    List<Violation> validate(Input input);

    /** Executes the immutable input and returns terminal evidence. */
    Result execute(Input input, Control control) throws Exception;
  }

  /** Cooperative cancellation and progress channel supplied to a backend. */
  public interface Control {
    /** Returns whether cancellation has been requested. */
    boolean cancellationRequested();

    /** Publishes bounded progress without changing the semantic input. */
    void progress(int percentage, String message);
  }

  private static String requireNotBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
