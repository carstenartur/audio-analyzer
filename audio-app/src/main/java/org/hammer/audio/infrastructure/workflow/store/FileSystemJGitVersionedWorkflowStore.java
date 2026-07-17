package org.hammer.audio.infrastructure.workflow.store;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitInfo;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.RefUpdateResult;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;
import org.hammer.audio.workflow.store.WorkflowSnapshot;

/**
 * Explicitly filesystem-backed JGit workflow store for tests and local demonstrations.
 *
 * <p>This class is not wired into the production workbench. Durable production persistence is
 * provided by {@link HibernateJGitVersionedWorkflowStore}.
 */
public final class FileSystemJGitVersionedWorkflowStore
    implements VersionedWorkflowStore, Closeable {

  private final Repository repository;
  private final JGitRepositoryVersionedWorkflowStore delegate;

  /** Opens or creates a bare filesystem repository. */
  public FileSystemJGitVersionedWorkflowStore(Path gitDirectory) {
    this.repository = openOrCreateRepository(gitDirectory);
    this.delegate = new JGitRepositoryVersionedWorkflowStore(repository);
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
    repository.close();
  }

  private static Repository openOrCreateRepository(Path gitDirectory) {
    Objects.requireNonNull(gitDirectory, "gitDirectory");
    try {
      Files.createDirectories(gitDirectory);
      Repository repository =
          new FileRepositoryBuilder().setGitDir(gitDirectory.toFile()).setBare().build();
      if (!repository.getObjectDatabase().exists()) {
        repository.create(true);
      }
      return repository;
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Failed to initialize filesystem repository at " + gitDirectory, exception);
    }
  }
}
