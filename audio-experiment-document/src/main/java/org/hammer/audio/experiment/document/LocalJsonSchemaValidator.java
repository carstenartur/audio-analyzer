package org.hammer.audio.experiment.document;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hammer.audio.plugin.document.DocumentDiagnostic;
import org.hammer.audio.plugin.document.DocumentValue;

/**
 * Small deterministic validator for the safe JSON Schema subset used by plugin parameter sections.
 *
 * <p>Supported keywords are {@code type}, {@code required}, {@code properties}, {@code
 * additionalProperties}, {@code enum}, {@code minimum}, {@code maximum}, {@code minLength}, {@code
 * maxLength}, {@code minItems} and {@code maxItems}. Network and implementation references are not
 * supported.
 */
final class LocalJsonSchemaValidator {

  private static final int MAX_SCHEMA_BYTES = 256 * 1024;
  private static final Set<String> ALLOWED_KEYWORDS =
      Set.of(
          "$id",
          "$schema",
          "title",
          "description",
          "type",
          "required",
          "properties",
          "additionalProperties",
          "enum",
          "minimum",
          "maximum",
          "minLength",
          "maxLength",
          "minItems",
          "maxItems");

  private final ObjectMapper mapper;

  LocalJsonSchemaValidator() {
    JsonFactory factory =
        JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    mapper = new ObjectMapper(factory);
  }

  List<DocumentDiagnostic> validate(
      String schemaJson, String expectedSha256, DocumentValue value, String pointer) {
    ArrayList<DocumentDiagnostic> diagnostics = new ArrayList<>();
    byte[] schemaBytes = schemaJson.getBytes(StandardCharsets.UTF_8);
    if (schemaBytes.length > MAX_SCHEMA_BYTES) {
      diagnostics.add(error(pointer, "schema-too-large", "Plugin schema exceeds 256 KiB"));
      return diagnostics;
    }
    if (!DocumentHashes.sha256(schemaBytes).equals(expectedSha256)) {
      diagnostics.add(error(pointer, "schema-digest", "Bundled plugin schema digest does not match"));
      return diagnostics;
    }
    JsonNode parsed;
    try {
      parsed = mapper.readTree(schemaBytes);
    } catch (JsonProcessingException exception) {
      diagnostics.add(error(pointer, "invalid-schema", "Bundled plugin schema is not strict JSON"));
      return diagnostics;
    }
    if (!(parsed instanceof ObjectNode root)) {
      diagnostics.add(error(pointer, "invalid-schema", "Plugin schema root must be an object"));
      return diagnostics;
    }
    validateSchemaNode(root, value, pointer, diagnostics, 0);
    return diagnostics;
  }

  private void validateSchemaNode(
      ObjectNode schema,
      DocumentValue value,
      String pointer,
      List<DocumentDiagnostic> diagnostics,
      int depth) {
    if (depth > ExperimentDocumentFormat.MAX_NESTING_DEPTH) {
      diagnostics.add(error(pointer, "schema-depth", "Plugin schema nesting exceeds the limit"));
      return;
    }
    Iterator<String> names = schema.fieldNames();
    while (names.hasNext()) {
      String name = names.next();
      if (name.equals("$ref")) {
        diagnostics.add(error(pointer, "schema-ref", "Plugin schemas must not contain $ref"));
      } else if (!ALLOWED_KEYWORDS.contains(name)) {
        diagnostics.add(
            error(pointer, "schema-keyword", "Unsupported plugin schema keyword: " + name));
      }
    }
    String type = schema.path("type").isTextual() ? schema.path("type").textValue() : "";
    if (!type.isBlank() && !matchesType(type, value)) {
      diagnostics.add(error(pointer, "schema-type", "Expected plugin data type " + type));
      return;
    }
    validateEnum(schema.get("enum"), value, pointer, diagnostics);
    switch (value) {
      case DocumentValue.ObjectValue objectValue ->
          validateObject(schema, objectValue, pointer, diagnostics, depth);
      case DocumentValue.ArrayValue arrayValue ->
          validateArray(schema, arrayValue, pointer, diagnostics);
      case DocumentValue.StringValue stringValue ->
          validateString(schema, stringValue, pointer, diagnostics);
      case DocumentValue.NumberValue numberValue ->
          validateNumber(schema, numberValue, pointer, diagnostics);
      case DocumentValue.BooleanValue ignored -> {
        // Type and enum checks are sufficient.
      }
      case DocumentValue.NullValue ignored -> {
        // Type and enum checks are sufficient.
      }
    }
  }

