package org.hammer.audio.workflow.catalog;

/** Stable metadata keys and values for executable experiment-node parameters. */
public final class ExperimentNodeParameters {

  /** Waveform selector on {@code synthetic-signal-generator} nodes. */
  public static final String SIGNAL_WAVEFORM = "signal.waveform";

  /** Supported deterministic sine waveform value. */
  public static final String WAVEFORM_SINE = "sine";

  /** Oscillator frequency in hertz. */
  public static final String SIGNAL_FREQUENCY_HZ = "signal.frequency-hz";

  /** Initial oscillator phase in radians. */
  public static final String SIGNAL_PHASE_RADIANS = "signal.phase-radians";

  /** Peak normalized oscillator amplitude. */
  public static final String SIGNAL_AMPLITUDE = "signal.amplitude";

  /** Output sample rate in hertz. */
  public static final String SIGNAL_SAMPLE_RATE_HZ = "signal.sample-rate-hz";

  /** Number of planar output channels. */
  public static final String SIGNAL_CHANNELS = "signal.channels";

  /** Number of generated audio frames per channel. */
  public static final String SIGNAL_FRAME_COUNT = "signal.frame-count";

  /** Linear multiplier on {@code gain} nodes. */
  public static final String GAIN_FACTOR = "gain.factor";

  private ExperimentNodeParameters() {
    throw new UnsupportedOperationException("Utility class");
  }
}
