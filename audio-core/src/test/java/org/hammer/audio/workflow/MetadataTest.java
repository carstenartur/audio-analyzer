package org.hammer.audio.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetadataTest {

  @Test
  void copiesEntriesDefensively() {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("semantic.role", "source");

    Metadata metadata = new Metadata(entries);
    entries.put("semantic.role", "changed");

    assertEquals(Map.of("semantic.role", "source"), metadata.entries());
  }

  @Test
  void rejectsUnstableMetadataKeys() {
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> new Metadata(Map.of("bad key", "x")));

    assertEquals(
        "metadata key must match [A-Za-z0-9][A-Za-z0-9._:-]*: bad key", exception.getMessage());
  }
}
