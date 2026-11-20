package org.hammer.audio.experiment.document.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import org.hammer.audio.experiment.document.ExperimentDocumentException;
import org.hammer.audio.experiment.document.ExperimentDocumentFormat;

/** Bounds the explicit local-binding wrapper before any JSON tree or DTO allocation. */
final class ExperimentBindingRequestReader {
  static final int MAX_BYTES = ExperimentDocumentFormat.MAX_DOCUMENT_BYTES * 2 + 512 * 1024;
  private static final ObjectMapper MAPPER =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(16)
                          .maxStringLength(ExperimentDocumentFormat.MAX_DOCUMENT_BYTES)
                          .maxNumberLength(128)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private ExperimentBindingRequestReader() {
    // Utility class.
  }

  static ExperimentDocumentSessionHttpAdapter.BindingRequest read(InputStream input)
      throws IOException {
    byte[] bytes = input.readNBytes(MAX_BYTES + 1);
    if (bytes.length > MAX_BYTES) {
      throw new ExperimentDocumentException(
          "/localBindings", "max-binding-bytes", "Local binding request exceeds its byte limit");
    }
    return MAPPER.readValue(bytes, ExperimentDocumentSessionHttpAdapter.BindingRequest.class);
  }
}
