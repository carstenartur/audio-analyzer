package org.hammer.audio.experiment.document;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

/** Bounds every JSON token before allocating a tree and retains exact decimal values. */
final class BoundedDocumentJson {

  private final ObjectMapper mapper;

  BoundedDocumentJson() {
    JsonFactory factory =
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(ExperimentDocumentFormat.MAX_NESTING_DEPTH)
                    .maxStringLength(ExperimentDocumentFormat.MAX_STRING_LENGTH)
                    .maxNameLength(ExperimentDocumentFormat.MAX_STRING_LENGTH)
                    .maxNumberLength(128)
                    .build())
            .build();
    mapper =
        new ObjectMapper(factory)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  JsonNode read(byte[] bytes) throws IOException {
    String text;
    try {
      text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException exception) {
      throw new ExperimentDocumentException("/", "invalid-utf8", "Expected UTF-8 JSON", exception);
    }
    // Check collection limits during streaming, before readTree allocates their members.
    try (JsonParser parser = mapper.createParser(text)) {
      int[] sizes = new int[ExperimentDocumentFormat.MAX_NESTING_DEPTH + 1];
      boolean[] arrays = new boolean[sizes.length];
      int depth = 0;
      boolean started = false;
      JsonToken token;
      while ((token = parser.nextToken()) != null) {
        if (started && depth == 0) {
          throw new ExperimentDocumentException("/", "invalid-json", "Trailing JSON content");
        }
        started = true;
        if (token == JsonToken.FIELD_NAME || (depth > 0 && arrays[depth] && !token.isStructEnd())) {
          if (++sizes[depth] > ExperimentDocumentFormat.MAX_COLLECTION_SIZE) {
            throw new ExperimentDocumentException(
                "/", "max-collection", "Collection limit exceeded");
          }
        }
        if (token == JsonToken.FIELD_NAME || token == JsonToken.VALUE_STRING) {
          if (parser.getTextLength() > ExperimentDocumentFormat.MAX_STRING_LENGTH) {
            throw new ExperimentDocumentException("/", "max-string", "String limit exceeded");
          }
        }
        if (token.isStructStart()) {
          depth++;
          sizes[depth] = 0;
          arrays[depth] = token == JsonToken.START_ARRAY;
        } else if (token.isStructEnd()) {
          depth--;
        }
      }
    }
    return mapper.readTree(text);
  }
}
