package org.hammer.audio.experiment.document.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;
import org.hammer.audio.experiment.document.ExperimentDocumentPreview;
import org.hammer.audio.experiment.document.ExperimentDocumentService;
import org.hammer.audio.experiment.document.ExperimentDocumentSession;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.editor.WorkflowEditorService;
import org.hammer.audio.workflow.editor.WorkflowProjection;

/** Coordinates confirmed portable-document application with the server-authoritative editor. */
// The workspace monitor protects correlated document/editor snapshots and the bounded copy map.
@SuppressWarnings({"PMD.AvoidSynchronizedAtMethodLevel", "PMD.UseConcurrentHashMap"})
public final class ExperimentDocumentWorkspaceService {

  private final ExperimentDocumentService documentService;
  private final WorkflowEditorService editorService;
  private final WorkflowSessionRegistry sessionRegistry;
  private org.hammer.audio.experiment.document.ExperimentDocumentSession documentSession;
  private final java.util.Map<String, ExperimentDocumentPreview> retainedCopies =
      new java.util.LinkedHashMap<>();

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
    this.documentSession =
        new org.hammer.audio.experiment.document.ExperimentDocumentSession(documentService);
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
    return apply(source, expectedCanonicalSha256, discardDirty, null);
  }

  /** Apply only if both the candidate document and the current workflow match the preview. */
  public synchronized ApplyResult apply(
      InputStream source,
      String expectedCanonicalSha256,
      boolean discardDirty,
      String expectedCurrentWorkflowSha256)
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
    org.hammer.audio.workflow.Workflow before = editorService.currentWorkflow();
    if (expectedCurrentWorkflowSha256 != null
        && !sameHash(normalizeExpectedHash(expectedCurrentWorkflowSha256), workflowHash(before))) {
      throw new ExperimentDocumentApplyException(
          "current-workflow-changed",
          "The current workflow changed after preview; inspect the new diff");
    }
    boolean saved = savedCurrent(before);
    if (!discardDirty && documentSession.status().dirty() && !saved) {
      throw new org.hammer.audio.workflow.editor.DirtyWorkflowException();
    }
    org.hammer.audio.experiment.document.ExperimentDocumentSession replacement =
        new org.hammer.audio.experiment.document.ExperimentDocumentSession(documentService);
    replacement.open(
        preview,
        null,
        org.hammer.audio.experiment.document.ExperimentDocumentSession.DirtyDecision.DISCARD,
        null);
    WorkflowProjection projection =
        editorService.importGraphIfCurrent(
            documentService.workflow(preview), discardDirty || saved, before);
    documentSession = replacement;
    return new ApplyResult(preview.canonicalSha256(), projection, true);
  }

  /** Non-mutating comparison against the current server-authoritative workflow. */
  public synchronized Comparison comparison(ExperimentDocumentPreview preview) {
    WorkflowEditorService.CurrentState state = editorService.currentState();
    Workflow current = state.workflow();
    return new Comparison(
        workflowHash(current),
        (state.dirty() || documentSession.status().dirty()) && !savedCurrent(current),
        org.hammer.audio.experiment.document.ExperimentDocumentComparison.compare(
            current, documentService.workflow(preview)));
  }

  /** Return a complete current portable document, retaining imported plugin/profile/asset data. */
  public synchronized byte[] exportCurrent() throws IOException {
    refreshDocument();
    return documentSession.bytes();
  }

  /** Report local session metadata after incorporating the current graph snapshot. */
  public synchronized org.hammer.audio.experiment.document.ExperimentDocumentSession.Status status()
      throws IOException {
    ReadinessContext context = readinessContext();
    if (context.document().status().open()) {
      return context.document().status();
    }
    return new org.hammer.audio.experiment.document.ExperimentDocumentSession.Status(
        false, null, context.workflowDirty(), null, null, false, java.util.List.of());
  }

  /**
   * Capture document and editor facts for the same immutable graph without creating a document.
   * Candidate binding reports are deliberately outside this snapshot.
   */
  public synchronized ReadinessContext readinessContext() throws IOException {
    WorkflowEditorService.CurrentState state = editorService.currentState();
    if (documentSession.status().open()) {
      refreshDocument(state.workflow());
    }
    return new ReadinessContext(
        documentSession.context(), state.workflow(), workflowHash(state.workflow()), state.dirty());
  }

  /** Acknowledge exactly the downloaded canonical snapshot; stale acknowledgments are rejected. */
  public synchronized void downloaded(String canonicalHash) throws IOException {
    refreshDocument();
    documentSession.downloaded(normalizeExpectedHash(canonicalHash));
  }

  /** Retain an independent workspace copy without replacing the current graph or document. */
  public synchronized CopyResult importCopy(InputStream source, String expectedHash)
      throws IOException {
    ExperimentDocumentPreview preview = documentService.preview(source);
    if (!sameHash(normalizeExpectedHash(expectedHash), preview.canonicalSha256())) {
      throw new ExperimentDocumentApplyException(
          "document-hash-mismatch", "Imported copy differs from the preview");
    }
    if (retainedCopies.size() >= 32) {
      throw new ExperimentDocumentApplyException(
          "copy-limit", "Save or remove a workspace copy before importing another");
    }
    String id = "copy-" + java.util.UUID.randomUUID();
    retainedCopies.put(id, preview);
    return new CopyResult(
        id, preview.document().experiment().name(), preview.canonicalSha256(), preview.readOnly());
  }

  /** List independent copies retained in this local workspace. */
  public synchronized java.util.List<CopyResult> copies() {
    return retainedCopies.entrySet().stream()
        .map(
            entry ->
                new CopyResult(
                    entry.getKey(),
                    entry.getValue().document().experiment().name(),
                    entry.getValue().canonicalSha256(),
                    entry.getValue().readOnly()))
        .toList();
  }

  /** Export a selected retained copy, including unsupported optional sections. */
  public synchronized byte[] exportCopy(String id) throws IOException {
    ExperimentDocumentPreview copy = retainedCopies.get(id);
    if (copy == null) {
      throw new ExperimentDocumentApplyException("copy-missing", "Workspace copy does not exist");
    }
    return documentService.inspectionJson(copy).getBytes(StandardCharsets.UTF_8);
  }

  /** Explicitly remove a workspace copy after the user has saved or discarded it. */
  public synchronized void removeCopy(String id) {
    retainedCopies.remove(id);
  }

  private void refreshDocument() throws IOException {
    refreshDocument(editorService.currentState().workflow());
  }

  private void refreshDocument(Workflow current) throws IOException {
    if (documentSession.status().open()) {
      documentSession.updateWorkflow(current);
    } else {
      org.hammer.audio.experiment.document.ExperimentDocumentCodec codec =
          new org.hammer.audio.experiment.document.ExperimentDocumentCodec();
      ExperimentDocumentPreview created =
          documentService.preview(
              codec.encode(documentService.fromWorkflow(current, java.time.Instant.now())));
      documentSession.open(
          created,
          null,
          org.hammer.audio.experiment.document.ExperimentDocumentSession.DirtyDecision.DISCARD,
          null);
    }
  }

  private boolean savedCurrent(org.hammer.audio.workflow.Workflow current) {
    return documentSession.status().open()
        && !documentSession.status().dirty()
        && documentService.workflow(documentSession.preview()).equals(current);
  }

  private static String workflowHash(org.hammer.audio.workflow.Workflow workflow) {
    return org.hammer.audio.experiment.document.DocumentHashes.sha256(
        new org.hammer.audio.workflow.dsl.WorkflowDslSerializer().serialize(workflow));
  }

  /**
   * Preview precondition and semantic changes; no current state is changed.
   *
   * @param currentWorkflowSha256 current graph identity for stale-confirmation detection
   * @param dirty whether the complete current setup has unsaved changes
   * @param changes domain-level workflow differences
   */
  public record Comparison(
      String currentWorkflowSha256,
      boolean dirty,
      java.util.List<org.hammer.audio.experiment.document.ExperimentDocumentComparison.Change>
          changes) {
    /* Preserve immutable comparison output. */
    public Comparison {
      changes = java.util.List.copyOf(changes);
    }
  }

  /**
   * A separate local workspace copy; no imported path is exposed or embedded.
   *
   * @param id workspace-local copy identity
   * @param name experiment name
   * @param canonicalSha256 canonical complete-document digest
   * @param readOnly whether the copy can only be inspected and exported
   */
  public record CopyResult(String id, String name, String canonicalSha256, boolean readOnly) {
    // Immutable value; record components carry the complete state.
  }

  /**
   * Correlated document and workflow inputs for health reporting; no local bindings are implied.
   *
   * @param document retained document snapshot or a closed document-session context
   * @param workflow actual immutable editor graph captured for this context
   * @param workflowSha256 semantic identity of the captured graph's canonical DSL
   * @param workflowDirty editor checkpoint state at the time this graph was captured
   */
  public record ReadinessContext(
      ExperimentDocumentSession.Context document,
      Workflow workflow,
      String workflowSha256,
      boolean workflowDirty) {
    /* Require a complete immutable context for every captured editor graph. */
    public ReadinessContext {
      Objects.requireNonNull(document, "document");
      Objects.requireNonNull(workflow, "workflow");
      Objects.requireNonNull(workflowSha256, "workflowSha256");
    }
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

    /* Validate immutable result values. */
    public ApplyResult {
      Objects.requireNonNull(canonicalSha256, "canonicalSha256");
      Objects.requireNonNull(projection, "projection");
    }
  }
}
