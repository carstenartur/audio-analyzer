package org.hammer.audio.workflow.editor.http;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.hammer.audio.workflow.history.WorkflowHistoryAccessException;
import org.hammer.audio.workflow.history.WorkflowMergeRejectedException;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.Conflict;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.StaleWorkflowHeadException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Machine-readable conflict mapping for explicit workflow-history commands. */
@RestControllerAdvice
public final class WorkflowHistoryCommandExceptionHandler {

  private static final String PROBLEM_BASE = "https://audio-analyzer.dev/problems/";

  /** Maps optimistic-concurrency history mutation conflicts to HTTP 409. */
  @ExceptionHandler(StaleWorkflowHeadException.class)
  public ProblemDetail handleStaleHead(
      StaleWorkflowHeadException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problem("stale-workflow-head", "Stale workflow head", exception.getMessage(), request);
    problem.setProperty("code", "STALE_WORKFLOW_HEAD");
    problem.setProperty("branch", exception.branch());
    problem.setProperty("expectedHeadCommitId", value(exception.expectedHead()));
    problem.setProperty("actualHeadCommitId", value(exception.actualHead()));
    return problem;
  }

  /** Maps unresolved semantic conflicts or invalid merged graphs to HTTP 409. */
  @ExceptionHandler(WorkflowMergeRejectedException.class)
  public ProblemDetail handleMergeRejected(
      WorkflowMergeRejectedException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problem(
            "workflow-merge-rejected",
            "Workflow merge rejected",
            exception.getMessage(),
            request);
    problem.setProperty("code", "WORKFLOW_MERGE_REJECTED");
    problem.setProperty(
        "conflicts", exception.unresolvedConflicts().stream().map(this::conflict).toList());
    problem.setProperty("validationViolations", exception.validationViolations());
    return problem;
  }

  /** Maps active collaboration history mutation conflicts to HTTP 409. */
  @ExceptionHandler(WorkflowHistoryAccessException.class)
  public ProblemDetail handleAccessConflict(
      WorkflowHistoryAccessException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problem(
            "workflow-history-access-conflict",
            "Workflow history access conflict",
            exception.getMessage(),
            request);
    problem.setProperty("code", "WORKFLOW_HISTORY_ACCESS_CONFLICT");
    problem.setProperty("branch", exception.branch());
    problem.setProperty("workflowId", exception.workflowId());
    return problem;
  }

  private Map<String, Object> conflict(Conflict conflict) {
    return Map.of(
        "conflictId", conflict.conflictId(),
        "kind", conflict.kind().name(),
        "elementKind", conflict.elementKind().name(),
        "elementId", conflict.elementId(),
        "fieldPath", conflict.fieldPath(),
        "allowedChoices", conflict.allowedChoices());
  }

  private static ProblemDetail problem(
      String name, String title, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
    problem.setTitle(title);
    problem.setType(URI.create(PROBLEM_BASE + name));
    problem.setInstance(URI.create(request.getRequestURI()));
    return problem;
  }

  private static String value(CommitId commitId) {
    return commitId == null ? "" : commitId.value();
  }
}
