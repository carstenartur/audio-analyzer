package org.hammer.audio.experiment.document.binding;

import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import org.hammer.audio.JavaSoundCaptureDeviceDiscovery;
import org.hammer.audio.experiment.document.ExperimentCaptureBinding;
import org.hammer.audio.experiment.document.ExperimentDocument;
import org.hammer.audio.plugin.document.DocumentValue;

/** Checks advertised JavaSound capabilities without acquiring a line or opening a device. */
public final class ExperimentCaptureBindingResolver {

  /** Resolve an explicitly selected provider id and verify the required PCM capability. */
  public ExperimentCaptureBinding resolve(
      ExperimentDocument document,
      String deviceId,
      int deviceChannels,
      List<Integer> mapping,
      boolean synchronizationConfirmed,
      boolean calibrationConfirmed) {
    if (deviceId == null || deviceId.isBlank()) {
      return null;
    }
    javax.sound.sampled.Mixer.Info mixer =
        new JavaSoundCaptureDeviceDiscovery()
            .findMixerInfo(deviceId)
            .orElseThrow(
                () -> new IllegalArgumentException("Selected capture device is unavailable"));
    if (!(document.profiles().fields().get("capture")
        instanceof DocumentValue.ObjectValue capture)) {
      throw new IllegalArgumentException("Document does not declare a capture profile");
    }
    double rate =
        ((DocumentValue.NumberValue) capture.fields().get("sampleRateHz")).value().doubleValue();
    int bits =
        ((DocumentValue.NumberValue) capture.fields().get("sampleSizeBits"))
            .value()
            .intValueExact();
    boolean signed = ((DocumentValue.BooleanValue) capture.fields().get("signed")).value();
    boolean endian = ((DocumentValue.BooleanValue) capture.fields().get("bigEndian")).value();
    ExperimentCaptureBinding binding =
        new ExperimentCaptureBinding(
            deviceId,
            rate,
            deviceChannels,
            bits,
            signed,
            endian,
            synchronizationConfirmed,
            mapping,
            calibrationConfirmed);
    AudioFormat format = new AudioFormat((float) rate, bits, deviceChannels, signed, endian);
    if (!AudioSystem.getMixer(mixer)
        .isLineSupported(new DataLine.Info(TargetDataLine.class, format))) {
      throw new IllegalArgumentException(
          "Selected device does not advertise the required PCM format and channel count");
    }
    return binding;
  }
}
