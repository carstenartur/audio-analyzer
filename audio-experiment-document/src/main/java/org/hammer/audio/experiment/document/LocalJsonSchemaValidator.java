package org.hammer.audio.experiment.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
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
@SuppressWarnings({
  "PMD.LooseCoupling",
  "PMD.AvoidDuplicateLiterals",
  "PMD.LiteralsFirstInComparisons",
  "PMD.ConfusingTernary"
})
final class LocalJsonSchemaValidator {

  private static final int MAX_SCHEMA_BYTES = 256 * 1024;
  private static final List<String> NUMERIC_BOUNDS =
      List.of("minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems");
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

  List<DocumentDiagnostic> validate(
      String schemaJson, String expectedSha256, DocumentValue value, String pointer) {
    ArrayList<DocumentDiagnostic> diagnostics = new ArrayList<>();
    byte[] schemaBytes = schemaJson.getBytes(StandardCharsets.UTF_8);
    if (schemaBytes.length > MAX_SCHEMA_BYTES) {
      diagnostics.add(error(pointer, "schema-too-large", "Plugin schema exceeds 256 KiB"));
      return diagnostics;
    }
    JsonNode parsed;
    try {
      parsed = new BoundedDocumentJson().read(schemaBytes);
    } catch (IOException exception) {
      diagnostics.add(error(pointer, "invalid-schema", "Bundled plugin schema is not strict JSON"));
      return diagnostics;
    }
    if (!(parsed instanceof ObjectNode root)) {
      diagnostics.add(error(pointer, "invalid-schema", "Plugin schema root must be an object"));
      return diagnostics;
    }
    try {
      if (!schemaDigest(schemaJson).equals(expectedSha256)) {
        diagnostics.add(
            error(pointer, "schema-digest", "Bundled plugin schema digest does not match"));
        return diagnostics;
      }
    } catch (IOException exception) {
      diagnostics.add(error(pointer, "invalid-schema", "Bundled plugin schema is not strict JSON"));
      return diagnostics;
    }
    checkSchema(root, pointer, diagnostics);
    if (!diagnostics.isEmpty()) {
      return diagnostics;
    }
    validateSchemaNode(root, value, pointer, diagnostics, 0);
    return diagnostics;
  }

  static String schemaDigest(String schemaJson) throws IOException {
    byte[] bytes = schemaJson.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_SCHEMA_BYTES) {
      throw new IOException("Plugin schema exceeds 256 KiB");
    }
    DocumentValue value = DocumentValueJson.fromJson(new BoundedDocumentJson().read(bytes));
    return DocumentHashes.sha256(
        new ObjectMapper().writeValueAsBytes(DocumentValueJson.toJson(value)));
  }

  private static void checkSchema(
      ObjectNode schema, String pointer, List<DocumentDiagnostic> diagnostics) {
    schema
        .fieldNames()
        .forEachRemaining(
            name -> {
              if ("$ref".equals(name)) {
                diagnostics.add(
                    error(pointer, "schema-ref", "Plugin schemas must not contain $ref"));
              } else if (!ALLOWED_KEYWORDS.contains(name)) {
                diagnostics.add(
                    error(pointer, "schema-keyword", "Unsupported plugin schema keyword: " + name));
              }
            });
    checkTypes(schema, pointer, diagnostics);
    checkBounds(schema, pointer, diagnostics);
    textSet(schema.get("required"), pointer, diagnostics);
    checkProperties(schema, pointer, diagnostics);
    if (schema.has("enum") && (!schema.get("enum").isArray() || schema.get("enum").isEmpty())) {
      diagnostics.add(error(pointer, "invalid-schema", "Schema enum must be a non-empty array"));
    }
  }

  private static void checkTypes(
      ObjectNode schema, String pointer, List<DocumentDiagnostic> diagnostics) {
    if (schema.has("type")
        && (!schema.get("type").isTextual()
            || !Set.of("object", "array", "string", "number", "integer", "boolean", "null")
                .contains(schema.get("type").asText()))) {
      diagnostics.add(
          error(pointer, "invalid-schema", "Schema type must name a supported JSON type"));
    }
    if (schema.has("additionalProperties") && !schema.get("additionalProperties").isBoolean()) {
      diagnostics.add(
          error(pointer, "invalid-schema", "Schema additionalProperties must be boolean"));
    }
  }

  private static void checkBounds(
      ObjectNode schema, String pointer, List<DocumentDiagnostic> diagnostics) {
    for (String bound : NUMERIC_BOUNDS) {
      if (schema.has(bound)
          && (!schema.get(bound).isNumber()
              || (bound.endsWith("Length") || bound.endsWith("Items"))
                  && (schema.get(bound).decimalValue().stripTrailingZeros().scale() > 0
                      || schema.get(bound).decimalValue().signum() < 0))) {
        diagnostics.add(error(pointer, "invalid-schema", "Invalid schema bound: " + bound));
      }
    }
  }

  private static void checkProperties(
      ObjectNode schema, String pointer, List<DocumentDiagnostic> diagnostics) {
    JsonNode properties = schema.get("properties");
    if (properties != null && !properties.isObject()) {
      diagnostics.add(error(pointer, "invalid-schema", "Schema properties must be an object"));
    } else if (properties != null) {
      properties
          .fields()
          .forEachRemaining(
              entry -> {
                if (entry.getValue() instanceof ObjectNode child) {
                  checkSchema(
                      child, DocumentValueJson.pointer(pointer, entry.getKey()), diagnostics);
                } else {
                  diagnostics.add(
                      error(pointer, "invalid-schema", "Property schema must be an object"));
                }
              });
    }
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
    boolean additional =
        !schema.has("additionalProperties") || schema.path("additionalProperties").asBoolean(true);
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
    BigDecimal size = BigDecimal.valueOf(value.values().size());
    if (schema.has("minItems") && size.compareTo(schema.path("minItems").decimalValue()) < 0) {
      diagnostics.add(error(pointer, "schema-min-items", "Plugin array has too few items"));
    }
    if (schema.has("maxItems") && size.compareTo(schema.path("maxItems").decimalValue()) > 0) {
      diagnostics.add(error(pointer, "schema-max-items", "Plugin array has too many items"));
    }
  }

  private static void validateString(
      ObjectNode schema,
      DocumentValue.StringValue value,
      String pointer,
      List<DocumentDiagnostic> diagnostics) {
    BigDecimal length = BigDecimal.valueOf(value.value().codePointCount(0, value.value().length()));
    if (schema.has("minLength") && length.compareTo(schema.path("minLength").decimalValue()) < 0) {
      diagnostics.add(error(pointer, "schema-min-length", "Plugin string is too short"));
    }
    if (schema.has("maxLength") && length.compareTo(schema.path("maxLength").decimalValue()) > 0) {
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
    for (JsonNode candidate : values) {
      try {
        if (DocumentValueJson.fromJson(candidate).equals(value)) {
          return;
        }
      } catch (ExperimentDocumentException exception) {
        diagnostics.add(error(pointer, "invalid-schema", "Schema enum exceeds value limits"));
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
      case "number" -> value instanceof DocumentValue.NumberValue;
      case "integer" ->
          value instanceof DocumentValue.NumberValue number && number.value().scale() <= 0;
      case "boolean" -> value instanceof DocumentValue.BooleanValue;
      case "null" -> value instanceof DocumentValue.NullValue;
      default -> false;
    };
  }

  private static DocumentDiagnostic error(String pointer, String code, String message) {
    return new DocumentDiagnostic(DocumentDiagnostic.Severity.ERROR, pointer, code, message);
  }
}
