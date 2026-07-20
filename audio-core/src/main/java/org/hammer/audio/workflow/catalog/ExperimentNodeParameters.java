package org.hammer.audio.workflow.catalog;

/** Stable metadata keys and values for executable experiment-node parameters. */
public interface ExperimentNodeParameters {

  /** Waveform selector on {@code synthetic-signal-generator} nodes. */
  String SIGNAL_WAVEFORM = "signal.waveform";

  /** Supported deterministic sine waveform value. */
  String WAVEFORM_SINE = "sine";

  /** Oscillator frequency in hertz. */
  String SIGNAL_FREQUENCY_HZ = "signal.frequency-hz";

  /** Initial oscillator phase in radians. */
  String SIGNAL_PHASE_RADIANS = "signal.phase-radians";

  /** Peak normalized oscillator amplitude. */
  String SIGNAL_AMPLITUDE = "signal.amplitude";

  /** Output sample rate in hertz. */
  String SIGNAL_SAMPLE_RATE_HZ = "signal.sample-rate-hz";

  /** Number of planar output channels. */
  String SIGNAL_CHANNELS = "signal.channels";

  /** Number of generated audio frames per channel. */
  String SIGNAL_FRAME_COUNT = "signal.frame-count";

  /** Linear multiplier on {@code gain} nodes. */
  String GAIN_FACTOR = "gain.factor";
}
