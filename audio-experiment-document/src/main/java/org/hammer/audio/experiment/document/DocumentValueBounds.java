package org.hammer.audio.experiment.document;

import java.nio.charset.StandardCharsets;
import org.hammer.audio.plugin.document.DocumentValue;

/** Validates plugin-produced values before recursive conversion or canonical serialization. */
final class DocumentValueBounds {

  private DocumentValueBounds() {
    // utility class
  }

  static void check(DocumentValue value, String pointer) throws ExperimentDocumentException {
    check(value, pointer, ExperimentDocumentFormat.MAX_DOCUMENT_BYTES / 2);
  }

  static void check(DocumentValue value, String pointer, int maximumBytes)
      throws ExperimentDocumentException {
    inspect(value, pointer, 0, new long[] {0, maximumBytes});
  }

  private static void inspect(DocumentValue value, String pointer, int depth, long[] size)
      throws ExperimentDocumentException {
    if (value == null || depth > ExperimentDocumentFormat.MAX_NESTING_DEPTH - 5) {
      throw new ExperimentDocumentException(
          pointer, "plugin-value-depth", "Plugin value exceeds nesting limits");
    }
    size[0] += 2;
    switch (value) {
      case DocumentValue.ObjectValue object -> {
        collection(object.fields().size(), pointer);
        for (java.util.Map.Entry<String, DocumentValue> entry : object.fields().entrySet()) {
          text(entry.getKey(), pointer, size);
          inspect(
              entry.getValue(),
              DocumentValueJson.pointer(pointer, entry.getKey()),
              depth + 1,
              size);
        }
      }
      case DocumentValue.ArrayValue array -> {
        collection(array.values().size(), pointer);
        for (int index = 0; index < array.values().size(); index++) {
          inspect(array.values().get(index), pointer + "/" + index, depth + 1, size);
        }
      }
      case DocumentValue.StringValue string -> text(string.value(), pointer, size);
      case DocumentValue.NumberValue number -> {
        if (number.value().precision() > 128 || number.value().toString().length() > 128) {
          throw new ExperimentDocumentException(
              pointer, "plugin-number-limit", "Plugin number exceeds token limits");
        }
        size[0] += number.value().toString().length();
      }
      case DocumentValue.BooleanValue ignored -> {
        size[0] += 5;
      }
      case DocumentValue.NullValue ignored -> {
        size[0] += 4;
      }
    }
    if (size[0] > size[1]) {
      throw new ExperimentDocumentException(
          pointer, "plugin-value-size", "Plugin section exceeds its byte limit");
    }
  }

  private static void text(String value, String pointer, long[] size)
      throws ExperimentDocumentException {
    if (value.length() > ExperimentDocumentFormat.MAX_STRING_LENGTH) {
      throw new ExperimentDocumentException(
          pointer, "max-string", "Plugin string exceeds the limit");
    }
    // Account for escapes without constructing an unbounded serialized tree.
    size[0] += value.getBytes(StandardCharsets.UTF_8).length + 2L;
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < 0x20) {
        size[0] += 5;
      } else if (character == '"' || character == '\\') {
        size[0]++;
      }
    }
  }

  private static void collection(int size, String pointer) throws ExperimentDocumentException {
    if (size > ExperimentDocumentFormat.MAX_COLLECTION_SIZE) {
      throw new ExperimentDocumentException(
          pointer, "max-collection", "Plugin collection exceeds the limit");
    }
  }
}
