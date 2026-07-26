package org.hammer.audio.experiment.document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.hammer.audio.plugin.document.DocumentDiagnostic;
import org.junit.jupiter.api.Test;

class ExperimentDocumentFixtureTest {

  private final ExperimentDocumentCodec codec = new ExperimentDocumentCodec();

  @Test
  void documentedMinimalFixtureIsCanonicalAndByteStable() throws Exception {
    Path fixture = fixture("minimal.audioexp");
    byte[] original = Files.readAllBytes(fixture);

    ExperimentDocument document = codec.decode(original);

    assertEquals("example.experiment", document.experiment().id());
    assertArrayEquals(original, codec.encode(document));
  }

  @Test
  void documentedUnknownOptionalPluginFixtureIsPreservedReadOnly() throws Exception {
    Path fixture = fixture("unknown-optional-plugin.audioexp");
    byte[] original = Files.readAllBytes(fixture);
    ExperimentDocument document = codec.decode(original);

    ExperimentDocumentPreview preview = PluginDocumentCatalog.empty().preview(document, codec);

    assertTrue(preview.readOnly());
    assertTrue(preview.executionAllowed());
    assertTrue(
        preview.diagnostics().stream()
            .anyMatch(
                item ->
                    item.severity() == DocumentDiagnostic.Severity.WARNING
                        && item.code().equals("missing-plugin")));
    assertArrayEquals(original, codec.encode(preview.document()));
  }

  private static Path fixture(String filename) {
    String root = System.getProperty("maven.multiModuleProjectDirectory", "..");
    return Path.of(root).resolve("docs").resolve("examples").resolve(filename).normalize();
  }
}
