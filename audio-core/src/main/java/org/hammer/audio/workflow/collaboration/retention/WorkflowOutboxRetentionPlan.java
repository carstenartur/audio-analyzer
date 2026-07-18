package org.hammer.audio.workflow.collaboration.retention;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable, bounded retention plan created at one injected-clock instant.
 *
 * @param plannedAt logical time used to compute eligibility
 * @param publishedCutoff inclusive publication cutoff
 * @param batchSize configured maximum batch size
 * @param scannedCount number of published rows considered when the plan was created
 * @param candidates stable ordered candidates captured for preview and deletion
 */
public record WorkflowOutboxRetentionPlan(
    Instant plannedAt,
    Instant publishedCutoff,
    int batchSize,
    long scannedCount,
    List<WorkflowOutboxRetentionCandidate> candidates) {

  public WorkflowOutboxRetentionPlan {
    Objects.requireNonNull(plannedAt, "plannedAt");
    Objects.requireNonNull(publishedCutoff, "publishedCutoff");
    if (publishedCutoff.isAfter(plannedAt)) {
      throw new IllegalArgumentException("publishedCutoff must not be after plannedAt");
    }
    if (batchSize <= 0 || batchSize > WorkflowOutboxRetentionSettings.MAXIMUM_BATCH_SIZE) {
      throw new IllegalArgumentException(
          "batchSize must be between 1 and "
              + WorkflowOutboxRetentionSettings.MAXIMUM_BATCH_SIZE);
    }
    if (scannedCount < 0) {
      throw new IllegalArgumentException("scannedCount must be >= 0");
    }
    candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
    if (candidates.size() > batchSize) {
      throw new IllegalArgumentException("candidate count exceeds batchSize");
    }
    if (scannedCount < candidates.size()) {
      throw new IllegalArgumentException("scannedCount must be >= candidate count");
    }
    Set<String> eventIds = new HashSet<>();
    Instant previousPublication = null;
    String previousEventId = null;
    for (WorkflowOutboxRetentionCandidate candidate : candidates) {
      Objects.requireNonNull(candidate, "candidate");
      if (candidate.publishedAt().isAfter(publishedCutoff)) {
        throw new IllegalArgumentException(
            "candidate publication is after cutoff: " + candidate.eventId());
      }
      if (!eventIds.add(candidate.eventId())) {
        throw new IllegalArgumentException("duplicate candidate eventId: " + candidate.eventId());
      }
      if (previousPublication != null) {
        int timestampOrder = previousPublication.compareTo(candidate.publishedAt());
        if (timestampOrder > 0
            || (timestampOrder == 0 && previousEventId.compareTo(candidate.eventId()) > 0)) {
          throw new IllegalArgumentException(
              "candidates must be ordered by publishedAt and eventId");
        }
      }
      previousPublication = candidate.publishedAt();
      previousEventId = candidate.eventId();
    }
  }

  /** Number of entries evaluated as eligible when this plan was created. */
  public int eligibleCount() {
    return candidates.size();
  }

  /** Number of published rows that were not selected into this bounded plan. */
  public long ineligibleOrDeferredCount() {
    return scannedCount - candidates.size();
  }

  /** Oldest candidate publication time, when the plan is non-empty. */
  public Optional<Instant> oldestPublishedAt() {
    return candidates.isEmpty()
        ? Optional.empty()
        : Optional.of(candidates.getFirst().publishedAt());
  }

  /** Newest candidate publication time, when the plan is non-empty. */
  public Optional<Instant> newestPublishedAt() {
    return candidates.isEmpty()
        ? Optional.empty()
        : Optional.of(candidates.getLast().publishedAt());
  }

  /** Stable candidate identifiers in deletion order. */
  public List<String> candidateEventIds() {
    return candidates.stream().map(WorkflowOutboxRetentionCandidate::eventId).toList();
  }
}
