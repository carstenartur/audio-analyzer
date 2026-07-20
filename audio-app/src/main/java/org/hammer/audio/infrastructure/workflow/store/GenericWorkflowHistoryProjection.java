package org.hammer.audio.infrastructure.workflow.store;

import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.hammer.audio.workflow.history.WorkflowHistoryTextQuery;
import org.hammer.audio.workflow.history.WorkflowHistoryTextResult;
import org.hammer.audio.workflow.store.CommitId;
import org.hibernate.SessionFactory;

/** Maintains the generic, rebuildable Git-history projection for one workflow repository. */
final class GenericWorkflowHistoryProjection {

  private static final Logger LOGGER =
      Logger.getLogger(GenericWorkflowHistoryProjection.class.getName());

  private final Repository repository;
  private final String repositoryName;
  private final CommitIndexer commitIndexer;
  private final GitHistorySearchService searchService;

  GenericWorkflowHistoryProjection(
      Repository repository, SessionFactory sessionFactory, String repositoryName) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.repositoryName = requireNotBlank(repositoryName, "repositoryName");
    SessionFactory requiredSessionFactory =
        Objects.requireNonNull(sessionFactory, "sessionFactory");
    this.commitIndexer = new CommitIndexer(requiredSessionFactory, this.repositoryName);
    this.searchService = new GitHistorySearchService(requiredSessionFactory);
  }

  List<WorkflowHistoryTextResult> search(WorkflowHistoryTextQuery query) {
    Objects.requireNonNull(query, "query");
    CommitHistoryQuery.Builder sharedQuery =
        CommitHistoryQuery.forRepository(repositoryName)
            .matchingText(query.text())
            .authoredBy(query.authorEmail())
            .touchingPath(query.pathText())
            .limit(query.limit());
    if (query.from() != null) {
      sharedQuery.from(query.from());
    }
    if (query.to() != null) {
      sharedQuery.to(query.to());
    }
    return searchService.findChanges(sharedQuery.build()).stream()
        .map(GenericWorkflowHistoryProjection::toResult)
        .toList();
  }

  int rebuild(String branch, int limit) {
    String requiredBranch = requireNotBlank(branch, "branch");
    try {
      ObjectId head = repository.resolve(requiredBranch);
      if (head == null) {
        head = repository.resolve(Constants.R_HEADS + requiredBranch);
      }
      if (head == null) {
        throw new NoSuchElementException("Unknown workflow branch: " + requiredBranch);
      }
      return commitIndexer.indexCommitsFrom(repository, head, limit);
    } catch (IOException failure) {
      throw new IllegalStateException(
          "Could not rebuild workflow history search for branch " + requiredBranch, failure);
    }
  }

  void indexBestEffort(CommitId commitId) {
    try {
      commitIndexer.indexCommit(repository, ObjectId.fromString(commitId.value()));
    } catch (IOException | RuntimeException failure) {
      LOGGER.log(
          Level.WARNING,
          "Workflow commit "
              + commitId.value()
              + " remains authoritative but its generic search projection is stale",
          failure);
    }
  }

  private static WorkflowHistoryTextResult toResult(GitCommitIndex hit) {
    List<String> changedPaths =
        hit.getChangedPaths() == null || hit.getChangedPaths().isBlank()
            ? List.of()
            : Arrays.stream(hit.getChangedPaths().split("\\R"))
                .filter(value -> !value.isBlank())
                .toList();
    return new WorkflowHistoryTextResult(
        new CommitId(hit.getObjectId()),
        hit.getShortMessage(),
        hit.getAuthorName(),
        hit.getAuthorEmail(),
        hit.getCommitTime(),
        changedPaths);
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
