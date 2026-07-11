package org.hammer.audio.workflow.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

abstract class VersionedWorkflowStoreContractTest {

  protected abstract VersionedWorkflowStore createStore();

  @Test
  void commitAndLoadHeadRoundTrip() {
    VersionedWorkflowStore store = createStore();
    WorkflowSnapshot snapshot = snapshot("workflow.alpha", "workflow workflow.alpha { node n1 }");

    CommitId commitId = store.commit("main", snapshot, metadata("first", 1));

    assertEquals(snapshot, store.loadAtCommit(commitId));
    assertEquals(snapshot, store.loadHead("main"));
  }

  @Test
  void loadAtCommitReturnsHistoricalSnapshot() {
    VersionedWorkflowStore store = createStore();
    WorkflowSnapshot first = snapshot("workflow.alpha", "workflow workflow.alpha { node n1 }");
    WorkflowSnapshot second = snapshot("workflow.alpha", "workflow workflow.alpha { node n2 }");

    CommitId firstCommit = store.commit("main", first, metadata("first", 1));
    store.commit("main", second, metadata("second", 2));

    assertEquals(first, store.loadAtCommit(firstCommit));
  }

  @Test
  void historyIsReverseChronologicalAndLimited() {
    VersionedWorkflowStore store = createStore();
    CommitId first =
        store.commit("main", snapshot("workflow.alpha", "dsl-1"), metadata("first", 1));
    CommitId second =
        store.commit("main", snapshot("workflow.alpha", "dsl-2"), metadata("second", 2));
    CommitId third =
        store.commit("main", snapshot("workflow.alpha", "dsl-3"), metadata("third", 3));

    List<CommitInfo> history = store.history("main", 2);

    assertEquals(2, history.size());
    assertEquals(third, history.get(0).commitId());
    assertEquals(second, history.get(1).commitId());
    assertEquals(
        List.of(third, second, first),
        store.history("main", 10).stream().map(CommitInfo::commitId).toList());
  }

  @Test
  void updateRefSucceedsWhenExpectedHeadMatches() {
    VersionedWorkflowStore store = createStore();
    CommitId base =
        store.commit("main", snapshot("workflow.alpha", "dsl-base"), metadata("base", 1));
    CommitId candidate =
        store.commit(
            "candidate", snapshot("workflow.alpha", "dsl-candidate"), metadata("candidate", 2));

    RefUpdateResult result = store.updateRef("main", base, candidate);

    assertEquals(RefUpdateResult.SUCCESS, result);
    assertEquals(snapshot("workflow.alpha", "dsl-candidate"), store.loadHead("main"));
  }

  @Test
  void updateRefReturnsStaleWhenExpectedHeadDiffers() {
    VersionedWorkflowStore store = createStore();
    CommitId base =
        store.commit("main", snapshot("workflow.alpha", "dsl-base"), metadata("base", 1));
    CommitId candidate =
        store.commit(
            "candidate", snapshot("workflow.alpha", "dsl-candidate"), metadata("candidate", 2));
    store.commit("main", snapshot("workflow.alpha", "dsl-main-new"), metadata("main-new", 3));

    RefUpdateResult result = store.updateRef("main", base, candidate);

    assertEquals(RefUpdateResult.STALE, result);
  }

  @Test
  void updateRefCanCreateNewBranchFromExistingCommit() {
    VersionedWorkflowStore store = createStore();
    WorkflowSnapshot snapshot = snapshot("workflow.alpha", "dsl-feature");
    CommitId commitId = store.commit("seed", snapshot, metadata("seed", 1));

    RefUpdateResult result = store.updateRef("feature/new", null, commitId);

    assertEquals(RefUpdateResult.SUCCESS, result);
    assertEquals(snapshot, store.loadHead("feature/new"));
  }

  @Test
  void updateRefTreatsNoChangeAsSuccess() {
    VersionedWorkflowStore store = createStore();
    WorkflowSnapshot snapshot = snapshot("workflow.alpha", "dsl-same");
    CommitId commitId = store.commit("main", snapshot, metadata("base", 1));

    RefUpdateResult result = store.updateRef("main", commitId, commitId);

    assertEquals(RefUpdateResult.SUCCESS, result);
    assertEquals(snapshot, store.loadHead("main"));
  }

  @Test
  void updateRefUnknownCommitThrows() {
    VersionedWorkflowStore store = createStore();
    store.commit("main", snapshot("workflow.alpha", "dsl"), metadata("base", 1));

    assertThrows(
        NoSuchElementException.class,
        () -> store.updateRef("main", new CommitId("unknown"), new CommitId("unknown")));
  }

  private static WorkflowSnapshot snapshot(String workflowId, String dsl) {
    return new WorkflowSnapshot(workflowId, dsl);
  }

  private static CommitMetadata metadata(String message, long secondOffset) {
    return new CommitMetadata(
        "tester", message, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(secondOffset));
  }
}
