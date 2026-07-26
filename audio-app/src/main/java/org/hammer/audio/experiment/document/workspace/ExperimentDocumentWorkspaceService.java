package org.hammer.audio.experiment.document.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;
import org.hammer.audio.experiment.document.ExperimentDocumentPreview;
import org.hammer.audio.experiment.document.ExperimentDocumentService;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.editor.WorkflowEditorService;
import org.hammer.audio.workflow.editor.WorkflowProjection;

/** Coordinates confirmed portable-document application with the server-authoritative editor. */
public final class ExperimentDocumentWorkspaceService {

  private final ExperimentDocumentService documentService;
  private final WorkflowEditorService editorService;
  private final WorkflowSessionRegistry sessionRegistry;

  /** Create a coordinator without collaboration-session awareness for focused tests/tools. */
  public ExperimentDocumentWorkspaceService(
      ExperimentDocumentService documentService, WorkflowEditorService editorService) {
    this(documentService, editorService, null);
  }

  /** Create the application coordinator with collaboration-session protection. */
  public ExperimentDocumentWorkspaceService(
      ExperimentDocumentService documentService,
      WorkflowEditorService editorService,
      WorkflowSessionRegistry sessionRegistry) {
    this.documentService = Objects.requireNonNull(documentService, "documentService");
    this.editorService = Objects.requireNonNull(editorService, "editorService");
    this.sessionRegistry = sessionRegistry;
  }

  /**
   * Revalidate and atomically import one explicitly confirmed document.
   *
   * @param source untrusted request body
   * @param expectedCanonicalSha256 hash shown during preview and confirmed by the caller
   * @param discardDirty whether unsaved current state may be discarded
   * @return applied projection and immutable document identity
   */
  public ApplyResult apply(InputStream source, String expectedCanonicalSha256, boolean discardDirty)
      throws IOException {
    ExperimentDocumentPreview preview = documentService.preview(source);
    if (!preview.executionAllowed()) {
      throw new ExperimentDocumentApplyException(
          "document-incompatible", "Experiment document has blocking compatibility diagnostics");
    }
    if (preview.readOnly()) {
      throw new ExperimentDocumentApplyException(
          "document-read-only", "Experiment document is available for read-only inspection only");
    }
    assertNoActiveCollaboration();
    String expected = normalizeExpectedHash(expectedCanonicalSha256);
    if (!sameHash(expected, preview.canonicalSha256())) {
      throw new ExperimentDocumentApplyException(
          "document-hash-mismatch", "Preview hash no longer matches the uploaded document");
    }
    WorkflowProjection projection =
        editorService.importGraph(documentService.workflow(preview), discardDirty);
    return new ApplyResult(preview.canonicalSha256(), projection, true);
  }

  private void assertNoActiveCollaboration() {
    if (sessionRegistry == null) {
      return;
    }
    boolean active =
        sessionRegistry.sessions().stream().anyMatch(session -> !session.participants().isEmpty());
    if (active) {
      throw new ExperimentDocumentApplyException(
          "collaboration-active",
          "Leave active collaboration sessions before replacing the single-user workflow");
    }
  }

  private static String normalizeExpectedHash(String value) {
    String expected = Objects.requireNonNull(value, "expectedCanonicalSha256").trim();
    if (expected.startsWith("W/")) {
      throw new ExperimentDocumentApplyException(
          "invalid-document-hash", "Weak If-Match values are not accepted");
    }
    if (expected.length() >= 2 && expected.startsWith("\"") && expected.endsWith("\"")) {
      expected = expected.substring(1, expected.length() - 1);
    }
    expected = expected.toLowerCase(Locale.ROOT);
    if (expected.length() != 64
        || !expected.chars().allMatch(ExperimentDocumentWorkspaceService::hex)) {
      throw new ExperimentDocumentApplyException(
          "invalid-document-hash", "If-Match must contain one SHA-256 value");
    }
    return expected;
  }

  private static boolean hex(int character) {
    return character >= '0' && character <= '9' || character >= 'a' && character <= 'f';
  }

  private static boolean sameHash(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
  }

  /**
   * Successful confirmed import response.
   *
   * @param canonicalSha256 canonical imported document digest
   * @param projection resulting server-authoritative workflow projection
   * @param dirty whether the imported workflow has uncheckpointed changes
   */
  public record ApplyResult(String canonicalSha256, WorkflowProjection projection, boolean dirty) {

    /** Validate immutable result values. */
    public ApplyResult {
      Objects.requireNonNull(canonicalSha256, "canonicalSha256");
      Objects.requireNonNull(projection, "projection");
    }
  }
}
