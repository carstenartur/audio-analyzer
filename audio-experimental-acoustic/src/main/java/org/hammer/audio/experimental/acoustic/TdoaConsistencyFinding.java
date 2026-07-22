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
    List<String> requiredIds = Objects.requireNonNull(microphoneIds, "microphoneIds");
    if (requiredIds.size() < 2 || requiredIds.stream().anyMatch(id -> id == null || id.isBlank())) {
      throw new IllegalArgumentException("microphoneIds must contain at least two non-blank ids");
    }
    microphoneIds = List.copyOf(requiredIds);
    requireFinite(residualSeconds, "residualSeconds");
    requirePositiveFinite(toleranceSeconds, "toleranceSeconds");
  }

  /** Absolute residual normalized to the configured tolerance. */
  public double normalizedSeverity() {
    return Math.abs(residualSeconds) / toleranceSeconds;
  }

  private static void requireFinite(double value, String name) {
    if (Double.isFinite(value)) {
      return;
    }
    throw new IllegalArgumentException(name + " must be finite");
  }

  private static void requirePositiveFinite(double value, String name) {
    if (Double.isFinite(value) && value > 0.0) {
      return;
    }
    throw new IllegalArgumentException(name + " must be finite and > 0");
  }
}
