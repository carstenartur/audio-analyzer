package org.hammer.audio.experiment.health;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.hammer.audio.acquisition.health.ExperimentHealthAggregator;
import org.hammer.audio.acquisition.health.ExperimentHealthFinding;
import org.hammer.audio.acquisition.health.ExperimentHealthFinding.Severity;
import org.hammer.audio.acquisition.health.ExperimentHealthSnapshot;
import org.hammer.audio.acquisition.health.ExperimentHealthState;
import org.hammer.audio.experiment.document.ExperimentDocument;
import org.hammer.audio.experiment.document.ExperimentDocumentPreview;
import org.hammer.audio.experiment.document.ExperimentLocalBindingPreview;
import org.hammer.audio.experiment.document.ExperimentLocalBindingService;
import org.hammer.audio.experiment.document.ExperimentLocalBindings;
import org.hammer.audio.plugin.document.DocumentDiagnostic;

/** Projects one correlated document/workflow snapshot without probing files or opening devices. */
// All maps are local to one immutable projection; no map instance is shared across calls.
@SuppressWarnings("PMD.UseConcurrentHashMap")
public final class ExperimentDocumentHealthProjector {

  private ExperimentDocumentHealthProjector() {
    // Shared immutable projection utility.
  }

  /** Inspect document compatibility and explicitly unresolved current-snapshot local bindings. */
  public static ExperimentHealthSnapshot project(Input input, Instant now) {
    Map<String, String> facts = new TreeMap<>();
    facts.put("readiness.scope", "EXPERIMENT_DOCUMENT");
    facts.put("workflow.sha256", input.workflowSha256());
    facts.put("workflow.validation", input.workflowViolations().isEmpty() ? "VALID" : "BLOCKED");
    facts.put("document.revision", Long.toString(input.revision()));
    facts.put("document.dirty", Boolean.toString(input.dirty()));
    facts.put("plugins.installed", input.installedPlugins());
    List<ExperimentHealthFinding> findings = new ArrayList<>();
    ExperimentDocumentPreview preview = input.preview();
    if (preview == null) {
      facts.put("document.status", "No experiment document is open");
      return snapshot(
          "", "Experiment document", ExperimentHealthState.NOT_CONFIGURED, facts, findings, now);
    }
    addDocumentFacts(input, facts);
    if (input.dirty()) {
      findings.add(
          finding(
              "document.dirty",
              Severity.WARNING,
              "Document has unsaved changes",
              "The canonical hash identifies the last validated snapshot. Validate and save or"
                  + " download current edits before preserving evidence.",
              "/",
              "save-document",
              now));
    }
    if (!preview.document().workflow().sha256().equals(input.workflowSha256())) {
      findings.add(
          finding(
              "workflow.document-mismatch",
              Severity.BLOCKING,
              "Workflow differs from the document snapshot",
              "Refresh the complete document from the current graph before using its hash as"
                  + " execution provenance.",
              "/workflow/sha256",
              "review-document",
              now));
    }
    preview.diagnostics().forEach(item -> findings.add(diagnostic("document", item, now)));
    if (!preview.executionAllowed() || preview.readOnly()) {
      findings.add(
          finding(
              "document.compatibility",
              Severity.BLOCKING,
              "Document compatibility blocks execution",
              "Resolve the required plugin, schema or migration diagnostics in document"
                  + " inspection.",
              "/requiredPlugins",
              "review-document",
              now));
    }
    addWorkflowFindings(input, findings, now);
    // No paths are supplied: this reuses the existing requirement checks without filesystem I/O.
    ExperimentLocalBindingPreview bindings =
        new ExperimentLocalBindingService()
            .inspect(preview, new ExperimentLocalBindings(Map.of(), null));
    bindings.diagnostics().forEach(item -> findings.add(diagnostic("bindings", item, now)));
    facts.put(
        "bindings.status",
        bindings.diagnostics().isEmpty() ? "NONE_REQUIRED" : "UNVERIFIED_FOR_CURRENT_SNAPSHOT");
    facts.put(
        "bindings.scope",
        "Import-candidate checks are not execution bindings for the current document"
            + " hash/revision");
    return snapshot(
        preview.document().experiment().id(),
        preview.document().experiment().name(),
        ExperimentHealthState.READY,
        facts,
        findings,
        now);
  }

