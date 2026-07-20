package org.hammer.audio.workflow.store;

import java.util.List;
import java.util.Objects;

/**
 * Persistence facade for versioned workflow checkpoints.
 *
 * <p>This interface is the only acceptable boundary between Audio Analyzer workflow services and
 * any concrete storage back end (e.g. JGit/Hibernate). All JGit and Hibernate types must stay
 * behind implementations of this interface and must never be exposed to callers.
 *
 * <p><b>Allowed callers</b>: application services. Must not be called directly from editor
 * adapters, UI components or workflow domain objects.
 *
 * <p><b>Dependency rule</b>: implementations may depend on JGit and Hibernate. Callers must depend
 * only on this interface and the value types in this package.
 */
public interface VersionedWorkflowStore {

  /**
   * Commits a workflow snapshot on the given branch and returns the new commit identifier.
   *
   * @param branch branch name (non-blank)
   * @param snapshot canonical DSL snapshot to persist
   * @param metadata author, message and timestamp for this commit
   * @return new commit identifier
   */
  CommitId commit(String branch, WorkflowSnapshot snapshot, CommitMetadata metadata);

  /**
   * Conditionally commits a workflow snapshot only while the branch still has the expected HEAD.
   *
   * <p>Implementations backed by an atomic ref store should override this method so the comparison
   * and ref publication share one optimistic-concurrency boundary. The default implementation keeps
   * test and lightweight stores compatible, but cannot eliminate a race between the history check
   * and {@link #commit(String, WorkflowSnapshot, CommitMetadata)}.
   *
   * @param branch branch name
   * @param expectedHead commit the caller expects at HEAD, or {@code null} for a new branch
   * @param snapshot canonical DSL snapshot to persist
   * @param metadata author, message and timestamp
   * @return new commit identifier
   * @throws StaleWorkflowHeadException when the observed HEAD differs
   */
  default CommitId commitIfHead(
      String branch,
      CommitId expectedHead,
      WorkflowSnapshot snapshot,
      CommitMetadata metadata) {
    List<CommitInfo> current = history(branch, 1);
    CommitId actualHead = current.isEmpty() ? null : current.getFirst().commitId();
    if (!Objects.equals(expectedHead, actualHead)) {
      throw new StaleWorkflowHeadException(branch, expectedHead, actualHead);
    }
    return commit(branch, snapshot, metadata);
  }

  /**
   * Loads the workflow snapshot at the given commit.
   *
   * @param commitId commit identifier returned by a previous {@link #commit} call
   * @return snapshot at that commit
   * @throws java.util.NoSuchElementException if the commit does not exist
   */
  WorkflowSnapshot loadAtCommit(CommitId commitId);

  /**
   * Loads the workflow snapshot at the HEAD of the given branch.
   *
   * @param branch branch name
   * @return snapshot at HEAD
   * @throws java.util.NoSuchElementException if the branch does not exist
   */
  WorkflowSnapshot loadHead(String branch);

  /**
   * Performs an optimistic-concurrency ref update on the given ref.
   *
   * <p>The update succeeds only if the ref currently points to {@code expectedOldCommit}. If
   * another writer has advanced the ref in the meantime, {@link RefUpdateResult#STALE} is returned
   * and the caller must retry.
   *
   * @param refName ref name to update
   * @param expectedOldCommit the commit the caller believes to be current HEAD
   * @param newCommit the commit to set as the new HEAD
   * @return update result
   */
  RefUpdateResult updateRef(String refName, CommitId expectedOldCommit, CommitId newCommit);

  /**
   * Returns a reverse-chronological list of recent commits on the given ref.
   *
   * @param refName ref or branch name
   * @param limit maximum number of entries to return
   * @return commit history, most recent first
   */
  List<CommitInfo> history(String refName, int limit);
}
