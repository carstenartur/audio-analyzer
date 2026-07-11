package org.hammer.audio.workflow.catalog;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hammer.audio.workflow.Metadata;
import org.junit.jupiter.api.Test;

/** Tests for {@link ExperimentMetadataKeys}. */
class ExperimentMetadataKeysTest {

  /**
   * Every constant in {@link ExperimentMetadataKeys} must satisfy the {@code StableIds} contract so
   * it can be used as a key in a {@link Metadata} entry without throwing.
   */
  @Test
  void allKeysAreValidMetadataKeys() throws IllegalAccessException {
    List<String> keys = collectStringConstants();
    for (String key : keys) {
      assertNotNull(key, "Key must not be null");
      // Verify the key is accepted by Metadata (StableIds validation happens in the constructor)
      assertDoesNotThrow(
          () -> new Metadata(Map.of(key, "test-value")),
          "Key '" + key + "' must be a valid Metadata key");
    }
  }

  /** Every constant must be non-blank. */
  @Test
  void allKeysAreNonBlank() throws IllegalAccessException {
    for (String key : collectStringConstants()) {
      assertNotNull(key);
      org.junit.jupiter.api.Assertions.assertFalse(key.isBlank(), "Key must not be blank: " + key);
    }
  }

  /** Every key must be unique within the class. */
  @Test
  void allKeysAreDistinct() throws IllegalAccessException {
    List<String> keys = collectStringConstants();
    long distinct = keys.stream().distinct().count();
    org.junit.jupiter.api.Assertions.assertEquals(
        keys.size(), distinct, "All ExperimentMetadataKeys constants must be distinct");
  }

  private static List<String> collectStringConstants() throws IllegalAccessException {
    List<String> result = new ArrayList<>();
    for (Field field : ExperimentMetadataKeys.class.getDeclaredFields()) {
      if (Modifier.isPublic(field.getModifiers())
          && Modifier.isStatic(field.getModifiers())
          && Modifier.isFinal(field.getModifiers())
          && field.getType() == String.class) {
        result.add((String) field.get(null));
      }
    }
    return result;
  }
}
