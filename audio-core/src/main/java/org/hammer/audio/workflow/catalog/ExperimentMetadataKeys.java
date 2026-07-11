package org.hammer.audio.workflow.catalog;

/**
 * Standard metadata key constants for experiment nodes.
 *
 * <p>These keys are used in {@code Metadata} entries on workflow graph elements ({@code Node},
 * {@code Workflow}, {@code Port}, {@code Edge}) to carry experiment-specific configuration that
 * does not belong in the structural workflow model. They are the answer to the spike question:
 * "What minimal metadata is missing for experiment setup, datasets, calibration and outputs?"
 * (issue #214).
 *
 * <p>All keys are stable identifiers matching {@code [A-Za-z0-9._:-]*} so they satisfy the {@code
 * StableIds} contract enforced by {@code Metadata}.
 *
 * <p><b>Design rule</b>: add a new constant here rather than inventing an ad-hoc string in
 * application code. This keeps the set of known keys explicit, searchable and testable.
 *
 * <p><b>Layer</b>: workflow domain. Must not depend on UI, execution runtime, persistence or JGit.
 */
public final class ExperimentMetadataKeys {

  // -------------------------------------------------------------------------
  // Experiment setup
  // -------------------------------------------------------------------------

  /**
   * Human-readable experiment description.
   *
   * <p>Example value: {@code "Baseline wingbeat classifier on HumBugDB 2024"}.
   */
  public static final String EXPERIMENT_DESCRIPTION = "experiment.description";

  /**
   * Experiment version tag, used for comparison between runs.
   *
   * <p>Example value: {@code "v1.0"}.
   */
  public static final String EXPERIMENT_VERSION = "experiment.version";

  // -------------------------------------------------------------------------
  // Dataset provenance
  // -------------------------------------------------------------------------

  /**
   * Identifier of the dataset source.
   *
   * <p>Example value: {@code "humbugdb-2024"} or {@code "field-recording-batch-07"}.
   */
  public static final String DATASET_SOURCE = "dataset.source";

  /**
   * Audio sample rate in Hz.
   *
   * <p>Example value: {@code "8000"}.
   */
  public static final String DATASET_SAMPLE_RATE_HZ = "dataset.sample-rate-hz";

  /**
   * Number of samples in the dataset.
   *
   * <p>Example value: {@code "1200"}.
   */
  public static final String DATASET_SAMPLE_COUNT = "dataset.sample-count";

  // -------------------------------------------------------------------------
  // Calibration
  // -------------------------------------------------------------------------

  /**
   * Name of the calibration preset applied to a generator or DSP node.
   *
   * <p>Example value: {@code "wingbeat-default-v2"}.
   */
  public static final String CALIBRATION_PRESET = "calibration.preset";

  /**
   * Lower bound of the frequency range of interest, in Hz.
   *
   * <p>Example value: {@code "100"}.
   */
  public static final String CALIBRATION_FREQ_MIN_HZ = "calibration.freq-min-hz";

  /**
   * Upper bound of the frequency range of interest, in Hz.
   *
   * <p>Example value: {@code "1000"}.
   */
  public static final String CALIBRATION_FREQ_MAX_HZ = "calibration.freq-max-hz";

  // -------------------------------------------------------------------------
  // Output configuration
  // -------------------------------------------------------------------------

  /**
   * Output format identifier used by sink nodes (e.g. {@code EvidenceExport} or {@code Report}).
   *
   * <p>Example value: {@code "json"} or {@code "csv"}.
   */
  public static final String OUTPUT_FORMAT = "output.format";

  /**
   * Target file path or directory for export sink nodes.
   *
   * <p>Example value: {@code "export/evidence"}.
   */
  public static final String OUTPUT_PATH = "output.path";

  private ExperimentMetadataKeys() {
    // constants class
  }
}
