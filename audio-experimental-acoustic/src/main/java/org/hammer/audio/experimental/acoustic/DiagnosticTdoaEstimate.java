package org.hammer.audio.experimental.acoustic;

import java.util.Objects;

/**
 * Estimate plus the confidence evidence used to accept or reject its selected peak.
 *
 * @param estimate selected TDOA estimate
 * @param diagnostics peak evidence supporting or rejecting the estimate
 */
public record DiagnosticTdoaEstimate(TdoaEstimate estimate, TdoaPeakDiagnostics diagnostics) {

  // Validate one diagnostic estimate.
  public DiagnosticTdoaEstimate {
    Objects.requireNonNull(estimate, "estimate");
    Objects.requireNonNull(diagnostics, "diagnostics");
  }
}
