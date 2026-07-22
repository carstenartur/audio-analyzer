package org.hammer.audio.acquisition;

import java.io.IOException;
import java.io.StringReader;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.hammer.audio.core.AudioFormatDescriptor;
import org.hammer.audio.geometry.Vector2;

/** Deterministic text codec for reusable microphone-array profiles. */
public final class MicrophoneArrayProfileCodec {

  private static final String SCHEMA_VERSION = "1";

  /** Encode a profile without timestamps or platform-dependent ordering. */
  public String encode(MicrophoneArrayProfile profile) {
    Map<String, String> values = new TreeMap<>();
    put(values, "schema", SCHEMA_VERSION);
    put(values, "profile.id", profile.profileId());
    put(values, "profile.name", profile.displayName());
    put(values, "profile.layout", profile.layout().name());
    put(
        values,
        "profile.modes",
        profile.supportedModes().stream()
            .map(Enum::name)
            .sorted()
            .collect(Collectors.joining(",")));
    writeArray(values, profile.array());
    writeCapture(values, profile.liveCapture());
    writeCalibration(values, profile.calibration());
    return values.entrySet().stream()
        .map(entry -> entry.getKey() + '=' + entry.getValue())
        .collect(Collectors.joining("\n", "", "\n"));
  }

  /** Decode one profile produced by {@link #encode(MicrophoneArrayProfile)}. */
  public MicrophoneArrayProfile decode(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      throw new IllegalArgumentException("encoded profile must not be blank");
    }
    Properties properties = new Properties();
    try {
      properties.load(new StringReader(encoded));
    } catch (IOException exception) {
      throw new IllegalArgumentException("profile text could not be read", exception);
    }
    requireValue(properties, "schema", SCHEMA_VERSION);
    MicrophoneArray array = readArray(properties);
    CaptureDeviceConfiguration capture = readCapture(properties);
    MicrophoneArrayCalibration calibration = readCalibration(properties, array);
    return new MicrophoneArrayProfile(
        value(properties, "profile.id"),
        value(properties, "profile.name"),
        MicrophoneArrayLayout.valueOf(value(properties, "profile.layout")),
        array,
        readModes(properties),
        capture,
        calibration);
  }

  private static void writeArray(Map<String, String> values, MicrophoneArray array) {
    put(values, "array.channels", Integer.toString(array.channels()));
    for (int index = 0; index < array.channels(); index++) {
      Microphone microphone = array.microphone(index);
      String prefix = "array.microphone." + index + '.';
      put(values, prefix + "id", microphone.id());
      put(values, prefix + "channel", Integer.toString(microphone.channel()));
      put(values, prefix + "x", Double.toString(microphone.positionMeters().x()));
      put(values, prefix + "y", Double.toString(microphone.positionMeters().y()));
    }
  }

  private static MicrophoneArray readArray(Properties properties) {
    int channels = integer(properties, "array.channels");
    List<Microphone> microphones = new ArrayList<>(channels);
    for (int index = 0; index < channels; index++) {
      String prefix = "array.microphone." + index + '.';
      microphones.add(
          new Microphone(
              value(properties, prefix + "id"),
              new Vector2(decimal(properties, prefix + "x"), decimal(properties, prefix + "y")),
              integer(properties, prefix + "channel")));
    }
    return new MicrophoneArray(microphones);
  }

  private static void writeCapture(
      Map<String, String> values, CaptureDeviceConfiguration configuration) {
    put(values, "capture.present", Boolean.toString(configuration != null));
    if (configuration == null) {
      return;
    }
    CaptureDeviceDescriptor device = configuration.device();
    put(values, "capture.device.id", device.deviceId());
    put(values, "capture.device.name", device.name());
    put(values, "capture.device.vendor", device.vendor());
    put(values, "capture.device.description", device.description());
    put(values, "capture.device.version", device.version());
    put(values, "capture.sampleRate", Float.toString(configuration.format().sampleRate()));
    put(values, "capture.channels", Integer.toString(configuration.format().channels()));
    put(
        values,
        "capture.sampleBits",
        Integer.toString(configuration.format().sourceSampleSizeInBits()));
    put(values, "capture.signed", Boolean.toString(configuration.signed()));
    put(values, "capture.bigEndian", Boolean.toString(configuration.bigEndian()));
  }

  private static CaptureDeviceConfiguration readCapture(Properties properties) {
    if (!flag(properties, "capture.present")) {
      return null;
    }
    CaptureDeviceDescriptor device =
        new CaptureDeviceDescriptor(
            value(properties, "capture.device.id"),
            value(properties, "capture.device.name"),
            value(properties, "capture.device.vendor"),
            value(properties, "capture.device.description"),
            value(properties, "capture.device.version"));
    AudioFormatDescriptor format =
        new AudioFormatDescriptor(
            floating(properties, "capture.sampleRate"),
            integer(properties, "capture.channels"),
            integer(properties, "capture.sampleBits"));
    return new CaptureDeviceConfiguration(
        device, format, flag(properties, "capture.signed"), flag(properties, "capture.bigEndian"));
  }

  private static void writeCalibration(
      Map<String, String> values, MicrophoneArrayCalibration calibration) {
    put(values, "calibration.present", Boolean.toString(calibration != null));
    if (calibration == null) {
      return;
    }
    put(values, "calibration.id", calibration.profileId());
    put(values, "calibration.referenceChannel", Integer.toString(calibration.referenceChannel()));
    put(values, "calibration.calibratedAt", calibration.calibratedAt().toString());
    put(values, "calibration.validUntil", calibration.validUntil().toString());
    for (ChannelTimingCalibration channel : calibration.channels()) {
      String prefix = "calibration.channel." + channel.channel() + '.';
      put(values, prefix + "referenceFrame", Long.toString(channel.referenceFrame()));
      put(values, prefix + "offsetSamples", Double.toString(channel.offsetSamples()));
      put(values, prefix + "driftPpm", Double.toString(channel.driftPpm()));
      put(values, prefix + "residualRmsSamples", Double.toString(channel.residualRmsSamples()));
      put(values, prefix + "jitterRmsSamples", Double.toString(channel.jitterRmsSamples()));
      put(values, prefix + "gainLinear", Double.toString(channel.gainLinear()));
      put(values, prefix + "invertedPolarity", Boolean.toString(channel.invertedPolarity()));
    }
  }

  private static MicrophoneArrayCalibration readCalibration(
      Properties properties, MicrophoneArray array) {
    if (!flag(properties, "calibration.present")) {
      return null;
    }
    List<ChannelTimingCalibration> channels = new ArrayList<>(array.channels());
    for (int channel = 0; channel < array.channels(); channel++) {
      String prefix = "calibration.channel." + channel + '.';
      channels.add(
          new ChannelTimingCalibration(
              channel,
              longValue(properties, prefix + "referenceFrame"),
              decimal(properties, prefix + "offsetSamples"),
              decimal(properties, prefix + "driftPpm"),
              decimal(properties, prefix + "residualRmsSamples"),
              decimal(properties, prefix + "jitterRmsSamples"),
              decimal(properties, prefix + "gainLinear"),
              flag(properties, prefix + "invertedPolarity")));
    }
    return new MicrophoneArrayCalibration(
        value(properties, "calibration.id"),
        array,
        integer(properties, "calibration.referenceChannel"),
        channels,
        Instant.parse(value(properties, "calibration.calibratedAt")),
        Instant.parse(value(properties, "calibration.validUntil")));
  }

  private static EnumSet<LocalizationInputMode> readModes(Properties properties) {
    String encodedModes = value(properties, "profile.modes");
    EnumSet<LocalizationInputMode> modes = EnumSet.noneOf(LocalizationInputMode.class);
    Arrays.stream(encodedModes.split(","))
        .filter(mode -> !mode.isBlank())
        .map(LocalizationInputMode::valueOf)
        .forEach(modes::add);
    if (modes.isEmpty()) {
      throw new IllegalArgumentException("profile.modes must not be empty");
    }
    return modes;
  }

  private static void put(Map<String, String> values, String key, String value) {
    values.put(key, URLEncoder.encode(value, StandardCharsets.UTF_8));
  }

  private static String value(Properties properties, String key) {
    String value = properties.getProperty(key);
    if (value == null) {
      throw new IllegalArgumentException("missing profile property: " + key);
    }
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  private static void requireValue(Properties properties, String key, String expected) {
    String actual = value(properties, key);
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException(
          "unsupported " + key + ": expected " + expected + " but was " + actual);
    }
  }

  private static boolean flag(Properties properties, String key) {
    return Boolean.parseBoolean(value(properties, key));
  }

  private static int integer(Properties properties, String key) {
    return Integer.parseInt(value(properties, key));
  }

  private static long longValue(Properties properties, String key) {
    return Long.parseLong(value(properties, key));
  }

  private static float floating(Properties properties, String key) {
    return Float.parseFloat(value(properties, key));
  }

  private static double decimal(Properties properties, String key) {
    return Double.parseDouble(value(properties, key));
  }
}
