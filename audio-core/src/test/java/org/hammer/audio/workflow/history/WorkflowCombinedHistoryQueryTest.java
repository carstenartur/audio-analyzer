package org.hammer.audio.workflow.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.hammer.audio.workflow.store.CommitId;
import org.junit.jupiter.api.Test;

class WorkflowCombinedHistoryQueryTest {

  @Test
  void keepsTheFinalLimitOnlyOnTheGenericQuery() {
    WorkflowHistoryTextQuery generic =
        new WorkflowHistoryTextQuery("wingbeat", null, "workflow", null, null, 7);
    WorkflowSemanticHistoryFilter semantic =
        new WorkflowSemanticHistoryFilter(
            " main ", "workflow.insect", null, "classifier", null, "mode", "safe");

    WorkflowCombinedHistoryQuery combined = new WorkflowCombinedHistoryQuery(generic, semantic);

    assertEquals(7, combined.genericQuery().limit());
    assertEquals("main", combined.semanticFilter().branch());
    assertTrue(combined.semanticFilter().hasDomainPredicates());
  }

  @Test
  void combinedResultRequiresMatchingCommitIdentities() {
    WorkflowHistoryTextResult generic =
        new WorkflowHistoryTextResult(
            new CommitId("generic"),
            "message",
            "Author",
            "author@example.org",
            Instant.EPOCH,
            List.of("workflow.dsl"));
    WorkflowSemanticHistoryResult semantic =
        new WorkflowSemanticHistoryResult(
            new CommitId("semantic"),
            "main",
            "workflow.insect",
            "Insect",
            List.of(),
            List.of(),
            List.of(),
            List.of());

    assertThrows(
        IllegalArgumentException.class, () -> new WorkflowCombinedHistoryResult(generic, semantic));
  }
}
