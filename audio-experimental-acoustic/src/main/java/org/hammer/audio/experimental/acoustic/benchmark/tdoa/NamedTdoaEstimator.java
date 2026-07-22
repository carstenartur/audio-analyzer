package org.hammer.audio.experimental.acoustic.benchmark.tdoa;

import java.util.Objects;
import org.hammer.audio.experimental.acoustic.TdoaEstimator;

/**
 * Named estimator registration for deterministic side-by-side benchmark reports.
 *
 * @param name stable algorithm name used in reports
 * @param estimator registered estimator implementation
 */
public record NamedTdoaEstimator(String name, TdoaEstimator estimator) {

  // Validate one estimator registration.
  public NamedTdoaEstimator {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    Objects.requireNonNull(estimator, "estimator");
  }
}
