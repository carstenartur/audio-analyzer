package org.hammer.audio.workflow.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * In-memory implementation of {@link VersionedWorkflowStore} for tests.
 *
 * <p>This class is intentionally in the {@code store} package so that tests can verify that
 * higher-layer code works correctly when backed only by the facade interface and value types, with
 * no JGit or Hibernate in the classpath.
 *
 * <p><b>Not thread-safe.</b> Suitable for single-threaded unit tests only.
 */
public final class InMemoryVersionedWorkflowStore implements VersionedWorkflowStore {

  private final Map<String, WorkflowSnapshot> commits = new LinkedHashMap<>();
  private final Map<String, CommitRecord> commitIndex = new LinkedHashMap<>();
  private final Map<String, List<CommitRecord>> refHistory = new LinkedHashMap<>();

  @Override
  public CommitId commit(String branch, WorkflowSnapshot snapshot, CommitMetadata metadata) {
    if (branch == null || branch.isBlank()) {
      throw new IllegalArgumentException("branch must not be blank");
    }
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(metadata, "metadata");
    String id = UUID.randomUUID().toString();
    CommitId commitId = new CommitId(id);
    commits.put(id, snapshot);
    CommitRecord record = new CommitRecord(commitId, metadata, snapshot.workflowId());
    commitIndex.put(id, record);
    refHistory
        .computeIfAbsent(branch, k -> new ArrayList<>())
        .add(record);
    return commitId;
  }

  @Override
  public WorkflowSnapshot loadAtCommit(CommitId commitId) {
    WorkflowSnapshot snapshot = commits.get(commitId.value());
    if (snapshot == null) {
      throw new NoSuchElementException("Commit not found: " + commitId);
    }
    return snapshot;
  }

  @Override
  public WorkflowSnapshot loadHead(String branch) {
    List<CommitRecord> records = refHistory.get(branch);
    if (records == null || records.isEmpty()) {
      throw new NoSuchElementException("Branch not found or empty: " + branch);
    }
    CommitId headId = records.get(records.size() - 1).commitId();
    return loadAtCommit(headId);
  }

  @Override
  public RefUpdateResult updateRef(String refName, CommitId expectedOldCommit, CommitId newCommit) {
    if (refName == null || refName.isBlank()) {
      throw new IllegalArgumentException("refName must not be blank");
    }
    Objects.requireNonNull(newCommit, "newCommit");
    CommitRecord newRecord = commitIndex.get(newCommit.value());
    if (newRecord == null) {
      throw new NoSuchElementException("Commit to set as new HEAD not found: " + newCommit);
    }
    List<CommitRecord> records = refHistory.get(refName);
    if (records == null || records.isEmpty()) {
      if (expectedOldCommit != null) {
        return RefUpdateResult.STALE;
      }
      refHistory.put(refName, new ArrayList<>(List.of(newRecord)));
      return RefUpdateResult.SUCCESS;
    }
    CommitId currentHead = records.get(records.size() - 1).commitId();
    if (currentHead.equals(newCommit)) {
      return RefUpdateResult.SUCCESS;
    }
    if (!currentHead.equals(expectedOldCommit)) {
      return RefUpdateResult.STALE;
    }
    records.add(newRecord);
    return RefUpdateResult.SUCCESS;
  }

  @Override
  public List<CommitInfo> history(String refName, int limit) {
    if (limit < 0) {
      throw new IllegalArgumentException("limit must be >= 0");
    }
    List<CommitRecord> records = refHistory.getOrDefault(refName, List.of());
    List<CommitRecord> reversed = new ArrayList<>(records);
    java.util.Collections.reverse(reversed);
    return reversed.stream()
        .limit(limit)
        .map(r -> new CommitInfo(r.commitId(), r.metadata(), r.workflowId()))
        .toList();
  }

  private record CommitRecord(CommitId commitId, CommitMetadata metadata, String workflowId) {}
}
