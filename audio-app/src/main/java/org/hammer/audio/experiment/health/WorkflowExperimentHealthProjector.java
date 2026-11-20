package org.hammer.audio.experiment.health;

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
import org.hammer.audio.experiment.document.DocumentHashes;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Input;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Preflight;
import org.hammer.audio.workflow.execution.http.WorkflowRunApiModels.RunResponse;
import org.hammer.audio.workflow.execution.http.WorkflowRunApiModels.RunResultResponse;

/** Shared readiness and runtime projection for the existing immutable workflow executor. */
// Each call owns its sorted facts map and publishes only an immutable snapshot.
@SuppressWarnings("PMD.UseConcurrentHashMap")
public final class WorkflowExperimentHealthProjector {

  private static final String OUTPUT_DIGEST = "output.digest.sha256";
  private static final String COMPLETED = "COMPLETED";

  private WorkflowExperimentHealthProjector() {
    // Shared immutable projection utility.
  }

  /** Project a successful real backend preflight, without calling it full-document execution. */
  public static ExperimentHealthSnapshot ready(
      Preflight preflight, Map<String, String> attribution, Instant now) {
    Map<String, String> facts = baseFacts();
    facts.putAll(attribution);
    Input input = preflight.input();
    facts.put("workflow.sha256", input.fingerprint());
    facts.put(
        "workflow.revision",
        input.semanticRevision() == null ? "Not applicable" : input.semanticRevision().toString());
    facts.put(
        "workflow.commit", input.commitId() == null ? "Not applicable" : input.commitId().value());
    facts.put("workflow.validation", "VALIDATED_BY_EXECUTION_BACKEND");
    facts.put("backend.mode", preflight.mode().name());
    facts.put("backend.nodes", Integer.toString(input.plan().orderedNodeIds().size()));
    List<ExperimentHealthFinding> findings = new ArrayList<>();
    if (preflight.mode() == org.hammer.audio.workflow.execution.WorkflowRunModels.Mode.SIMULATION) {
      findings.add(
          finding(
              "workflow.simulation-only",
              Severity.WARNING,
              "Backend performs lifecycle simulation only",
              "No audio computation or captured samples are produced by this backend.",
              now));
    }
    return project(
        input.snapshot().workflowId(),
        input.snapshot().workflowId(),
        ExperimentHealthState.READY,
        "EXACT WORKFLOW PREFLIGHT",
        facts,
        findings,
        now);
  }

  /**
   * Show an explicit rejected capability/source check rather than an optimistic empty checklist.
   */
  public static ExperimentHealthSnapshot blocked(
      String code, String reason, Map<String, String> facts, Instant now) {
    Map<String, String> combined = baseFacts();
    combined.putAll(facts);
    return project(
        "",
        "Workflow execution",
        ExperimentHealthState.BLOCKED,
        "PREFLIGHT BLOCKED",
        combined,
        List.of(finding(code, Severity.BLOCKING, "Workflow is not ready to run", reason, now)),
        now);
  }

  /** Map actual executor lifecycle/results into the same immutable dashboard transport. */
  public static ExperimentHealthSnapshot runtime(
      RunResponse run, RunResultResponse result, Instant now) {
    return runtime(run, result, Map.of(), now);
  }

