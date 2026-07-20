package org.hammer.audio.workflow.editor.http;

import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.history.IndexedWorkflowSemanticHistorySearch;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryQuery;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryResult;
import org.hammer.audio.workflow.history.WorkflowSemanticProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP boundary for branch-aware semantic workflow-history queries. */
@RestController
@RequestMapping("/workflow/history/semantic")
@ConditionalOnBean(IndexedWorkflowSemanticHistorySearch.class)
public final class WorkflowSemanticHistoryHttpAdapter {

  private static final int DEFAULT_LIMIT = 20;
  private final IndexedWorkflowSemanticHistorySearch semanticSearch;

  public WorkflowSemanticHistoryHttpAdapter(IndexedWorkflowSemanticHistorySearch semanticSearch) {
    this.semanticSearch = Objects.requireNonNull(semanticSearch, "semanticSearch");
  }

  /** Searches exact workflow semantics derived from commits reachable on one branch. */
  @GetMapping
  public List<SemanticHitResponse> search(@ModelAttribute SemanticSearchRequest request) {
    return semanticSearch.searchSemantic(request.toQuery()).stream()
        .map(SemanticHitResponse::from)
        .toList();
  }

  /**
   * Bindable semantic-history query parameters.
   *
   * @param branch branch whose reachable commits are searched; defaults to {@code main}
   * @param workflow exact workflow identifier
   * @param node exact node identifier
   * @param type exact node type
   * @param label workflow-name or node-label full-text expression
   * @param propertyKey exact metadata key
   * @param propertyValue exact metadata value
   * @param limit bounded result count; defaults to 20
   */
  public record SemanticSearchRequest(
      String branch,
      String workflow,
      String node,
      String type,
      String label,
      String propertyKey,
      String propertyValue,
      String limit) {

    public SemanticSearchRequest {
      branch = branch == null || branch.isBlank() ? "main" : branch.trim();
      limit = limit == null || limit.isBlank() ? Integer.toString(DEFAULT_LIMIT) : limit.trim();
    }

    WorkflowSemanticHistoryQuery toQuery() {
      return new WorkflowSemanticHistoryQuery(
          branch,
          workflow,
          node,
          type,
          label,
          propertyKey,
          propertyValue,
          Integer.parseInt(limit));
    }
  }

  /**
   * Domain-semantic evidence tied to one exact authoritative Git commit.
   *
   * @param commitId exact Git commit identity
   * @param branch branch for which the commit is reachable
   * @param workflowId stable workflow identifier
   * @param workflowName human-readable workflow name
   * @param nodeIds stable node identifiers contained in the workflow
   * @param nodeTypes logical node types contained in the workflow
   * @param nodeLabels human-readable node labels contained in the workflow
   * @param properties exact workflow and node metadata entries
   */
  public record SemanticHitResponse(
      String commitId,
      String branch,
      String workflowId,
      String workflowName,
      List<String> nodeIds,
      List<String> nodeTypes,
      List<String> nodeLabels,
      List<WorkflowSemanticProperty> properties) {

    public SemanticHitResponse {
      commitId = Objects.requireNonNull(commitId, "commitId");
      branch = Objects.requireNonNull(branch, "branch");
      workflowId = Objects.requireNonNull(workflowId, "workflowId");
      workflowName = Objects.requireNonNull(workflowName, "workflowName");
      nodeIds = List.copyOf(Objects.requireNonNull(nodeIds, "nodeIds"));
      nodeTypes = List.copyOf(Objects.requireNonNull(nodeTypes, "nodeTypes"));
      nodeLabels = List.copyOf(Objects.requireNonNull(nodeLabels, "nodeLabels"));
      properties = List.copyOf(Objects.requireNonNull(properties, "properties"));
    }

    static SemanticHitResponse from(WorkflowSemanticHistoryResult result) {
      return new SemanticHitResponse(
          result.commitId().value(),
          result.branch(),
          result.workflowId(),
          result.workflowName(),
          result.nodeIds(),
          result.nodeTypes(),
          result.nodeLabels(),
          result.properties());
    }
  }
}
