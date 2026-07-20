package org.hammer.audio.dsp.workflow;

/** Stable textual artifact keys emitted by the deterministic audio workflow backend. */
public interface DeterministicAudioArtifacts {

  String BACKEND_MODE = "backendMode";
  String BACKEND_VERSION = "backendVersion";
  String OUTPUT_NODE_ID = "output.nodeId";
  String OUTPUT_PORT_ID = "output.portId";
  String OUTPUT_DIGEST_ALGORITHM = "output.digest.algorithm";
  String OUTPUT_DIGEST_ENCODING = "output.digest.encoding";
  String OUTPUT_DIGEST_SHA256 = "output.digest.sha256";
  String OUTPUT_SAMPLE_RATE_HZ = "output.sampleRateHz";
  String OUTPUT_CHANNELS = "output.channels";
  String OUTPUT_FRAMES = "output.frames";
  String OUTPUT_MIN = "output.min";
  String OUTPUT_MAX = "output.max";
  String OUTPUT_MEAN = "output.mean";
  String OUTPUT_RMS = "output.rms";
  String OUTPUT_CHANNEL_ZERO_PREVIEW = "output.preview.channel0.hex";
  String CANCELLED_AT_NODE = "cancelledAtNode";
  String FAILED_NODE_ID = "failure.nodeId";
  String FAILED_NODE_TYPE = "failure.nodeType";
  String FAILURE_CLASS = "failure.class";
  String FAILURE_MESSAGE = "failure.message";
  String SKIPPED_NODE_IDS = "failure.skippedNodeIds";
}
