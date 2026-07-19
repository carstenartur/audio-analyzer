package org.hammer.audio.workflow.editor.http;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.collaboration.WorkflowSessionException;
import org.hammer.audio.workflow.collaboration.WorkflowUndoConflictException;
import org.hammer.audio.workflow.collaboration.WorkflowUndoPreview;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionRevisionConflictException;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionSequenceConflictException;
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
  private static final String CODE_PROPERTY = "code";
  private static final String SESSION_ID_PROPERTY = "sessionId";

  /** Maps typed collaboration-domain errors without inspecting exception-message text. */
  @ExceptionHandler(WorkflowSessionException.class)
  public ProblemDetail handleSessionException(
      WorkflowSessionException exception, HttpServletRequest request) {
    HttpStatus status = statusFor(exception.code());
    ProblemDetail problem =
        problem(status, problemName(exception.code()), exception.getMessage(), request);
    problem.setProperty(CODE_PROPERTY, exception.code().name());
    if (exception.sessionId() != null) {
      problem.setProperty(SESSION_ID_PROPERTY, exception.sessionId());
    }
    return problem;
  }

  /** Maps blocked undo/redo commands with the concrete conflicting operations. */
  @ExceptionHandler(WorkflowUndoConflictException.class)
  public ProblemDetail handleUndoConflict(
      WorkflowUndoConflictException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problem(HttpStatus.CONFLICT, "undo-conflict", exception.getMessage(), request);
    problem.setProperty(CODE_PROPERTY, "UNDO_CONFLICT");
    problem.setProperty(SESSION_ID_PROPERTY, exception.sessionId());
    problem.setProperty("targetOperationId", exception.targetOperationId());
    problem.setProperty(
        "blockingOperations",
        exception.blockingOperations().stream().map(BlockingOperationDetail::from).toList());
    return problem;
  }

  /** Maps stale semantic commands to a machine-readable 409 response. */
  @ExceptionHandler(WorkflowSessionRevisionConflictException.class)
  public ProblemDetail handleRevisionConflict(
      WorkflowSessionRevisionConflictException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problem(
            HttpStatus.CONFLICT,
            "workflow-session-revision-conflict",
            exception.getMessage(),
            request);
    problem.setProperty(CODE_PROPERTY, "WORKFLOW_SESSION_REVISION_CONFLICT");
    problem.setProperty(SESSION_ID_PROPERTY, exception.sessionId());
    problem.setProperty("expectedRevision", exception.expectedRevision());
    problem.setProperty("actualRevision", exception.actualRevision());
    return problem;
  }

  /** Maps stale collaboration-event cursors to a machine-readable 409 response. */
  @ExceptionHandler(WorkflowSessionSequenceConflictException.class)
  public ProblemDetail handleSequenceConflict(
      WorkflowSessionSequenceConflictException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problem(
            HttpStatus.CONFLICT,
            "workflow-session-sequence-conflict",
            exception.getMessage(),
            request);
    problem.setProperty(CODE_PROPERTY, "WORKFLOW_SESSION_SEQUENCE_CONFLICT");
    problem.setProperty(SESSION_ID_PROPERTY, exception.sessionId());
    problem.setProperty("expectedSequence", exception.expectedSequence());
    problem.setProperty("actualSequence", exception.actualSequence());
    return problem;
  }

  /** Maps bean-validation failures to a structured invalid-request response. */
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
            .map(
                error ->
                    new FieldViolation(
                        error.getField(), Objects.toString(error.getDefaultMessage(), "")))
            .toList();
    problem.setProperty("violations", violations);
    return problem;
  }

  /** Maps malformed JSON and incompatible enum values to a stable 400 response. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail handleUnreadableBody(
      HttpMessageNotReadableException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "invalid-json",
        "The request body is not valid JSON for this endpoint.",
        request);
  }

  /** Maps remaining request-contract violations. */
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
      case SESSION_NOT_FOUND, UNDO_TARGET_NOT_FOUND, REDO_TARGET_NOT_FOUND -> HttpStatus.NOT_FOUND;
      case SESSION_ALREADY_EXISTS,
              PRIVATE_WORKSPACE_ACCESS_DENIED,
              ACTOR_METADATA_MISMATCH,
              ACTOR_NOT_JOINED,
              SESSION_MODE_MISMATCH,
              SESSION_CLOSE_FORBIDDEN,
              DUPLICATE_OPERATION_ID,
              OPERATION_NOT_UNDOABLE,
              UNDO_PREVIEW_STALE,
              UNDO_CONFLICT,
              REDO_TARGET_INVALID,
              REDO_ALREADY_APPLIED ->
          HttpStatus.CONFLICT;
      case INVALID_OPERATION_AUTHOR, UNDO_TARGET_REQUIRED, UNDO_PREVIEW_REQUIRED ->
          HttpStatus.BAD_REQUEST;
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

  /**
   * Field-level validation detail included in invalid-request problem responses.
   *
   * @param field invalid request field
   * @param message validation message
   */
  public record FieldViolation(String field, String message) {
    public FieldViolation {
      field = Objects.requireNonNull(field, "field");
      message = Objects.requireNonNull(message, "message");
    }
  }

  /**
   * Transport-safe blocker detail included in undo-conflict problem responses.
   *
   * @param operationId stable blocking-operation identifier
   * @param actorId actor that authored the blocking operation
   * @param conflictingObjectIds semantic objects shared with the requested inverse
   */
  public record BlockingOperationDetail(
      String operationId, String actorId, List<String> conflictingObjectIds) {
    public BlockingOperationDetail {
      operationId = Objects.requireNonNull(operationId, "operationId");
      actorId = Objects.requireNonNull(actorId, "actorId");
      conflictingObjectIds = List.copyOf(conflictingObjectIds);
    }

    static BlockingOperationDetail from(WorkflowUndoPreview.BlockingOperation blocker) {
      return new BlockingOperationDetail(
          blocker.operationId(), blocker.actorId(), blocker.conflictingObjectIds());
    }
  }
}
