package org.hammer.audio.workflow.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowValidator;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionRevisionConflictException;
import org.hammer.audio.workflow.dsl.WorkflowDslParser;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
import org.hammer.audio.workflow.execution.WorkflowRunException.Code;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Command;
import org.hammer.audio.workflow.execution.WorkflowRunModels.ExecutionBackend;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Input;
import org.hammer.audio.workflow.execution.WorkflowRunModels.LiveSessionSource;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Mode;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Result;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Snapshot;
import org.hammer.audio.workflow.execution.WorkflowRunModels.State;
import org.hammer.audio.workflow.execution.WorkflowRunModels.StoredCommitSource;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Violation;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.InMemoryVersionedWorkflowStore;
import org.hammer.audio.workflow.store.WorkflowSnapshot;

class WorkflowRunServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");
  private static final OperationActor OWNER =
      new OperationActor("actor.owner", "user.owner", "Owner");

  @org.junit.jupiter.api.Test
  void executesExactLiveRevisionAsClearlyLabelledSimulation() {
    WorkflowSessionRegistry sessions = sessionsWithEmptyWorkflow();
    WorkflowRunService service =
        service(sessions, null, new SimulationWorkflowExecutionBackend(), Runnable::run);

    Snapshot run =
        service.start(new Command("command.live", new LiveSessionSource("session.test", 0)));

    assertEquals(State.COMPLETED, run.state());
    assertEquals(Mode.SIMULATION, run.mode());
    assertEquals(0L, run.semanticRevision());
    assertEquals(64, run.fingerprint().length());
    assertEquals(
        ExecutionStatus.COMPLETED,
        service.result(run.runId()).reproducibilityBundle().result().overallStatus());
  }

  @org.junit.jupiter.api.Test
  void loadsExactStoredCommitAndRetainsItsProvenance() {
    InMemoryVersionedWorkflowStore store = new InMemoryVersionedWorkflowStore();
    Workflow workflow = emptyWorkflow();
    String dsl = new WorkflowDslSerializer().serialize(workflow);
    CommitId commit =
        store.commit(
            "main",
            new WorkflowSnapshot(workflow.id(), dsl),
            new CommitMetadata("tester", "checkpoint", NOW));
    WorkflowRunService service =
        service(
            new WorkflowSessionRegistry(),
            store,
            new SimulationWorkflowExecutionBackend(),
            Runnable::run);

    Snapshot run = service.start(new Command("command.commit", new StoredCommitSource(commit)));

    assertEquals(commit, run.commitId());
    assertEquals(commit, service.result(run.runId()).reproducibilityBundle().commitId());
  }

  @org.junit.jupiter.api.Test
  void identicalStartCommandIsIdempotent() {
    WorkflowRunService service =
        service(
            sessionsWithEmptyWorkflow(),
            null,
            new SimulationWorkflowExecutionBackend(),
            Runnable::run);
    Command command = new Command("command.retry", new LiveSessionSource("session.test", 0));

    Snapshot first = service.start(command);
    Snapshot retry = service.start(command);

    assertEquals(first.runId(), retry.runId());
    assertEquals(1, service.runs().size());
  }

  @org.junit.jupiter.api.Test
  void reusedStartCommandForDifferentSourceIsRejected() {
    WorkflowRunService service =
        service(
            sessionsWithEmptyWorkflow(),
            null,
            new SimulationWorkflowExecutionBackend(),
            Runnable::run);
    service.start(new Command("command.conflict", new LiveSessionSource("session.test", 0)));

    WorkflowRunException exception =
        assertThrows(
            WorkflowRunException.class,
            () ->
                service.start(
                    new Command("command.conflict", new LiveSessionSource("session.other", 0))));

    assertEquals(Code.DUPLICATE_START_COMMAND, exception.code());
  }

  @org.junit.jupiter.api.Test
  void staleLiveRevisionFailsBeforeDispatch() {
    WorkflowRunService service =
        service(
            sessionsWithEmptyWorkflow(),
            null,
            new SimulationWorkflowExecutionBackend(),
            Runnable::run);

    assertThrows(
        WorkflowSessionRevisionConflictException.class,
        () ->
            service.start(new Command("command.stale", new LiveSessionSource("session.test", 1))));
  }

  @org.junit.jupiter.api.Test
  void unsupportedNodeCapabilityFailsBeforeBackendExecution() {
    AtomicInteger executions = new AtomicInteger();
    ExecutionBackend backend =
        new ExecutionBackend() {
          @Override
          public Mode mode() {
            return Mode.COMPUTATION;
          }

          @Override
          public List<Violation> validate(Input input) {
            return List.of(new Violation("UNSUPPORTED_NODE", "No executor", "node.test"));
          }

          @Override
          public Result execute(Input input, WorkflowRunModels.Control control) {
            executions.incrementAndGet();
            throw new AssertionError("must not execute");
          }
        };
    WorkflowRunService service = service(sessionsWithEmptyWorkflow(), null, backend, Runnable::run);

    WorkflowRunException exception =
        assertThrows(
            WorkflowRunException.class,
            () ->
                service.start(
                    new Command("command.unsupported", new LiveSessionSource("session.test", 0))));

    assertEquals(Code.UNSUPPORTED_NODE, exception.code());
    assertEquals(0, executions.get());
  }

  @org.junit.jupiter.api.Test
  void queuedCancellationPreventsBackendDispatch() {
    AtomicReference<Runnable> queued = new AtomicReference<>();
    Executor executor = queued::set;
    WorkflowRunService service =
        service(
            sessionsWithEmptyWorkflow(), null, new SimulationWorkflowExecutionBackend(), executor);

    Snapshot started =
        service.start(new Command("command.cancel", new LiveSessionSource("session.test", 0)));
    Snapshot requested = service.cancel(started.runId());
    queued.get().run();
    Snapshot cancelled = service.inspect(started.runId());

    assertEquals(State.QUEUED, started.state());
    assertEquals(State.CANCEL_REQUESTED, requested.state());
    assertEquals(State.CANCELLED, cancelled.state());
    assertNotNull(cancelled.finishedAt());
    assertEquals(
        Code.RESULT_NOT_AVAILABLE,
        assertThrows(WorkflowRunException.class, () -> service.result(started.runId())).code());
  }

  @org.junit.jupiter.api.Test
  void unknownRunUsesTypedFailure() {
    WorkflowRunService service =
        service(
            new WorkflowSessionRegistry(),
            null,
            new SimulationWorkflowExecutionBackend(),
            Runnable::run);

    WorkflowRunException exception =
        assertThrows(WorkflowRunException.class, () -> service.inspect("run.missing"));

    assertEquals(Code.UNKNOWN_RUN, exception.code());
  }

  private static WorkflowRunService service(
      WorkflowSessionRegistry sessions,
      InMemoryVersionedWorkflowStore store,
      ExecutionBackend backend,
      Executor executor) {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    AtomicInteger sequence = new AtomicInteger();
    return new WorkflowRunService(
        sessions,
        store,
        backend,
        executor,
        new WorkflowDslParser(),
        new WorkflowDslSerializer(),
        new WorkflowValidator(),
        clock,
        () -> "run.test." + sequence.incrementAndGet());
  }

  private static WorkflowSessionRegistry sessionsWithEmptyWorkflow() {
    WorkflowSessionRegistry sessions = new WorkflowSessionRegistry();
    assertSame(
        OWNER,
        sessions
            .create("session.test", CollaborationMode.PRIVATE_WORKSPACE, OWNER, emptyWorkflow())
            .owner());
    return sessions;
  }

  private static Workflow emptyWorkflow() {
    return new Workflow("workflow.test", "Test Workflow", List.of(), List.of());
  }
}
