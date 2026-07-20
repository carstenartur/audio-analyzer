package org.hammer.audio.workflow.execution.http;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.workflow.execution.ExecutionStatus;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Command;
import org.hammer.audio.workflow.execution.WorkflowRunModels.LiveSessionSource;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Result;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Snapshot;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Source;
import org.hammer.audio.workflow.execution.WorkflowRunModels.SourceKind;
import org.hammer.audio.workflow.execution.WorkflowRunModels.StoredCommitSource;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Violation;
import org.hammer.audio.workflow.store.CommitId;

/** Request and response values for the immutable workflow-run REST API. */
public final class WorkflowRunApiModels {

  private WorkflowRunApiModels() {
    throw new UnsupportedOperationException("Utility class");
  }

  /** Request selecting either an exact session revision or stored commit. */
  public record StartRunRequest(
      @NotBlank String startCommandId,
      @NotNull SourceKind sourceKind,
      String sessionId,
      @PositiveOrZero Long expectedRevision,
      String commitId) {

    Command toCommand() {
      Objects.requireNonNull(sourceKind, "sourceKind");
      Source source =
          switch (sourceKind) {
            case LIVE_SESSION -> liveSource();
            case STORED_COMMIT -> storedSource();
          };
      return new Command(startCommandId, source);
    }

    private LiveSessionSource liveSource() {
      if (sessionId == null || sessionId.isBlank() || expectedRevision == null) {
        throw new IllegalArgumentException(
            "LIVE_SESSION requires sessionId and expectedRevision");
      }
      if (commitId != null && !commitId.isBlank()) {
        throw new IllegalArgumentException("LIVE_SESSION must not include commitId");
      }
      return new LiveSessionSource(sessionId, expectedRevision);
    }

    private StoredCommitSource storedSource() {
      if (commitId == null || commitId.isBlank()) {
        throw new IllegalArgumentException("STORED_COMMIT requires commitId");
      }
      if ((sessionId != null && !sessionId.isBlank()) || expectedRevision != null) {
        throw new IllegalArgumentException(
            "STORED_COMMIT must not include sessionId or expectedRevision");
      }
      return new StoredCommitSource(new CommitId(commitId));
    }
  }

  /** Stable transport representation of one workflow-run source. */
  public record RunSourceResponse(
      SourceKind kind, String sessionId, Long semanticRevision, String commitId) {

    static RunSourceResponse from(Snapshot snapshot) {
      if (snapshot.source() instanceof LiveSessionSource live) {
        return new RunSourceResponse(
            SourceKind.LIVE_SESSION, live.sessionId(), snapshot.semanticRevision(), null);
      }
      return new RunSourceResponse(
          SourceKind.STORED_COMMIT, null, null, snapshot.commitId().value());
    }
  }

  /** Stable run lifecycle response without executor or persistence implementation types. */
  public record RunResponse(
      String runId,
      String startCommandId,
      String state,
      String mode,
      RunSourceResponse source,
      String workflowId,
      String snapshotId,
      String planId,
      String fingerprint,
      Instant capturedAt,
      Instant startedAt,
      Instant finishedAt,
      int progressPercent,
      String statusMessage,
      List<ViolationResponse> violations) {

    static RunResponse from(Snapshot snapshot) {
      return new RunResponse(
          snapshot.runId(),
          snapshot.startCommandId(),
          snapshot.state().name(),
          snapshot.mode().name(),
          RunSourceResponse.from(snapshot),
          snapshot.workflowId(),
          snapshot.snapshotId(),
          snapshot.planId(),
          snapshot.fingerprint(),
          snapshot.capturedAt(),
          snapshot.startedAt(),
          snapshot.finishedAt(),
          snapshot.progressPercent(),
          snapshot.statusMessage(),
          snapshot.violations().stream().map(ViolationResponse::from).toList());
    }
  }

  /** Machine-readable validation or backend failure detail. */
  public record ViolationResponse(String code, String message, String nodeId) {
    static ViolationResponse from(Violation violation) {
      return new ViolationResponse(violation.code(), violation.message(), violation.nodeId());
    }
  }

  /** Terminal result with reproducibility provenance and backend artifacts. */
  public record RunResultResponse(
      RunResponse run,
      String overallStatus,
      Map<String, ExecutionStatus> nodeStatuses,
      Instant executionStartedAt,
      Instant executionCompletedAt,
      String commitId,
      Map<String, String> artifacts) {

    static RunResultResponse from(Snapshot snapshot, Result result) {
      var executionResult = result.reproducibilityBundle().result();
      CommitId commitId = result.reproducibilityBundle().commitId();
      return new RunResultResponse(
          RunResponse.from(snapshot),
          executionResult.overallStatus().name(),
          executionResult.nodeStatuses(),
          executionResult.startedAt(),
          executionResult.completedAt(),
          commitId == null ? null : commitId.value(),
          result.artifacts());
    }
  }
}
