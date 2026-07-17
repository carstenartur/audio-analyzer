package org.hammer.audio.workflow.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.junit.jupiter.api.Test;

class WorkflowMergeServiceTest {

  @Test
  void differentNodesMergeWithoutConflict() {
    Workflow base = workflow(List.of(node("a", "A"), node("b", "B")));
    Workflow local = workflow(List.of(node("a", "Local A"), node("b", "B")));
    Workflow remote = workflow(List.of(node("a", "A"), node("b", "Remote B")));

    WorkflowMergeService.MergeResult result = new WorkflowMergeService().merge(base, local, remote);

    assertTrue(result.resolved());
    assertTrue(result.conflicts().isEmpty());
    assertEquals(
        List.of("Local A", "Remote B"),
        result.mergedWorkflow().nodes().stream().map(Node::label).toList());
  }

  @Test
  void sameNodeChangedDifferentlyProducesDeterministicConflictAndResolution() {
    Workflow base = workflow(List.of(node("a", "A")));
    Workflow local = workflow(List.of(node("a", "Local")));
    Workflow remote = workflow(List.of(node("a", "Remote")));
    WorkflowMergeService service = new WorkflowMergeService();

    WorkflowMergeService.MergeResult conflicted = service.merge(base, local, remote);
    assertFalse(conflicted.resolved());
    assertEquals("node:a", conflicted.conflicts().getFirst().conflictId());

    WorkflowMergeService.MergeResult resolved =
        service.merge(base, local, remote, Map.of("node:a", WorkflowMergeResolution.Choice.REMOTE));
    assertTrue(resolved.resolved());
    assertEquals("Remote", resolved.mergedWorkflow().nodes().getFirst().label());
  }

  private static Workflow workflow(List<Node> nodes) {
    return new Workflow("workflow", "Workflow", nodes, List.of());
  }

  private static Node node(String id, String label) {
    return new Node(id, "type", label, List.of(), List.of(), Metadata.empty());
  }
}
