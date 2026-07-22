package org.hammer.audio.acquisition;

/** Ordered lifecycle of a reproducible localization experiment. */
public enum LocalizationExperimentStage {
  /** Array, source and experiment identity have been defined. */
  DEFINED,
  /** Synchronization and calibration readiness have been assessed. */
  CALIBRATED,
  /** Source material has been captured, selected or generated. */
  RECORDED,
  /** Localization snapshots have been produced. */
  LOCALIZED,
  /** Outputs have been compared with available evidence or ground truth. */
  BENCHMARKED,
  /** Reproducibility metadata and results have been exported. */
  EXPORTED
}
