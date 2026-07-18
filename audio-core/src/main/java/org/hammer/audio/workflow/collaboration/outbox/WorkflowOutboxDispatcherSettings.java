package org.hammer.audio.workflow.collaboration.outbox;

import java.time.Duration;
import java.util.Objects;

/** Immutable operational settings for one durable workflow outbox dispatcher instance. */
public record WorkflowOutboxDispatcherSettings(
    String dispatcherId,
    int batchSize,
    Duration leaseDuration,
    WorkflowOutboxBackoffPolicy backoffPolicy) {

  public WorkflowOutboxDispatcherSettings {
    dispatcherId = requireNotBlank(dispatcherId, "dispatcherId");
    if (batchSize <= 0) {
      throw new IllegalArgumentException("batchSize must be > 0");
    }
    Objects.requireNonNull(leaseDuration, "leaseDuration");
    if (leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("leaseDuration must be > 0");
    }
    Objects.requireNonNull(backoffPolicy, "backoffPolicy");
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
