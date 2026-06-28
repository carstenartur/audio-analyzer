package org.hammer.audio.workflow;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable metadata bag for workflow domain objects.
 *
 * @param entries immutable metadata entries keyed by stable names
 */
public record Metadata(Map<String, String> entries) {

  private static final Metadata EMPTY = new Metadata(Map.of());

  public Metadata {
    Objects.requireNonNull(entries, "entries");
    for (String key : entries.keySet()) {
      StableIds.requireStable(key, "metadata key");
    }
    entries = Map.copyOf(entries);
  }

  public static Metadata empty() {
    return EMPTY;
  }
}
