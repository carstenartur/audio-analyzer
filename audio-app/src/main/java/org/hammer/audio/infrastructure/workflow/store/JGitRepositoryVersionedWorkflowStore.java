package org.hammer.audio.infrastructure.workflow.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.RawParseUtils;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitInfo;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.RefUpdateResult;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;
import org.hammer.audio.workflow.store.WorkflowSnapshot;

/** Workflow checkpoint implementation over an already opened public JGit repository API. */
final class JGitRepositoryVersionedWorkflowStore implements VersionedWorkflowStore {

  private static final String WORKFLOW_DSL_PATH = "workflow.dsl";
  private static final String WORKFLOW_ID_PATH = "workflow.id";
  private static final ObjectId ZERO_ID = ObjectId.zeroId();

  private final Repository repository;

  JGitRepositoryVersionedWorkflowStore(Repository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  @Override
  public CommitId commit(String branch, WorkflowSnapshot snapshot, CommitMetadata metadata) {
    if (branch == null || branch.isBlank()) {
      throw new IllegalArgumentException("branch must not be blank");
    }
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(metadata, "metadata");
    String refName = toRefName(branch);

    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blobDsl =
          inserter.insert(Constants.OBJ_BLOB, snapshot.dslText().getBytes(StandardCharsets.UTF_8));
      ObjectId blobWorkflowId =
          inserter.insert(
              Constants.OBJ_BLOB, snapshot.workflowId().getBytes(StandardCharsets.UTF_8));

      TreeFormatter formatter = new TreeFormatter();
      formatter.append(WORKFLOW_DSL_PATH, FileMode.REGULAR_FILE, blobDsl);
      formatter.append(WORKFLOW_ID_PATH, FileMode.REGULAR_FILE, blobWorkflowId);
      ObjectId treeId = inserter.insert(formatter);

      ObjectId parent = repository.resolve(refName);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(treeId);
      if (parent != null) {
        commit.setParentIds(parent);
      }
      PersonIdent ident = toPersonIdent(metadata);
      commit.setAuthor(ident);
      commit.setCommitter(ident);
      commit.setMessage(metadata.message());

      ObjectId commitId = inserter.insert(commit);
      inserter.flush();

      RefUpdate update = repository.updateRef(refName);
      update.setExpectedOldObjectId(parent == null ? ZERO_ID : parent);
      update.setNewObjectId(commitId);
      update.setRefLogMessage("workflow checkpoint", false);
      RefUpdate.Result result = update.update();
      if (!isSuccessfulRefUpdate(result)) {
        throw new IllegalStateException("Failed to update ref '" + refName + "': " + result);
      }
      return new CommitId(commitId.name());
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to commit workflow snapshot", ex);
    }
  }

  @Override
  public WorkflowSnapshot loadAtCommit(CommitId commitId) {
    Objects.requireNonNull(commitId, "commitId");
    ObjectId objectId = parseObjectId(commitId);
    try (RevWalk walk = new RevWalk(repository)) {
      RevCommit commit = walk.parseCommit(objectId);
      return readSnapshot(commit);
    } catch (IOException ex) {
      NoSuchElementException exception =
          new NoSuchElementException("Commit not found: " + commitId);
      exception.initCause(ex);
      throw exception;
    }
  }

  @Override
  public WorkflowSnapshot loadHead(String branch) {
    if (branch == null || branch.isBlank()) {
      throw new IllegalArgumentException("branch must not be blank");
    }
    String refName = toRefName(branch);
    try {
      ObjectId head = repository.resolve(refName);
      if (head == null) {
        throw new NoSuchElementException("Branch not found: " + branch);
      }
      return loadAtCommit(new CommitId(head.name()));
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to resolve branch: " + branch, ex);
    }
  }

  @Override
  public RefUpdateResult updateRef(String refName, CommitId expectedOldCommit, CommitId newCommit) {
    if (refName == null || refName.isBlank()) {
      throw new IllegalArgumentException("refName must not be blank");
    }
    Objects.requireNonNull(newCommit, "newCommit");
    String normalizedRef = toRefName(refName);
    ObjectId newObjectId = parseObjectId(newCommit);
    verifyCommitExists(newObjectId, newCommit);
    try {
      RefUpdate update = repository.updateRef(normalizedRef);
      update.setExpectedOldObjectId(
          expectedOldCommit == null ? ZERO_ID : parseObjectId(expectedOldCommit));
      update.setNewObjectId(newObjectId);
      update.setForceUpdate(true);
      update.setRefLogMessage("workflow ref update", false);
      RefUpdate.Result result = update.update();
      return mapRefUpdateResult(result);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to update ref: " + refName, ex);
    }
  }

