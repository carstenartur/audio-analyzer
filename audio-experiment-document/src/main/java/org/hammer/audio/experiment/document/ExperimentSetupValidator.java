package org.hammer.audio.experiment.document;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Workflow;

/** Cross-field invariants that JSON Schema alone cannot express. */
final class ExperimentSetupValidator {

  private static final String PROFILES = "/profiles/";
  private static final String OUTPUTS = "outputs";
  private static final String OUTPUT_POINTER = "/outputs";
  private static final String CALIBRATION = "calibration";
  private static final String MICROPHONE_ARRAY = "microphoneArray";
  private static final String MICROPHONES = "microphones";
  private static final String CHANNELS = "channels";
  private static final String CHANNEL = "channel";
  private static final String CAPTURE = "capture";
  private static final String SOURCE = "source";
  private static final Set<String> LOCAL_OR_ACTIVE_KEYS =
      Set.of(
          "class",
          "classname",
          "javaclass",
          "implementation",
          "implementationclass",
          "script",
          "shell",
          "command",
          "password",
          "token",
          "accesstoken",
          "apikey",
          "deviceid",
          "outputdirectory",
          "outputpath");

  private ExperimentSetupValidator() {
    // utility class
  }

  static void validate(JsonNode root) throws ExperimentDocumentException {
    Set<String> assets = unique(root.path("assets"), "id", "/assets");
    unique(root.path(OUTPUTS), "id", OUTPUT_POINTER);
    unique(root.path("requiredPlugins"), "id", "/requiredPlugins");
    Set<String> outputNames = new HashSet<>();
    for (JsonNode output : root.path(OUTPUTS)) {
      String name = portableName(output.path("baseName").textValue(), "/outputs/baseName");
      if (!outputNames.add(name.toLowerCase(Locale.ROOT))) {
        throw failure(OUTPUT_POINTER, "Output basenames must be unique across platforms");
      }
    }
    for (JsonNode asset : root.path("assets")) {
      try {
        PortableNames.requireRelativePath(asset.path("relativePath").textValue());
      } catch (IllegalArgumentException exception) {
        throw failure("/assets", "Asset path is not portable");
      }
    }
    JsonNode profiles = root.path("profiles");
    if (profiles.has(SOURCE)) {
      requireAsset(assets, profiles.path(SOURCE).path("assetId"), PROFILES + "source/assetId");
    }
    String mode = root.path("experiment").path("sourceMode").textValue();
    if (Set.of("recording", "dataset", "replay").contains(mode) && !profiles.has(SOURCE)) {
      throw failure(PROFILES + SOURCE, "Recorded input requires a portable source asset");
    }
    if (profiles.has(MICROPHONE_ARRAY)) {
      validateArray(profiles);
    }
    if (profiles.has(CALIBRATION)) {
      validateCalibration(profiles, assets);
    }
  }

  private static void validateArray(JsonNode profiles) throws ExperimentDocumentException {
    JsonNode microphones = profiles.path(MICROPHONE_ARRAY).path(MICROPHONES);
    String pointer = PROFILES + "microphoneArray/microphones";
    unique(microphones, "id", pointer);
    Set<String> channels = unique(microphones, CHANNEL, pointer);
    for (int channel = 0; channel < microphones.size(); channel++) {
      if (!channels.contains(Integer.toString(channel))) {
        throw failure(pointer, "Microphone channels must cover 0 through channel count minus one");
      }
    }
    if (profiles.has(CAPTURE)
        && profiles.path(CAPTURE).path(CHANNELS).intValue() != microphones.size()) {
      throw failure(PROFILES + "capture/channels", "Capture and geometry channel counts differ");
    }
  }

  private static void validateCalibration(JsonNode profiles, Set<String> assets)
      throws ExperimentDocumentException {
    JsonNode calibration = profiles.path(CALIBRATION);
    if (!profiles.has(MICROPHONE_ARRAY)) {
      throw failure(PROFILES + CALIBRATION, "Calibration requires microphone geometry");
    }
    requireAsset(
        assets, calibration.path("evidenceAssetId"), PROFILES + "calibration/evidenceAssetId");
    int count = profiles.path(MICROPHONE_ARRAY).path(MICROPHONES).size();
    Set<String> channels =
        unique(calibration.path(CHANNELS), CHANNEL, PROFILES + "calibration/channels");
    if (channels.size() != count || calibration.path("referenceChannel").intValue() >= count) {
      throw failure(PROFILES + CALIBRATION, "Calibration channels must match the geometry");
    }
    for (int channel = 0; channel < count; channel++) {
      if (!channels.contains(Integer.toString(channel))) {
        throw failure(PROFILES + "calibration/channels", "Missing microphone calibration channel");
      }
    }
    Instant calibrated = Instant.parse(calibration.path("calibratedAt").textValue());
    Instant until = Instant.parse(calibration.path("validUntil").textValue());
    if (!until.isAfter(calibrated)) {
      throw failure(PROFILES + "calibration/validUntil", "Validity must end after calibration");
    }
  }

  private static Set<String> unique(JsonNode values, String field, String pointer)
      throws ExperimentDocumentException {
    Set<String> seen = new HashSet<>();
    int index = 0;
    for (JsonNode item : values) {
      if (!seen.add(item.path(field).asText())) {
        throw failure(pointer + "/" + index + "/" + field, "Duplicate " + field);
      }
      index++;
    }
    return seen;
  }

  private static void requireAsset(Set<String> assets, JsonNode reference, String pointer)
      throws ExperimentDocumentException {
    if (!assets.contains(reference.textValue())) {
      throw failure(pointer, "Referenced asset is absent from the document");
    }
  }

  private static String portableName(String name, String pointer)
      throws ExperimentDocumentException {
    try {
      return PortableNames.requireBaseName(name);
    } catch (IllegalArgumentException exception) {
      throw failure(pointer, "Output basename is not portable");
    }
  }

  static void validateWorkflow(Workflow workflow) throws ExperimentDocumentException {
    validateMetadata(workflow.metadata());
    for (var node : workflow.nodes()) {
      validateMetadata(node.metadata());
      for (var port : node.inputPorts()) {
        validateMetadata(port.metadata());
      }
      for (var port : node.outputPorts()) {
        validateMetadata(port.metadata());
      }
    }
    for (var edge : workflow.edges()) {
      validateMetadata(edge.metadata());
    }
  }

  private static void validateMetadata(Metadata metadata) throws ExperimentDocumentException {
    for (String key : metadata.entries().keySet()) {
      String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[._:-]", "");
      if (LOCAL_OR_ACTIVE_KEYS.stream().anyMatch(normalized::endsWith)) {
        throw failure(
            "/workflow/content",
            "Local bindings, secrets and active metadata are not portable: " + key);
      }
    }
  }

  private static ExperimentDocumentException failure(String pointer, String message) {
    return new ExperimentDocumentException(pointer, "invalid-setup", message);
  }
}
