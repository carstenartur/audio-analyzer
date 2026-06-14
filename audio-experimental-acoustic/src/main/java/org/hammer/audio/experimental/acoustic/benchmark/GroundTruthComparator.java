package org.hammer.audio.experimental.acoustic.benchmark;

/**
 * Minimal extension point for benchmark comparisons between measured output and ground truth.
 *
 * @param <T> ground-truth input type
 * @param <M> measured result type
 * @param <R> comparison metric type
 */
@FunctionalInterface
public interface GroundTruthComparator<T, M, R> {

  /** Compare one measured result against its ground truth. */
  R compare(T truth, M measurement);
}
