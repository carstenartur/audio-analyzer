package org.hammer.audio.workflow.catalog;

/** Stable node-type and port identifiers shared by catalog, editor and execution adapters. */
public final class ExperimentNodeProtocol {

  /** Deterministic synthetic signal source node type. */
  public static final String TYPE_SYNTHETIC_SIGNAL_GENERATOR = "synthetic-signal-generator";

  /** Linear gain node type. */
  public static final String TYPE_GAIN = "gain";

  /** Output port of a synthetic signal generator. */
  public static final String SIGNAL_OUTPUT_PORT = "signal-out";

  /** Shared audio input port used by DSP nodes. */
  public static final String AUDIO_INPUT_PORT = "audio-in";

  /** Shared audio output port used by DSP nodes. */
  public static final String AUDIO_OUTPUT_PORT = "audio-out";

  private ExperimentNodeProtocol() {
    throw new UnsupportedOperationException("Utility class");
  }
}
