package org.hammer.audio.workflow.catalog;

/** Stable node-type and port identifiers shared by catalog, editor and execution adapters. */
public interface ExperimentNodeProtocol {

  /** Deterministic synthetic signal source node type. */
  String TYPE_SYNTHETIC_SIGNAL_GENERATOR = "synthetic-signal-generator";

  /** Linear gain node type. */
  String TYPE_GAIN = "gain";

  /** Output port of a synthetic signal generator. */
  String SIGNAL_OUTPUT_PORT = "signal-out";

  /** Shared audio input port used by DSP nodes. */
  String AUDIO_INPUT_PORT = "audio-in";

  /** Shared audio output port used by DSP nodes. */
  String AUDIO_OUTPUT_PORT = "audio-out";
}
