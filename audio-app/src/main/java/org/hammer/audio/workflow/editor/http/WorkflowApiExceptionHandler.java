package org.hammer.audio.workflow.editor.http;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.hammer.audio.workflow.collaboration.WorkflowSessionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Central RFC 9457 error mapping for workflow REST controllers. */
@RestControllerAdvice
public final class WorkflowApiExceptionHandler {

  private static final String PROBLEM_BASE = "https://audio-analyzer.dev/problems/";

  @ExceptionHandler(WorkflowSessionException.class)
  public ProblemDetail handleSessionException(
      WorkflowSessionException exception, HttpServletRequest request) {
    HttpStatus status = statusFor(exception.code());
    ProblemDetail problem =
        problem(status, problemName(exception.code()), exception.getMessage(), request);
    problem.setProperty("code", exception.code().name());
    if (exception.sessionId() != null) {
      problem.setProperty("sessionId", exception.sessionId());
    }
    return problem;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleValidation(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problem(
            HttpStatus.BAD_REQUEST,
            "invalid-request",
            "The request body contains invalid values.",
            request);
    List<FieldViolation> violations =
        exception.getBindingResult().getFieldErrors().stream()
            .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
            .toList();
    problem.setProperty("violations", violations);
    return problem;
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail handleUnreadableBody(
      HttpMessageNotReadableException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "invalid-json",
        "The request body is not valid JSON for this endpoint.",
        request);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleIllegalArgument(
      IllegalArgumentException exception, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "invalid-request", exception.getMessage(), request);
  }

  private static ProblemDetail problem(
      HttpStatus status, String name, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title(name));
    problem.setType(URI.create(PROBLEM_BASE + name));
    problem.setInstance(URI.create(request.getRequestURI()));
    return problem;
  }

  private static HttpStatus statusFor(WorkflowSessionException.Code code) {
    return switch (code) {
      case SESSION_NOT_FOUND -> HttpStatus.NOT_FOUND;
      case INVALID_OPERATION_AUTHOR, INVALID_WORKFLOW_OPERATION -> HttpStatus.UNPROCESSABLE_ENTITY;
      case SESSION_ALREADY_EXISTS,
              PRIVATE_WORKSPACE_ACCESS_DENIED,
              ACTOR_METADATA_MISMATCH,
              ACTOR_NOT_JOINED,
              SESSION_MODE_MISMATCH,
              SESSION_CLOSE_FORBIDDEN,
              REVISION_CONFLICT,
              NOTHING_TO_UNDO,
              NOTHING_TO_REDO ->
          HttpStatus.CONFLICT;
    };
  }

  private static String problemName(WorkflowSessionException.Code code) {
    return code.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
  }

  private static String title(String problemName) {
    String[] words = problemName.split("-");
    StringBuilder result = new StringBuilder();
    for (String word : words) {
      if (!result.isEmpty()) {
        result.append(' ');
      }
      result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
    }
    return result.toString();
  }

  public record FieldViolation(String field, String message) {}
}