  private void validateObject(
      ObjectNode schema,
      DocumentValue.ObjectValue value,
      String pointer,
      List<DocumentDiagnostic> diagnostics,
      int depth) {
    Set<String> required = textSet(schema.get("required"), pointer, diagnostics);
    for (String requiredField : required) {
      if (!value.fields().containsKey(requiredField)) {
        diagnostics.add(
            error(
                DocumentValueJson.pointer(pointer, requiredField),
                "schema-required",
                "Missing required plugin field"));
      }
    }
    JsonNode propertiesNode = schema.get("properties");
    ObjectNode properties = propertiesNode instanceof ObjectNode objectNode ? objectNode : null;
    boolean additional = !schema.has("additionalProperties") || schema.path("additionalProperties").asBoolean(true);
    for (Map.Entry<String, DocumentValue> entry : value.fields().entrySet()) {
      String childPointer = DocumentValueJson.pointer(pointer, entry.getKey());
      JsonNode propertySchema = properties == null ? null : properties.get(entry.getKey());
      if (propertySchema instanceof ObjectNode objectSchema) {
        validateSchemaNode(objectSchema, entry.getValue(), childPointer, diagnostics, depth + 1);
      } else if (!additional) {
        diagnostics.add(error(childPointer, "schema-additional", "Unknown plugin field"));
      }
    }
  }

  private static void validateArray(
      ObjectNode schema,
      DocumentValue.ArrayValue value,
      String pointer,
      List<DocumentDiagnostic> diagnostics) {
    int size = value.values().size();
    if (schema.has("minItems") && size < schema.path("minItems").asInt()) {
      diagnostics.add(error(pointer, "schema-min-items", "Plugin array has too few items"));
    }
    if (schema.has("maxItems") && size > schema.path("maxItems").asInt()) {
      diagnostics.add(error(pointer, "schema-max-items", "Plugin array has too many items"));
    }
  }

  private static void validateString(
      ObjectNode schema,
      DocumentValue.StringValue value,
      String pointer,
      List<DocumentDiagnostic> diagnostics) {
    int length = value.value().length();
    if (schema.has("minLength") && length < schema.path("minLength").asInt()) {
      diagnostics.add(error(pointer, "schema-min-length", "Plugin string is too short"));
    }
    if (schema.has("maxLength") && length > schema.path("maxLength").asInt()) {
      diagnostics.add(error(pointer, "schema-max-length", "Plugin string is too long"));
    }
  }

  private static void validateNumber(
      ObjectNode schema,
      DocumentValue.NumberValue value,
      String pointer,
      List<DocumentDiagnostic> diagnostics) {
    BigDecimal number = value.value();
    if (schema.has("minimum") && number.compareTo(schema.path("minimum").decimalValue()) < 0) {
      diagnostics.add(error(pointer, "schema-minimum", "Plugin number is below minimum"));
    }
    if (schema.has("maximum") && number.compareTo(schema.path("maximum").decimalValue()) > 0) {
      diagnostics.add(error(pointer, "schema-maximum", "Plugin number is above maximum"));
    }
  }

  private static void validateEnum(
      JsonNode enumNode,
      DocumentValue value,
      String pointer,
      List<DocumentDiagnostic> diagnostics) {
    if (!(enumNode instanceof ArrayNode values)) {
      return;
    }
    JsonNode actual = DocumentValueJson.toJson(value);
    for (JsonNode candidate : values) {
      if (candidate.equals(actual)) {
        return;
      }
    }
    diagnostics.add(error(pointer, "schema-enum", "Plugin value is not in the allowed set"));
  }

  private static Set<String> textSet(
      JsonNode node, String pointer, List<DocumentDiagnostic> diagnostics) {
    if (node == null) {
      return Set.of();
    }
    if (!(node instanceof ArrayNode array)) {
      diagnostics.add(error(pointer, "invalid-schema", "Schema required must be an array"));
      return Set.of();
    }
    HashSet<String> result = new HashSet<>();
    for (JsonNode item : array) {
      if (!item.isTextual()) {
        diagnostics.add(error(pointer, "invalid-schema", "Schema required items must be strings"));
      } else {
        result.add(item.textValue());
      }
    }
    return result;
  }

  private static boolean matchesType(String type, DocumentValue value) {
    return switch (type) {
      case "object" -> value instanceof DocumentValue.ObjectValue;
      case "array" -> value instanceof DocumentValue.ArrayValue;
      case "string" -> value instanceof DocumentValue.StringValue;
      case "number", "integer" -> value instanceof DocumentValue.NumberValue;
      case "boolean" -> value instanceof DocumentValue.BooleanValue;
      case "null" -> value instanceof DocumentValue.NullValue;
      default -> false;
    };
  }

  private static DocumentDiagnostic error(String pointer, String code, String message) {
    return new DocumentDiagnostic(DocumentDiagnostic.Severity.ERROR, pointer, code, message);
  }
}
