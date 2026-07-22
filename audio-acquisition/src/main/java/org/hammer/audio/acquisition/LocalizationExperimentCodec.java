package org.hammer.audio.acquisition;

import java.io.IOException;
import java.io.StringReader;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Deterministic text codec for complete localization experiment manifests. */
public final class LocalizationExperimentCodec {

  private static final String SCHEMA_VERSION = "1";

  private final MicrophoneArrayProfileCodec profileCodec;

  /** Create a codec using the canonical profile codec. */
  public LocalizationExperimentCodec() {
    this(new MicrophoneArrayProfileCodec());
  }

  /** Create a codec with an explicit profile codec. */
  public LocalizationExperimentCodec(MicrophoneArrayProfileCodec profileCodec) {
    this.profileCodec = java.util.Objects.requireNonNull(profileCodec, "profileCodec");
  }

  /** Encode one complete experiment manifest in stable key order. */
  public String encode(LocalizationExperiment experiment) {
    TreeMap<String, String> values = new TreeMap<>();
    put(values, "schema", SCHEMA_VERSION);
    put(values, "experiment.id", experiment.experimentId());
    put(values, "experiment.name", experiment.displayName());
    put(values, "experiment.mode", experiment.inputMode().name());
    put(values, "experiment.source", experiment.sourceReference());
    put(values, "experiment.createdAt", experiment.createdAt().toString());
    put(values, "experiment.stage", experiment.stage().name());
    String profile =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                profileCodec.encode(experiment.profile()).getBytes(StandardCharsets.UTF_8));
    put(values, "experiment.profile", profile);
    put(values, "metadata.count", Integer.toString(experiment.metadata().size()));
    int index = 0;
    for (Map.Entry<String, String> entry : new TreeMap<>(experiment.metadata()).entrySet()) {
      put(values, "metadata." + index + ".key", entry.getKey());
      put(values, "metadata." + index + ".value", entry.getValue());
      index++;
    }
    return values.entrySet().stream()
        .map(entry -> entry.getKey() + '=' + entry.getValue())
        .collect(Collectors.joining("\n", "", "\n"));
  }

  /** Decode one complete experiment manifest. */
  public LocalizationExperiment decode(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      throw new IllegalArgumentException("encoded experiment must not be blank");
    }
    Properties properties = new Properties();
    try {
      properties.load(new StringReader(encoded));
    } catch (IOException exception) {
      throw new IllegalArgumentException("experiment text could not be read", exception);
    }
    requireValue(properties, "schema", SCHEMA_VERSION);
    byte[] profileBytes = Base64.getUrlDecoder().decode(value(properties, "experiment.profile"));
    MicrophoneArrayProfile profile =
        profileCodec.decode(new String(profileBytes, StandardCharsets.UTF_8));
    int metadataCount = Integer.parseInt(value(properties, "metadata.count"));
    TreeMap<String, String> metadata = new TreeMap<>();
    for (int index = 0; index < metadataCount; index++) {
      metadata.put(
          value(properties, "metadata." + index + ".key"),
          value(properties, "metadata." + index + ".value"));
    }
    return new LocalizationExperiment(
        value(properties, "experiment.id"),
        value(properties, "experiment.name"),
        profile,
        LocalizationInputMode.valueOf(value(properties, "experiment.mode")),
        value(properties, "experiment.source"),
        Instant.parse(value(properties, "experiment.createdAt")),
        LocalizationExperimentStage.valueOf(value(properties, "experiment.stage")),
        metadata);
  }

  private static void put(Map<String, String> values, String key, String value) {
    values.put(key, URLEncoder.encode(value, StandardCharsets.UTF_8));
  }

  private static String value(Properties properties, String key) {
    String value = properties.getProperty(key);
    if (value == null) {
      throw new IllegalArgumentException("missing experiment property: " + key);
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
}
