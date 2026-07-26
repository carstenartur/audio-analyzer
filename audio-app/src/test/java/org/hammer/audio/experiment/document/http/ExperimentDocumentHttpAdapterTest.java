package org.hammer.audio.experiment.document.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hammer.audio.experiment.document.ExperimentDocument;
import org.hammer.audio.experiment.document.ExperimentDocumentCodec;
import org.hammer.audio.experiment.document.ExperimentDocumentException;
import org.hammer.audio.experiment.document.ExperimentDocumentFormat;
import org.hammer.audio.experiment.document.ExperimentDocumentService;
import org.hammer.audio.plugin.document.DocumentValue;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ExperimentDocumentHttpAdapterTest {

  private static final String ZERO_SHA256 = "0".repeat(64);
  private static final String WORKFLOW =
      "workflow\n  id: http-workflow\n  name: HTTP workflow\n  nodes:\n  edges:\n";

  private final ExperimentDocumentCodec codec = new ExperimentDocumentCodec();
  private final ExperimentDocumentService service = new ExperimentDocumentService(List.of());
  private final ExperimentDocumentHttpAdapter adapter = new ExperimentDocumentHttpAdapter(service);

  @Test
  void previewReturnsSafeWorkflowAndDocumentSummary() throws Exception {
    byte[] source = codec.encode(document());
    MockHttpServletRequest request = request(source);

    ExperimentDocumentHttpAdapter.PreviewResponse preview = adapter.preview(request);

    assertEquals(ExperimentDocumentFormat.FORMAT_ID, preview.format());
    assertEquals(ExperimentDocumentFormat.VERSION, preview.formatVersion());
    assertEquals("http-experiment", preview.experimentId());
    assertEquals("http-workflow", preview.workflowId());
    assertEquals(0, preview.nodeCount());
    assertEquals(0, preview.edgeCount());
    assertTrue(preview.executionAllowed());
    assertTrue(preview.diagnostics().isEmpty());
  }

  @Test
  void normalizeReturnsCanonicalDedicatedMediaTypeRepresentation() throws Exception {
    byte[] source = codec.encode(document());

    var response = adapter.normalize(request(source));

    assertEquals(
        ExperimentDocumentFormat.MEDIA_TYPE, response.getHeaders().getContentType().toString());
    assertArrayEquals(source, response.getBody());
    assertTrue(response.getHeaders().getContentDisposition().getFilename().endsWith(".audioexp"));
  }

  @Test
  void schemaIsBundledAndNeverFetchedFromItsIdentifier() throws Exception {
    var response = adapter.schema();

    String schema = new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(schema.contains(ExperimentDocumentFormat.FORMAT_ID));
  }

  @Test
  void oversizedRequestIsRejectedBeforeParsing() {
    byte[] oversized = new byte[ExperimentDocumentFormat.MAX_DOCUMENT_BYTES + 1];

    ExperimentDocumentException failure =
        assertThrows(ExperimentDocumentException.class, () -> adapter.preview(request(oversized)));

    assertEquals("max-bytes", failure.code());
    assertEquals("/", failure.pointer());
  }

  private static MockHttpServletRequest request(byte[] content) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContentType(ExperimentDocumentFormat.MEDIA_TYPE);
    request.setContent(content);
    return request;
  }

  private static ExperimentDocument document() {
    return new ExperimentDocument(
        ExperimentDocumentFormat.SCHEMA_RESOURCE,
        ExperimentDocumentFormat.FORMAT_ID,
        ExperimentDocumentFormat.VERSION,
        new ExperimentDocument.ExperimentInfo(
            "http-experiment",
            "HTTP experiment",
            "REST fixture",
            List.of("fixture"),
            "analysis",
            "simulation",
            null,
            "0.0.4-SNAPSHOT"),
        new ExperimentDocument.WorkflowPayload(
            ExperimentDocumentFormat.WORKFLOW_FORMAT_ID,
            ExperimentDocumentFormat.WORKFLOW_VERSION,
            WORKFLOW,
            ZERO_SHA256),
        DocumentValue.object(Map.of()),
        List.of(),
        Map.of(),
        List.of(),
        List.of(new ExperimentDocument.OutputRequest("report", "text/markdown", "report.md")),
        new ExperimentDocument.Provenance(
            "HTTP Test",
            "",
            Instant.parse("2026-07-23T12:00:00Z"),
            Instant.parse("2026-07-23T12:00:00Z"),
            "0.0.4-SNAPSHOT",
            ZERO_SHA256,
            List.of()));
  }
}
