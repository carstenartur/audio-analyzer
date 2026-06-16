package org.hammer.audio.experimental.acoustic.feature.ranking;

import java.util.Map;
import org.hammer.audio.experimental.acoustic.feature.evaluation.ClassSeparationScore;
import org.hammer.audio.experimental.acoustic.feature.evaluation.FeatureEvaluationEntry;

/**
 * Estimates feature utility with a Fisher-weighted entropy proxy.
 *
 * <p>This scorer computes the class-label prior entropy {@code H(Y)} from {@link
 * ClassSeparationScore#classCounts()} and then scales it by {@code fisherRatio / (1 +
 * fisherRatio)}. It is an information-gain-inspired heuristic, not an exact information gain
 * computation.
 */
public final class InformationGainScorer implements FeatureScorer {

  private static final double LOG2 = Math.log(2.0);

  @Override
  public double score(FeatureEvaluationEntry entry) {
    ClassSeparationScore sep = entry.separation();
    Map<String, Integer> counts = sep.classCounts();

    int total = 0;
    for (int c : counts.values()) {
      total += c;
    }
    if (total == 0) {
      return 0.0;
    }

    // H(Y): entropy of the marginal class distribution
    double priorEntropy = 0.0;
    for (int c : counts.values()) {
      if (c > 0) {
        double p = c / (double) total;
        priorEntropy -= p * (Math.log(p) / LOG2);
      }
    }

    // Approximation: features with high Fisher ratio reduce entropy proportionally.
    // IG ≈ priorEntropy * (fisherRatio / (1 + fisherRatio))
    double fisher = sep.fisherRatio();
    return priorEntropy * (fisher / (1.0 + fisher));
  }

  @Override
  public String name() {
    return "informationGain";
  }
}
