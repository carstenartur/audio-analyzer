package org.hammer.audio.infrastructure.workflow.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.workflow.history.WorkflowCombinedHistoryQuery;
import org.hammer.audio.workflow.history.WorkflowCombinedHistoryResult;
import org.hammer.audio.workflow.history.WorkflowHistoryTextResult;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryResult;
import org.hammer.audio.workflow.store.CommitId;

/** Composes semantic candidates with generic ranking and final limiting. */
final class CombinedWorkflowHistorySearch {

  private final GenericWorkflowHistoryProjection genericProjection;
  private final WorkflowSemanticHistoryProjection semanticProjection;

  CombinedWorkflowHistorySearch(
      GenericWorkflowHistoryProjection genericProjection,
      WorkflowSemanticHistoryProjection semanticProjection) {
    this.genericProjection = Objects.requireNonNull(genericProjection, "genericProjection");
    this.semanticProjection = Objects.requireNonNull(semanticProjection, "semanticProjection");
  }

  List<WorkflowCombinedHistoryResult> search(WorkflowCombinedHistoryQuery query) {
    Objects.requireNonNull(query, "query");
    List<CommitId> candidates =
        semanticProjection.candidateCommitIds(query.semanticFilter());
    List<WorkflowHistoryTextResult> commits =
        genericProjection.searchWithinCandidates(query.genericQuery(), candidates);
    Map<String, WorkflowSemanticHistoryResult> evidence =
        semanticProjection.evidence(
            query.semanticFilter().branch(),
            commits.stream().map(WorkflowHistoryTextResult::commitId).toList());
    List<WorkflowCombinedHistoryResult> results = new ArrayList<>(commits.size());
    for (WorkflowHistoryTextResult commit : commits) {
      WorkflowSemanticHistoryResult semantics = evidence.get(commit.commitId().value());
      if (semantics == null) {
        throw new IllegalStateException(
            "Semantic history projection changed while composing commit "
                + commit.commitId().value()
                + "; retry the query");
      }
      results.add(new WorkflowCombinedHistoryResult(commit, semantics));
    }
    return List.copyOf(results);
  }
}
