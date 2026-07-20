package org.hammer.audio.infrastructure.workflow.store;

import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.HibernateGitStorage;
import io.github.carstenartur.jgit.storage.hibernate.HibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.hammer.audio.infrastructure.workflow.search.WorkflowSemanticIndexService;
import org.hammer.audio.infrastructure.workflow.search.WorkflowSemanticProjectionEntry;
import org.hammer.audio.workflow.history.IndexedWorkflowHistorySearch;
import org.hammer.audio.workflow.history.IndexedWorkflowSemanticHistorySearch;
import org.hammer.audio.workflow.history.WorkflowHistoryTextQuery;
import org.hammer.audio.workflow.history.WorkflowHistoryTextResult;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryQuery;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryResult;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitInfo;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.RefUpdateResult;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;
import org.hammer.audio.workflow.store.WorkflowSnapshot;
import org.hibernate.SessionFactory;

/** Production workflow store and indexed search adapter over one shared Hibernate context. */
public final class HibernateJGitVersionedWorkflowStore
    implements VersionedWorkflowStore,
        IndexedWorkflowHistorySearch,
        IndexedWorkflowSemanticHistorySearch,
        AutoCloseable {

  private static final Logger LOGGER =
      Logger.getLogger(HibernateJGitVersionedWorkflowStore.class.getName());

  private final HibernateGitStorage storage;
  private final JGitRepositoryVersionedWorkflowStore delegate;
  private final String repositoryName;
  private final CommitIndexer commitIndexer;
  private final GitHistorySearchService searchService;
  private final WorkflowSemanticIndexService semanticIndexService;

  /** Opens a searchable logical repository using the application-managed SessionFactory. */
  public HibernateJGitVersionedWorkflowStore(SessionFactory sessionFactory, String repositoryName) {
    this(openStorage(sessionFactory, repositoryName), sessionFactory, repositoryName);
  }

  /** Opens a storage-only adapter through a supplied shared repository factory. */
  public HibernateJGitVersionedWorkflowStore(
      HibernateRepositoryFactory repositoryFactory, RepositoryName repositoryName) {
    this(
        Objects.requireNonNull(repositoryFactory, "repositoryFactory")
            .open(Objects.requireNonNull(repositoryName, "repositoryName")));
  }

  HibernateJGitVersionedWorkflowStore(HibernateGitStorage storage) {
    this.storage = Objects.requireNonNull(storage, "storage");
    this.delegate = new JGitRepositoryVersionedWorkflowStore(storage.repository());
    this.repositoryName = null;
    this.commitIndexer = null;
    this.searchService = null;
    this.semanticIndexService = null;
  }

  private HibernateJGitVersionedWorkflowStore(
      HibernateGitStorage storage, SessionFactory sessionFactory, String repositoryName) {
    this.storage = Objects.requireNonNull(storage, "storage");
    this.delegate = new JGitRepositoryVersionedWorkflowStore(storage.repository());
    this.repositoryName = requireNotBlank(repositoryName, "repositoryName");
    SessionFactory requiredSessionFactory =
        Objects.requireNonNull(sessionFactory, "sessionFactory");
    this.commitIndexer = new CommitIndexer(requiredSessionFactory, this.repositoryName);
    this.searchService = new GitHistorySearchService(requiredSessionFactory);
    this.semanticIndexService =
        new WorkflowSemanticIndexService(requiredSessionFactory, this.repositoryName);
  }

  @Override
  public CommitId commit(String branch, WorkflowSnapshot snapshot, CommitMetadata metadata) {
    CommitId commitId = delegate.commit(branch, snapshot, metadata);
    indexBestEffort(commitId);
    indexSemanticBestEffort(branch, commitId, snapshot);
    return commitId;
  }

  @Override
  public WorkflowSnapshot loadAtCommit(CommitId commitId) {
    return delegate.loadAtCommit(commitId);
  }

  @Override
  public WorkflowSnapshot loadHead(String branch) {
    return delegate.loadHead(branch);
  }

  @Override
  public RefUpdateResult updateRef(String refName, CommitId expectedOldCommit, CommitId newCommit) {
    RefUpdateResult result = delegate.updateRef(refName, expectedOldCommit, newCommit);
    if (result == RefUpdateResult.SUCCESS) {
      rebuildSemanticBestEffort(refName);
    }
    return result;
  }

  @Override
  public List<CommitInfo> history(String refName, int limit) {
    return delegate.history(refName, limit);
  }

  @Override
  public List<WorkflowHistoryTextResult> search(WorkflowHistoryTextQuery query) {
    Objects.requireNonNull(query, "query");
    requireSearchEnabled();
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
        .map(HibernateJGitVersionedWorkflowStore::toResult)
        .toList();
  }

  @Override
  public List<WorkflowSemanticHistoryResult> searchSemantic(WorkflowSemanticHistoryQuery query) {
    requireSemanticSearchEnabled();
    return semanticIndexService.search(Objects.requireNonNull(query, "query"));
  }

  @Override
  public int rebuild(String branch, int limit) {
    requireSearchEnabled();
    requireSemanticSearchEnabled();
    String requiredBranch = requireNotBlank(branch, "branch");
    if (limit == 0) {
      return 0;
    }
    try {
      ObjectId head = storage.repository().resolve(requiredBranch);
      if (head == null) {
        head = storage.repository().resolve(Constants.R_HEADS + requiredBranch);
      }
      if (head == null) {
        throw new NoSuchElementException("Unknown workflow branch: " + requiredBranch);
      }
      int newlyIndexed = commitIndexer.indexCommitsFrom(storage.repository(), head, limit);
      rebuildSemanticBranch(requiredBranch, limit);
      return newlyIndexed;
    } catch (IOException failure) {
      throw new IllegalStateException(
          "Could not rebuild workflow history search for branch " + requiredBranch, failure);
    }
  }

  @Override
  public void close() {
    storage.close();
  }

  private static HibernateGitStorage openStorage(
      SessionFactory sessionFactory, String repositoryName) {
    return new DefaultHibernateRepositoryFactory(
            Objects.requireNonNull(sessionFactory, "sessionFactory"))
        .open(new RepositoryName(repositoryName));
  }

  private void indexBestEffort(CommitId commitId) {
    if (commitIndexer == null) {
      return;
    }
    try {
      commitIndexer.indexCommit(storage.repository(), ObjectId.fromString(commitId.value()));
    } catch (IOException | RuntimeException failure) {
      LOGGER.log(
          Level.WARNING,
          "Workflow commit "
              + commitId.value()
              + " remains authoritative but its generic search projection is stale",
          failure);
    }
  }

  private void indexSemanticBestEffort(
      String branch, CommitId commitId, WorkflowSnapshot authoritativeSnapshot) {
    if (semanticIndexService == null) {
      return;
    }
    try {
      semanticIndexService.indexCheckpoint(branch, commitId, authoritativeSnapshot);
    } catch (RuntimeException failure) {
      LOGGER.log(
          Level.WARNING,
          "Workflow commit "
              + commitId.value()
              + " remains authoritative but its semantic search projection is stale",
          failure);
    }
  }

  private void rebuildSemanticBestEffort(String branch) {
    if (semanticIndexService == null) {
      return;
    }
    try {
      rebuildSemanticBranch(branch, -1);
    } catch (RuntimeException failure) {
      LOGGER.log(
          Level.WARNING,
          "Workflow branch "
              + branch
              + " remains authoritative but its semantic search projection is stale",
          failure);
    }
  }

  private void rebuildSemanticBranch(String branch, int limit) {
    int historyLimit = limit < 0 ? Integer.MAX_VALUE : limit;
    List<CommitInfo> commits = delegate.history(branch, historyLimit);
    List<WorkflowSemanticProjectionEntry> entries = new ArrayList<>(commits.size());
    for (int position = 0; position < commits.size(); position++) {
      CommitId commitId = commits.get(position).commitId();
      entries.add(
          new WorkflowSemanticProjectionEntry(
              commitId, position, delegate.loadAtCommit(commitId)));
    }
    semanticIndexService.replaceBranch(branch, entries);
  }

  private void requireSearchEnabled() {
    if (repositoryName == null || commitIndexer == null || searchService == null) {
      throw new IllegalStateException(
          "Indexed workflow history search requires the application-managed SessionFactory");
    }
  }

  private void requireSemanticSearchEnabled() {
    if (repositoryName == null || semanticIndexService == null) {
      throw new IllegalStateException(
          "Semantic workflow history search requires the application-managed SessionFactory");
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
