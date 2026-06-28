package org.hammer.audio.workflow;

/**
 * Strongly typed identifier for artifacts exchanged between workflow ports.
 *
 * @param id stable workflow data type identifier
 */
public record DataType(String id) {

  public DataType {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
  }
}
