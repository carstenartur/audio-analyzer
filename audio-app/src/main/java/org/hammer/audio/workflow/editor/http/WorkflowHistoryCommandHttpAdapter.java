package org.hammer.audio.workflow.editor.http;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.hammer.audio.workflow.history.RestoreWorkflowVersionCommand;
import org.hammer.audio.workflow.history.WorkflowChange;
import org.hammer.audio.workflow.history.WorkflowHistoryCommandService;
import org.hammer.audio.workflow.history.WorkflowHistoryComparison;
import org.hammer.audio.workflow.history.WorkflowRestoreResult;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP boundary for explicit branch-scoped workflow comparison and non-destructive restore. */
@RestController
@RequestMapping("/workflow/history")
@ConditionalOnBean(WorkflowHistoryCommandService.class)
public final class WorkflowHistoryCommandHttpAdapter {

  private final WorkflowHistoryCommandService commandService;

  /** Creates the history-command controller. */
  public WorkflowHistoryCommandHttpAdapter(WorkflowHistoryCommandService commandService) {
    this.commandService = commandService;
  }

  /** Compares two exact commits reachable from one branch. */
  @PostMapping("/compare")
  public ComparisonResponse compare(@Valid @RequestBody CompareRequest request) {
    return ComparisonResponse.from(
        commandService.compare(
            request.branch(),
            new CommitId(request.beforeCommitId()),
            new CommitId(request.afterCommitId())));
  }

  /** Restores a historical snapshot as a new audit commit on the expected current HEAD. */
  @PostMapping("/restore")
  public RestoreResponse restore(@Valid @RequestBody RestoreRequest request) {
    WorkflowRestoreResult result =
        commandService.restore(
            new RestoreWorkflowVersionCommand(
                request.branch(),
                new CommitId(request.targetCommitId()),
                new CommitId(request.expectedHeadCommitId()),
                new CommitMetadata(request.author(), request.message(), request.timestamp())));
    return RestoreResponse.from(result);
  }

  /**
   * Branch-scoped comparison request.
   *
   * @param branch branch from which both commits must be reachable
   * @param beforeCommitId exact earlier commit
   * @param afterCommitId exact later commit
   */
  public record CompareRequest(
      @NotBlank String branch,
      @NotBlank String beforeCommitId,
      @NotBlank String afterCommitId) {}

  /**
   * Non-destructive restore request.
   *
   * @param branch branch receiving the new restore commit
   * @param targetCommitId reachable historical commit whose snapshot is restored
   * @param expectedHeadCommitId optimistic-concurrency branch HEAD
   * @param author restore commit author
   * @param message restore commit message
   * @param timestamp restore commit timestamp
   */
  public record RestoreRequest(
      @NotBlank String branch,
      @NotBlank String targetCommitId,
      @NotBlank String expectedHeadCommitId,
      @NotBlank String author,
      @NotBlank String message,
      @NotNull Instant timestamp) {}

  /**
   * Transport-safe comparison including both graph states and semantic change atoms.
   *
   * @param beforeCommitId exact before commit
   * @param afterCommitId exact after commit
   * @param before graph projection before the change
   * @param after graph projection after the change
   * @param changes ordered semantic changes
   */
  public record ComparisonResponse(
      String beforeCommitId,
      String afterCommitId,
      WorkflowProjection before,
      WorkflowProjection after,
      List<ChangeResponse> changes) {

    static ComparisonResponse from(WorkflowHistoryComparison comparison) {
      return new ComparisonResponse(
          comparison.beforeCommit().value(),
          comparison.afterCommit().value(),
          WorkflowProjection.fromWorkflow(comparison.beforeWorkflow()),
          WorkflowProjection.fromWorkflow(comparison.afterWorkflow()),
          comparison.diff().changes().stream().map(ChangeResponse::from).toList());
    }
  }

  /**
   * Transport-safe semantic change atom.
   *
   * @param kind stable change kind
   * @param targetId affected node or edge identifier
   * @param propertyKey metadata key for parameter changes
   * @param oldValue previous label or property value
   * @param newValue new label or property value
   */
  public record ChangeResponse(
      String kind, String targetId, String propertyKey, String oldValue, String newValue) {

    static ChangeResponse from(WorkflowChange change) {
      return switch (change) {
        case WorkflowChange.NodeAdded added ->
            new ChangeResponse("NODE_ADDED", added.node().id(), null, null, added.node().label());
        case WorkflowChange.NodeRemoved removed ->
            new ChangeResponse(
                "NODE_REMOVED", removed.node().id(), null, removed.node().label(), null);
        case WorkflowChange.EdgeAdded added ->
            new ChangeResponse("EDGE_ADDED", added.edge().id(), null, null, null);
        case WorkflowChange.EdgeRemoved removed ->
            new ChangeResponse("EDGE_REMOVED", removed.edge().id(), null, null, null);
        case WorkflowChange.ParameterChanged parameter ->
            new ChangeResponse(
                "PARAMETER_CHANGED",
                parameter.targetId(),
                parameter.propertyKey(),
                parameter.oldValue(),
                parameter.newValue());
      };
    }
  }

  /**
   * Audit response for a completed non-destructive restore.
   *
   * @param branch restored branch
   * @param targetCommitId historical source commit
   * @param previousHeadCommitId HEAD protected by optimistic concurrency
   * @param restoredCommitId newly created audit commit
   */
  public record RestoreResponse(
      String branch,
      String targetCommitId,
      String previousHeadCommitId,
      String restoredCommitId) {

    static RestoreResponse from(WorkflowRestoreResult result) {
      return new RestoreResponse(
          result.branch(),
          result.targetCommit().value(),
          result.previousHead().value(),
          result.restoredCommit().value());
    }
  }
}
