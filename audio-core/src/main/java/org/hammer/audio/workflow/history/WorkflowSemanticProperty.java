package org.hammer.audio.workflow.history;

import java.util.Objects;

/** Exact workflow or node metadata evidence from one historical commit. */
public record WorkflowSemanticProperty(String key, String value) {

  public WorkflowSemanticProperty {
    key = Objects.requireNonNull(key, "key").trim();
    value = Objects.requireNonNull(value, "value");
    if (key.isEmpty()) {
      throw new IllegalArgumentException("key must not be blank");
    }
  }
}
