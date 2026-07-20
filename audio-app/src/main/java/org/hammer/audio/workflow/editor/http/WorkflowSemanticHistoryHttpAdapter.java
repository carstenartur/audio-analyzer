package org.hammer.audio.workflow.editor.http;

import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.history.IndexedWorkflowSemanticHistorySearch;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryQuery;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryResult;
import org.hammer.audio.workflow.history.WorkflowSemanticProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP boundary for branch-aware semantic workflow-history queries. */
@RestController
@RequestMapping("/workflow/history/semantic")
@ConditionalOnBean(IndexedWorkflowSemanticHistorySearch.class)
public final class WorkflowSemanticHistoryHttpAdapter {

  private static final String DEFAULT_LIMIT = "20";
  private final IndexedWorkflowSemanticHistorySearch semanticSearch;

  public WorkflowSemanticHistoryHttpAdapter(
      IndexedWorkflowSemanticHistorySearch semanticSearch) {
    this.semanticSearch = Objects.requireNonNull(semanticSearch, "semanticSearch");
  }

  /** Searches exact workflow semantics derived from commits reachable on one branch. */
  @GetMapping
  public List<SemanticHitResponse> search(
      @RequestParam(name = "branch", defaultValue = "main") String branch,
      @RequestParam(name = "workflow", required = false) String workflowId,
      @RequestParam(name = "node", required = false) String nodeId,
      @RequestParam(name = "type", required = false) String nodeType,
      @RequestParam(name = "label", required = false) String labelText,
      @RequestParam(name = "propertyKey", required = false) String propertyKey,
      @RequestParam(name = "propertyValue", required = false) String propertyValue,
      @RequestParam(name = "limit", defaultValue = DEFAULT_LIMIT) int limit) {
    return semanticSearch
        .searchSemantic(
            new WorkflowSemanticHistoryQuery(
                branch,
                workflowId,
                nodeId,
                nodeType,
                labelText,
                propertyKey,
                propertyValue,
                limit))
        .stream()
        .map(SemanticHitResponse::from)
        .toList();
  }

  /** Domain-semantic evidence tied to one exact authoritative Git commit. */
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
