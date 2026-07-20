package org.hammer.audio.infrastructure.workflow.store;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.hammer.audio.infrastructure.workflow.search.WorkflowSemanticIndexService;
import org.hammer.audio.infrastructure.workflow.search.WorkflowSemanticProjectionEntry;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryFilter;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryQuery;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryResult;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitInfo;
import org.hammer.audio.workflow.store.WorkflowSnapshot;
import org.hibernate.SessionFactory;

/** Maintains branch-aware semantic workflow projections beside authoritative Git history. */
final class WorkflowSemanticHistoryProjection {

  private static final Logger LOGGER =
      Logger.getLogger(WorkflowSemanticHistoryProjection.class.getName());

  private final JGitRepositoryVersionedWorkflowStore store;
  private final WorkflowSemanticIndexService indexService;

  WorkflowSemanticHistoryProjection(
      JGitRepositoryVersionedWorkflowStore store,
      SessionFactory sessionFactory,
      String repositoryName) {
    this.store = Objects.requireNonNull(store, "store");
    this.indexService =
        new WorkflowSemanticIndexService(
            Objects.requireNonNull(sessionFactory, "sessionFactory"), repositoryName);
  }

  List<WorkflowSemanticHistoryResult> search(WorkflowSemanticHistoryQuery query) {
    return indexService.search(Objects.requireNonNull(query, "query"));
  }

  List<CommitId> candidateCommitIds(WorkflowSemanticHistoryFilter filter) {
    return indexService.findCandidateCommitIds(Objects.requireNonNull(filter, "filter"));
  }

  Map<String, WorkflowSemanticHistoryResult> evidence(
      String branch, Collection<CommitId> commits) {
    return indexService.findEvidence(branch, commits);
  }

  void indexBestEffort(String branch, CommitId commitId, WorkflowSnapshot authoritativeSnapshot) {
    try {
      indexService.indexCheckpoint(branch, commitId, authoritativeSnapshot);
    } catch (RuntimeException failure) {
      LOGGER.log(
          Level.WARNING,
          "Workflow commit "
              + commitId.value()
              + " remains authoritative but its semantic search projection is stale",
          failure);
    }
  }

  void rebuildBestEffort(String branch) {
    try {
      rebuild(branch, -1);
    } catch (RuntimeException failure) {
      LOGGER.log(
          Level.WARNING,
          "Workflow branch "
              + branch
              + " remains authoritative but its semantic search projection is stale",
          failure);
    }
  }

  void rebuild(String branch, int limit) {
    int historyLimit = limit < 0 ? Integer.MAX_VALUE : limit;
    List<CommitInfo> commits = store.history(branch, historyLimit);
    List<WorkflowSemanticProjectionEntry> entries = new ArrayList<>(commits.size());
    for (int position = 0; position < commits.size(); position++) {
      CommitId commitId = commits.get(position).commitId();
      entries.add(
          new WorkflowSemanticProjectionEntry(commitId, position, store.loadAtCommit(commitId)));
    }
    indexService.replaceBranch(branch, entries);
  }
}
