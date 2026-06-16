/**
 * Feature ranking for wingbeat acoustic feature vectors.
 *
 * <p>Scorers rank features by their estimated discriminative utility using statistical methods only
 * (no machine learning):
 *
 * <ul>
 *   <li>{@link org.hammer.audio.experimental.acoustic.feature.ranking.VarianceBetweenClassesScorer}
 *   <li>{@link org.hammer.audio.experimental.acoustic.feature.ranking.FisherScorer}
 *   <li>{@link org.hammer.audio.experimental.acoustic.feature.ranking.InformationGainScorer}
 * </ul>
 *
 * <p>The framework is extensible: any {@link
 * org.hammer.audio.experimental.acoustic.feature.ranking.FeatureScorer} implementation can be
 * registered with {@link
 * org.hammer.audio.experimental.acoustic.feature.ranking.FeatureRankingService}.
 */
package org.hammer.audio.experimental.acoustic.feature.ranking;
