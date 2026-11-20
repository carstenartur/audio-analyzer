package org.hammer.audio.experiment.document;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/** Protects the schema boundary between the host parser and the validator's JSON implementation. */
class ExperimentDocumentSchemaBridgeTest {

  @Test
  void validatesTheBoundedHostTreeAndRetainsFormatAssertions() throws Exception {
    ObjectNode document = minimal();
    assertDoesNotThrow(() -> ExperimentDocumentSchema.validate(document));

    ((ObjectNode) document.path("provenance")).put("createdAt", "2026-07-23 12:00:00Z");
    ExperimentDocumentException invalid =
        assertThrows(
            ExperimentDocumentException.class, () -> ExperimentDocumentSchema.validate(document));
    assertEquals("schema-invalid", invalid.code());
    assertEquals("/provenance/createdAt", invalid.pointer());
  }

  @Test
  void fractionalAssetSizeCannotBeRoundedToAnAcceptedInteger() throws Exception {
    ObjectNode document = minimal();
    ObjectNode asset = document.withArray("assets").addObject();
    asset.put("id", "recording");
    asset.put("relativePath", "input.wav");
    asset.put("mediaType", "audio/wav");
    asset.put("sha256", "0".repeat(64));
    asset.put("sizeBytes", new BigDecimal("9007199254740990.5"));

    assertThrows(
        ExperimentDocumentException.class, () -> ExperimentDocumentSchema.validate(document));
    asset.put("sizeBytes", 9007199254740991L);
    assertDoesNotThrow(() -> ExperimentDocumentSchema.validate(document));
  }

  private static ObjectNode minimal() throws Exception {
    return (ObjectNode)
        new ObjectMapper()
            .readTree(
                Files.readAllBytes(ExperimentDocumentSecurityTest.fixture("minimal.audioexp")));
  }
}
