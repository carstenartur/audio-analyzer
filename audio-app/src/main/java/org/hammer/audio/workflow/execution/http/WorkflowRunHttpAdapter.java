package org.hammer.audio.workflow.execution.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.WorkflowSessionException;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry.SessionSnapshot;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry.SessionWorkflowSnapshot;
import org.hammer.audio.workflow.editor.http.CollaborationIdentityException;
import org.hammer.audio.workflow.editor.http.CollaborationIdentityService;
import org.hammer.audio.workflow.editor.http.WorkflowSessionApiModels.ActorResponse;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Command;
import org.hammer.audio.workflow.execution.WorkflowRunModels.LiveSessionSource;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Snapshot;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Source;
import org.hammer.audio.workflow.execution.WorkflowRunService;
import org.hammer.audio.workflow.execution.http.WorkflowRunApiModels.RunResponse;
import org.hammer.audio.workflow.execution.http.WorkflowRunApiModels.RunResultResponse;
import org.hammer.audio.workflow.execution.http.WorkflowRunApiModels.StartRunRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/** Spring MVC adapter for immutable workflow-run lifecycle commands and queries. */
// The HTTP boundary composes immutable execution, retained access and public attribution contracts.
@SuppressWarnings("PMD.CouplingBetweenObjects")
@RestController
@RequestMapping("/workflow/runs")
public final class WorkflowRunHttpAdapter {

  private static final String RUN_ID = "runId";
  private final WorkflowRunService runs;
  private final WorkflowSessionRegistry sessions;
  private final CollaborationIdentityService identities;
  private final Map<String, RunAccess> retainedAccess = new ConcurrentHashMap<>();
  private final ReentrantLock startGuard = new ReentrantLock();

  /** Creates the REST adapter. */
  public WorkflowRunHttpAdapter(
      WorkflowRunService runs,
      WorkflowSessionRegistry sessions,
      CollaborationIdentityService identities) {
    this.runs = Objects.requireNonNull(runs, "runs");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.identities = Objects.requireNonNull(identities, "identities");
  }

  /** Starts one idempotent immutable run and returns its current state. */
  @PostMapping
  public ResponseEntity<RunResponse> start(
      @Valid @RequestBody StartRunRequest request, HttpServletRequest http) {
    RunResponse response = RunResponse.from(startAuthorized(request.toCommand(), http));
    URI location =
        UriComponentsBuilder.fromPath("/workflow/runs/{" + RUN_ID + "}")
            .buildAndExpand(response.runId())
            .encode()
            .toUri();
    return ResponseEntity.accepted().location(location).body(response);
  }

  /** Lists process-local runs visible to the selected server-bound client. */
  @GetMapping
  public List<RunResponse> list(HttpServletRequest http) {
    return runs.runs().stream().filter(run -> visible(run, http)).map(RunResponse::from).toList();
  }

  /** Returns one current run record. */
  @GetMapping("/{runId}")
  public RunResponse inspect(@PathVariable(RUN_ID) String runId, HttpServletRequest http) {
    Snapshot snapshot = runs.inspect(runId);
    requireAccess(snapshot, http);
    return RunResponse.from(snapshot);
  }

  /** Requests cooperative cancellation. */
  @PostMapping("/{runId}/cancel")
  public RunResponse cancel(@PathVariable(RUN_ID) String runId, HttpServletRequest http) {
    requireAccess(runs.inspect(runId), http);
    return RunResponse.from(runs.cancel(runId));
  }

  /** Returns terminal reproducibility evidence and backend artifacts. */
  @GetMapping("/{runId}/result")
  public RunResultResponse result(@PathVariable(RUN_ID) String runId, HttpServletRequest http) {
    Snapshot snapshot = runs.inspect(runId);
    requireAccess(snapshot, http);
    return RunResultResponse.from(snapshot, runs.result(runId));
  }

  /** Return original run attribution under the same retained access rules as result evidence. */
  public RunAttribution authorizedAttribution(String runId, HttpServletRequest http) {
    requireAccess(runs.inspect(runId), http);
    RunAccess retained = retainedAccess.get(runId);
    return retained == null
        ? new RunAttribution(null, null)
        : new RunAttribution(
            identities.historyActor(retained.creatorActorId()),
            identities.historyActor(retained.sessionOwnerActorId()));
  }

