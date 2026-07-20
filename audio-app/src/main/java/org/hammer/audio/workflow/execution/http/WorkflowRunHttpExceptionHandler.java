package org.hammer.audio.workflow.execution.http;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import org.hammer.audio.workflow.execution.WorkflowRunException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 mapping for typed workflow-run failures. */
@RestControllerAdvice
public final class WorkflowRunHttpExceptionHandler {

  private static final String PROBLEM_BASE = "https://audio-analyzer.dev/problems/";

  /** Maps run lifecycle and preflight failures to stable problem responses. */
  @ExceptionHandler(WorkflowRunException.class)
  public ProblemDetail handleRunFailure(
      WorkflowRunException exception, HttpServletRequest request) {
    HttpStatus status = status(exception.code());
    String problemName = exception.code().name().toLowerCase(Locale.ROOT).replace('_', '-');
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
    problem.setTitle(title(problemName));
    problem.setType(URI.create(PROBLEM_BASE + problemName));
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", exception.code().name());
    if (exception.runId() != null) {
      problem.setProperty("runId", exception.runId());
    }
    if (exception.startCommandId() != null) {
      problem.setProperty("startCommandId", exception.startCommandId());
    }
    if (!exception.violations().isEmpty()) {
      problem.setProperty(
          "violations",
          exception.violations().stream()
              .map(WorkflowRunApiModels.ViolationResponse::from)
              .toList());
    }
    return problem;
  }

  private static HttpStatus status(WorkflowRunException.Code code) {
    return switch (code) {
      case UNKNOWN_RUN -> HttpStatus.NOT_FOUND;
      case SOURCE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
      case VALIDATION_FAILED, UNSUPPORTED_NODE -> HttpStatus.UNPROCESSABLE_ENTITY;
      case DUPLICATE_START_COMMAND, ILLEGAL_TRANSITION -> HttpStatus.CONFLICT;
      case RESULT_NOT_AVAILABLE -> HttpStatus.TOO_EARLY;
      case BACKEND_FAILURE -> HttpStatus.INTERNAL_SERVER_ERROR;
    };
  }

  private static String title(String problemName) {
    StringBuilder result = new StringBuilder();
    for (String word : problemName.split("-")) {
      if (!result.isEmpty()) {
        result.append(' ');
      }
      result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
    }
    return result.toString();
  }
}
