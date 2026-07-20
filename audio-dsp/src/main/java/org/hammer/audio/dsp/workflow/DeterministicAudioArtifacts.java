package org.hammer.audio.dsp.workflow;

/** Stable textual artifact keys emitted by the deterministic audio workflow backend. */
public final class DeterministicAudioArtifacts {

  public static final String BACKEND_MODE = "backendMode";
  public static final String BACKEND_VERSION = "backendVersion";
  public static final String OUTPUT_NODE_ID = "output.nodeId";
  public static final String OUTPUT_PORT_ID = "output.portId";
  public static final String OUTPUT_DIGEST_ALGORITHM = "output.digest.algorithm";
  public static final String OUTPUT_DIGEST_ENCODING = "output.digest.encoding";
  public static final String OUTPUT_DIGEST_SHA256 = "output.digest.sha256";
  public static final String OUTPUT_SAMPLE_RATE_HZ = "output.sampleRateHz";
  public static final String OUTPUT_CHANNELS = "output.channels";
  public static final String OUTPUT_FRAMES = "output.frames";
  public static final String OUTPUT_MIN = "output.min";
  public static final String OUTPUT_MAX = "output.max";
  public static final String OUTPUT_MEAN = "output.mean";
  public static final String OUTPUT_RMS = "output.rms";
  public static final String OUTPUT_CHANNEL_ZERO_PREVIEW = "output.preview.channel0.hex";
  public static final String CANCELLED_AT_NODE = "cancelledAtNode";
  public static final String FAILED_NODE_ID = "failure.nodeId";
  public static final String FAILED_NODE_TYPE = "failure.nodeType";
  public static final String FAILURE_CLASS = "failure.class";
  public static final String FAILURE_MESSAGE = "failure.message";
  public static final String SKIPPED_NODE_IDS = "failure.skippedNodeIds";

  private DeterministicAudioArtifacts() {
    throw new UnsupportedOperationException("Utility class");
  }
}
