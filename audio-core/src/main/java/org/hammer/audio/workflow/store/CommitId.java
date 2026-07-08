package org.hammer.audio.workflow.store;

import java.util.Objects;

/**
 * Opaque, stable reference to a committed workflow version.
 *
 * <p>The format of the contained string is an implementation detail of the storage back end. Higher
 * layers must treat it as an opaque handle and must not parse its contents.
 *
 * <p>Owned by the persistence facade layer. Used by application services and tests; must not be
 * exposed to editor adapters or UI code.
 *
 * @param value opaque commit identifier string
 */
public record CommitId(String value) {

  public CommitId {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("CommitId value must not be blank");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
