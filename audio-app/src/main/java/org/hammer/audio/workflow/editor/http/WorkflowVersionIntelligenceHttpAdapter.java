package org.hammer.audio.workflow.editor.http;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.dsl.WorkflowDslParser;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
import org.hammer.audio.workflow.execution.WorkflowRunService;
import org.hammer.audio.workflow.search.WorkflowHistoryDocument;
import org.hammer.audio.workflow.search.WorkflowHistoryQuery;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;
import org.hammer.audio.workflow.store.WorkflowSnapshot;
import org.hammer.audio.workflow.version.WorkflowDiff;
import org.hammer.audio.workflow.version.WorkflowMergeResolution;
import org.hammer.audio.workflow.version.WorkflowMergeService;
import org.hammer.audio.workflow.version.WorkflowSemanticDiffService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST facade for semantic compare/merge, rebuildable search and immutable executions. */
@RestController
@RequestMapping("/workflow")
public final class WorkflowVersionIntelligenceHttpAdapter {

  private final ObjectProvider<VersionedWorkflowStore> storeProvider;
  private final WorkflowHistorySearchService searchService;
  private final WorkflowSessionRegistry sessions;
  private final WorkflowRunService runs;
  private final WorkflowDslParser parser = new WorkflowDslParser();
  private final WorkflowDslSerializer serializer = new WorkflowDslSerializer();
  private final WorkflowSemanticDiffService diffService = new WorkflowSemanticDiffService();
  private final WorkflowMergeService mergeService = new WorkflowMergeService();

  public WorkflowVersionIntelligenceHttpAdapter(
      ObjectProvider<VersionedWorkflowStore> storeProvider,
      WorkflowHistorySearchService searchService,
      WorkflowSessionRegistry sessions,
      WorkflowRunService runs) {
    this.storeProvider = Objects.requireNonNull(storeProvider, "storeProvider");
    this.searchService = Objects.requireNonNull(searchService, "searchService");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.runs = Objects.requireNonNull(runs, "runs");
  }

  @PostMapping("/versions/compare")
  public WorkflowDiff compare(@Valid @RequestBody CompareRequest request) {
    return diffService.compare(load(request.leftCommitId()), load(request.rightCommitId()));
  }

  @PostMapping("/versions/merge")
  public MergeResponse merge(@Valid @RequestBody MergeRequest request) {
    WorkflowMergeService.MergeResult result =
        mergeService.merge(
            load(request.baseCommitId()),
            load(request.localCommitId()),
            load(request.remoteCommitId()),
            request.resolutions());
    String commitId = null;
    if (result.resolved() && request.targetBranch() != null && !request.targetBranch().isBlank()) {
      Workflow merged = result.mergedWorkflow();
      WorkflowSnapshot snapshot = new WorkflowSnapshot(merged.id(), serializer.serialize(merged));
      CommitMetadata metadata =
          new CommitMetadata(
              request.author(),
              request.message() == null || request.message().isBlank()
                  ? "Semantic workflow merge"
                  : request.message(),
              Instant.now());
      commitId = requireStore().commit(request.targetBranch(), snapshot, metadata).value();
    }
    return new MergeResponse(result, commitId);
  }

  @PostMapping("/search/rebuild")
  public RebuildResponse rebuild(@Valid @RequestBody RebuildRequest request) {
    return new RebuildResponse(searchService.rebuild(request.branches(), request.limitPerBranch()));
  }

  @GetMapping("/search")
  public List<WorkflowHistoryDocument> search(
      @RequestParam(required = false) String text,
      @RequestParam(required = false) String branch,
      @RequestParam(required = false) String author,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(required = false) String nodeType,
      @RequestParam(required = false) String propertyKey,
      @RequestParam(required = false) String propertyValue,
      @RequestParam(defaultValue = "50") int limit) {
    return searchService.search(
        new WorkflowHistoryQuery(
            text, branch, author, from, to, nodeType, propertyKey, propertyValue, limit));
  }

  @PostMapping("/executions")
  public WorkflowRunService.RunSnapshot start(@Valid @RequestBody ExecuteRequest request) {
    if (request.sessionId() != null && !request.sessionId().isBlank()) {
      return runs.start(sessions.workflow(request.sessionId()), null);
    }
    if (request.commitId() != null && !request.commitId().isBlank()) {
      return runs.start(load(request.commitId()), request.commitId());
    }
    throw new IllegalArgumentException("Either sessionId or commitId is required");
  }

  @GetMapping("/executions")
  public List<WorkflowRunService.RunSnapshot> executions() {
    return runs.runs();
  }

  @GetMapping("/executions/{runId}")
  public WorkflowRunService.RunSnapshot execution(@PathVariable String runId) {
    return runs.get(runId);
  }

  @DeleteMapping("/executions/{runId}")
  public WorkflowRunService.RunSnapshot cancel(@PathVariable String runId) {
    return runs.cancel(runId);
  }

  private Workflow load(String commitId) {
    WorkflowSnapshot snapshot = requireStore().loadAtCommit(new CommitId(commitId));
    Workflow workflow = parser.parse(snapshot.dslText());
    if (!snapshot.workflowId().equals(workflow.id())) {
      throw new IllegalArgumentException("Stored snapshot id and DSL workflow id differ");
    }
    return workflow;
  }

  private VersionedWorkflowStore requireStore() {
    VersionedWorkflowStore store = storeProvider.getIfAvailable();
    if (store == null) {
      throw new IllegalStateException("VersionedWorkflowStore is not configured");
    }
    return store;
  }

  public record CompareRequest(@NotBlank String leftCommitId, @NotBlank String rightCommitId) {}

  public record MergeRequest(
      @NotBlank String baseCommitId,
      @NotBlank String localCommitId,
      @NotBlank String remoteCommitId,
      @NotNull Map<String, WorkflowMergeResolution.Choice> resolutions,
      String targetBranch,
      @NotBlank String author,
      String message) {}

  public record MergeResponse(WorkflowMergeService.MergeResult result, String commitId) {}

  public record RebuildRequest(
      @NotEmpty List<@NotBlank String> branches, @Min(1) @Max(10000) int limitPerBranch) {
    public RebuildRequest {
      branches = List.copyOf(branches);
    }
  }

  public record RebuildResponse(int indexedDocuments) {}

  public record ExecuteRequest(String sessionId, String commitId) {}
}
