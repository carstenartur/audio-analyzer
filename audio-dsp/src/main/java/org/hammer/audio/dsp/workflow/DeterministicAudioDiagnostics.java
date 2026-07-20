package org.hammer.audio.dsp.workflow;

/** Stable diagnostic codes produced by the deterministic audio backend. */
public final class DeterministicAudioDiagnostics {

  public static final String UNSUPPORTED_NODE = "UNSUPPORTED_NODE";
  public static final String INVALID_PARAMETER = "INVALID_PARAMETER";
  public static final String INVALID_TOPOLOGY = "INVALID_TOPOLOGY";

  private DeterministicAudioDiagnostics() {
    throw new UnsupportedOperationException("Utility class");
  }
}
