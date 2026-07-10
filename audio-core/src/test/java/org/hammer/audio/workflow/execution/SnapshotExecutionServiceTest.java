package org.hammer.audio.workflow.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import org.hammer.audio.workflow.DataTypes;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Port;
import org.hammer.audio.workflow.PortDirection;
import org.hammer.audio.workflow.PortMultiplicity;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.dsl.WorkflowDslParser;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitInfo;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.InMemoryVersionedWorkflowStore;
import org.hammer.audio.workflow.store.WorkflowSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SnapshotExecutionService}. */
class SnapshotExecutionServiceTest {

  private static final Instant SNAP_TIME = Instant.parse("2024-01-01T10:00:00Z");
  private static final Instant START_TIME = Instant.parse("2024-01-01T10:00:01Z");
  private static final Instant END_TIME = Instant.parse("2024-01-01T10:00:05Z");

  private static final WorkflowDslSerializer SERIALIZER = new WorkflowDslSerializer();
  private static final WorkflowDslParser PARSER = new WorkflowDslParser();

  private InMemoryVersionedWorkflowStore store;
  private SnapshotExecutionService service;

  @BeforeEach
  void setUp() {
    store = new InMemoryVersionedWorkflowStore();
    service = new SnapshotExecutionService(store, PARSER);
  }

  // -----------------------------------------------------------------------
  // snapshotAtCommit
  // -----------------------------------------------------------------------

  @Test
  void snapshotAtCommitReturnsImmutableFreezeOfStoredWorkflow() {
    Workflow workflow = buildLinearWorkflow("workflow.snap", "node.src", "node.sink");
    CommitId commitId = storeWorkflow(workflow);

    ExecutionSnapshot snapshot = service.snapshotAtCommit(commitId, "snap.1", SNAP_TIME);

    assertEquals("snap.1", snapshot.snapshotId());
    assertEquals("workflow.snap", snapshot.workflowId());
    assertEquals(2, snapshot.nodes().size());
    assertEquals(1, snapshot.edges().size());
    assertEquals(SNAP_TIME, snapshot.createdAt());
  }

