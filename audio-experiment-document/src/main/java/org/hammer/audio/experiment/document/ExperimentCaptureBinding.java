package org.hammer.audio.experiment.document;

import java.util.HashSet;
import java.util.List;

/**
 * Locally selected capture capabilities and explicit logical-to-physical channel mapping.
 *
 * @param deviceId explicitly selected local device identity
 * @param sampleRateHz advertised sample rate
 * @param channels physical capture channel count
 * @param sampleSizeBits PCM sample width
 * @param signed whether PCM samples are signed
 * @param bigEndian PCM byte order
 * @param synchronizedChannels explicit synchronization confirmation
 * @param channelMapping unique physical index for each logical channel
 * @param calibrationConfirmed confirmation of calibration applicability to this device and mapping
 */
public record ExperimentCaptureBinding(
    String deviceId,
    double sampleRateHz,
    int channels,
    int sampleSizeBits,
    boolean signed,
    boolean bigEndian,
    boolean synchronizedChannels,
    List<Integer> channelMapping,
    boolean calibrationConfirmed) {

  /* Require a finite format and a one-to-one mapping within the selected device. */
  public ExperimentCaptureBinding {
    if (deviceId == null
        || deviceId.isBlank()
        || !Double.isFinite(sampleRateHz)
        || sampleRateHz <= 0
        || channels < 1
        || channels > 256
        || !List.of(8, 16, 24, 32).contains(sampleSizeBits)) {
      throw new IllegalArgumentException("Invalid local capture capabilities");
    }
    channelMapping = List.copyOf(channelMapping);
    if (new HashSet<>(channelMapping).size() != channelMapping.size()
        || channelMapping.stream().anyMatch(channel -> channel < 0 || channel >= channels)) {
      throw new IllegalArgumentException(
          "Capture mapping must contain unique physical channel indices");
    }
  }
}