  /** Project original, authorized run attribution alongside active and final evidence. */
  public static ExperimentHealthSnapshot runtime(
      RunResponse run, RunResultResponse result, Map<String, String> attribution, Instant now) {
    Map<String, String> facts = baseFacts();
    facts.put("operator.attribution", "Original run creator was not recorded");
    facts.put("collaboration.owner", "Original session owner was not recorded");
    facts.putAll(attribution);
    facts.put("workflow.sha256", run.fingerprint());
    facts.put("workflow.revision", String.valueOf(run.source().semanticRevision()));
    facts.put("workflow.commit", String.valueOf(run.source().commitId()));
    facts.put("workflow.sessionId", String.valueOf(run.source().sessionId()));
    facts.put("backend.mode", run.mode());
    facts.put("run.progressPercent", Integer.toString(run.progressPercent()));
    facts.put("run.capturedAt", run.capturedAt().toString());
    facts.put("run.finishedAt", String.valueOf(run.finishedAt()));
    facts.put("evidence.scope", "WORKFLOW_COMPUTATION_ONLY");
    List<ExperimentHealthFinding> findings = new ArrayList<>();
    run.violations()
        .forEach(
            item ->
                findings.add(
                    finding(
                        "workflow." + item.code(),
                        Severity.ERROR,
                        item.message(),
                        item.nodeId() == null ? "Workflow execution" : "Node: " + item.nodeId(),
                        now)));
    boolean complete = COMPLETED.equals(run.state()) && completeOutputEvidence(result);
    if (result != null) {
      facts.put(
          "outputs.generated",
          String.join(", ", new java.util.TreeSet<>(result.artifacts().keySet())));
      result
          .artifacts()
          .forEach(
              (key, value) ->
                  facts.put("artifact." + key + ".sha256", DocumentHashes.sha256(value)));
      facts.put(
          "backend.version",
          result.artifacts().getOrDefault("backendVersion", "Not supplied by backend"));
      facts.put(OUTPUT_DIGEST, result.artifacts().getOrDefault(OUTPUT_DIGEST, "Not produced"));
    }
    if ("SIMULATION".equals(run.mode())) {
      complete = false;
      findings.add(
          finding(
              "workflow.simulation-only",
              Severity.WARNING,
              "Lifecycle simulation is not audio evidence",
              "This run contains execution-order simulation only; no audio computation was"
                  + " performed.",
              now));
    }
    if (COMPLETED.equals(run.state()) && !complete) {
      findings.add(
          finding(
              "workflow.evidence-incomplete",
              Severity.ERROR,
              "Computation completion does not establish complete evidence",
              "A required terminal result or output inventory is unavailable for this run.",
              now));
    }
    facts.put("evidence.complete", Boolean.toString(complete));
    ExperimentHealthState state =
        switch (run.state()) {
          case "QUEUED", "RUNNING" -> ExperimentHealthState.RUNNING;
          case "CANCEL_REQUESTED" -> ExperimentHealthState.STOPPING;
          case "CANCELLED" -> ExperimentHealthState.INCOMPLETE;
          case COMPLETED -> ExperimentHealthState.COMPLETED;
          default -> ExperimentHealthState.FAILED;
        };
    return project(run.runId(), run.workflowId(), state, run.statusMessage(), facts, findings, now);
  }

  private static boolean completeOutputEvidence(RunResultResponse result) {
    return result != null
        && COMPLETED.equals(result.overallStatus())
        && result.artifacts().getOrDefault(OUTPUT_DIGEST, "").matches("[0-9a-f]{64}")
        && !result.nodeStatuses().isEmpty()
        && result.nodeStatuses().values().stream()
            .allMatch(
                status -> status == org.hammer.audio.workflow.execution.ExecutionStatus.COMPLETED);
  }

  private static Map<String, String> baseFacts() {
    Map<String, String> facts = new TreeMap<>();
    facts.put("readiness.scope", "IMMUTABLE_WORKFLOW");
    facts.put(
        "document.execution",
        "Workflow graph only; portable document profiles, assets and requested files are not"
            + " execution bindings");
    facts.put(
        "capture.status", "Not applicable: this executor does not open live microphone devices");
    facts.put(
        "recording.status", "Not applicable: workflow result artifacts are not an audio recording");
    facts.put(
        "storage.status",
        "In-memory workflow result; document output storage must be checked separately");
    return facts;
  }

  private static ExperimentHealthFinding finding(
      String code, Severity severity, String message, String detail, Instant now) {
    return new ExperimentHealthFinding(
        code, severity, message, detail, "workflow", now, 0L, "review-workflow", false, false);
  }

  private static ExperimentHealthSnapshot project(
      String id,
      String name,
      ExperimentHealthState state,
      String stage,
      Map<String, String> facts,
      List<ExperimentHealthFinding> findings,
      Instant now) {
    ExperimentHealthAggregator aggregate = new ExperimentHealthAggregator();
    aggregate.update("workflow", findings, now);
    return aggregate.snapshot(id, name, state, stage, facts, now);
  }
}
