package org.hammer.audio.experiment.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import org.hammer.audio.plugin.document.DocumentValue;

/** Converts host-owned JSON trees to the framework-neutral plugin value model and back. */
final class DocumentValueJson {

  private DocumentValueJson() {
    // utility class
  }

  static DocumentValue fromJson(JsonNode node) throws ExperimentDocumentException {
    return fromJson(node, "/", 0);
  }

  private static DocumentValue fromJson(JsonNode node, String pointer, int depth)
      throws ExperimentDocumentException {
    requireDepth(depth, pointer);
    if (node == null || node.isNull()) {
      return DocumentValue.nullValue();
    }
    if (node.isObject()) {
      requireSize(node.size(), pointer);
      TreeMap<String, DocumentValue> fields = new TreeMap<>();
      node.fields()
          .forEachRemaining(
              entry -> {
                try {
                  String childPointer = pointer(pointer, entry.getKey());
                  requireString(entry.getKey(), childPointer);
                  fields.put(entry.getKey(), fromJson(entry.getValue(), childPointer, depth + 1));
                } catch (ExperimentDocumentException exception) {
                  throw new WrappedDocumentException(exception);
                }
              });
      return DocumentValue.object(fields);
    }
    if (node.isArray()) {
      requireSize(node.size(), pointer);
      ArrayList<DocumentValue> values = new ArrayList<>(node.size());
      for (int index = 0; index < node.size(); index++) {
        values.add(fromJson(node.get(index), pointer + "/" + index, depth + 1));
      }
      return DocumentValue.array(values);
    }
    if (node.isTextual()) {
      return DocumentValue.string(requireString(node.textValue(), pointer));
    }
    if (node.isNumber()) {
      return DocumentValue.number(node.decimalValue());
    }
    if (node.isBoolean()) {
      return DocumentValue.bool(node.booleanValue());
    }
    throw new ExperimentDocumentException(
        pointer, "unsupported-value", "Unsupported JSON value type at " + pointer);
  }

  static JsonNode toJson(DocumentValue value) {
    return switch (value) {
      case DocumentValue.ObjectValue objectValue -> objectNode(objectValue.fields());
      case DocumentValue.ArrayValue arrayValue -> arrayNode(arrayValue);
      case DocumentValue.StringValue stringValue -> new TextNode(stringValue.value());
      case DocumentValue.NumberValue numberValue -> new DecimalNode(numberValue.value());
      case DocumentValue.BooleanValue booleanValue -> BooleanNode.valueOf(booleanValue.value());
      case DocumentValue.NullValue ignored -> NullNode.getInstance();
    };
  }

  private static ObjectNode objectNode(Map<String, DocumentValue> values) {
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    new TreeMap<>(values).forEach((key, value) -> result.set(key, toJson(value)));
    return result;
  }

  private static ArrayNode arrayNode(DocumentValue.ArrayValue value) {
    ArrayNode result = JsonNodeFactory.instance.arrayNode();
    value.values().forEach(item -> result.add(toJson(item)));
    return result;
  }

  static String pointer(String parent, String key) {
    String escaped = key.replace("~", "~0").replace("/", "~1");
    return parent.equals("/") ? "/" + escaped : parent + "/" + escaped;
  }

  private static void requireDepth(int depth, String pointer) throws ExperimentDocumentException {
    if (depth > ExperimentDocumentFormat.MAX_NESTING_DEPTH) {
      throw new ExperimentDocumentException(
          pointer, "max-depth", "Document nesting exceeds the configured limit");
    }
  }

  private static void requireSize(int size, String pointer) throws ExperimentDocumentException {
    if (size > ExperimentDocumentFormat.MAX_COLLECTION_SIZE) {
      throw new ExperimentDocumentException(
          pointer, "max-collection", "Document collection exceeds the configured limit");
    }
  }

  private static String requireString(String value, String pointer)
      throws ExperimentDocumentException {
    if (value.length() > ExperimentDocumentFormat.MAX_STRING_LENGTH) {
      throw new ExperimentDocumentException(
          pointer, "max-string", "Document string exceeds the configured limit");
    }
    return value;
  }

  private static final class WrappedDocumentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private WrappedDocumentException(ExperimentDocumentException cause) {
      super(cause);
    }
  }
}
