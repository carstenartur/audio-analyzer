package org.hammer.audio.workflow.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import org.hammer.audio.workflow.WorkflowOperationLog;
import org.hammer.audio.workflow.dsl.WorkflowDslParser;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
import org.hammer.audio.workflow.execution.ExecutionSnapshot;
import org.junit.jupiter.api.Test;

/**
 * Vertical-slice test for the {@code Input -> Gain -> Output} workflow store roundtrip.
 *
 * <p>This test suite covers the domain/DSL/persistence-facade layers and stays UI-free and
 * collaboration-free, as required by issue #216.
 *
 * <p>Invariants verified:
 *
 * <ul>
 *   <li>Minimal workflow is created using {@code audio-core} workflow types.
 *   <li>Workflow serializes to deterministic DSL text.
 *   <li>Workflow is committed through {@code VersionedWorkflowStore}.
 *   <li>HEAD can be loaded.
 *   <li>A historical commit can be loaded.
 *   <li>{@code WorkflowOperationLog} state matches the committed workflow.
 *   <li>A stable {@code ExecutionSnapshot} can be created from the loaded workflow.
 * </ul>
 */
class WorkflowStoreRoundTripTest {

  private static final String BRANCH = "main";
  private static final Instant COMMIT_TIME = Instant.parse("2024-01-01T12:00:00Z");
  private static final CommitMetadata METADATA =
      new CommitMetadata("test-author", "Initial experiment workflow", COMMIT_TIME);
  private static final WorkflowDslSerializer SERIALIZER = new WorkflowDslSerializer();
  private static final WorkflowDslParser PARSER = new WorkflowDslParser();

  /**
   * DSL layer test: serializing the minimal workflow produces deterministic, byte-identical text.
   */
  @Test
  void minimalWorkflowSerializesToDeterministicDsl() {
    Workflow workflow = buildMinimalWorkflow();

    String text1 = SERIALIZER.serialize(workflow);
    String text2 = SERIALIZER.serialize(workflow);

    assertEquals(text1, text2, "Serializer must produce identical output for the same workflow");
    assertNotNull(text1);
  }

  /** DSL layer test: a serialized workflow can be parsed back to an equivalent workflow. */
  @Test
  void minimalWorkflowRoundTripsThroughDsl() {
    Workflow original = buildMinimalWorkflow();

    String dslText = SERIALIZER.serialize(original);
    Workflow restored = PARSER.parse(dslText);

    assertEquals(original, restored);
  }

  /** Persistence facade test: workflow can be committed and loaded back at HEAD. */
  @Test
  void workflowCanBeCommittedAndLoadedAtHead() {
    VersionedWorkflowStore store = new InMemoryVersionedWorkflowStore();
    Workflow workflow = buildMinimalWorkflow();
    WorkflowSnapshot snapshot = toSnapshot(workflow);

    store.commit(BRANCH, snapshot, METADATA);

    WorkflowSnapshot loaded = store.loadHead(BRANCH);
    assertEquals(workflow, PARSER.parse(loaded.dslText()));
  }

  /** Persistence facade test: a specific historical commit can be loaded by commit id. */
  @Test
  void historicalCommitCanBeLoadedByCommitId() {
    VersionedWorkflowStore store = new InMemoryVersionedWorkflowStore();
    Workflow v1 = buildMinimalWorkflow();
    CommitId firstCommit = store.commit(BRANCH, toSnapshot(v1), METADATA);

    // Add a second commit to make history non-trivial
    Workflow v2 = buildExtendedWorkflow();
    store.commit(BRANCH, toSnapshot(v2), new CommitMetadata("author", "Add FFT node", COMMIT_TIME));

    WorkflowSnapshot loaded = store.loadAtCommit(firstCommit);
    assertEquals(v1, PARSER.parse(loaded.dslText()));
  }

  /** Persistence facade test: history lists commits in most-recent-first order. */
  @Test
  void historyReturnsCommitsInReverseChronologicalOrder() {
    VersionedWorkflowStore store = new InMemoryVersionedWorkflowStore();
    CommitId first = store.commit(BRANCH, toSnapshot(buildMinimalWorkflow()), METADATA);
    CommitId second =
        store.commit(
            BRANCH,
            toSnapshot(buildExtendedWorkflow()),
            new CommitMetadata("author", "Second commit", COMMIT_TIME));

    List<CommitInfo> history = store.history(BRANCH, 10);

    assertEquals(2, history.size());
    assertEquals(second, history.get(0).commitId());
    assertEquals(first, history.get(1).commitId());
  }

  /**
   * Persistence facade test: loading HEAD from a nonexistent branch throws {@link
   * NoSuchElementException}.
   */
  @Test
  void loadHeadFromNonexistentBranchThrows() {
    VersionedWorkflowStore store = new InMemoryVersionedWorkflowStore();
    assertThrows(NoSuchElementException.class, () -> store.loadHead("nonexistent"));
  }

