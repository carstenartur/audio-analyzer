package org.hammer.audio.workflow.collaboration.outbox;

import java.time.Duration;
import java.util.Objects;

/** Deterministic capped exponential retry policy for failed outbox publication. */
public record WorkflowOutboxBackoffPolicy(Duration initialDelay, Duration maximumDelay) {

  public WorkflowOutboxBackoffPolicy {
    Objects.requireNonNull(initialDelay, "initialDelay");
    Objects.requireNonNull(maximumDelay, "maximumDelay");
    if (initialDelay.isZero() || initialDelay.isNegative()) {
      throw new IllegalArgumentException("initialDelay must be > 0");
    }
    if (maximumDelay.compareTo(initialDelay) < 0) {
      throw new IllegalArgumentException("maximumDelay must be >= initialDelay");
    }
  }

  /** Returns the delay after the supplied one-based failed publication attempt. */
  public Duration delayAfterFailure(int failedAttempt) {
    if (failedAttempt <= 0) {
      throw new IllegalArgumentException("failedAttempt must be > 0");
    }
    Duration delay = initialDelay;
    for (int attempt = 1; attempt < failedAttempt && delay.compareTo(maximumDelay) < 0; attempt++) {
      if (delay.compareTo(maximumDelay.dividedBy(2)) > 0) {
        return maximumDelay;
      }
      delay = delay.multipliedBy(2);
    }
    return delay.compareTo(maximumDelay) > 0 ? maximumDelay : delay;
  }
}
