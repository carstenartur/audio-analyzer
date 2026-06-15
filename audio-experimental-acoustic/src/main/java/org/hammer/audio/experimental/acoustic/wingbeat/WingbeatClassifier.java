package org.hammer.audio.experimental.acoustic.wingbeat;

/**
 * Contract for wingbeat classifiers.
 *
 * <p>Implementations receive a {@link WingbeatFeatureVector} and return a {@link
 * ClassificationResult} containing a label and a confidence score. All implementations must handle
 * any non-null feature vector without throwing.
 *
 * <p>The interface is intentionally minimal so that both rule-based and ML-based classifiers can be
 * evaluated through the same {@link WingbeatDataset} benchmarking framework.
 */
@FunctionalInterface
public interface WingbeatClassifier {

  /**
   * Classify a wingbeat feature vector.
   *
   * @param features the extracted wingbeat feature vector; must not be {@code null}
   * @return classification result; never {@code null}
   */
  ClassificationResult classify(WingbeatFeatureVector features);
}