  /**
   * Privacy-policy projections of the original run actors, never the querying user.
   *
   * @param creator retained creator, or null when attribution was not recorded
   * @param owner retained session owner, or null when attribution was not recorded
   */
  public record RunAttribution(ActorResponse creator, ActorResponse owner) {
    // Public identity projections contain no private account subject or credential.
  }

  private Snapshot startAuthorized(Command command, HttpServletRequest http) {
    startGuard.lock();
    try {
      Optional<Snapshot> existing =
          runs.runs().stream()
              .filter(run -> run.startCommandId().equals(command.startCommandId()))
              .findFirst();
      if (existing.isPresent()) {
        requireAccess(existing.get(), http);
        // An idempotent retry belongs to its captured run, even after its source closes/reappears.
        return runs.start(command);
      }
      AuthorizedRunSource granted = authorizeSource(command.source(), http);
      Snapshot snapshot =
          granted == null ? runs.start(command) : runs.start(command, granted.captured());
      if (!snapshot.source().equals(command.source())) {
        throw new CollaborationIdentityException();
      }
      if (granted != null) {
        retainedAccess.putIfAbsent(snapshot.runId(), granted.access());
      }
      requireAccess(snapshot, http);
      return snapshot;
    } finally {
      startGuard.unlock();
    }
  }

  private AuthorizedRunSource authorizeSource(Source source, HttpServletRequest http) {
    if (!(source instanceof LiveSessionSource live)) {
      return null;
    }
    OperationActor actor = identities.requireActor(http, null);
    try {
      SessionWorkflowSnapshot captured = sessions.authorizedWorkflow(live.sessionId(), actor);
      SessionSnapshot session = captured.session();
      return new AuthorizedRunSource(
          new RunAccess(actor.actorId(), session.owner().actorId(), session.createdAt()), captured);
    } catch (WorkflowSessionException denied) {
      if (denied.code() == WorkflowSessionException.Code.ACTOR_NOT_JOINED
          || denied.code() == WorkflowSessionException.Code.ACTOR_METADATA_MISMATCH) {
        throw new CollaborationIdentityException(denied);
      }
      throw denied;
    }
  }

  private boolean visible(Snapshot snapshot, HttpServletRequest http) {
    try {
      requireAccess(snapshot, http);
      return true;
    } catch (CollaborationIdentityException denied) {
      return false;
    }
  }

  private void requireAccess(Snapshot snapshot, HttpServletRequest http) {
    if (!(snapshot.source() instanceof LiveSessionSource live)) {
      return;
    }
    OperationActor actor = identities.requireActor(http, null);
    RunAccess retained = retainedAccess.get(snapshot.runId());
    if (retained != null
        && (retained.creatorActorId().equals(actor.actorId())
            || retained.sessionOwnerActorId().equals(actor.actorId()))) {
      return;
    }
    if (retained != null && isCurrentMember(live.sessionId(), retained, actor)) {
      return;
    }
    throw new CollaborationIdentityException();
  }

  private boolean isCurrentMember(String sessionId, RunAccess retained, OperationActor actor) {
    try {
      SessionSnapshot session = sessions.inspect(sessionId);
      return session.createdAt().equals(retained.sessionCreatedAt())
          && session.owner().actorId().equals(retained.sessionOwnerActorId())
          && session.participants().contains(actor);
    } catch (WorkflowSessionException exception) {
      if (exception.code() == WorkflowSessionException.Code.SESSION_NOT_FOUND) {
        return false;
      }
      throw exception;
    }
  }

  private record RunAccess(
      String creatorActorId, String sessionOwnerActorId, Instant sessionCreatedAt) {
    // These process-local grants share the run registry's lifetime and contain no credentials.
  }

  private record AuthorizedRunSource(RunAccess access, SessionWorkflowSnapshot captured) {
    // The access grant and workflow come from the same atomic membership check.
  }
}
