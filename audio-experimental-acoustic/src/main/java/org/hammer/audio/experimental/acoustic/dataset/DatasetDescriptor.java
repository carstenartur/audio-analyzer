package org.hammer.audio.experimental.acoustic.dataset;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Descriptor for one local or requestable dataset source.
 *
 * @param id stable identifier (for example {@code humbugdb})
 * @param name human-readable dataset name
 * @param source source URL or publication URL
 * @param license license identifier or usage statement
 * @param localRootPath local root directory used for offline import
 * @param metadataSchema metadata keys and semantic meaning
 */
public record DatasetDescriptor(
    String id,
    String name,
    URI source,
    String license,
    Path localRootPath,
    Map<String, String> metadataSchema) {

  public DatasetDescriptor {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(license, "license");
    Objects.requireNonNull(localRootPath, "localRootPath");
    Objects.requireNonNull(metadataSchema, "metadataSchema");
    if (id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (license.isBlank()) {
      throw new IllegalArgumentException("license must not be blank");
    }
    if (!localRootPath.isAbsolute()) {
      throw new IllegalArgumentException("localRootPath must be absolute");
    }
    metadataSchema = validateAndCopyMap(metadataSchema, "metadataSchema");
  }

  private static Map<String, String> validateAndCopyMap(Map<String, String> values, String field) {
    for (Map.Entry<String, String> entry : values.entrySet()) {
      String key = Objects.requireNonNull(entry.getKey(), field + " key");
      String value = Objects.requireNonNull(entry.getValue(), field + " value");
      if (key.isBlank()) {
        throw new IllegalArgumentException(field + " keys must not be blank");
      }
      if (value.isBlank()) {
        throw new IllegalArgumentException(field + " values must not be blank");
      }
    }
    return Map.copyOf(values);
  }
}
