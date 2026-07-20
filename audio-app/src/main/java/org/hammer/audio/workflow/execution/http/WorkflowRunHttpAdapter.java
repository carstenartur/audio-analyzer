package org.hammer.audio.workflow.execution.http;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Objects;
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
@RestController
@RequestMapping("/workflow/runs")
public final class WorkflowRunHttpAdapter {

  private static final String RUN_ID = "runId";
  private final WorkflowRunService runs;

  /** Creates the REST adapter. */
  public WorkflowRunHttpAdapter(WorkflowRunService runs) {
    this.runs = Objects.requireNonNull(runs, "runs");
  }

  /** Starts one idempotent immutable run and returns its current state. */
  @PostMapping
  public ResponseEntity<RunResponse> start(@Valid @RequestBody StartRunRequest request) {
    RunResponse response = RunResponse.from(runs.start(request.toCommand()));
    URI location =
        UriComponentsBuilder.fromPath("/workflow/runs/{" + RUN_ID + "}")
            .buildAndExpand(response.runId())
            .encode()
            .toUri();
    return ResponseEntity.accepted().location(location).body(response);
  }

  /** Lists all process-local run records. */
  @GetMapping
  public List<RunResponse> list() {
    return runs.runs().stream().map(RunResponse::from).toList();
  }

  /** Returns one current run record. */
  @GetMapping("/{runId}")
  public RunResponse inspect(@PathVariable(RUN_ID) String runId) {
    return RunResponse.from(runs.inspect(runId));
  }

  /** Requests cooperative cancellation. */
  @PostMapping("/{runId}/cancel")
  public RunResponse cancel(@PathVariable(RUN_ID) String runId) {
    return RunResponse.from(runs.cancel(runId));
  }

  /** Returns terminal reproducibility evidence and backend artifacts. */
  @GetMapping("/{runId}/result")
  public RunResultResponse result(@PathVariable(RUN_ID) String runId) {
    return RunResultResponse.from(runs.inspect(runId), runs.result(runId));
  }
}
