package org.hammer.audio.workflow.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class WorkflowHistoryTextQueryTest {

  @Test
  void normalizesOptionalStructuredFiltersAndPreservesTheOriginalConstructor() {
    WorkflowHistoryTextQuery legacy = new WorkflowHistoryTextQuery("  wingbeat  ", 20);
    WorkflowHistoryTextQuery structured =
        new WorkflowHistoryTextQuery(
            "  ",
            "  researcher@example.org  ",
            "  workflows insect  ",
            null,
            null,
            25);

    assertEquals("wingbeat", legacy.text());
    assertNull(legacy.authorEmail());
    assertNull(legacy.pathText());
    assertEquals("", structured.text());
    assertEquals("researcher@example.org", structured.authorEmail());
    assertEquals("workflows insect", structured.pathText());
  }

  @Test
  void rejectsAnInvertedTimeRange() {
    Instant earlier = Instant.parse("2026-07-01T00:00:00Z");
    Instant later = Instant.parse("2026-07-19T00:00:00Z");

    assertThrows(
        IllegalArgumentException.class,
        () -> new WorkflowHistoryTextQuery("history", null, null, later, earlier, 20));
  }
}