  @Test
  void snapshotAtCommitNodeListIsUnmodifiable() {
    Workflow workflow = buildLinearWorkflow("workflow.immut", "node.a", "node.b");
    CommitId commitId = storeWorkflow(workflow);

    ExecutionSnapshot snapshot = service.snapshotAtCommit(commitId, "snap.immut", SNAP_TIME);

    assertThrows(UnsupportedOperationException.class, () -> snapshot.nodes().clear());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.edges().clear());
  }

  @Test
  void snapshotAtCommitThrowsWhenCommitNotFound() {
    CommitId missing = new CommitId("commit-does-not-exist");

    assertThrows(
        NoSuchElementException.class,
        () -> service.snapshotAtCommit(missing, "snap.missing", SNAP_TIME));
  }

  @Test
  void snapshotAtCommitRejectsNullCommitId() {
    assertThrows(
        NullPointerException.class, () -> service.snapshotAtCommit(null, "snap.1", SNAP_TIME));
  }

  @Test
  void snapshotAtCommitRejectsNullSnapshotId() {
    Workflow workflow = buildLinearWorkflow("workflow.nullsnap", "node.x", "node.y");
    CommitId commitId = storeWorkflow(workflow);

    assertThrows(
        NullPointerException.class, () -> service.snapshotAtCommit(commitId, null, SNAP_TIME));
  }

  // -----------------------------------------------------------------------
  // run
  // -----------------------------------------------------------------------

  @Test
  void runProducesCompletedResultForLinearWorkflow() {
    Workflow workflow = buildLinearWorkflow("workflow.run", "node.a", "node.b");
    CommitId commitId = storeWorkflow(workflow);
    ExecutionSnapshot snapshot = service.snapshotAtCommit(commitId, "snap.run", SNAP_TIME);

    ReproducibilityBundle bundle =
        service.run(snapshot, "plan.run", "exec.run", null, null, START_TIME, END_TIME);

    assertNotNull(bundle);
    assertEquals(ExecutionStatus.COMPLETED, bundle.result().overallStatus());
    assertEquals("exec.run", bundle.result().executionId());
    assertEquals("plan.run", bundle.result().planId());
    assertEquals(2, bundle.result().nodeStatuses().size());
    assertEquals(ExecutionStatus.COMPLETED, bundle.result().nodeStatuses().get("node.a"));
    assertEquals(ExecutionStatus.COMPLETED, bundle.result().nodeStatuses().get("node.b"));
  }

  @Test
  void runPreservesSnapshotInBundle() {
    Workflow workflow = buildLinearWorkflow("workflow.preserve", "node.x", "node.y");
    CommitId commitId = storeWorkflow(workflow);
    ExecutionSnapshot snapshot = service.snapshotAtCommit(commitId, "snap.preserve", SNAP_TIME);

    ReproducibilityBundle bundle =
        service.run(snapshot, "plan.p", "exec.p", null, null, START_TIME, END_TIME);

    assertEquals(snapshot, bundle.snapshot());
  }

  @Test
  void runAttachesCommitProvenanceWhenProvided() {
    Workflow workflow = buildLinearWorkflow("workflow.prov", "node.1", "node.2");
    CommitId commitId = storeWorkflow(workflow);
    CommitInfo commitInfo =
        new CommitInfo(
            commitId,
            new CommitMetadata("tester", "Reproducibility test", SNAP_TIME),
            "workflow.prov");
    ExecutionSnapshot snapshot = service.snapshotAtCommit(commitId, "snap.prov", SNAP_TIME);

    ReproducibilityBundle bundle =
        service.run(snapshot, "plan.prov", "exec.prov", commitId, commitInfo, START_TIME, END_TIME);

    assertTrue(bundle.hasStoredProvenance());
    assertEquals(commitId, bundle.commitId());
    assertEquals(commitInfo, bundle.commitInfo());
  }

  @Test
  void runThrowsForCyclicWorkflow() {
    Node nodeA =
        new Node(
            "node.a",
            "type",
            "A",
            List.of(
                new Port(
                    "in",
                    "In",
                    PortDirection.INPUT,
                    DataTypes.AUDIO_BLOCK,
                    true,
                    PortMultiplicity.SINGLE)),
            List.of(
                new Port(
                    "out",
                    "Out",
                    PortDirection.OUTPUT,
                    DataTypes.AUDIO_BLOCK,
                    true,
                    PortMultiplicity.SINGLE)));
    Node nodeB =
        new Node(
            "node.b",
            "type",
            "B",
            List.of(
                new Port(
                    "in",
                    "In",
                    PortDirection.INPUT,
                    DataTypes.AUDIO_BLOCK,
                    true,
                    PortMultiplicity.SINGLE)),
            List.of(
                new Port(
                    "out",
                    "Out",
                    PortDirection.OUTPUT,
                    DataTypes.AUDIO_BLOCK,
                    true,
                    PortMultiplicity.SINGLE)));
    ExecutionSnapshot cyclicSnapshot =
        new ExecutionSnapshot(
            "snap.cycle",
            "workflow.cycle",
            List.of(nodeA, nodeB),
            List.of(
                new Edge("edge.ab", "node.a", "out", "node.b", "in"),
                new Edge("edge.ba", "node.b", "out", "node.a", "in")),
            null,
            SNAP_TIME);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.run(cyclicSnapshot, "plan.c", "exec.c", null, null, START_TIME, END_TIME));
  }

  @Test
  void runWithEmptyWorkflowProducesCompletedBundle() {
    ExecutionSnapshot emptySnapshot =
        new ExecutionSnapshot(
            "snap.empty", "workflow.empty", List.of(), List.of(), null, SNAP_TIME);

    ReproducibilityBundle bundle =
        service.run(emptySnapshot, "plan.e", "exec.e", null, null, START_TIME, END_TIME);

    assertEquals(ExecutionStatus.COMPLETED, bundle.result().overallStatus());
    assertFalse(bundle.hasStoredProvenance());
  }

  @Test
  void runRejectsNullSnapshot() {
    assertThrows(
        NullPointerException.class,
        () -> service.run(null, "plan.1", "exec.1", null, null, START_TIME, END_TIME));
  }

  @Test
  void constructorRejectsNullStore() {
    assertThrows(NullPointerException.class, () -> new SnapshotExecutionService(null, PARSER));
  }

  @Test
  void constructorRejectsNullParser() {
    assertThrows(NullPointerException.class, () -> new SnapshotExecutionService(store, null));
  }

  // -----------------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------------

  private static Workflow buildLinearWorkflow(String workflowId, String sourceId, String sinkId) {
    Node source =
        new Node(
            sourceId,
            "source",
            "Source",
            List.of(),
            List.of(
                new Port(
                    "out",
                    "Out",
                    PortDirection.OUTPUT,
                    DataTypes.AUDIO_BLOCK,
                    true,
                    PortMultiplicity.SINGLE)));
    Node sink =
        new Node(
            sinkId,
            "sink",
            "Sink",
            List.of(
                new Port(
                    "in",
                    "In",
                    PortDirection.INPUT,
                    DataTypes.AUDIO_BLOCK,
                    true,
                    PortMultiplicity.SINGLE)),
            List.of());
    return new Workflow(
        workflowId,
        "Test Workflow",
        List.of(source, sink),
        List.of(new Edge("edge.1", sourceId, "out", sinkId, "in")));
  }

  private CommitId storeWorkflow(Workflow workflow) {
    String dslText = SERIALIZER.serialize(workflow);
    WorkflowSnapshot snapshot = new WorkflowSnapshot(workflow.id(), dslText);
    CommitMetadata meta =
        new CommitMetadata("test-author", "Test commit", Instant.parse("2024-01-01T09:00:00Z"));
    return store.commit("main", snapshot, meta);
  }
}