  /**
   * Domain layer test: {@link WorkflowOperationLog} state initialized from a loaded workflow
   * reflects the committed workflow with no pending operations.
   */
  @Test
  void workflowOperationLogStateMatchesCommittedWorkflow() {
    VersionedWorkflowStore store = new InMemoryVersionedWorkflowStore();
    Workflow workflow = buildMinimalWorkflow();
    store.commit(BRANCH, toSnapshot(workflow), METADATA);

    WorkflowSnapshot loaded = store.loadHead(BRANCH);
    Workflow restoredWorkflow = PARSER.parse(loaded.dslText());

    WorkflowOperationLog log = new WorkflowOperationLog(restoredWorkflow);

    assertEquals(workflow, log.currentWorkflow());
    assertEquals(List.of(), log.operations());
  }

  /**
   * Execution layer test: a stable {@link ExecutionSnapshot} can be created from the loaded
   * workflow.
   */
  @Test
  void executionSnapshotCanBeCreatedFromLoadedWorkflow() {
    VersionedWorkflowStore store = new InMemoryVersionedWorkflowStore();
    Workflow workflow = buildMinimalWorkflow();
    store.commit(BRANCH, toSnapshot(workflow), METADATA);

    WorkflowSnapshot loaded = store.loadHead(BRANCH);
    Workflow restoredWorkflow = PARSER.parse(loaded.dslText());

    ExecutionSnapshot snapshot =
        ExecutionSnapshot.of("snap.1", restoredWorkflow, Instant.parse("2024-01-02T00:00:00Z"));

    assertNotNull(snapshot.snapshotId());
    assertEquals(restoredWorkflow.id(), snapshot.workflowId());
    assertEquals(restoredWorkflow.nodes(), snapshot.nodes());
    assertEquals(restoredWorkflow.edges(), snapshot.edges());
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /**
   * Builds the minimal {@code Input -> Gain -> Output} workflow.
   *
   * <p>Nodes and edges are listed in alphabetically sorted ID order so that the list returned by
   * the DSL parser (which sorts by ID) is identical to this construction order.
   */
  private static Workflow buildMinimalWorkflow() {
    // Sorted order: node.gain < node.input < node.output
    Node gain =
        new Node(
            "node.gain",
            "gain",
            "Gain",
            List.of(
                new Port(
                    "audio-in",
                    "Audio In",
                    PortDirection.INPUT,
                    DataTypes.AUDIO_BLOCK,
                    true,
                    PortMultiplicity.SINGLE)),
            List.of(
                new Port(
                    "audio-out",
                    "Audio Out",
                    PortDirection.OUTPUT,
                    DataTypes.AUDIO_BLOCK,
                    false,
                    PortMultiplicity.SINGLE)));
    Node input =
        new Node(
            "node.input",
            "audio-source",
            "Audio Source",
            List.of(),
            List.of(
                new Port(
                    "audio-out",
                    "Audio Out",
                    PortDirection.OUTPUT,
                    DataTypes.AUDIO_BLOCK,
                    false,
                    PortMultiplicity.SINGLE)));
    Node output =
        new Node(
            "node.output",
            "audio-sink",
            "Audio Sink",
            List.of(
                new Port(
                    "audio-in",
                    "Audio In",
                    PortDirection.INPUT,
                    DataTypes.AUDIO_BLOCK,
                    true,
                    PortMultiplicity.SINGLE)),
            List.of());
    // Sorted order: edge.gain-output < edge.input-gain
    List<Edge> edges =
        List.of(
            new Edge("edge.gain-output", "node.gain", "audio-out", "node.output", "audio-in"),
            new Edge("edge.input-gain", "node.input", "audio-out", "node.gain", "audio-in"));
    return new Workflow(
        "workflow.minimal", "Input -> Gain -> Output", List.of(gain, input, output), edges);
  }

  /** Builds a slightly richer workflow used for history tests. */
  private static Workflow buildExtendedWorkflow() {
    // Sorted order: node.fft < node.input
    Node fft =
        new Node(
            "node.fft",
            "fft",
            "FFT",
            List.of(
                new Port(
                    "audio-in",
                    "Audio In",
                    PortDirection.INPUT,
                    DataTypes.AUDIO_BLOCK,
                    true,
                    PortMultiplicity.SINGLE)),
            List.of(
                new Port(
                    "spectrum-out",
                    "Spectrum",
                    PortDirection.OUTPUT,
                    DataTypes.SPECTRUM,
                    false,
                    PortMultiplicity.SINGLE)));
    Node input =
        new Node(
            "node.input",
            "audio-source",
            "Audio Source",
            List.of(),
            List.of(
                new Port(
                    "audio-out",
                    "Audio Out",
                    PortDirection.OUTPUT,
                    DataTypes.AUDIO_BLOCK,
                    false,
                    PortMultiplicity.SINGLE)));
    return new Workflow(
        "workflow.minimal",
        "Input -> FFT",
        List.of(fft, input),
        List.of(new Edge("edge.input-fft", "node.input", "audio-out", "node.fft", "audio-in")));
  }

  private static WorkflowSnapshot toSnapshot(Workflow workflow) {
    return new WorkflowSnapshot(workflow.id(), SERIALIZER.serialize(workflow));
  }
}
