package org.hammer.audio.workflow.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.WorkflowOperationLog;
import org.hammer.audio.workflow.WorkflowValidator;
import org.hammer.audio.workflow.catalog.ExperimentNodeCatalog;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.InMemoryVersionedWorkflowStore;
import org.junit.jupiter.api.Test;

class WorkflowEditorDirtyStateTest {

  private static final Instant OCCURRED_AT = Instant.parse("2026-07-23T12:00:00Z");

  @Test
  void successfulOperationsAndImportsBecomeDirtyWhileLoadsAndCheckpointsBecomeClean() {
    InMemoryVersionedWorkflowStore store = new InMemoryVersionedWorkflowStore();
    WorkflowEditorService service = service(initialWorkflow(), store);
    assertFalse(service.isDirty());

    service.applyOperation(
        new WorkflowOperation.UpdateProperty(
            "op.gain",
            OCCURRED_AT,
            "tester",
            WorkflowOperation.PropertyTarget.NODE,
            "gain",
            "gain",
            null,
            "2.0"));
    assertTrue(service.isDirty());

    service.checkpoint(
        "main", new CommitMetadata("tester", "saved", Instant.parse("2026-07-23T12:01:00Z")));
    assertFalse(service.isDirty());

    Workflow imported = new Workflow("imported", "Imported", List.of(), List.of());
    service.importGraph(imported);
    assertTrue(service.isDirty());
    assertEquals("imported", service.currentProjection().workflowId());

    service.loadGraph("main");
    assertFalse(service.isDirty());
    assertEquals("initial", service.currentProjection().workflowId());
  }

  @Test
  void rejectedOperationAndRejectedImportLeaveWorkflowAndDirtyStateUnchanged() {
    WorkflowEditorService service = service(initialWorkflow(), null);
    Edge invalidEdge = new Edge("bad", "missing", "out", "gain", "audio-in");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.applyOperation(
                new WorkflowOperation.ConnectPorts(
                    "op.invalid", OCCURRED_AT, "tester", invalidEdge)));
    assertFalse(service.isDirty());
    assertEquals("initial", service.currentProjection().workflowId());

    service.importGraph(new Workflow("imported", "Imported", List.of(), List.of()));
    assertTrue(service.isDirty());
    Workflow invalid =
        new Workflow(
            "invalid",
            "Invalid",
            List.of(ExperimentNodeCatalog.gain("gain")),
            List.of(new Edge("bad", "missing", "out", "gain", "audio-in")));

    assertThrows(WorkflowOperationRejectedException.class, () -> service.importGraph(invalid));
    assertTrue(service.isDirty());
    assertEquals("imported", service.currentProjection().workflowId());
  }

  private static WorkflowEditorService service(
      Workflow initial, InMemoryVersionedWorkflowStore store) {
    return new WorkflowEditorService(
        new WorkflowOperationLog(initial), new WorkflowValidator(), store);
  }

  private static Workflow initialWorkflow() {
    return new Workflow(
        "initial",
        "Initial",
        List.of(
            ExperimentNodeCatalog.syntheticSignalGenerator("input"),
            ExperimentNodeCatalog.gain("gain")),
        List.of());
  }
}
