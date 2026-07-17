package org.hammer.audio.workflow.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InMemoryWorkflowHistorySearchIndexTest {

  @Test
  void textNodeTypeAndPropertyFiltersAreDeterministic() {
    InMemoryWorkflowHistorySearchIndex index = new InMemoryWorkflowHistorySearchIndex();
    index.upsert(
        new WorkflowHistoryDocument(
            "main",
            "c1",
            "workflow",
            "alice",
            "FFT experiment",
            Instant.parse("2026-01-01T00:00:00Z"),
            Set.of("fft"),
            Map.of("windowSize", "4096"),
            "fft experiment windowsize 4096"));

    assertEquals(
        1,
        index
            .search(
                new WorkflowHistoryQuery(
                    "experiment", "main", "alice", null, null, "fft", "windowSize", "4096", 10))
            .size());
  }
}
