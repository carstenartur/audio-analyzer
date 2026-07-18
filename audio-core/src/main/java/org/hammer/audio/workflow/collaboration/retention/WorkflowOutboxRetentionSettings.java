package org.hammer.audio.workflow.collaboration.retention;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Conservative settings for bounded published-outbox retention.
 *
 * <p>This contract does not delete session aggregates, accepted operations or Git checkpoints.
 * Command ids therefore remain available indefinitely unless a separate, explicitly proven
 * operation-compaction policy is introduced later.
 *
 * @param publishedRetention minimum age of a successfully published event before eligibility
 * @param batchSize maximum candidates captured by one immutable plan
 */
public record WorkflowOutboxRetentionSettings(Duration publishedRetention, int batchSize) {

  /** Default published-event diagnostic horizon. */
  public static final Duration DEFAULT_PUBLISHED_RETENTION = Duration.ofDays(30);

  /** Conservative default batch size. */
  public static final int DEFAULT_BATCH_SIZE = 100;

  /** Hard upper bound preventing accidental unbounded cleanup transactions. */
  public static final int MAXIMUM_BATCH_SIZE = 1000;

  public WorkflowOutboxRetentionSettings {
    // Validate settings once before they can participate in candidate selection.
    Objects.requireNonNull(publishedRetention, "publishedRetention");
    if (publishedRetention.isZero() || publishedRetention.isNegative()) {
      throw new IllegalArgumentException("publishedRetention must be positive");
    }
    if (batchSize <= 0 || batchSize > MAXIMUM_BATCH_SIZE) {
      throw new IllegalArgumentException(
          "batchSize must be between 1 and " + MAXIMUM_BATCH_SIZE);
    }
  }

  /** Returns settings that retain published rows for 30 days and delete at most 100 per batch. */
  public static WorkflowOutboxRetentionSettings conservativeDefaults() {
    return new WorkflowOutboxRetentionSettings(DEFAULT_PUBLISHED_RETENTION, DEFAULT_BATCH_SIZE);
  }

  /** Computes the inclusive publication cutoff for one logical cleanup time. */
  public Instant publishedCutoffAt(Instant plannedAt) {
    return Objects.requireNonNull(plannedAt, "plannedAt").minus(publishedRetention);
  }
}
