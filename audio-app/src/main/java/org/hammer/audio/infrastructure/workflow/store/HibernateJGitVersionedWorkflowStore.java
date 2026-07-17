package org.hammer.audio.infrastructure.workflow.store;

import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.HibernateGitStorage;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitInfo;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.RefUpdateResult;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;
import org.hammer.audio.workflow.store.WorkflowSnapshot;
import org.hibernate.SessionFactory;

/**
 * Production workflow checkpoint adapter backed by {@code jgit-storage-hibernate-core}.
 *
 * <p>Only the public storage facade and public JGit repository API are used here. DFS/Reftable
 * implementation details remain isolated inside the shared storage library.
 */
public final class HibernateJGitVersionedWorkflowStore
    implements VersionedWorkflowStore, AutoCloseable {

  private final HibernateGitStorage storage;
  private final JGitRepositoryVersionedWorkflowStore delegate;

  /** Opens the named database-backed repository from an application-managed SessionFactory. */
  public HibernateJGitVersionedWorkflowStore(
      SessionFactory sessionFactory, String repositoryName) {
    this(
        new DefaultHibernateRepositoryFactory(
                Objects.requireNonNull(sessionFactory, "sessionFactory"))
            .open(new RepositoryName(repositoryName)));
  }

  HibernateJGitVersionedWorkflowStore(HibernateGitStorage storage) {
    this.storage = Objects.requireNonNull(storage, "storage");
    this.delegate = new JGitRepositoryVersionedWorkflowStore(storage.repository());
  }

  @Override
  public CommitId commit(String branch, WorkflowSnapshot snapshot, CommitMetadata metadata) {
    return delegate.commit(branch, snapshot, metadata);
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
    return delegate.updateRef(refName, expectedOldCommit, newCommit);
  }

  @Override
  public List<CommitInfo> history(String refName, int limit) {
    return delegate.history(refName, limit);
  }

  @Override
  public void close() {
    storage.close();
  }
}
