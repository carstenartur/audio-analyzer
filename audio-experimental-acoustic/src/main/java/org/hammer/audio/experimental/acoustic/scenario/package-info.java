/**
 * Ground-truth scenario model for localization, tracking and classification evaluation.
 *
 * <p>The model is independent of UI code, dataset implementations and benchmark tooling. It is
 * designed to support partial ground truth so that real-world datasets with incomplete metadata can
 * still be expressed.
 *
 * <p>Key types:
 *
 * <ul>
 *   <li>{@link org.hammer.audio.experimental.acoustic.scenario.Scenario} – top-level container
 *   <li>{@link org.hammer.audio.experimental.acoustic.scenario.ScenarioSource} – ground truth for
 *       one acoustic source
 *   <li>{@link org.hammer.audio.experimental.acoustic.scenario.ScenarioTrajectory} – position and
 *       velocity truth
 *   <li>{@link org.hammer.audio.experimental.acoustic.scenario.AcousticGroundTruth} – frequency and
 *       signal truth
 *   <li>{@link org.hammer.audio.experimental.acoustic.scenario.ClassificationGroundTruth} –
 *       species, sex and custom labels
 * </ul>
 */
package org.hammer.audio.experimental.acoustic.scenario;
