package org.hammer.audio.infrastructure.workflow.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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
import org.hammer.audio.workflow.store.RefUpdateResult;
import org.hammer.audio.workflow.store.WorkflowSnapshot;
import org.junit.jupiter.api.Test;

class FileSystemJGitVersionedWorkflowStoreTest {

  private static final WorkflowDslSerializer SERIALIZER = new WorkflowDslSerializer();
  private static final WorkflowDslParser PARSER = new WorkflowDslParser();

  @Test
  void workflowRoundTripHistoryAndReopenWorkAgainstRealJGitStore() throws IOException {
    Path gitDir = Files.createTempDirectory("audio-analyzer-jgit-store-");
    Workflow firstWorkflow = buildMinimalWorkflow();
    Workflow secondWorkflow = buildExtendedWorkflow();
    CommitMetadata firstMetadata =
        new CommitMetadata("author-1", "initial", Instant.parse("2026-01-01T00:00:00Z"));
    CommitMetadata secondMetadata =
        new CommitMetadata("author-2", "second", Instant.parse("2026-01-02T00:00:00Z"));

    CommitId firstCommit;
    try (FileSystemJGitVersionedWorkflowStore store =
        new FileSystemJGitVersionedWorkflowStore(gitDir)) {
      firstCommit = store.commit("main", toSnapshot(firstWorkflow), firstMetadata);
      assertEquals(firstWorkflow, PARSER.parse(store.loadHead("main").dslText()));
    }

    CommitId secondCommit;
    try (FileSystemJGitVersionedWorkflowStore reopenedStore =
        new FileSystemJGitVersionedWorkflowStore(gitDir)) {
      assertEquals(firstWorkflow, PARSER.parse(reopenedStore.loadHead("main").dslText()));
      secondCommit = reopenedStore.commit("main", toSnapshot(secondWorkflow), secondMetadata);

      List<CommitInfo> history = reopenedStore.history("main", 10);
      assertEquals(2, history.size());
      assertEquals(secondCommit, history.get(0).commitId());
      assertEquals(firstCommit, history.get(1).commitId());
      assertEquals(firstWorkflow, PARSER.parse(reopenedStore.loadAtCommit(firstCommit).dslText()));
    }
  }

  @Test
  void updateRefSupportsSuccessStaleNewBranchAndNoChange() throws IOException {
    Path gitDir = Files.createTempDirectory("audio-analyzer-jgit-ref-");
    CommitMetadata metadata =
        new CommitMetadata("author", "commit", Instant.parse("2026-01-01T00:00:00Z"));

    try (FileSystemJGitVersionedWorkflowStore store =
        new FileSystemJGitVersionedWorkflowStore(gitDir)) {
      CommitId base = store.commit("main", toSnapshot(buildMinimalWorkflow()), metadata);
      CommitId candidate =
          store.commit(
              "candidate",
              toSnapshot(buildExtendedWorkflow()),
              new CommitMetadata("author", "candidate", Instant.parse("2026-01-02T00:00:00Z")));

      assertEquals(RefUpdateResult.SUCCESS, store.updateRef("main", base, candidate));
      assertEquals(buildExtendedWorkflow(), PARSER.parse(store.loadHead("main").dslText()));

      assertEquals(RefUpdateResult.STALE, store.updateRef("main", base, base));

      CommitId featureHead =
          store.commit(
              "feature-source",
              toSnapshot(buildMinimalWorkflow()),
              new CommitMetadata(
                  "author", "feature source", Instant.parse("2026-01-03T00:00:00Z")));
      assertEquals(
          RefUpdateResult.SUCCESS, store.updateRef("refs/heads/feature/new", null, featureHead));
      assertEquals(buildMinimalWorkflow(), PARSER.parse(store.loadHead("feature/new").dslText()));

      assertEquals(
          RefUpdateResult.SUCCESS,
          store.updateRef("refs/heads/feature/new", featureHead, featureHead));
      assertTrue(store.history("feature/new", 10).size() >= 1);
    }
  }

  private static WorkflowSnapshot toSnapshot(Workflow workflow) {
    return new WorkflowSnapshot(workflow.id(), SERIALIZER.serialize(workflow));
  }

  private static Workflow buildMinimalWorkflow() {
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
    List<Edge> edges =
        List.of(
            new Edge("edge.gain-output", "node.gain", "audio-out", "node.output", "audio-in"),
            new Edge("edge.input-gain", "node.input", "audio-out", "node.gain", "audio-in"));
    return new Workflow(
        "workflow.minimal", "Input -> Gain -> Output", List.of(gain, input, output), edges);
  }

  private static Workflow buildExtendedWorkflow() {
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
}