  private static void addDocumentFacts(Input input, Map<String, String> facts) {
    ExperimentDocumentPreview preview = input.preview();
    ExperimentDocument document = preview.document();
    facts.put("document.name", document.experiment().name());
    facts.put(
        "document.path", input.path() == null ? "Unsaved local copy" : input.path().toString());
    facts.put("document.format", document.format() + " / " + document.formatVersion());
    facts.put("document.sha256", preview.canonicalSha256());
    facts.put(
        "document.hashState",
        input.dirty() ? "Validated snapshot; unsaved draft may differ" : "Current saved snapshot");
    facts.put("document.readOnly", Boolean.toString(preview.readOnly()));
    facts.put("document.migrations", String.join("; ", preview.migrations()));
    facts.put(
        "document.sourceCommit",
        document.provenance().sourceCommit().isEmpty()
            ? "Not supplied"
            : document.provenance().sourceCommit());
    facts.put("plugins.required", document.requiredPlugins().toString());
    facts.put("plugins.sectionHashes", preview.sectionHashes().toString());
    facts.put("plugins.algorithmVersions", document.provenance().algorithmVersions().toString());
    facts.put("source.mode", document.experiment().sourceMode());
    facts.put("outputs.requested", document.outputs().toString());
    facts.put("assets.required", document.assets().toString());
  }

  private static void addWorkflowFindings(
      Input input, List<ExperimentHealthFinding> findings, Instant now) {
    for (int index = 0; index < input.workflowViolations().size(); index++) {
      findings.add(
          finding(
              "workflow.validation." + index,
              Severity.BLOCKING,
              "Workflow validation failed",
              input.workflowViolations().get(index),
              "/workflow",
              "review-workflow",
              now));
    }
  }

  private static ExperimentHealthFinding diagnostic(
      String prefix, DocumentDiagnostic item, Instant now) {
    Severity severity =
        switch (item.severity()) {
          case ERROR -> Severity.BLOCKING;
          case WARNING -> Severity.WARNING;
          case INFO -> Severity.INFO;
        };
    return finding(
        prefix + "." + item.code() + ":" + item.pointer(),
        severity,
        item.message(),
        item.pointer() + ": " + item.message(),
        item.pointer(),
        "review-document",
        now);
  }

  private static ExperimentHealthFinding finding(
      String code,
      Severity severity,
      String message,
      String detail,
      String component,
      String remedy,
      Instant now) {
    return new ExperimentHealthFinding(
        code, severity, message, detail, component, now, 0L, remedy, false, false);
  }

  private static ExperimentHealthSnapshot snapshot(
      String id,
      String name,
      ExperimentHealthState state,
      Map<String, String> facts,
      List<ExperimentHealthFinding> findings,
      Instant now) {
    ExperimentHealthAggregator aggregate = new ExperimentHealthAggregator();
    aggregate.update("document", findings, now);
    return aggregate.snapshot(id, name, state, "DOCUMENT PREFLIGHT", facts, now);
  }

  /**
   * One atomic observation supplied by the desktop session or server workspace.
   *
   * @param preview retained validated document, or null when none is open
   * @param path current local document path, not a file to inspect
   * @param dirty whether current edits differ from the saved document
   * @param revision local document session revision
   * @param workflowSha256 SHA-256 of the same captured workflow graph
   * @param workflowViolations validation of that captured graph
   * @param installedPlugins installed package/section versions from the document service
   */
  public record Input(
      ExperimentDocumentPreview preview,
      Path path,
      boolean dirty,
      long revision,
      String workflowSha256,
      List<String> workflowViolations,
      String installedPlugins) {
    // Freeze the observations before handing them to either UI.
    public Input {
      workflowViolations = List.copyOf(workflowViolations);
    }
  }
}
