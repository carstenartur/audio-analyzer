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
  private final IndexedWorkflowHistorySearch historySearch;

  public WorkflowHistoryIndexHttpAdapter(IndexedWorkflowHistorySearch historySearch) {
    this.historySearch = Objects.requireNonNull(historySearch, "historySearch");
  }

  /** Searches messages, paths and deterministic workflow DSL content with optional metadata filters. */
  @GetMapping
  public List<SearchHitResponse> search(
      @RequestParam(name = "q", defaultValue = "") String query,
      @RequestParam(name = "author", required = false) String authorEmail,
      @RequestParam(name = "path", required = false) String pathText,
      @RequestParam(name = "from", required = false) Instant from,
      @RequestParam(name = "to", required = false) Instant to,
      @RequestParam(name = "limit", defaultValue = DEFAULT_LIMIT) int limit) {
    return historySearch
        .search(new WorkflowHistoryTextQuery(query, authorEmail, pathText, from, to, limit))
        .stream()
        .map(SearchHitResponse::from)
        .toList();
  }

  /** Rebuilds missing derived projections from authoritative branch history. */
  @PostMapping("/rebuild")
  public RebuildResponse rebuild(
      @RequestParam(name = "branch", defaultValue = "main") String branch,
      @RequestParam(name = "limit", defaultValue = "-1") int limit) {
    return new RebuildResponse(historySearch.rebuild(branch, limit));
  }

  /**
   * Exact indexed commit hit returned without persistence implementation types.
   *
   * @param commitId authoritative Git commit identity
   * @param message indexed commit summary
   * @param authorName author display name, or {@code null}
   * @param authorEmail author email, or {@code null}
   * @param timestamp author timestamp, or {@code null}
   * @param changedPaths first-parent changed paths represented by the projection
   */
  public record SearchHitResponse(
      String commitId,
      String message,
      String authorName,
      String authorEmail,
      Instant timestamp,
      List<String> changedPaths) {

    public SearchHitResponse {
      commitId = Objects.requireNonNull(commitId, "commitId");
      message = message == null ? "" : message;
      changedPaths = List.copyOf(Objects.requireNonNull(changedPaths, "changedPaths"));
    }

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

  /**
   * Outcome of an explicit rebuild request.
   *
   * @param indexedCommits number of previously missing commit projections created
   */
  public record RebuildResponse(int indexedCommits) {

    public RebuildResponse {
      if (indexedCommits < 0) {
        throw new IllegalArgumentException("indexedCommits must be >= 0");
      }
    }
  }
}
