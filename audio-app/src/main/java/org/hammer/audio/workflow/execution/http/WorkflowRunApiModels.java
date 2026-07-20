package org.hammer.audio.workflow.execution.http;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.workflow.execution.ExecutionResult;
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
public interface WorkflowRunApiModels {

  /**
   * Request selecting either an exact session revision or stored commit.
   *
   * @param startCommandId stable idempotency identity supplied by the client
   * @param sourceKind discriminator for live-session or stored-commit execution
   * @param sessionId live collaboration-session identifier, otherwise {@code null}
   * @param expectedRevision exact live semantic revision, otherwise {@code null}
   * @param commitId exact stored workflow commit, otherwise {@code null}
   */
  record StartRunRequest(
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
        throw new IllegalArgumentException("LIVE_SESSION requires sessionId and expectedRevision");
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

  /**
   * Stable transport representation of one workflow-run source.
   *
   * @param kind source discriminator
   * @param sessionId live collaboration-session identifier, otherwise {@code null}
   * @param semanticRevision captured live semantic revision, otherwise {@code null}
   * @param commitId captured stored commit identifier, otherwise {@code null}
   */
  record RunSourceResponse(
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

  /**
   * Stable run lifecycle response without executor or persistence implementation types.
   *
   * @param runId stable run identifier
   * @param startCommandId client idempotency identity
   * @param state current lifecycle state
   * @param mode truthful backend capability mode
   * @param source exact immutable source provenance
   * @param workflowId stable workflow identifier
   * @param snapshotId immutable snapshot identifier
   * @param planId immutable execution-plan identifier
   * @param fingerprint SHA-256 fingerprint of the captured canonical DSL
   * @param capturedAt source capture instant
   * @param startedAt backend start instant, if started
   * @param finishedAt terminal instant, if finished
   * @param progressPercent bounded progress percentage
   * @param statusMessage current human-readable status
   * @param violations machine-readable diagnostics
   */
  record RunResponse(
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

    public RunResponse {
      violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
    }

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

  /**
   * Machine-readable validation or backend failure detail.
   *
   * @param code stable diagnostic code
   * @param message human-readable detail
   * @param nodeId optional affected workflow node
   */
  record ViolationResponse(String code, String message, String nodeId) {
    static ViolationResponse from(Violation violation) {
      return new ViolationResponse(violation.code(), violation.message(), violation.nodeId());
    }
  }

  /**
   * Terminal result with reproducibility provenance and backend artifacts.
   *
   * @param run terminal run metadata
   * @param overallStatus aggregate execution status
   * @param nodeStatuses terminal status by node identifier
   * @param executionStartedAt execution start instant
   * @param executionCompletedAt execution completion instant
   * @param commitId stored source commit, if applicable
   * @param artifacts backend-specific immutable textual artifacts
   */
  record RunResultResponse(
      RunResponse run,
      String overallStatus,
      Map<String, ExecutionStatus> nodeStatuses,
      Instant executionStartedAt,
      Instant executionCompletedAt,
      String commitId,
      Map<String, String> artifacts) {

    public RunResultResponse {
      nodeStatuses = Map.copyOf(Objects.requireNonNull(nodeStatuses, "nodeStatuses"));
      artifacts = Map.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
    }

    static RunResultResponse from(Snapshot snapshot, Result result) {
      ExecutionResult executionResult = result.reproducibilityBundle().result();
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
