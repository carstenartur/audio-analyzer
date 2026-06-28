package org.hammer.audio.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class StableIdsTest {

  @Test
  void acceptsStableIdentifiers() {
    for (String value : List.of("abc", "node.import", "port:dataset-out", "workflow_demo-1")) {
      assertDoesNotThrow(() -> StableIds.requireStable(value, "id"));
    }
  }

  @Test
  void rejectsBlankOrMalformedIdentifiers() {
    assertRejected(null, "id must not be blank");
    assertRejected("", "id must not be blank");
    assertRejected("   ", "id must not be blank");
    assertRejected(".leading", "id must match [A-Za-z0-9][A-Za-z0-9._:-]*: .leading");
    assertRejected("-leading", "id must match [A-Za-z0-9][A-Za-z0-9._:-]*: -leading");
    assertRejected("has space", "id must match [A-Za-z0-9][A-Za-z0-9._:-]*: has space");
  }

  private static void assertRejected(String value, String message) {
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> StableIds.requireStable(value, "id"));

    assertEquals(message, exception.getMessage());
  }
}
