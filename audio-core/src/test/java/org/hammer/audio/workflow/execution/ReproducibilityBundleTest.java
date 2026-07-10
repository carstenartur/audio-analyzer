package org.hammer.audio.workflow.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitInfo;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.junit.jupiter.api.Test;

class ReproducibilityBundleTest {

  private static final Instant STARTED = Instant.parse("2024-01-01T10:00:00Z");
  private static final Instant COMPLETED = Instant.parse("2024-01-01T10:05:00Z");
  private static final Instant SNAP_TIME = Instant.parse("2024-01-01T09:59:00Z");

  private static ExecutionSnapshot minimalSnapshot() {
    return new ExecutionSnapshot("snap.1", "workflow.test", List.of(), List.of(), null, SNAP_TIME);
  }

  private static ExecutionResult completedResult() {
    return new ExecutionResult("exec.1", "plan.1", Map.of(), STARTED, COMPLETED);
  }

  @Test
  void bundleWithProvenanceHasAllFields() {
    CommitId commitId = new CommitId("commit-abc");
    CommitInfo commitInfo =
        new CommitInfo(
            commitId, new CommitMetadata("alice", "Save checkpoint", SNAP_TIME), "workflow.test");

    ReproducibilityBundle bundle =
        new ReproducibilityBundle(minimalSnapshot(), completedResult(), commitId, commitInfo);

    assertEquals("snap.1", bundle.snapshot().snapshotId());
    assertEquals("workflow.test", bundle.snapshot().workflowId());
    assertEquals("plan.1", bundle.result().planId());
    assertEquals(commitId, bundle.commitId());
    assertEquals(commitInfo, bundle.commitInfo());
    assertTrue(bundle.hasStoredProvenance());
  }

  @Test
  void bundleWithoutProvenanceHasNullCommitFields() {
    ReproducibilityBundle bundle =
        new ReproducibilityBundle(minimalSnapshot(), completedResult(), null, null);

    assertNull(bundle.commitId());
    assertNull(bundle.commitInfo());
    assertFalse(bundle.hasStoredProvenance());
  }

  @Test
  void bundleWithOnlyCommitIdHasNoStoredProvenance() {
    CommitId commitId = new CommitId("commit-xyz");

    ReproducibilityBundle bundle =
        new ReproducibilityBundle(minimalSnapshot(), completedResult(), commitId, null);

    assertFalse(bundle.hasStoredProvenance());
  }

  @Test
  void rejectsCommitInfoWithNullCommitId() {
    CommitId commitId = new CommitId("commit-abc");
    CommitInfo commitInfo =
        new CommitInfo(
            commitId, new CommitMetadata("alice", "Save checkpoint", SNAP_TIME), "workflow.test");

    assertThrows(
        IllegalArgumentException.class,
        () -> new ReproducibilityBundle(minimalSnapshot(), completedResult(), null, commitInfo));
  }

  @Test
  void rejectsCommitInfoWithMismatchedCommitId() {
    CommitId commitId = new CommitId("commit-abc");
    CommitId differentId = new CommitId("commit-xyz");
    CommitInfo commitInfo =
        new CommitInfo(
            commitId, new CommitMetadata("alice", "Save checkpoint", SNAP_TIME), "workflow.test");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReproducibilityBundle(
                minimalSnapshot(), completedResult(), differentId, commitInfo));
  }

  @Test
  void rejectsNullSnapshot() {
    assertThrows(
        NullPointerException.class,
        () -> new ReproducibilityBundle(null, completedResult(), null, null));
  }

  @Test
  void rejectsNullResult() {
    assertThrows(
        NullPointerException.class,
        () -> new ReproducibilityBundle(minimalSnapshot(), null, null, null));
  }

  @Test
  void bundlePreservesSnapshotAndResultIntact() {
    ExecutionSnapshot snapshot =
        new ExecutionSnapshot(
            "snap.preserved",
            "workflow.preserved",
            List.of(
                new Node("node.a", "typeA", "A", List.of(), List.of()),
                new Node("node.b", "typeB", "B", List.of(), List.of())),
            List.of(new Edge("edge.1", "node.a", "out", "node.b", "in")),
            null,
            SNAP_TIME);
    ExecutionResult result =
        new ExecutionResult(
            "exec.preserved",
            "plan.preserved",
            Map.of("node.a", ExecutionStatus.COMPLETED, "node.b", ExecutionStatus.COMPLETED),
            STARTED,
            COMPLETED);

    ReproducibilityBundle bundle = new ReproducibilityBundle(snapshot, result, null, null);

    assertEquals(snapshot, bundle.snapshot());
    assertEquals(result, bundle.result());
    assertEquals(2, bundle.snapshot().nodes().size());
    assertEquals(ExecutionStatus.COMPLETED, bundle.result().overallStatus());
  }
}
