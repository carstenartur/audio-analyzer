package org.hammer.audio.experimental.acoustic;

import java.util.List;
import java.util.Objects;

/**
 * One deterministic physical or cycle-consistency problem in a set of pairwise TDOA estimates.
 *
 * @param kind finding classification
 * @param microphoneIds ordered microphones participating in the check
 * @param residualSeconds signed excess or cycle residual in seconds
 * @param toleranceSeconds configured absolute tolerance in seconds
 */
public record TdoaConsistencyFinding(
    Kind kind, List<String> microphoneIds, double residualSeconds, double toleranceSeconds) {

  /** Finding classification. */
  public enum Kind {
    /** Pair delay exceeds the maximum acoustic path difference for the microphone spacing. */
    PHYSICAL_LIMIT,
    /** Three pair estimates violate the additive delay cycle around one microphone triple. */
    CYCLE_RESIDUAL
  }

  // Validate and defensively copy one finding.
  public TdoaConsistencyFinding {
    Objects.requireNonNull(kind, "kind");
    microphoneIds = List.copyOf(Objects.requireNonNull(microphoneIds, "microphoneIds"));
    if (microphoneIds.size() < 2
        || microphoneIds.stream().anyMatch(id -> id == null || id.isBlank())) {
      throw new IllegalArgumentException("microphoneIds must contain at least two non-blank ids");
    }
    if (!Double.isFinite(residualSeconds)) {
      throw new IllegalArgumentException("residualSeconds must be finite");
    }
    if (!(toleranceSeconds > 0.0) || !Double.isFinite(toleranceSeconds)) {
      throw new IllegalArgumentException("toleranceSeconds must be finite and > 0");
    }
  }

  /** Absolute residual normalized to the configured tolerance. */
  public double normalizedSeverity() {
    return Math.abs(residualSeconds) / toleranceSeconds;
  }
}