  private void verifyCommitExists(ObjectId objectId, CommitId commitId) {
    try (RevWalk walk = new RevWalk(repository)) {
      walk.parseCommit(objectId);
    } catch (IOException ex) {
      NoSuchElementException exception =
          new NoSuchElementException("Commit not found: " + commitId.value());
      exception.initCause(ex);
      throw exception;
    }
  }

  @Override
  public List<CommitInfo> history(String refName, int limit) {
    if (refName == null || refName.isBlank()) {
      throw new IllegalArgumentException("refName must not be blank");
    }
    if (limit < 0) {
      throw new IllegalArgumentException("limit must be >= 0");
    }
    if (limit == 0) {
      return List.of();
    }
    String normalizedRef = toRefName(refName);
    try {
      ObjectId head = repository.resolve(normalizedRef);
      if (head == null) {
        return List.of();
      }
      List<CommitInfo> history = new ArrayList<>();
      try (RevWalk walk = new RevWalk(repository)) {
        walk.markStart(walk.parseCommit(head));
        for (RevCommit commit : walk) {
          history.add(
              new CommitInfo(
                  new CommitId(commit.getId().name()),
                  commitMetadataFrom(commit),
                  readWorkflowId(commit)));
          if (history.size() >= limit) {
            break;
          }
        }
      }
      return List.copyOf(history);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to load history for ref: " + refName, ex);
    }
  }

  private static String toRefName(String branchOrRef) {
    if (branchOrRef.startsWith("refs/")) {
      return branchOrRef;
    }
    return "refs/heads/" + branchOrRef;
  }

  private static PersonIdent toPersonIdent(CommitMetadata metadata) {
    String sanitizedAuthor = metadata.author().trim().replaceAll("[\\r\\n]", " ");
    String emailLocalPart = sanitizedAuthor.replaceAll("[^A-Za-z0-9._-]", "-");
    if (emailLocalPart.isBlank()) {
      emailLocalPart = "workflow-author";
    }
    Instant instant = metadata.timestamp();
    return new PersonIdent(
        sanitizedAuthor, emailLocalPart + "@audio-analyzer.invalid", instant, ZoneOffset.UTC);
  }

  private static boolean isSuccessfulRefUpdate(RefUpdate.Result result) {
    return result == RefUpdate.Result.NEW
        || result == RefUpdate.Result.FAST_FORWARD
        || result == RefUpdate.Result.FORCED
        || result == RefUpdate.Result.NO_CHANGE;
  }

  private static RefUpdateResult mapRefUpdateResult(RefUpdate.Result result) {
    if (isSuccessfulRefUpdate(result)) {
      return RefUpdateResult.SUCCESS;
    }
    return RefUpdateResult.STALE;
  }

  private static ObjectId parseObjectId(CommitId commitId) {
    try {
      return ObjectId.fromString(commitId.value());
    } catch (IllegalArgumentException ex) {
      NoSuchElementException exception =
          new NoSuchElementException("Invalid commit id: " + commitId.value());
      exception.initCause(ex);
      throw exception;
    }
  }

  private WorkflowSnapshot readSnapshot(RevCommit commit) throws IOException {
    String workflowId = readFile(commit, WORKFLOW_ID_PATH);
    String dslText = readFile(commit, WORKFLOW_DSL_PATH);
    return new WorkflowSnapshot(workflowId, dslText);
  }

  private String readWorkflowId(RevCommit commit) throws IOException {
    return readFile(commit, WORKFLOW_ID_PATH);
  }

  private String readFile(RevCommit commit, String filePath) throws IOException {
    try (TreeWalk walk = TreeWalk.forPath(repository, filePath, commit.getTree())) {
      if (walk == null) {
        throw new NoSuchElementException(
            "File '" + filePath + "' not found in commit " + commit.getId().name());
      }
      return RawParseUtils.decode(repository.open(walk.getObjectId(0)).getBytes());
    }
  }

  private static CommitMetadata commitMetadataFrom(RevCommit commit) {
    PersonIdent author = commit.getAuthorIdent();
    String authorName = author == null ? "unknown" : author.getName();
    Instant timestamp = author == null ? Instant.EPOCH : author.getWhenAsInstant();
    return new CommitMetadata(authorName, commit.getFullMessage().trim(), timestamp);
  }
}
