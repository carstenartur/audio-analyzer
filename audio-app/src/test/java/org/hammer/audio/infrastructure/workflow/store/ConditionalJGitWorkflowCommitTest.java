package org.hammer.audio.infrastructure.workflow.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.StaleWorkflowHeadException;
import org.hammer.audio.workflow.store.WorkflowSnapshot;
import org.junit.jupiter.api.Test;

class ConditionalJGitWorkflowCommitTest {

  private static final WorkflowDslSerializer SERIALIZER = new WorkflowDslSerializer();

  @Test
  void conditionalCommitUsesAtomicExpectedHeadAndPreservesHistory() throws IOException {
    WorkflowSnapshot baseline = snapshot("Baseline");
    WorkflowSnapshot changed = snapshot("Changed");

    try (FileSystemJGitVersionedWorkflowStore store =
        new FileSystemJGitVersionedWorkflowStore(
            Files.createTempDirectory("audio-analyzer-conditional-commit-"))) {
      CommitId first = store.commit("main", baseline, metadata("first", 1));
      CommitId second = store.commit("main", changed, metadata("second", 2));

      assertThrows(
          StaleWorkflowHeadException.class,
          () -> store.commitIfHead("main", first, baseline, metadata("stale", 3)));
      assertEquals(List.of(second, first), store.history("main", 10).stream().map(info -> info.commitId()).toList());

      CommitId restored =
          store.commitIfHead("main", second, baseline, metadata("restore baseline", 4));
      assertEquals(
          List.of(restored, second, first),
          store.history("main", 10).stream().map(info -> info.commitId()).toList());
      assertEquals(baseline, store.loadAtCommit(restored));
    }
  }

  private static WorkflowSnapshot snapshot(String name) {
    Workflow workflow = new Workflow("workflow.conditional", name, List.of(), List.of());
    return new WorkflowSnapshot(workflow.id(), SERIALIZER.serialize(workflow));
  }

  private static CommitMetadata metadata(String message, long seconds) {
    return new CommitMetadata(
        "conditional-test", message, Instant.parse("2026-07-20T00:00:00Z").plusSeconds(seconds));
  }
}
