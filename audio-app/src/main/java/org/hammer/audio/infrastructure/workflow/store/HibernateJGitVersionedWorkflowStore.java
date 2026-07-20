package org.hammer.audio.infrastructure.workflow.store;

import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.HibernateGitStorage;
import io.github.carstenartur.jgit.storage.hibernate.HibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hammer.audio.workflow.history.IndexedWorkflowCombinedHistorySearch;
import org.hammer.audio.workflow.history.IndexedWorkflowHistorySearch;
import org.hammer.audio.workflow.history.IndexedWorkflowSemanticHistorySearch;
import org.hammer.audio.workflow.history.WorkflowCombinedHistoryQuery;
import org.hammer.audio.workflow.history.WorkflowCombinedHistoryResult;
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
        IndexedWorkflowCombinedHistorySearch,
        AutoCloseable {

  private final HibernateGitStorage storage;
  private final JGitRepositoryVersionedWorkflowStore delegate;
  private final GenericWorkflowHistoryProjection genericHistoryProjection;
  private final WorkflowSemanticHistoryProjection semanticHistoryProjection;

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
    this.genericHistoryProjection = null;
    this.semanticHistoryProjection = null;
  }

  private HibernateJGitVersionedWorkflowStore(
      HibernateGitStorage storage, SessionFactory sessionFactory, String repositoryName) {
    this.storage = Objects.requireNonNull(storage, "storage");
    this.delegate = new JGitRepositoryVersionedWorkflowStore(storage.repository());
    String requiredRepositoryName = requireNotBlank(repositoryName, "repositoryName");
    SessionFactory requiredSessionFactory =
        Objects.requireNonNull(sessionFactory, "sessionFactory");
    this.genericHistoryProjection =
        new GenericWorkflowHistoryProjection(
            storage.repository(), requiredSessionFactory, requiredRepositoryName);
    this.semanticHistoryProjection =
        new WorkflowSemanticHistoryProjection(
            delegate, requiredSessionFactory, requiredRepositoryName);
  }

  @Override
  public CommitId commit(String branch, WorkflowSnapshot snapshot, CommitMetadata metadata) {
    CommitId commitId = delegate.commit(branch, snapshot, metadata);
    indexBestEffort(branch, commitId, snapshot);
    return commitId;
  }

  @Override
  public CommitId commitIfHead(
      String branch, CommitId expectedHead, WorkflowSnapshot snapshot, CommitMetadata metadata) {
    CommitId commitId = delegate.commitIfHead(branch, expectedHead, snapshot, metadata);
    indexBestEffort(branch, commitId, snapshot);
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
    if (result == RefUpdateResult.SUCCESS && semanticHistoryProjection != null) {
      semanticHistoryProjection.rebuildBestEffort(refName);
    }
    return result;
  }

  @Override
  public List<CommitInfo> history(String refName, int limit) {
    return delegate.history(refName, limit);
  }

  @Override
  public List<WorkflowHistoryTextResult> search(WorkflowHistoryTextQuery query) {
    requireSearchEnabled();
    return genericHistoryProjection.search(query);
  }

  @Override
  public List<WorkflowSemanticHistoryResult> searchSemantic(WorkflowSemanticHistoryQuery query) {
    requireSemanticSearchEnabled();
    return semanticHistoryProjection.search(query);
  }

  @Override
  public List<WorkflowCombinedHistoryResult> searchCombined(WorkflowCombinedHistoryQuery query) {
    Objects.requireNonNull(query, "query");
    requireSearchEnabled();
    requireSemanticSearchEnabled();
    List<CommitId> candidates =
        semanticHistoryProjection.candidateCommitIds(query.semanticFilter());
    List<WorkflowHistoryTextResult> commits =
        genericHistoryProjection.searchWithinCandidates(query.genericQuery(), candidates);
    Map<String, WorkflowSemanticHistoryResult> evidence =
        semanticHistoryProjection.evidence(
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

  @Override
  public int rebuild(String branch, int limit) {
    requireSearchEnabled();
    requireSemanticSearchEnabled();
    if (limit == 0) {
      return 0;
    }
    int newlyIndexed = genericHistoryProjection.rebuild(branch, limit);
    semanticHistoryProjection.rebuild(branch, limit);
    return newlyIndexed;
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

  private void indexBestEffort(
      String branch, CommitId commitId, WorkflowSnapshot authoritativeSnapshot) {
    if (genericHistoryProjection != null) {
      genericHistoryProjection.indexBestEffort(commitId);
    }
    if (semanticHistoryProjection != null) {
      semanticHistoryProjection.indexBestEffort(branch, commitId, authoritativeSnapshot);
    }
  }

  private void requireSearchEnabled() {
    if (genericHistoryProjection == null) {
      throw new IllegalStateException(
          "Indexed workflow history search requires the application-managed SessionFactory");
    }
  }

  private void requireSemanticSearchEnabled() {
    if (semanticHistoryProjection == null) {
      throw new IllegalStateException(
          "Semantic workflow history search requires the application-managed SessionFactory");
    }
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
