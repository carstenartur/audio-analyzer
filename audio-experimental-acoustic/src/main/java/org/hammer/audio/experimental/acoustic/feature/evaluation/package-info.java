/**
 * Feature evaluation report for wingbeat acoustic feature vectors.
 *
 * <p>This package provides per-feature descriptive statistics and class-separation analysis over a
 * labelled collection of {@link
 * org.hammer.audio.experimental.acoustic.wingbeat.WingbeatFeatureVector}s:
 *
 * <pre>
 *   List&lt;WingbeatFeatureVector&gt; + labels
 *       -&gt; {@link org.hammer.audio.experimental.acoustic.feature.evaluation.FeatureEvaluationService}
 *       -&gt; {@link org.hammer.audio.experimental.acoustic.feature.evaluation.FeatureEvaluationReport}
 *           (one {@link org.hammer.audio.experimental.acoustic.feature.evaluation.FeatureEvaluationEntry} per feature)
 * </pre>
 *
 * <p>Results are consumed by the feature ranking and synthetic-vs-real comparison packages.
 */
package org.hammer.audio.experimental.acoustic.feature.evaluation;
