package org.hammer.audio.acquisition;

/** Origin of synchronized multichannel samples used by a localization experiment. */
public enum LocalizationInputMode {
  /** Deterministic or externally supplied simulation source. */
  SIMULATION,
  /** Previously recorded synchronized multichannel material. */
  REPLAY,
  /** A currently connected physical capture device. */
  LIVE
}
