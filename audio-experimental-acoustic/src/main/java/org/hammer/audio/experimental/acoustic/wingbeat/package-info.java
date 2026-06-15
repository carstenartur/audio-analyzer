/**
 * Wingbeat feature extraction and acoustic classification for narrowband tonal sources.
 *
 * <p>This package provides a reusable processing chain:
 *
 * <pre>
 *   AudioBlock / TrackedSource
 *       -&gt; {@link org.hammer.audio.experimental.acoustic.wingbeat.WingbeatFeatureExtractor}
 *       -&gt; {@link org.hammer.audio.experimental.acoustic.wingbeat.WingbeatFeatureVector}
 *       -&gt; {@link org.hammer.audio.experimental.acoustic.wingbeat.WingbeatClassifier}
 *       -&gt; {@link org.hammer.audio.experimental.acoustic.wingbeat.ClassificationResult}
 * </pre>
 *
 * <p>The rule-based baseline ({@link
 * org.hammer.audio.experimental.acoustic.wingbeat.RuleBasedWingbeatClassifier}) uses published
 * mosquito wingbeat frequency ranges as transparent, reproducible thresholds. Any classifier
 * implementing {@link org.hammer.audio.experimental.acoustic.wingbeat.WingbeatClassifier} can be
 * evaluated against a {@link org.hammer.audio.experimental.acoustic.wingbeat.WingbeatDataset} of
 * labelled recordings.
 *
 * <p>Design constraints:
 *
 * <ul>
 *   <li>The feature model is generic and reusable for insects and other narrowband emitters.
 *   <li>No opaque ML-only solutions; the baseline remains fully transparent and deterministic.
 *   <li>Classification claims must be evidence-based and benchmarkable.
 *   <li>No pest-control functionality.
 *   <li>No biological manipulation workflows.
 * </ul>
 */
package org.hammer.audio.experimental.acoustic.wingbeat;
