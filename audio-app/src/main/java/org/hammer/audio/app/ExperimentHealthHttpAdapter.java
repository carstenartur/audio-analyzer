package org.hammer.audio.app;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.hammer.audio.acquisition.health.ExperimentHealthSnapshot;
import org.hammer.audio.experiment.document.ExperimentDocumentService;
import org.hammer.audio.experiment.document.ExperimentDocumentSession;
import org.hammer.audio.experiment.document.workspace.ExperimentDocumentWorkspaceService;
import org.hammer.audio.experiment.health.ExperimentDocumentHealthProjector;
import org.hammer.audio.experiment.health.WorkflowExperimentHealthProjector;
import org.hammer.audio.workflow.WorkflowValidator;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.WorkflowSessionException;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionRevisionConflictException;
import org.hammer.audio.workflow.editor.http.CollaborationIdentityException;
import org.hammer.audio.workflow.editor.http.CollaborationIdentityService;
import org.hammer.audio.workflow.editor.http.WorkflowSessionApiModels.ActorResponse;
import org.hammer.audio.workflow.execution.WorkflowRunException;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Command;
import org.hammer.audio.workflow.execution.WorkflowRunModels.LiveSessionSource;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Preflight;
import org.hammer.audio.workflow.execution.WorkflowRunModels.StoredCommitSource;
import org.hammer.audio.workflow.execution.WorkflowRunService;
import org.hammer.audio.workflow.execution.http.WorkflowRunApiModels.RunResponse;
import org.hammer.audio.workflow.execution.http.WorkflowRunApiModels.RunResultResponse;
import org.hammer.audio.workflow.execution.http.WorkflowRunHttpAdapter;
import org.hammer.audio.workflow.store.CommitId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only health over existing document, authorized workflow and execution services. */
@RestController
@RequestMapping("/workflow/experiment-health")
public final class ExperimentHealthHttpAdapter {
  private final ExperimentDocumentWorkspaceService workspace;
  private final ExperimentDocumentService documents;
  private final WorkflowSessionRegistry sessions;
  private final CollaborationIdentityService identities;
  private final WorkflowRunService runs;
  private final WorkflowRunHttpAdapter runQueries;

  /** Use the same backend and authority boundaries as execution and document editing. */
  public ExperimentHealthHttpAdapter(
      ExperimentDocumentWorkspaceService workspace,
      ExperimentDocumentService documents,
      WorkflowSessionRegistry sessions,
      CollaborationIdentityService identities,
      WorkflowRunService runs,
      WorkflowRunHttpAdapter runQueries) {
    this.workspace = workspace;
    this.documents = documents;
    this.sessions = sessions;
    this.identities = identities;
    this.runs = runs;
    this.runQueries = runQueries;
  }

  /** Query one explicit source; no selector means the local document/workflow workspace. */
  @GetMapping
  public ExperimentHealthSnapshot status(
      @RequestParam(name = "sessionId", required = false) String sessionId,
      @RequestParam(name = "revision", required = false) Long revision,
      @RequestParam(name = "commitId", required = false) String commitId,
      @RequestParam(name = "runId", required = false) String runId,
      HttpServletRequest request)
      throws IOException {
    int selections =
        (sessionId == null ? 0 : 1) + (commitId == null ? 0 : 1) + (runId == null ? 0 : 1);
    if (selections > 1) {
      throw new IllegalArgumentException("Select one document, session revision, commit or run.");
    }
    if (runId != null) {
      return runtime(runId, request);
    }
    if (sessionId != null) {
      return session(sessionId, revision, request);
    }
    if (commitId != null) {
      return stored(commitId);
    }
    ExperimentDocumentWorkspaceService.ReadinessContext context = workspace.readinessContext();
    ExperimentDocumentSession.Context document = context.document();
    return ExperimentDocumentHealthProjector.project(
        new ExperimentDocumentHealthProjector.Input(
            document.preview(),
            document.status().path(),
            document.status().dirty() || context.workflowDirty(),
            document.revision(),
            context.workflowSha256(),
            new WorkflowValidator().validate(context.workflow()),
            documents.pluginSections().toString()),
        Instant.now());
  }

