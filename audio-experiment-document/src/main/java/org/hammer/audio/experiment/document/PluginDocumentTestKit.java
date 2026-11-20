package org.hammer.audio.experiment.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hammer.audio.plugin.document.DocumentValue;

/** Host-equivalent schema, fixture and migration checks available to plugin authors. */
public final class PluginDocumentTestKit {

  private PluginDocumentTestKit() {
    // utility class
  }

  /** Canonical schema SHA-256, independent of object key order and whitespace. */
  public static String schemaSha256(String schemaJson) {
    try {
      return LocalJsonSchemaValidator.schemaDigest(schemaJson);
    } catch (IOException exception) {
      throw new IllegalArgumentException("Invalid bundled schema", exception);
    }
  }

  /** Canonical section hash includes schema and algorithm identity as well as data. */
  public static String sectionSha256(ExperimentDocument.PluginSection section) {
    try {
      DocumentValueBounds.check(
          section.data(), "/data", ExperimentDocumentFormat.MAX_DOCUMENT_BYTES);
      DocumentValue value =
          DocumentValue.object(
              Map.of(
                  "schemaVersion",
                      DocumentValue.number(java.math.BigDecimal.valueOf(section.schemaVersion())),
                  "algorithmVersion", DocumentValue.string(section.algorithmVersion()),
                  "data", section.data()));
      return DocumentHashes.sha256(
          new ObjectMapper().writeValueAsBytes(DocumentValueJson.toJson(value)));
    } catch (IOException exception) {
      throw new IllegalArgumentException("Section exceeds canonical value limits", exception);
    }
  }

  /** Verify a migration fixture is deterministic, byte-exact and idempotent on its output. */
  public static void verifyFixture(ExperimentDocumentService service, byte[] input, byte[] expected)
      throws ExperimentDocumentException {
    byte[] first = service.normalize(input);
    if (!Arrays.equals(expected, first)
        || !Arrays.equals(first, service.normalize(input))
        || !Arrays.equals(first, service.normalize(first))) {
      throw new IllegalArgumentException(
          "Plugin fixture is not canonical, deterministic and idempotent");
    }
    if (!service.preview(first).executionAllowed()) {
      throw new IllegalArgumentException("Plugin fixture has blocking diagnostics");
    }
  }

  /** Produce a stable parameter table without evaluating or dereferencing external resources. */
  public static List<Parameter> parameters(String schemaJson) {
    try {
      JsonNode schema = new BoundedDocumentJson().read(schemaJson.getBytes(StandardCharsets.UTF_8));
      List<Parameter> parameters = new ArrayList<>();
      collectParameters(schema, "", parameters);
      return List.copyOf(parameters);
    } catch (IOException exception) {
      throw new IllegalArgumentException("Invalid bundled schema", exception);
    }
  }

  private static void collectParameters(
      JsonNode schema, String pointer, List<Parameter> parameters) {
    Set<String> required = new HashSet<>();
    schema.path("required").forEach(item -> required.add(item.asText()));
    schema
        .path("properties")
        .fields()
        .forEachRemaining(
            entry -> {
              String child = DocumentValueJson.pointer(pointer, entry.getKey());
              JsonNode value = entry.getValue();
              parameters.add(
                  new Parameter(
                      child,
                      value.path("type").asText("any"),
                      required.contains(entry.getKey()),
                      value.path("description").asText("")));
              collectParameters(value, child, parameters);
            });
  }

  /**
   * One local-schema parameter for documentation and import previews.
   *
   * @param pointer data-relative parameter pointer
   * @param type declared JSON type
   * @param required whether the containing object requires this property
   * @param description human-readable parameter documentation
   */
  public record Parameter(String pointer, String type, boolean required, String description) {
    // Immutable value; record components carry the complete state.
  }
}
