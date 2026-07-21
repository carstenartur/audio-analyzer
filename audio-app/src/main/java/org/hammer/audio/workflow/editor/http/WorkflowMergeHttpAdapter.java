package org.hammer.audio.workflow.editor.http;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.hammer.audio.workflow.editor.WorkflowProjection;
import org.hammer.audio.workflow.history.PreviewWorkflowMergeCommand;
import org.hammer.audio.workflow.history.ResolveWorkflowMergeCommand;
import org.hammer.audio.workflow.history.WorkflowMergeCommandService;
import org.hammer.audio.workflow.history.WorkflowMergeCommitResult;
import org.hammer.audio.workflow.history.WorkflowMergePreview;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.Conflict;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.ResolutionChoice;
import org.hammer.audio.workflow.merge.WorkflowMergeResolution;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP boundary for deterministic semantic merge preview and resolved checkpoint creation. */
@RestController
@RequestMapping("/workflow/history/merge")
@ConditionalOnProperty(name = "workbench.persistence.mode", havingValue = "hibernate")
public final class WorkflowMergeHttpAdapter {

  private final WorkflowMergeCommandService service;

  /** Creates the transport adapter over the framework-independent merge service. */
  public WorkflowMergeHttpAdapter(WorkflowMergeCommandService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  /** Loads exact base/local/remote commits and returns a deterministic semantic preview. */
  @PostMapping("/preview")
  public PreviewResponse preview(@Valid @RequestBody PreviewRequest request) {
    return PreviewResponse.from(service.preview(request.toCommand()));
  }

  /** Applies explicit conflict decisions and commits the validated result on the expected HEAD. */
  @PostMapping("/resolve")
  public ResolveResponse resolve(@Valid @RequestBody ResolveRequest request) {
    return ResolveResponse.from(service.resolveAndCommit(request.toCommand()));
  }

  /**
   * Exact stored versions for one preview.
   *
   * @param targetBranch local branch and eventual checkpoint target
   * @param remoteBranch remote source branch
   * @param baseCommitId common base commit
   * @param localCommitId exact local commit
   * @param remoteCommitId exact remote commit
   */
  public record PreviewRequest(
      @NotBlank String targetBranch,
      @NotBlank String remoteBranch,
      @NotBlank String baseCommitId,
      @NotBlank String localCommitId,
      @NotBlank String remoteCommitId) {

    PreviewWorkflowMergeCommand toCommand() {
      return new PreviewWorkflowMergeCommand(
          targetBranch,
          remoteBranch,
          new CommitId(baseCommitId),
          new CommitId(localCommitId),
          new CommitId(remoteCommitId));
    }
  }

  /**
   * One explicit semantic conflict decision.
   *
   * @param conflictId exact conflict identity returned by preview
   * @param choice selected resolution choice
   * @param customValue custom scalar value for {@code CUSTOM}
   */
  public record ResolutionRequest(
      @NotBlank String conflictId, @NotNull ResolutionChoice choice, String customValue) {

    WorkflowMergeResolution toDecision() {
      return new WorkflowMergeResolution(conflictId, choice, customValue);
    }
  }

  /**
   * Resolve-and-commit request with optimistic target-HEAD protection and audit metadata.
   *
   * @param targetBranch local target branch
   * @param remoteBranch remote source branch
   * @param baseCommitId common base commit
   * @param localCommitId exact local commit
   * @param remoteCommitId exact remote commit
   * @param expectedHeadCommitId target HEAD expected by the client
   * @param resolutions explicit conflict decisions
   * @param author merge checkpoint author
   * @param message merge checkpoint message
   * @param timestamp merge checkpoint timestamp
   */
  public record ResolveRequest(
      @NotBlank String targetBranch,
      @NotBlank String remoteBranch,
      @NotBlank String baseCommitId,
      @NotBlank String localCommitId,
      @NotBlank String remoteCommitId,
      @NotBlank String expectedHeadCommitId,
      @NotNull List<@Valid ResolutionRequest> resolutions,
      @NotBlank String author,
      @NotBlank String message,
      @NotNull Instant timestamp) {

    ResolveWorkflowMergeCommand toCommand() {
      PreviewWorkflowMergeCommand preview =
          new PreviewRequest(
                  targetBranch,
                  remoteBranch,
                  baseCommitId,
                  localCommitId,
                  remoteCommitId)
              .toCommand();
      return new ResolveWorkflowMergeCommand(
          preview,
          new CommitId(expectedHeadCommitId),
          resolutions.stream().map(ResolutionRequest::toDecision).toList(),
          new CommitMetadata(author, message, timestamp));
    }
  }

  /**
   * Transport-safe semantic conflict.
   *
   * @param conflictId deterministic conflict identity
   * @param kind typed conflict classification
   * @param elementKind workflow, node or edge
   * @param elementId stable element identity
   * @param fieldPath semantic field path
   * @param baseValue canonical base value
   * @param localValue canonical local value
   * @param remoteValue canonical remote value
   * @param allowedChoices supported explicit decisions
   */
  public record ConflictResponse(
      String conflictId,
      String kind,
      String elementKind,
      String elementId,
      String fieldPath,
      String baseValue,
      String localValue,
      String remoteValue,
      Set<ResolutionChoice> allowedChoices) {

    static ConflictResponse from(Conflict conflict) {
      return new ConflictResponse(
          conflict.conflictId(),
          conflict.kind().name(),
          conflict.elementKind().name(),
          conflict.elementId(),
          conflict.fieldPath(),
          conflict.baseValue(),
          conflict.localValue(),
          conflict.remoteValue(),
          conflict.allowedChoices());
    }
  }

  /** Complete graph states, automatic candidate, ordered conflicts and validation impact. */
  public record PreviewResponse(
      String targetBranch,
      String remoteBranch,
      String baseCommitId,
      String localCommitId,
      String remoteCommitId,
      WorkflowProjection base,
      WorkflowProjection local,
      WorkflowProjection remote,
      WorkflowProjection autoMerged,
      List<ConflictResponse> conflicts,
      List<String> validationViolations,
      boolean readyToCommit) {

    public PreviewResponse {
      conflicts = List.copyOf(Objects.requireNonNull(conflicts, "conflicts"));
      validationViolations =
          List.copyOf(Objects.requireNonNull(validationViolations, "validationViolations"));
    }

    static PreviewResponse from(WorkflowMergePreview preview) {
      return new PreviewResponse(
          preview.targetBranch(),
          preview.remoteBranch(),
          preview.baseCommit().value(),
          preview.localCommit().value(),
          preview.remoteCommit().value(),
          WorkflowProjection.fromWorkflow(preview.baseWorkflow()),
          WorkflowProjection.fromWorkflow(preview.localWorkflow()),
          WorkflowProjection.fromWorkflow(preview.remoteWorkflow()),
          WorkflowProjection.fromWorkflow(preview.merge().autoMergedWorkflow()),
          preview.merge().conflicts().stream().map(ConflictResponse::from).toList(),
          preview.merge().validationViolations(),
          preview.merge().readyToCommit());
    }
  }

  /** Newly created merge checkpoint and exact reloaded workflow projection. */
  public record ResolveResponse(
      String targetBranch,
      String baseCommitId,
      String localCommitId,
      String remoteCommitId,
      String mergedCommitId,
      WorkflowProjection workflow,
      String auditMessage) {

    static ResolveResponse from(WorkflowMergeCommitResult result) {
      return new ResolveResponse(
          result.targetBranch(),
          result.baseCommit().value(),
          result.localCommit().value(),
          result.remoteCommit().value(),
          result.mergedCommit().value(),
          WorkflowProjection.fromWorkflow(result.workflow()),
          result.auditMessage());
    }
  }
}
