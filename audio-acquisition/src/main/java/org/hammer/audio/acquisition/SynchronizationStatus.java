package org.hammer.audio.acquisition;

/** Trust decision derived from synchronization calibration and a caller-provided error budget. */
public enum SynchronizationStatus {
  /** Calibration is current and remains comfortably inside the timing-error budget. */
  TRUSTED,
  /** Calibration is usable but consumes more than half of the timing-error budget. */
  DEGRADED,
  /** Calibration is expired, incomplete or exceeds the timing-error budget. */
  REJECTED
}
