package org.hammer.audio.experimental.acoustic.benchmark.classifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.experimental.acoustic.wingbeat.ClassificationResult;
import org.hammer.audio.experimental.acoustic.wingbeat.RuleBasedWingbeatClassifier;
import org.hammer.audio.experimental.acoustic.wingbeat.WingbeatFeatureVector;

/**
 * {@link ClassifierBenchmark} implementation for the {@link RuleBasedWingbeatClassifier}.
 *
 * <p>Each feature vector is passed to a fresh (stateless) {@link RuleBasedWingbeatClassifier}
 * instance. The predicted label is compared against the ground-truth label to build a confusion
 * matrix.
 */
public final class RuleBasedClassifierBenchmark implements ClassifierBenchmark {

  private final RuleBasedWingbeatClassifier classifier = new RuleBasedWingbeatClassifier();

  @Override
  public ClassifierBenchmarkResult run(List<WingbeatFeatureVector> vectors, List<String> labels) {
    Objects.requireNonNull(vectors, "vectors");
    Objects.requireNonNull(labels, "labels");
    if (vectors.isEmpty()) {
      throw new IllegalArgumentException("vectors must not be empty");
    }
    if (vectors.size() != labels.size()) {
      throw new IllegalArgumentException("vectors and labels must have the same size");
    }

    List<String> predicted = new ArrayList<>(vectors.size());
    for (WingbeatFeatureVector fv : vectors) {
      ClassificationResult result = classifier.classify(fv);
      predicted.add(result.label());
    }
    ConfusionMatrix matrix = ConfusionMatrix.of(labels, predicted);
    return ClassifierBenchmarkResult.of(matrix);
  }

  @Override
  public String name() {
    return "RuleBasedWingbeatClassifier";
  }
}
