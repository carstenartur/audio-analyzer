package org.hammer.audio.acquisition;

/** Declares how inter-channel timing is modeled for one microphone-array observation. */
public enum SynchronizationMode {
  /** All channels are assumed to originate from one hardware sample clock. */
  NOMINAL_SHARED_CLOCK,
  /** Static per-channel timing offsets are calibrated. */
  CALIBRATED_OFFSET,
  /** Static offsets and affine sample-rate drift are calibrated. */
  DRIFT_COMPENSATED
}
