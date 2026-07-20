package org.hammer.audio.dsp.workflow;

import java.util.Map;
import org.hammer.audio.dsp.GainProcessor;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.catalog.ExperimentNodeParameters;

/** Parses and bounds executable node metadata for the deterministic audio slice. */
final class DeterministicAudioParameters {

  static final float MIN_SAMPLE_RATE_HZ = 8_000.0f;
  static final float MAX_SAMPLE_RATE_HZ = 384_000.0f;
  static final int MAX_CHANNELS = 32;
  static final int MAX_TOTAL_SAMPLES = 16_777_216;

  private DeterministicAudioParameters() {
    throw new UnsupportedOperationException("Utility class");
  }

  static SyntheticSignal parseSyntheticSignal(Node node) {
    Map<String, String> entries = metadataEntries(node);
    String waveform = required(entries, ExperimentNodeParameters.SIGNAL_WAVEFORM);
    if (!ExperimentNodeParameters.WAVEFORM_SINE.equals(waveform)) {
      throw invalid(
          ExperimentNodeParameters.SIGNAL_WAVEFORM,
          "must be '" + ExperimentNodeParameters.WAVEFORM_SINE + "', was '" + waveform + "'");
    }
    float sampleRate = finiteFloat(entries, ExperimentNodeParameters.SIGNAL_SAMPLE_RATE_HZ);
    if (sampleRate < MIN_SAMPLE_RATE_HZ || sampleRate > MAX_SAMPLE_RATE_HZ) {
      throw invalid(
          ExperimentNodeParameters.SIGNAL_SAMPLE_RATE_HZ,
          "must be in [8000, 384000], was " + sampleRate);
    }
    int channels = positiveInt(entries, ExperimentNodeParameters.SIGNAL_CHANNELS);
    if (channels > MAX_CHANNELS) {
      throw invalid(
          ExperimentNodeParameters.SIGNAL_CHANNELS,
          "must be <= " + MAX_CHANNELS + ", was " + channels);
    }
    int frameCount = positiveInt(entries, ExperimentNodeParameters.SIGNAL_FRAME_COUNT);
    long sampleCount = (long) channels * frameCount;
    if (sampleCount > MAX_TOTAL_SAMPLES) {
      throw invalid(
          ExperimentNodeParameters.SIGNAL_FRAME_COUNT,
          "channels * frame-count must be <= "
              + MAX_TOTAL_SAMPLES
              + ", was "
              + sampleCount);
    }
    float frequency = finiteFloat(entries, ExperimentNodeParameters.SIGNAL_FREQUENCY_HZ);
    if (!(frequency > 0.0f) || frequency >= sampleRate / 2.0f) {
      throw invalid(
          ExperimentNodeParameters.SIGNAL_FREQUENCY_HZ,
          "must be > 0 and below Nyquist " + (sampleRate / 2.0f) + ", was " + frequency);
    }
    double phase = finiteDouble(entries, ExperimentNodeParameters.SIGNAL_PHASE_RADIANS);
    float amplitude = finiteFloat(entries, ExperimentNodeParameters.SIGNAL_AMPLITUDE);
    if (amplitude < 0.0f || amplitude > 1.0f) {
      throw invalid(
          ExperimentNodeParameters.SIGNAL_AMPLITUDE,
          "must be in [0, 1], was " + amplitude);
    }
    return new SyntheticSignal(
        waveform, frequency, phase, amplitude, sampleRate, channels, frameCount);
  }

  static Gain parseGain(Node node) {
    float factor = finiteFloat(metadataEntries(node), ExperimentNodeParameters.GAIN_FACTOR);
    if (factor < 0.0f || factor > GainProcessor.MAX_GAIN) {
      throw invalid(
          ExperimentNodeParameters.GAIN_FACTOR,
          "must be in [0, " + GainProcessor.MAX_GAIN + "], was " + factor);
    }
    return new Gain(factor);
  }

  private static Map<String, String> metadataEntries(Node node) {
    Metadata metadata = node.metadata();
    return metadata == null ? Map.of() : metadata.entries();
  }

  private static String required(Map<String, String> entries, String key) {
    String value = entries.get(key);
    if (value == null || value.isBlank()) {
      throw invalid(key, "is required and must not be blank");
    }
    return value;
  }

  private static float finiteFloat(Map<String, String> entries, String key) {
    String value = required(entries, key);
    try {
      float parsed = Float.parseFloat(value);
      if (!Float.isFinite(parsed)) {
        throw invalid(key, "must be finite, was " + value);
      }
      return parsed;
    } catch (NumberFormatException exception) {
      throw invalid(key, "must be a floating-point number, was '" + value + "'", exception);
    }
  }

  private static double finiteDouble(Map<String, String> entries, String key) {
    String value = required(entries, key);
    try {
      double parsed = Double.parseDouble(value);
      if (!Double.isFinite(parsed)) {
        throw invalid(key, "must be finite, was " + value);
      }
      return parsed;
    } catch (NumberFormatException exception) {
      throw invalid(key, "must be a floating-point number, was '" + value + "'", exception);
    }
  }

  private static int positiveInt(Map<String, String> entries, String key) {
    String value = required(entries, key);
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < 1) {
        throw invalid(key, "must be >= 1, was " + parsed);
      }
      return parsed;
    } catch (NumberFormatException exception) {
      throw invalid(key, "must be an integer, was '" + value + "'", exception);
    }
  }

  private static IllegalArgumentException invalid(String key, String detail) {
    return new IllegalArgumentException("Parameter '" + key + "' " + detail);
  }

  private static IllegalArgumentException invalid(
      String key, String detail, NumberFormatException cause) {
    return new IllegalArgumentException("Parameter '" + key + "' " + detail, cause);
  }

  record SyntheticSignal(
      String waveform,
      float frequencyHz,
      double phaseRadians,
      float amplitude,
      float sampleRateHz,
      int channels,
      int frameCount) {}

  record Gain(float factor) {}
}
