package org.hammer.audio.experimental.acoustic;

import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.core.AudioBlock;

/** TDOA strategy that exposes peak evidence in addition to the compatibility estimate. */
@FunctionalInterface
public interface DiagnosticTdoaEstimator extends TdoaEstimator {

  /** Estimates TDOA and returns the peak evidence used to derive confidence. */
  DiagnosticTdoaEstimate estimateDetailed(
      AudioBlock block, MicrophoneArray array, int firstChannel, int secondChannel);

  @Override
  default TdoaEstimate estimate(
      AudioBlock block, MicrophoneArray array, int firstChannel, int secondChannel) {
    return estimateDetailed(block, array, firstChannel, secondChannel).estimate();
  }

  /** Returns an estimate only when its configured ambiguity policy accepts the selected peak. */
  default TdoaEstimate estimateReliable(
      AudioBlock block, MicrophoneArray array, int firstChannel, int secondChannel) {
    DiagnosticTdoaEstimate detailed = estimateDetailed(block, array, firstChannel, secondChannel);
    if (detailed.diagnostics().ambiguous()) {
      throw new AmbiguousTdoaEstimateException(
          "Ambiguous TDOA peak: ratio="
              + detailed.diagnostics().peakRatio()
              + ", curvature="
              + detailed.diagnostics().normalizedCurvature());
    }
    return detailed.estimate();
  }
}
