package org.hammer.audio.workflow.editor.http;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.catalog.ExperimentNodeCatalog;
import org.hammer.audio.workflow.editor.WorkflowEditorService;
import org.hammer.audio.workflow.editor.WorkflowOperationRejectedException;
import org.hammer.audio.workflow.editor.WorkflowProjection;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitInfo;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.WorkflowSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Spring MVC REST controller for the workflow editor MVP (ADR-007 / issue #210). */
@RestController
@RequestMapping("/workflow")
public final class WorkflowEditorHttpAdapter {

  private static final String DEFAULT_BRANCH = "main";
  private static final int DEFAULT_HISTORY_LIMIT = 20;
  private static final String DEFAULT_HISTORY_LIMIT_STR = "20";
  private static final String JSON_FIELD_COMMIT_ID = "commitId";
  private static final String JSON_FIELD_TIMESTAMP = "timestamp";

  private final WorkflowEditorService editorService;

  /**
   * Creates an adapter backed by the given workflow editor service.
   *
   * @param editorService the server-authoritative editor service
   */
  public WorkflowEditorHttpAdapter(WorkflowEditorService editorService) {
    this.editorService = Objects.requireNonNull(editorService, "editorService");
  }

  /** Returns the current workflow projection. */
  @GetMapping("/projection")
  public WorkflowProjection projection() {
    return editorService.currentProjection();
  }

  /** Returns the full node palette. */
  @GetMapping("/catalog")
  public List<CatalogEntry> catalog() {
    return catalogEntries();
  }

  /** Returns the current structural validation status. */
  @GetMapping("/validation")
  public ViolationsResponse validation() {
    return new ViolationsResponse(editorService.validate());
  }

  /**
   * Applies a workflow operation and returns the updated projection.
   *
   * @param json operation descriptor
   * @return updated projection on success, or 422 with violations on rejection
   */
  @PostMapping("/operations")
  public ResponseEntity<?> operations(@RequestBody JsonNode json) {
    WorkflowOperation operation = parseOperation(json);
    WorkflowProjection projection = editorService.applyOperation(operation);
    return ResponseEntity.ok(projection);
  }

  /**
   * Creates a checkpoint and returns the new commit identifier.
   *
   * @param json checkpoint metadata (branch, author, message, timestamp)
   * @return checkpoint response on success, or 422 with violations on rejection
   */
  @PostMapping("/checkpoints")
  public ResponseEntity<?> checkpoints(@RequestBody JsonNode json) {
    String branch = textOrDefault(json, "branch", DEFAULT_BRANCH);
    String author = textOrDefault(json, "author", "web-editor");
    String message = textOrDefault(json, "message", "Workbench checkpoint");
    Instant timestamp =
        json.has(JSON_FIELD_TIMESTAMP)
            ? Instant.parse(json.get(JSON_FIELD_TIMESTAMP).asText())
            : Instant.now();
    CommitMetadata metadata = new CommitMetadata(author, message, timestamp);
    CommitId commitId = editorService.checkpoint(branch, metadata);
    return ResponseEntity.ok(new CheckpointResponse(commitId.value()));
  }

  /**
   * Returns commit history for a branch.
   *
   * @param branch branch name (default: {@code main})
   * @param limit maximum number of entries (default: 20)
   * @return list of history entries
   */
  @GetMapping("/history")
  public List<HistoryEntry> history(
      @RequestParam(defaultValue = DEFAULT_BRANCH) String branch,
      @RequestParam(defaultValue = DEFAULT_HISTORY_LIMIT_STR) int limit) {
    return editorService.history(branch, limit).stream().map(HistoryEntry::from).toList();
  }

  /**
   * Loads a workflow snapshot by commit identifier or branch name.
   *
   * @param json load descriptor ({@code commitId} or {@code branch})
   * @return updated projection on success, or 422 with violations on rejection
   */
  @PostMapping("/load")
  public ResponseEntity<?> load(@RequestBody JsonNode json) {
    WorkflowProjection projection;
    if (json.has(JSON_FIELD_COMMIT_ID) && !json.get(JSON_FIELD_COMMIT_ID).asText().isBlank()) {
      projection = editorService.loadGraph(new CommitId(json.get(JSON_FIELD_COMMIT_ID).asText()));
    } else {
      projection = editorService.loadGraph(textOrDefault(json, "branch", DEFAULT_BRANCH));
    }
    return ResponseEntity.ok(projection);
  }

  /** Returns the current workflow execution snapshot. */
  @GetMapping("/snapshot")
  public WorkflowSnapshot snapshot() {
    return editorService.executeSnapshot();
  }

  // -------------------------------------------------------------------------
  // Exception mapping
  // -------------------------------------------------------------------------

  @ExceptionHandler(WorkflowOperationRejectedException.class)
  public ResponseEntity<ViolationsResponse> handleRejected(WorkflowOperationRejectedException ex) {
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .body(new ViolationsResponse(ex.violations()));
  }

  @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
  public ResponseEntity<String> handleBadRequest(RuntimeException ex) {
    return ResponseEntity.badRequest().body(ex.getMessage());
  }

  // -------------------------------------------------------------------------
  // Parsing helpers
  // -------------------------------------------------------------------------

  private static WorkflowOperation parseOperation(JsonNode json) {
    String type = requiredText(json, "type");
    String operationId = requiredText(json, "operationId");
    String author = json.has("author") ? json.get("author").asText() : "web-editor";
    Instant timestamp =
        json.has(JSON_FIELD_TIMESTAMP)
            ? Instant.parse(json.get(JSON_FIELD_TIMESTAMP).asText())
            : Instant.now();
    return switch (type) {
      case "CreateNode" -> {
        String nodeId = requiredText(json, "nodeId");
        String catalogType = requiredText(json, "catalogType");
        Node node = createCatalogNode(catalogType, nodeId);
        yield new WorkflowOperation.CreateNode(operationId, timestamp, author, node);
      }
      case "ConnectPorts" -> {
        JsonNode edgeJson = requireObject(json, "edge");
        Edge edge =
            new Edge(
                requiredText(edgeJson, "id"),
                requiredText(edgeJson, "sourceNodeId"),
                requiredText(edgeJson, "sourcePortId"),
                requiredText(edgeJson, "targetNodeId"),
                requiredText(edgeJson, "targetPortId"));
        yield new WorkflowOperation.ConnectPorts(operationId, timestamp, author, edge);
      }
      case "DisconnectPorts" -> {
        String edgeId = requiredText(json, "edgeId");
        JsonNode edgeJson = requireObject(json, "disconnectedEdge");
        Edge disconnectedEdge =
            new Edge(
                requiredText(edgeJson, "id"),
                requiredText(edgeJson, "sourceNodeId"),
                requiredText(edgeJson, "sourcePortId"),
                requiredText(edgeJson, "targetNodeId"),
                requiredText(edgeJson, "targetPortId"));
        yield new WorkflowOperation.DisconnectPorts(
            operationId, timestamp, author, edgeId, disconnectedEdge);
      }
      case "UpdateProperty" -> {
        String target = requiredText(json, "target");
        String targetId = requiredText(json, "targetId");
        String propertyKey = requiredText(json, "propertyKey");
        String newValue = json.has("newValue") ? json.get("newValue").asText() : null;
        String previousValue =
            json.has("previousValue") ? json.get("previousValue").asText() : null;
        WorkflowOperation.PropertyTarget propertyTarget = parsePropertyTarget(target);
        yield new WorkflowOperation.UpdateProperty(
            operationId,
            timestamp,
            author,
            propertyTarget,
            targetId,
            propertyKey,
            previousValue,
            newValue);
      }
      default -> throw new IllegalArgumentException("Unknown operation type: " + type);
    };
  }

  private static WorkflowOperation.PropertyTarget parsePropertyTarget(String target) {
    return Arrays.stream(WorkflowOperation.PropertyTarget.values())
        .filter(t -> t.name().equals(target))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown PropertyTarget: " + target));
  }

  private static Node createCatalogNode(String catalogType, String nodeId) {
    return switch (catalogType) {
      case "recording-input" -> ExperimentNodeCatalog.recordingInput(nodeId);
      case "synthetic-signal-generator" -> ExperimentNodeCatalog.syntheticSignalGenerator(nodeId);
      case "humbug-db-import" -> ExperimentNodeCatalog.humBugDbImport(nodeId);
      case "gain" -> ExperimentNodeCatalog.gain(nodeId);
      case "bandpass-filter" -> ExperimentNodeCatalog.bandpassFilter(nodeId);
      case "fft" -> ExperimentNodeCatalog.fft(nodeId);
      case "wingbeat-feature-extraction" -> ExperimentNodeCatalog.wingbeatFeatureExtraction(nodeId);
      case "classifier" -> ExperimentNodeCatalog.classifier(nodeId);
      case "localization" -> ExperimentNodeCatalog.localization(nodeId);
      case "benchmark" -> ExperimentNodeCatalog.benchmark(nodeId);
      case "report" -> ExperimentNodeCatalog.report(nodeId);
      case "evidence-export" -> ExperimentNodeCatalog.evidenceExport(nodeId);
      default -> throw new IllegalArgumentException("Unknown catalog node type: " + catalogType);
    };
  }

  private static List<CatalogEntry> catalogEntries() {
    return List.of(
        CatalogEntry.from(
            "recording-input", ExperimentNodeCatalog.recordingInput("catalog.recording")),
        CatalogEntry.from(
            "synthetic-signal-generator",
            ExperimentNodeCatalog.syntheticSignalGenerator("catalog.synthetic")),
        CatalogEntry.from(
            "humbug-db-import", ExperimentNodeCatalog.humBugDbImport("catalog.humbug")),
        CatalogEntry.from("gain", ExperimentNodeCatalog.gain("catalog.gain")),
        CatalogEntry.from(
            "bandpass-filter", ExperimentNodeCatalog.bandpassFilter("catalog.bandpass")),
        CatalogEntry.from("fft", ExperimentNodeCatalog.fft("catalog.fft")),
        CatalogEntry.from(
            "wingbeat-feature-extraction",
            ExperimentNodeCatalog.wingbeatFeatureExtraction("catalog.features")),
        CatalogEntry.from("classifier", ExperimentNodeCatalog.classifier("catalog.classifier")),
        CatalogEntry.from(
            "localization", ExperimentNodeCatalog.localization("catalog.localization")),
        CatalogEntry.from("benchmark", ExperimentNodeCatalog.benchmark("catalog.benchmark")),
        CatalogEntry.from("report", ExperimentNodeCatalog.report("catalog.report")),
        CatalogEntry.from(
            "evidence-export", ExperimentNodeCatalog.evidenceExport("catalog.evidence")));
  }

  private static JsonNode requireObject(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("Missing required object field: " + field);
    }
    return value;
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException("Missing required field: " + field);
    }
    return value.asText();
  }

  private static String textOrDefault(JsonNode node, String field, String fallback) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull() || value.asText().isBlank()) {
      return fallback;
    }
    return value.asText();
  }

  // -------------------------------------------------------------------------
  // Response value objects
  // -------------------------------------------------------------------------

  /**
   * JSON response body for rejected operations or current validation status.
   *
   * @param violations list of structural violation messages
   */
  public record ViolationsResponse(List<String> violations) {

    public ViolationsResponse {
      violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
    }
  }

  /**
   * JSON response body for a created checkpoint.
   *
   * @param commitId stable identifier of the created commit
   */
  public record CheckpointResponse(String commitId) {
    public CheckpointResponse {
      Objects.requireNonNull(
          commitId, () -> "CheckpointResponse parameter commitId must not be null");
    }
  }

  /**
   * JSON response entry for checkpoint history.
   *
   * @param commitId stable identifier of the commit
   * @param workflowId domain identifier of the workflow
   * @param author author of the commit
   * @param message human-readable commit message
   * @param timestamp instant at which the commit was created
   */
  public record HistoryEntry(
      String commitId, String workflowId, String author, String message, Instant timestamp) {

    static HistoryEntry from(CommitInfo info) {
      return new HistoryEntry(
          info.commitId().value(),
          info.workflowId(),
          info.metadata().author(),
          info.metadata().message(),
          info.metadata().timestamp());
    }
  }

  /**
   * JSON response entry for node palette items.
   *
   * @param type node type identifier
   * @param label human-readable node label
   * @param inputHandles typed input port handles
   * @param outputHandles typed output port handles
   */
  public record CatalogEntry(
      String type,
      String label,
      List<WorkflowProjection.HandleProjection> inputHandles,
      List<WorkflowProjection.HandleProjection> outputHandles) {

    public CatalogEntry {
      inputHandles = List.copyOf(Objects.requireNonNull(inputHandles, "inputHandles"));
      outputHandles = List.copyOf(Objects.requireNonNull(outputHandles, "outputHandles"));
    }

    static CatalogEntry from(String type, Node node) {
      Workflow catalogWorkflow = new Workflow("catalog", "Catalog", List.of(node), List.of());
      WorkflowProjection.NodeProjection nodeProjection =
          WorkflowProjection.fromWorkflow(catalogWorkflow).nodes().get(0);
      return new CatalogEntry(
          type, node.label(), nodeProjection.inputHandles(), nodeProjection.outputHandles());
    }
  }
}
