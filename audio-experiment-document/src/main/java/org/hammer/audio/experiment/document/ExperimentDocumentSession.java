package org.hammer.audio.experiment.document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.history.WorkflowDiff;

/**
 * Full portable-document editing lifecycle; local paths and bindings never enter the envelope. The
 * session monitor also protects adapters' compound revision-check/save transactions.
 */
@SuppressWarnings("PMD.AvoidSynchronizedAtMethodLevel")
public final class ExperimentDocumentSession {

  private final ExperimentDocumentService service;
  private final ExperimentDocumentCodec codec = new ExperimentDocumentCodec();
  private final Clock clock;
  private final List<Path> recent = new ArrayList<>();
  private ExperimentDocumentPreview current;
  private Path original;
  private Path path;
  private Instant lastSavedAt;
  private String savedHash;
  private boolean dirty;
  private String workflowDraft;
  private long stateRevision;

  /** Create a document session using the system clock for explicit edits and saves. */
  public ExperimentDocumentSession(ExperimentDocumentService service) {
    this(service, Clock.systemUTC());
  }

  /** Create a session with a deterministic clock for reproducible lifecycle tests. */
  public ExperimentDocumentSession(ExperimentDocumentService service, Clock clock) {
    this.service = Objects.requireNonNull(service, "service");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Apply a preview only after the caller's explicit dirty-state decision. */
  public synchronized boolean open(
      ExperimentDocumentPreview preview, Path source, DirtyDecision decision, Path saveTarget)
      throws IOException {
    Objects.requireNonNull(preview, "preview");
    Objects.requireNonNull(decision, "decision");
    if (dirty) {
      if (decision == DirtyDecision.CANCEL) {
        return false;
      }
      if (decision == DirtyDecision.SAVE) {
        if (saveTarget != null) {
          saveAs(saveTarget);
        } else {
          save();
        }
      }
    }
    if (Thread.currentThread().isInterrupted()) {
      throw new java.io.InterruptedIOException("Document open cancelled");
    }
    current = preview;
    original = source == null ? null : source.toAbsolutePath().normalize();
    path = null;
    lastSavedAt = null;
    savedHash = source == null ? null : preview.canonicalSha256();
    workflowDraft = null;
    dirty = source == null || !preview.migrations().isEmpty();
    stateRevision++;
    if (original != null) {
      remember(original);
    }
    return true;
  }

  /** Apply only while the local session still matches the state shown in the preview. */
  public synchronized boolean openIfCurrent(
      ExperimentDocumentPreview preview,
      Path source,
      DirtyDecision decision,
      Path saveTarget,
      long expectedRevision)
      throws IOException {
    requireRevision(expectedRevision);
    return open(preview, source, decision, saveTarget);
  }

  /** Local generation for modeless editors and confirmation previews. */
  public synchronized long revision() {
    return stateRevision;
  }

  /** Retain incomplete workflow editing without treating it as validated executable data. */
  public synchronized void editWorkflow(String draft, long expectedRevision) {
    requireRevision(expectedRevision);
    requireCurrent();
    if (current.readOnly()) {
      throw new IllegalStateException("Document is read-only");
    }
    if (draft.length() > ExperimentDocumentFormat.MAX_DOCUMENT_BYTES) {
      throw new IllegalArgumentException("Workflow draft exceeds the document size limit");
    }
    workflowDraft = draft.equals(current.document().workflow().content()) ? null : draft;
    dirty = workflowDraft != null || !current.canonicalSha256().equals(savedHash);
    stateRevision++;
  }

  /** Current draft text for the editable document surface. */
  public synchronized String workflowText() {
    requireCurrent();
    return workflowDraft == null ? current.document().workflow().content() : workflowDraft;
  }

  /** Validate the current draft through the established workflow parser and document service. */
  public synchronized void validateDraft() throws ExperimentDocumentException {
    if (workflowDraft != null) {
      updateWorkflow(new org.hammer.audio.workflow.dsl.WorkflowDslParser().parse(workflowDraft));
      workflowDraft = null;
      dirty = !current.canonicalSha256().equals(savedHash);
    }
  }

  /** Reject stale actions from a modeless editor rather than modifying a different document. */
  public synchronized void requireRevision(long expectedRevision) {
    if (stateRevision != expectedRevision) {
      throw new IllegalStateException(
          "Document changed in another window; reopen the current editor or preview");
    }
  }

  /** Semantic differences against the current setup, without changing it. */
  public synchronized WorkflowDiff diff(ExperimentDocumentPreview preview) {
    Workflow incoming = service.workflow(preview);
    Workflow before =
        current == null
            ? new Workflow(incoming.id(), incoming.name(), List.of(), List.of())
            : service.workflow(current);
    return WorkflowDiff.compute(before, incoming);
  }

  /** Replace only the workflow, retaining profiles, plugins, assets, outputs and provenance. */
  public synchronized void updateWorkflow(Workflow workflow) throws ExperimentDocumentException {
    requireCurrent();
    if (current.readOnly()) {
      throw new IllegalStateException(
          "Unsupported document is read-only; export an unchanged copy");
    }
    if (service.workflow(current).equals(workflow)) {
      return;
    }
    ExperimentDocument changed =
        service.withWorkflow(current.document(), workflow, clock.instant());
    ExperimentDocumentPreview candidate = service.preview(codec.encode(changed));
    if (candidate.readOnly() || !candidate.executionAllowed()) {
      throw new ExperimentDocumentException(
          "/workflow",
          "document-edit-invalid",
          "Edited workflow is incompatible with document requirements");
    }
    if (Thread.currentThread().isInterrupted()) {
      throw new ExperimentDocumentException(
          "/workflow", "document-cancelled", "Document edit cancelled");
    }
    current = candidate;
    dirty = !candidate.canonicalSha256().equals(savedHash);
    workflowDraft = null;
    stateRevision++;
  }

  /** Save to the previously selected copy; first save requires an explicit Save As destination. */
  public synchronized void save() throws IOException {
    requireCurrent();
    if (path == null) {
      throw new IOException("Select a Save As destination before the first save");
    }
    if (current.readOnly()) {
      throw new IOException("Read-only documents can only be exported with Save As");
    }
    write(path);
  }

  /**
   * Atomically write an explicitly selected normalized copy without overwriting the imported
   * source.
   */
  public synchronized void saveAs(Path target) throws IOException {
    requireCurrent();
    Path selected = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
    if (original != null
        && (original.equals(selected)
            || Files.exists(selected) && Files.isSameFile(original, selected))) {
      throw new IOException("Save As requires a destination distinct from the imported original");
    }
    write(selected);
  }

  /** Return current canonical bytes for a browser download or evidence export. */
  public synchronized byte[] bytes() throws ExperimentDocumentException {
    requireCurrent();
    validateDraft();
    return codec.encode(current.document());
  }

  /** Record an explicit successful download without placing browser paths in the document. */
  public synchronized void downloaded(String canonicalHash) {
    requireCurrent();
    if (workflowDraft != null || !current.canonicalSha256().equals(canonicalHash)) {
      throw new IllegalStateException("The downloaded snapshot is no longer current");
    }
    savedHash = canonicalHash;
    lastSavedAt = clock.instant();
    dirty = false;
    stateRevision++;
  }

  /** Local imported source protected from overwrite by document and evidence export. */
  public synchronized Path originalPath() {
    return original;
  }

  /** Return the retained complete immutable preview. */
  public synchronized ExperimentDocumentPreview preview() {
    requireCurrent();
    return current;
  }

  /** Capture the retained preview, lifecycle metadata and local generation atomically. */
  public synchronized Context context() {
    return new Context(current, status(), stateRevision);
  }

  /** Return local lifecycle metadata; do not embed this value in exported documents. */
  public synchronized Status status() {
    return new Status(
        current != null,
        path,
        dirty,
        lastSavedAt,
        current == null ? null : current.canonicalSha256(),
        current != null && current.readOnly(),
        List.copyOf(recent));
  }

  private void write(Path selected) throws IOException {
    validateDraft();
    codec.save(selected, current.document());
    path = selected;
    savedHash = current.canonicalSha256();
    lastSavedAt = clock.instant();
    dirty = false;
    stateRevision++;
    remember(selected);
  }

  private void remember(Path selected) {
    recent.remove(selected);
    recent.addFirst(selected);
    if (recent.size() > 10) {
      recent.removeLast();
    }
  }

  private void requireCurrent() {
    if (current == null) {
      throw new IllegalStateException("No experiment document is open");
    }
  }

  /** Explicit response to a dirty-document replacement decision. */
  public enum DirtyDecision {
    SAVE,
    DISCARD,
    CANCEL
  }

  /**
   * One immutable document-session snapshot; a closed session has no preview.
   *
   * @param preview validated retained document, or null when no document is open
   * @param status lifecycle metadata from the same session generation
   * @param revision local generation for correlating edits and confirmation previews
   */
  public record Context(ExperimentDocumentPreview preview, Status status, long revision) {
    /* Require lifecycle metadata even when no document is open. */
    public Context {
      Objects.requireNonNull(status, "status");
    }

    /** Represent a host that has not opened a document or attached its document adapter. */
    public static Context empty() {
      return new Context(null, new Status(false, null, false, null, null, false, List.of()), 0);
    }
  }

  /**
   * Session metadata local to this application, including a maximum of ten recent files.
   *
   * @param open whether a complete document is retained
   * @param path selected local copy path, if any
   * @param dirty whether edits differ from the saved snapshot
   * @param lastSavedAt last successful local save or browser download acknowledgement
   * @param canonicalSha256 digest of the validated complete document
   * @param readOnly whether editing is blocked by compatibility diagnostics
   * @param recentFiles most recent local paths, newest first
   */
  public record Status(
      boolean open,
      Path path,
      boolean dirty,
      Instant lastSavedAt,
      String canonicalSha256,
      boolean readOnly,
      List<Path> recentFiles) {
    /* Defensively copy the local recent-file list. */
    public Status {
      recentFiles = List.copyOf(recentFiles);
    }
  }
}
