package org.hammer.audio.workflow.editor.http;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.history.IndexedWorkflowHistorySearch;
import org.hammer.audio.workflow.history.WorkflowHistoryTextQuery;
import org.hammer.audio.workflow.history.WorkflowHistoryTextResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP boundary for rebuildable indexed checkpoint history search. */
@RestController
@RequestMapping("/workflow/history/index")
@ConditionalOnBean(IndexedWorkflowHistorySearch.class)
public final class WorkflowHistoryIndexHttpAdapter {

  private static final String DEFAULT_LIMIT = "20";
  private final IndexedWorkflowHistorySearch search;

  public WorkflowHistoryIndexHttpAdapter(IndexedWorkflowHistorySearch search) {
    this.search = Objects.requireNonNull(search, "search");
  }

  /** Searches messages, paths and deterministic workflow DSL content. */
  @GetMapping
  public List<SearchHitResponse> search(
      @RequestParam(name = "q", defaultValue = "") String query,
      @RequestParam(name = "limit", defaultValue = DEFAULT_LIMIT) int limit) {
    return search.search(new WorkflowHistoryTextQuery(query, limit)).stream()
        .map(SearchHitResponse::from)
        .toList();
  }

  /** Rebuilds missing derived projections from authoritative branch history. */
  @PostMapping("/rebuild")
  public RebuildResponse rebuild(
      @RequestParam(name = "branch", defaultValue = "main") String branch,
      @RequestParam(name = "limit", defaultValue = "-1") int limit) {
    return new RebuildResponse(search.rebuild(branch, limit));
  }

  public record SearchHitResponse(
      String commitId,
      String message,
      String authorName,
      String authorEmail,
      Instant timestamp,
      List<String> changedPaths) {

    static SearchHitResponse from(WorkflowHistoryTextResult result) {
      return new SearchHitResponse(
          result.commitId().value(),
          result.shortMessage(),
          result.authorName(),
          result.authorEmail(),
          result.timestamp(),
          result.changedPaths());
    }
  }

  public record RebuildResponse(int indexedCommits) {}
}
