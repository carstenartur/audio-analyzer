package org.hammer.audio.workflow.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.InMemoryVersionedWorkflowStore;
import org.hammer.audio.workflow.store.StaleWorkflowHeadException;
import org.hammer.audio.workflow.store.WorkflowSnapshot;
import org.junit.jupiter.api.Test;

class WorkflowHistoryCommandServiceTest {

  private static final WorkflowDslSerializer SERIALIZER = new WorkflowDslSerializer();
  private static final Instant BASE_TIME = Instant.parse("2026-07-20T00:00:00Z");

  @Test
  void comparesReachableCommitsAndRestoresTargetAsNewAuditCommit() {
    InMemoryVersionedWorkflowStore store = new InMemoryVersionedWorkflowStore();
    Workflow baseline = workflow("Baseline", List.of());
    Workflow changed =
        workflow(
            "Changed",
            List.of(new Node("node.gain", "gain", "Gain", List.of(), List.of())));
    CommitId baselineCommit = store.commit("main", snapshot(baseline), metadata("baseline", 1));
    CommitId changedCommit = store.commit("main", snapshot(changed), metadata("changed", 2));
    WorkflowHistoryCommandService service =
        new WorkflowHistoryCommandService(store, WorkflowHistoryAccessPolicy.allowAll());

    WorkflowHistoryComparison comparison =
        service.compare("main", baselineCommit, changedCommit);
    assertEquals(baseline, comparison.beforeWorkflow());
    assertEquals(changed, comparison.afterWorkflow());
    assertEquals(1, comparison.diff().changes().size());
    assertInstanceOf(WorkflowChange.NodeAdded.class, comparison.diff().changes().getFirst());

    WorkflowRestoreResult result =
        service.restore(
            new RestoreWorkflowVersionCommand(
                "main", baselineCommit, changedCommit, metadata("restore baseline", 3)));
    assertEquals(baselineCommit, result.targetCommit());
    assertEquals(changedCommit, result.previousHead());
    assertEquals(snapshot(baseline), store.loadAtCommit(result.restoredCommit()));
    assertEquals(
        List.of(result.restoredCommit(), changedCommit, baselineCommit),
        store.history("main", 10).stream().map(info -> info.commitId()).toList());
  }

  @Test
  void rejectsUnreachableStaleAndAccessBlockedCommands() {
    InMemoryVersionedWorkflowStore store = new InMemoryVersionedWorkflowStore();
    WorkflowSnapshot baseline = snapshot(workflow("Baseline", List.of()));
    CommitId mainCommit = store.commit("main", baseline, metadata("main", 1));
    CommitId otherCommit = store.commit("other", baseline, metadata("other", 2));
    WorkflowHistoryCommandService allowed =
        new WorkflowHistoryCommandService(store, WorkflowHistoryAccessPolicy.allowAll());

    assertThrows(
        IllegalArgumentException.class,
        () -> allowed.compare("main", mainCommit, otherCommit));
    assertThrows(
        StaleWorkflowHeadException.class,
        () ->
            allowed.restore(
                new RestoreWorkflowVersionCommand(
                    "main", mainCommit, otherCommit, metadata("stale", 3))));

    WorkflowHistoryCommandService blocked =
        new WorkflowHistoryCommandService(
            store,
            (branch, workflowId) -> {
              throw new WorkflowHistoryAccessException(branch, workflowId, "active session");
            });
    assertThrows(
        WorkflowHistoryAccessException.class,
        () ->
            blocked.restore(
                new RestoreWorkflowVersionCommand(
                    "main", mainCommit, mainCommit, metadata("blocked", 4))));
  }

  private static Workflow workflow(String name, List<Node> nodes) {
    return new Workflow("workflow.history-command", name, nodes, List.of());
  }

  private static WorkflowSnapshot snapshot(Workflow workflow) {
    return new WorkflowSnapshot(workflow.id(), SERIALIZER.serialize(workflow));
  }

  private static CommitMetadata metadata(String message, long seconds) {
    return new CommitMetadata("history-test", message, BASE_TIME.plusSeconds(seconds));
  }
}
