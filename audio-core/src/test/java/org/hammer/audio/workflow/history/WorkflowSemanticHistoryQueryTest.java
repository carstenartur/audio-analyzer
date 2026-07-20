package org.hammer.audio.workflow.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class WorkflowSemanticHistoryQueryTest {

  @Test
  void normalizesBranchAndOptionalSemanticFilters() {
    WorkflowSemanticHistoryQuery query =
        new WorkflowSemanticHistoryQuery(
            "  main  ",
            " workflow.example ",
            "  ",
            " classifier ",
            null,
            " threshold ",
            " high ",
            20);

    assertEquals("main", query.branch());
    assertEquals("workflow.example", query.workflowId());
    assertNull(query.nodeId());
    assertEquals("classifier", query.nodeType());
    assertEquals("threshold", query.propertyKey());
    assertEquals("high", query.propertyValue());
  }

  @Test
  void rejectsMissingBranchAndUnboundedLimits() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorkflowSemanticHistoryQuery(" ", null, null, null, null, null, null, 20));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorkflowSemanticHistoryQuery("main", null, null, null, null, null, null, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorkflowSemanticHistoryQuery("main", null, null, null, null, null, null, 201));
  }
}
