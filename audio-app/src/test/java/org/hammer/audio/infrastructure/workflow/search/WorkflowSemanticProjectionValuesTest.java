package org.hammer.audio.infrastructure.workflow.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowSemanticProjectionValuesTest {

  @Test
  void roundTripsEmptyDelimiterRichAndUnicodeValuesLosslessly() {
    List<String> values = List.of("", "line one\nline two", "a:b.c/d", "Mücke");

    String encoded = WorkflowSemanticProjectionValues.encodeValues(values);

    assertFalse(encoded.lines().anyMatch(String::isEmpty));
    assertEquals(values.stream().sorted().toList(), WorkflowSemanticProjectionValues.decodeValues(encoded));
  }

  @Test
  void propertyPairsRemainUnambiguous() {
    assertFalse(
        WorkflowSemanticProjectionValues.encodePair("mode", "high")
            .equals(WorkflowSemanticProjectionValues.encodePair("mode.high", "")));
  }
}
