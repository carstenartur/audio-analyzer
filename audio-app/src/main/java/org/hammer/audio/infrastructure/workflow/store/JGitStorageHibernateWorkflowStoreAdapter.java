package org.hammer.audio.infrastructure.workflow.store;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitInfo;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.RefUpdateResult;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;
import org.hammer.audio.workflow.store.WorkflowSnapshot;

/**
 * Production {@link VersionedWorkflowStore} adapter backed by {@link JGitVersionedWorkflowStore}.
 *
 * <p>This adapter exposes the JGit-backed store through the {@link VersionedWorkflowStore}
 * interface, keeping all storage details out of workflow-facing packages.
 */
public final class JGitStorageHibernateWorkflowStoreAdapter
    implements VersionedWorkflowStore, Closeable {

  private final JGitVersionedWorkflowStore delegate;

  public JGitStorageHibernateWorkflowStoreAdapter(Path repositoryPath) {
    this.delegate = new JGitVersionedWorkflowStore(repositoryPath);
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
  public void close() throws IOException {
    delegate.close();
  }
}
