package org.hammer.audio.workflow.editor.http;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.history.IndexedWorkflowCombinedHistorySearch;
import org.hammer.audio.workflow.history.WorkflowCombinedHistoryQuery;
import org.hammer.audio.workflow.history.WorkflowCombinedHistoryResult;
import org.hammer.audio.workflow.history.WorkflowHistoryTextQuery;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryFilter;
import org.hammer.audio.workflow.history.WorkflowSemanticProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP boundary for one correctly composed generic and semantic workflow-history query. */
@RestController
@RequestMapping("/workflow/history/combined")
@ConditionalOnProperty(name = "workbench.persistence.mode", havingValue = "hibernate")
public final class WorkflowCombinedHistoryHttpAdapter {

  private static final int DEFAULT_LIMIT = 20;
  private final IndexedWorkflowCombinedHistorySearch search;

  /** Creates the combined history controller. */
  public WorkflowCombinedHistoryHttpAdapter(IndexedWorkflowCombinedHistorySearch search) {
    this.search = Objects.requireNonNull(search, "search");
  }

  /** Executes semantic candidate selection before generic ranking and the final result limit. */
  @PostMapping("/query")
  public List<CombinedHitResponse> query(@Valid @RequestBody CombinedSearchRequest request) {
    return search.searchCombined(request.toDomain()).stream()
        .map(CombinedHitResponse::from)
        .toList();
  }

  /**
   * Combined request with independently named generic and semantic filters.
   *
   * @param generic generic commit-history predicates and final result limit
   * @param semantic branch and workflow-domain predicates
   */
  public record CombinedSearchRequest(
      @NotNull @Valid GenericFilters generic, @NotNull @Valid SemanticFilters semantic) {

    public CombinedSearchRequest {
      // Bean validation owns the transport contract.
    }

    WorkflowCombinedHistoryQuery toDomain() {
      return new WorkflowCombinedHistoryQuery(generic.toDomain(), semantic.toDomain());
    }
  }

  /**
   * Generic commit-history filters.
   *
   * @param text full-text expression
   * @param authorEmail exact author email
   * @param pathText analyzed changed-path expression
   * @param from inclusive lower time bound
   * @param to inclusive upper time bound
   * @param limit final result limit, defaulting to 20
   */
  public record GenericFilters(
      String text,
      String authorEmail,
      String pathText,
      Instant from,
      Instant to,
      @Min(1) @Max(200) Integer limit) {

    public GenericFilters {
      // Domain construction normalizes optional strings and validates time bounds.
    }

    WorkflowHistoryTextQuery toDomain() {
      return new WorkflowHistoryTextQuery(
          text, authorEmail, pathText, from, to, limit == null ? DEFAULT_LIMIT : limit);
    }
  }

  /**
   * Workflow-semantic filters.
   *
   * @param branch required reachability boundary
   * @param workflowId exact workflow identifier
   * @param nodeId exact node identifier
   * @param nodeType exact node type
   * @param labelText workflow-name or node-label expression
   * @param propertyKey exact metadata key
   * @param propertyValue exact metadata value
   */
  public record SemanticFilters(
      @NotBlank String branch,
      String workflowId,
      String nodeId,
      String nodeType,
      String labelText,
      String propertyKey,
      String propertyValue) {

    public SemanticFilters {
      // Domain construction normalizes optional semantic values.
    }

    WorkflowSemanticHistoryFilter toDomain() {
      return new WorkflowSemanticHistoryFilter(
          branch, workflowId, nodeId, nodeType, labelText, propertyKey, propertyValue);
    }
  }

  /**
   * Combined response for one exact commit.
   *
   * @param commit generic commit metadata and changed-path evidence
   * @param semantics branch-specific workflow-domain evidence
   */
  public record CombinedHitResponse(CommitEvidence commit, SemanticEvidence semantics) {

    public CombinedHitResponse {
      Objects.requireNonNull(commit, "commit");
      Objects.requireNonNull(semantics, "semantics");
    }

    static CombinedHitResponse from(WorkflowCombinedHistoryResult result) {
      return new CombinedHitResponse(CommitEvidence.from(result), SemanticEvidence.from(result));
    }
  }

  /**
   * Generic exact-commit evidence.
   *
   * @param commitId exact Git commit identity
   * @param message short commit message
   * @param authorName author display name
   * @param authorEmail exact author email
   * @param timestamp commit timestamp
   * @param changedPaths first-parent changed paths
   */
  public record CommitEvidence(
      String commitId,
      String message,
      String authorName,
      String authorEmail,
      Instant timestamp,
      List<String> changedPaths) {

    public CommitEvidence {
      commitId = Objects.requireNonNull(commitId, "commitId");
      changedPaths = List.copyOf(Objects.requireNonNull(changedPaths, "changedPaths"));
    }

    static CommitEvidence from(WorkflowCombinedHistoryResult result) {
      return new CommitEvidence(
          result.commit().commitId().value(),
          result.commit().shortMessage(),
          result.commit().authorName(),
          result.commit().authorEmail(),
          result.commit().timestamp(),
          result.commit().changedPaths());
    }
  }

  /**
   * Branch-specific workflow-domain evidence.
   *
   * @param branch branch from which the commit is reachable
   * @param workflowId exact workflow identifier
   * @param workflowName workflow display name
   * @param nodeIds exact node identifiers
   * @param nodeTypes exact node types
   * @param nodeLabels node labels
   * @param properties correlated metadata entries
   */
  public record SemanticEvidence(
      String branch,
      String workflowId,
      String workflowName,
      List<String> nodeIds,
      List<String> nodeTypes,
      List<String> nodeLabels,
      List<WorkflowSemanticProperty> properties) {

    public SemanticEvidence {
      branch = Objects.requireNonNull(branch, "branch");
      workflowId = Objects.requireNonNull(workflowId, "workflowId");
      workflowName = Objects.requireNonNull(workflowName, "workflowName");
      nodeIds = List.copyOf(Objects.requireNonNull(nodeIds, "nodeIds"));
      nodeTypes = List.copyOf(Objects.requireNonNull(nodeTypes, "nodeTypes"));
      nodeLabels = List.copyOf(Objects.requireNonNull(nodeLabels, "nodeLabels"));
      properties = List.copyOf(Objects.requireNonNull(properties, "properties"));
    }

    static SemanticEvidence from(WorkflowCombinedHistoryResult result) {
      return new SemanticEvidence(
          result.semantics().branch(),
          result.semantics().workflowId(),
          result.semantics().workflowName(),
          result.semantics().nodeIds(),
          result.semantics().nodeTypes(),
          result.semantics().nodeLabels(),
          result.semantics().properties());
    }
  }
}
