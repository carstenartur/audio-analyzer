package org.hammer.audio.experiment.document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.hammer.audio.plugin.document.DocumentValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExperimentDocumentSecurityTest {

  private final ExperimentDocumentCodec codec = new ExperimentDocumentCodec();
  private final ObjectMapper mapper = new ObjectMapper();
  @TempDir Path temporaryDirectory;

  @Test
  void trailingJsonAndNonUtf8AreRejected() throws Exception {
    String json = Files.readString(fixture("minimal.audioexp"));
    for (String suffix : List.of("{}", "[]", "true", " garbage")) {
      assertEquals("invalid-json", reject((json + suffix).getBytes(StandardCharsets.UTF_8)).code());
    }
    assertThrows(
        ExperimentDocumentException.class,
        () -> codec.decode(json.getBytes(StandardCharsets.UTF_16)));
    byte[] malformed = {(byte) 0xc3, (byte) 0x28};
    assertEquals("invalid-utf8", reject(malformed).code());
  }

  @Test
  void limitsApplyBeforeCoreOrPluginInterpretation() throws Exception {
    assertEquals(
        "max-bytes", reject(new byte[ExperimentDocumentFormat.MAX_DOCUMENT_BYTES + 1]).code());
    assertEquals(
        "max-collection",
        reject(("[" + "0,".repeat(10_000) + "0]").getBytes(StandardCharsets.UTF_8)).code());
    assertEquals(
        "invalid-json",
        reject(("[".repeat(65) + "0" + "]".repeat(65)).getBytes(StandardCharsets.UTF_8)).code());
    String oversizedString = "[\"" + "x".repeat(1_000_001) + "\"]";
    assertTrue(
        List.of("invalid-json", "max-string")
            .contains(reject(oversizedString.getBytes(StandardCharsets.UTF_8)).code()));
  }

  @Test
  void futureAndFractionalVersionsCannotBeInterpretedAsVersionOne() throws Exception {
    ObjectNode root = minimal();
    root.put("formatVersion", 2);
    assertEquals("unsupported-version", reject(root).code());
    root.put("formatVersion", 1.5);
    assertEquals("expected-positive-integer", reject(root).code());
    root.put("formatVersion", 1.0);
    assertEquals(1, codec.decode(mapper.writeValueAsBytes(root)).formatVersion());
    root.put("formatVersion", 1);
    ((ObjectNode) root.path("workflow")).put("formatVersion", 2);
    assertEquals("schema-invalid", reject(root).code());
  }

  @Test
  void publicSchemaRejectsUnknownCoreFieldsAndUnsafeShapes() throws Exception {
    ObjectNode root = minimal();
    ((ObjectNode) root.path("profiles")).put("javaClass", "java.lang.ProcessBuilder");
    assertEquals("schema-invalid", reject(root).code());
    root = minimal();
    ((ObjectNode) root.path("profiles"))
        .set("capture", mapper.readTree("{\"deviceId\":\"host-secret-device\"}"));
    assertEquals("schema-invalid", reject(root).code());
    root = minimal();
    ((ObjectNode) root.path("provenance")).put("password", "must-not-be-imported");
    assertEquals("schema-invalid", reject(root).code());
    root = minimal();
    root.withArray("assets").add("not-an-asset");
    assertEquals("schema-invalid", reject(root).code());
    assertFalse(Files.exists(temporaryDirectory.resolve("executed")));
  }

  @Test
  void portableNamesAreIndependentOfTheHostOperatingSystem() {
    for (String name :
        List.of(
            ".",
            "..",
            "../report",
            "/tmp/report",
            "C:\\report",
            "a/b",
            "a\\b",
            "report.",
            "report ",
            "NUL",
            "con.txt",
            "LPT1.log",
            "a:b",
            "a?b",
            "a\nb")) {
      assertThrows(IllegalArgumentException.class, () -> PortableNames.requireBaseName(name), name);
    }
    for (String path : List.of(".", "a/../b", "a//b", "a/", "/b", "a/NUL/b")) {
      assertThrows(
          IllegalArgumentException.class, () -> PortableNames.requireRelativePath(path), path);
    }
    assertEquals("assets/input.aarec", PortableNames.requireRelativePath("assets/input.aarec"));
  }

  @Test
  void exactPluginDecimalsAndEquivalentWorkflowSyntaxHaveStableHashes() throws Exception {
    ExperimentDocument base = codec.load(fixture("minimal.audioexp"));
    BigDecimal decimal = new BigDecimal("0.123456789012345678901234567890123456789");
    ExperimentDocument.PluginSection section =
        new ExperimentDocument.PluginSection(
            1,
            "opaque/1",
            DocumentValue.object(Map.of("precision", DocumentValue.number(decimal))));
    ExperimentDocument source =
        new ExperimentDocument(
            base.schema(),
            base.format(),
            base.formatVersion(),
            base.experiment(),
            base.workflow(),
            base.profiles(),
            List.of(),
            Map.of("unknown", Map.of("settings", section)),
            base.assets(),
            base.outputs(),
            base.provenance());
    byte[] encoded = codec.encode(source);
    ExperimentDocument loaded = codec.decode(encoded);
    assertEquals(section, loaded.pluginData().get("unknown").get("settings"));
    assertArrayEquals(encoded, codec.encode(loaded));

    String alternateDsl = "\n" + base.workflow().content().replace("workflow\n", "workflow\n\n");
    ExperimentDocument alternate =
        new ExperimentDocument(
            base.schema(),
            base.format(),
            base.formatVersion(),
            base.experiment(),
            new ExperimentDocument.WorkflowPayload(
                base.workflow().format(), 1, alternateDsl, base.workflow().sha256()),
            base.profiles(),
            base.requiredPlugins(),
            base.pluginData(),
            base.assets(),
            base.outputs(),
            base.provenance());
    assertEquals(codec.canonicalHash(base), codec.canonicalHash(alternate));
    assertArrayEquals(codec.encode(base), codec.encode(alternate));
  }

  @Test
  void workflowTrailingContentDuplicateMetadataAndDanglingEdgesAreRejected() throws Exception {
    ObjectNode root = minimal();
    ObjectNode workflow = (ObjectNode) root.path("workflow");
    String content = workflow.path("content").textValue();
    workflow.put("content", content + "script: touch executed\n");
    assertEquals("invalid-workflow", reject(root).code());
    workflow.put(
        "content",
        content.replace("  nodes:", "  metadata:\n    value: one\n    value: two\n  nodes:"));
    assertEquals("invalid-workflow", reject(root).code());
    workflow.put(
        "content",
        content
            + "    - id: dangling\n      sourceNodeId: missing\n      sourcePortId: out\n"
            + "      targetNodeId: absent\n      targetPortId: in\n");
    assertEquals("invalid-workflow", reject(root).code());
  }

  @Test
  void workflowCannotCarryLocalBindingsOrExecutableMetadata() throws Exception {
    for (String key :
        List.of("output.path", "capture.deviceId", "password", "javaClass", "shell.command")) {
      ObjectNode root = minimal();
      ObjectNode workflow = (ObjectNode) root.path("workflow");
      workflow.put(
          "content",
          workflow
              .path("content")
              .textValue()
              .replace("  nodes:", "  metadata:\n    " + key + ": forbidden\n  nodes:"));
      assertEquals("invalid-setup", reject(root).code(), key);
    }
  }

  @Test
  void saveAsProtectsSourceAliasesAndPredictablePartialSymlinks() throws Exception {
    Path source = temporaryDirectory.resolve("source.audioexp");
    Files.writeString(
        source, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(minimal()));
    byte[] original = Files.readAllBytes(source);
    ExperimentDocumentService service = new ExperimentDocumentService(List.of());
    Path symbolic =
        Files.createSymbolicLink(temporaryDirectory.resolve("symbolic.audioexp"), source);
    Path hard = Files.createLink(temporaryDirectory.resolve("hard.audioexp"), source);
    for (Path alias : List.of(source, symbolic, hard)) {
      assertThrows(IOException.class, () -> service.normalize(source, alias));
    }
    Path target = temporaryDirectory.resolve("copy.audioexp");
    Path victim = temporaryDirectory.resolve("unrelated.txt");
    Files.writeString(victim, "untouched");
    Files.createSymbolicLink(temporaryDirectory.resolve("copy.audioexp.partial"), victim);
    service.normalize(source, target);
    assertArrayEquals(original, Files.readAllBytes(source));
    assertEquals("untouched", Files.readString(victim));
    assertArrayEquals(codec.encode(codec.load(source)), Files.readAllBytes(target));
  }

  @Test
  void cliUsesTheSaveAsContractAndShowsTheCompleteDocument() throws Exception {
    Path source = temporaryDirectory.resolve("source.audioexp");
    Files.copy(fixture("minimal.audioexp"), source);
    byte[] original = Files.readAllBytes(source);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
      assertEquals(
          1,
          ExperimentDocumentCli.run(
              new String[] {"normalize", source.toString(), source.toString()}, stream, stream));
      assertArrayEquals(original, Files.readAllBytes(source));
      assertEquals(
          0,
          ExperimentDocumentCli.run(new String[] {"validate", source.toString()}, stream, stream));
      assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"provenance\""));
      assertEquals(
          2, ExperimentDocumentCli.run(new String[] {"invalid", "missing"}, stream, stream));
    }
  }

  private ObjectNode minimal() throws IOException {
    return (ObjectNode) mapper.readTree(Files.readAllBytes(fixture("minimal.audioexp")));
  }

  private ExperimentDocumentException reject(JsonNode root) throws IOException {
    return reject(mapper.writeValueAsBytes(root));
  }

  private ExperimentDocumentException reject(byte[] bytes) {
    return assertThrows(ExperimentDocumentException.class, () -> codec.decode(bytes));
  }

  static Path fixture(String name) {
    return Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."))
        .resolve("docs/examples")
        .resolve(name);
  }
}
