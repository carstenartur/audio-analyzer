package org.hammer.audio.experiment.document.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.hammer.audio.experiment.document.ExperimentDocumentCodec;
import org.hammer.audio.experiment.document.ExperimentDocumentFormat;
import org.hammer.audio.experiment.document.ExperimentDocumentService;
import org.hammer.audio.experiment.document.workspace.ExperimentDocumentWorkspaceService;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperationLog;
import org.hammer.audio.workflow.WorkflowValidator;
import org.hammer.audio.workflow.editor.WorkflowEditorService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ExperimentDocumentSessionHttpAdapterTest {
  private final ExperimentDocumentService service = new ExperimentDocumentService(List.of());
  private final WorkflowEditorService editor =
      new WorkflowEditorService(
          new WorkflowOperationLog(new Workflow("current", "Current", List.of(), List.of())),
          new WorkflowValidator());
  private final ExperimentDocumentWorkspaceService workspace =
      new ExperimentDocumentWorkspaceService(service, editor);
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(
              new ExperimentDocumentHttpAdapter(service, workspace),
              new ExperimentDocumentApplyHttpAdapter(workspace),
              new ExperimentDocumentSessionHttpAdapter(workspace, service))
          .build();

  @Test
  void previewRejectsUnsupportedMediaType() throws Exception {
    mvc.perform(
            post("/experiment-documents/preview")
                .contentType(MediaType.TEXT_PLAIN)
                .content(source()))
        .andExpect(status().isUnsupportedMediaType());
    assertEquals("current", editor.currentWorkflow().id());
  }

  @Test
  void applyRequiresBothSnapshotPreconditionsAndRejectsStaleCurrentGraph() throws Exception {
    byte[] source = source();
    String hash = service.preview(source).canonicalSha256();
    mvc.perform(
            post("/experiment-documents/apply")
                .contentType(ExperimentDocumentFormat.MEDIA_TYPE)
                .header(HttpHeaders.IF_MATCH, hash)
                .content(source))
        .andExpect(status().isBadRequest());
    String current = workspace.comparison(service.preview(source)).currentWorkflowSha256();
    editor.importGraph(new Workflow("later", "Later", List.of(), List.of()));
    mvc.perform(
            post("/experiment-documents/apply")
                .contentType(ExperimentDocumentFormat.MEDIA_TYPE)
                .header(HttpHeaders.IF_MATCH, hash)
                .header(ExperimentDocumentApplyHttpAdapter.CURRENT_WORKFLOW_HEADER, current)
                .header(ExperimentDocumentApplyHttpAdapter.DISCARD_DIRTY_HEADER, true)
                .content(source))
        .andExpect(status().isPreconditionFailed())
        .andExpect(jsonPath("$.code").value("current-workflow-changed"));
    assertEquals("later", editor.currentWorkflow().id());
  }

  @Test
  void independentCopyDownloadUsesFixedSafeAttachmentAndWholeDocument() throws Exception {
    byte[] source = source();
    ExperimentDocumentWorkspaceService.CopyResult copy =
        workspace.importCopy(
            new ByteArrayInputStream(source), service.preview(source).canonicalSha256());
    mvc.perform(get("/experiment-documents/copies/" + copy.id()))
        .andExpect(status().isOk())
        .andExpect(content().contentType(ExperimentDocumentFormat.MEDIA_TYPE))
        .andExpect(
            header()
                .string(
                    HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"experiment-copy.audioexp\""))
        .andExpect(content().bytes(source));
    assertEquals("current", editor.currentWorkflow().id());
  }

  @Test
  void unapprovedPathsAreRejectedBeforeFileInspectionOrExport(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    java.nio.file.Files.writeString(directory.resolve("report.md"), "private host result");
    String json =
        new ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "document",
                    new String(source(), StandardCharsets.UTF_8),
                    "outputDirectory",
                    directory.toString()));
    mvc.perform(
            post("/experiment-documents/bindings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("local-resource-not-approved"));
    mvc.perform(
            post("/experiment-documents/evidence")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("local-resource-not-approved"));
  }

  @Test
  void approvedResultExportWorksButOutsideAssetContentCannotBeRead(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    java.nio.file.Path approved =
        java.nio.file.Files.createDirectory(directory.resolve("approved"));
    java.nio.file.Files.writeString(approved.resolve("report.md"), "deliberately shared report");
    java.nio.file.Path secret =
        java.nio.file.Files.writeString(directory.resolve("secret"), "private host content");
    MockMvc authorized =
        MockMvcBuilders.standaloneSetup(
                new ExperimentDocumentSessionHttpAdapter(
                    workspace, service, new ExperimentLocalResourcePolicy(List.of(approved))))
            .build();
    String allowed =
        new ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "document",
                    new String(source(), StandardCharsets.UTF_8),
                    "outputDirectory",
                    approved.toString()));
    var pending =
        authorized
            .perform(
                post("/experiment-documents/evidence")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(allowed))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.request()
                    .asyncStarted())
            .andReturn();
    byte[] zipped =
        authorized
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(
                    pending))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/zip"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    boolean found = false;
    try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zipped))) {
      java.util.zip.ZipEntry entry = zip.getNextEntry();
      while (entry != null) {
        if ("results/report.md".equals(entry.getName())) {
          assertEquals(
              "deliberately shared report", new String(zip.readAllBytes(), StandardCharsets.UTF_8));
          found = true;
        }
        entry = zip.getNextEntry();
      }
    }
    org.junit.jupiter.api.Assertions.assertTrue(found);
    String forbidden =
        new ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "document",
                    new String(sourceWithAsset(secret), StandardCharsets.UTF_8),
                    "assetPaths",
                    Map.of("secret", secret.toString()),
                    "outputDirectory",
                    approved.toString()));
    for (String action : List.of("bindings", "evidence")) {
      authorized
          .perform(
              post("/experiment-documents/" + action)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(forbidden))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("local-resource-not-approved"));
    }
  }

  @Test
  void approvedOutputDirectoryDoesNotAuthorizeSymlinkedResultFiles(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    java.nio.file.Path approved =
        java.nio.file.Files.createDirectory(directory.resolve("approved"));
    java.nio.file.Path secret =
        java.nio.file.Files.writeString(directory.resolve("private-report"), "outside host data");
    java.nio.file.Files.createSymbolicLink(approved.resolve("report.md"), secret);
    MockMvc authorized =
        MockMvcBuilders.standaloneSetup(
                new ExperimentDocumentSessionHttpAdapter(
                    workspace, service, new ExperimentLocalResourcePolicy(List.of(approved))))
            .build();
    String json =
        new ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "document",
                    new String(source(), StandardCharsets.UTF_8),
                    "outputDirectory",
                    approved.toString()));
    var pending =
        authorized
            .perform(
                post("/experiment-documents/evidence")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.request()
                    .asyncStarted())
            .andReturn();
    byte[] zipped =
        authorized
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(
                    pending))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    boolean manifestFound = false;
    try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zipped))) {
      java.util.zip.ZipEntry entry = zip.getNextEntry();
      while (entry != null) {
        org.junit.jupiter.api.Assertions.assertNotEquals("results/report.md", entry.getName());
        if ("evidence-manifest.json".equals(entry.getName())) {
          var manifest = new ObjectMapper().readTree(zip.readAllBytes());
          assertEquals("report", manifest.path("unavailableOutputs").get(0).asText());
          manifestFound = true;
        }
        entry = zip.getNextEntry();
      }
    }
    org.junit.jupiter.api.Assertions.assertTrue(manifestFound);
  }

  @Test
  void uploadedFilenameCannotInfluenceSuggestedDownloadName() throws Exception {
    mvc.perform(
            post("/experiment-documents/normalize")
                .contentType(ExperimentDocumentFormat.MEDIA_TYPE)
                .header(
                    HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"../../private/unsafe.exe\"")
                .content(source()))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"normalized.audioexp\""));
  }

  @Test
  void bindingWrapperIsBoundedAndRejectsDuplicateKeys() throws Exception {
    mvc.perform(
            post("/experiment-documents/bindings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new byte[ExperimentBindingRequestReader.MAX_BYTES + 1]))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("max-binding-bytes"));
    mvc.perform(
            post("/experiment-documents/bindings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"document\":\"one\",\"document\":\"two\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void bindingReadinessReportsMissingOutputWithoutOpeningAnyDevice() throws Exception {
    String json =
        new ObjectMapper()
            .writeValueAsString(Map.of("document", new String(source(), StandardCharsets.UTF_8)));
    mvc.perform(
            post("/experiment-documents/bindings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ready").value(false))
        .andExpect(jsonPath("$.diagnostics[0].code").value("output-binding-missing"));
  }

  private static byte[] sourceWithAsset(java.nio.file.Path asset) throws Exception {
    org.hammer.audio.experiment.document.ExperimentDocument base =
        ExperimentDocumentHttpAdapterTest.document();
    var reference =
        new org.hammer.audio.experiment.document.ExperimentDocument.AssetReference(
            "secret",
            "assets/secret.txt",
            "text/plain",
            java.nio.file.Files.size(asset),
            org.hammer.audio.experiment.document.DocumentHashes.sha256(asset));
    return new ExperimentDocumentCodec()
        .encode(
            new org.hammer.audio.experiment.document.ExperimentDocument(
                base.schema(),
                base.format(),
                base.formatVersion(),
                base.experiment(),
                base.workflow(),
                base.profiles(),
                base.requiredPlugins(),
                base.pluginData(),
                List.of(reference),
                base.outputs(),
                base.provenance()));
  }

  private static byte[] source() throws Exception {
    return new ExperimentDocumentCodec().encode(ExperimentDocumentHttpAdapterTest.document());
  }
}
