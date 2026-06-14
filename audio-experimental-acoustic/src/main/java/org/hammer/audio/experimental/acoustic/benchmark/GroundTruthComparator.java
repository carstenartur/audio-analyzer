package org.hammer.audio.experimental.acoustic.benchmark;

/**
 * Minimal extension point for benchmark comparisons between measured output and ground truth.
 *
 * @param <TTruth> ground-truth input type
 * @param <TMeasurement> measured result type
 * @param <TMetric> comparison metric type
 */
@FunctionalInterface
public interface GroundTruthComparator<TTruth, TMeasurement, TMetric> {

  /** Compare one measured result against its ground truth. */
  TMetric compare(TTruth truth, TMeasurement measurement);
}