  private ExperimentHealthSnapshot session(String id, Long revision, HttpServletRequest request) {
    if (revision == null) {
      throw new IllegalArgumentException(
          "A session readiness check requires its displayed revision.");
    }
    OperationActor actor = identities.requireActor(request, null);
    // Capture membership and graph under one session lock; never re-read a recreated private id.
    WorkflowSessionRegistry.SessionWorkflowSnapshot captured;
    try {
      captured = sessions.authorizedWorkflow(id, actor);
    } catch (WorkflowSessionException denied) {
      // A failed private-source membership check never exposes session metadata through health.
      throw new CollaborationIdentityException(denied);
    }
    ActorResponse owner = identities.project(captured.session().owner(), "OWNER");
    ActorResponse operator = identities.project(actor, "EDITOR");
    Map<String, String> facts =
        Map.of(
            "workflow.sessionId",
            id,
            "workflow.revision",
            Long.toString(captured.session().revision()),
            "collaboration.owner",
            owner.displayName() + " (" + owner.provenance() + ")",
            "operator.attribution",
            operator.displayName() + " (" + operator.provenance() + ")");
    try {
      Preflight preflight =
          runs.preflight(
              new Command("readiness.session", new LiveSessionSource(id, revision)), captured);
      return WorkflowExperimentHealthProjector.ready(preflight, facts, Instant.now());
    } catch (WorkflowSessionRevisionConflictException exception) {
      return WorkflowExperimentHealthProjector.blocked(
          "workflow.revision-changed", exception.getMessage(), facts, Instant.now());
    } catch (WorkflowRunException exception) {
      return rejected(exception, facts);
    }
  }

  private ExperimentHealthSnapshot stored(String commit) {
    Map<String, String> facts = Map.of("workflow.commit", commit);
    try {
      return WorkflowExperimentHealthProjector.ready(
          runs.preflight(
              new Command("readiness.commit", new StoredCommitSource(new CommitId(commit)))),
          facts,
          Instant.now());
    } catch (WorkflowRunException exception) {
      return rejected(exception, facts);
    }
  }

  private ExperimentHealthSnapshot runtime(String id, HttpServletRequest request) {
    // Reuse the run adapter's retained-owner/member grants, including after session closure.
    RunResponse run = runQueries.inspect(id, request);
    WorkflowRunHttpAdapter.RunAttribution attribution =
        runQueries.authorizedAttribution(id, request);
    RunResultResponse result = null;
    if (run.finishedAt() != null) {
      try {
        result = runQueries.result(id, request);
      } catch (WorkflowRunException unavailable) {
        // The projector explicitly reports missing terminal evidence as incomplete.
        result = null;
      }
    }
    return WorkflowExperimentHealthProjector.runtime(
        run,
        result,
        Map.of(
            "operator.attribution",
            publicAttribution(attribution.creator()),
            "collaboration.owner",
            publicAttribution(attribution.owner())),
        Instant.now());
  }

  private static String publicAttribution(ActorResponse actor) {
    return actor == null
        ? "Unavailable: original run actor was not recorded"
        : actor.displayName() + " (" + actor.provenance() + ")";
  }

  private static ExperimentHealthSnapshot rejected(
      WorkflowRunException exception, Map<String, String> facts) {
    String detail = exception.getMessage() + " " + exception.violations();
    return WorkflowExperimentHealthProjector.blocked(
        "workflow." + exception.code(), detail, facts, Instant.now());
  }

  /** Keep transport failures explicit; identity failures retain their dedicated 403 handler. */
  @ExceptionHandler({IOException.class, IllegalArgumentException.class})
  public ResponseEntity<Map<String, String>> failure(Exception exception) {
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "code",
                "experiment-health",
                "detail",
                exception.getMessage() == null
                    ? "Readiness could not be inspected"
                    : exception.getMessage()));
  }
}
