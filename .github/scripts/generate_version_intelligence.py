from pathlib import Path
from textwrap import dedent
import re

ROOT = Path(__file__).resolve().parents[2]


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(dedent(content).lstrip(), encoding="utf-8")


write(
    "audio-core/src/main/java/org/hammer/audio/workflow/store/WorkflowCheckpointListener.java",
    r'''
    package org.hammer.audio.workflow.store;

    /** Application hook invoked after a durable workflow checkpoint was created. */
    @FunctionalInterface
    public interface WorkflowCheckpointListener {

      void checkpointCreated(
          String branch, CommitId commitId, WorkflowSnapshot snapshot, CommitMetadata metadata);

      static WorkflowCheckpointListener noOp() {
        return (branch, commitId, snapshot, metadata) -> {};
      }
    }
    ''',
)

# Add a framework-independent checkpoint hook without breaking existing constructors.
editor_path = ROOT / "audio-core/src/main/java/org/hammer/audio/workflow/editor/WorkflowEditorService.java"
editor = editor_path.read_text(encoding="utf-8")
if "WorkflowCheckpointListener" not in editor:
    editor = editor.replace(
        "import org.hammer.audio.workflow.store.WorkflowSnapshot;",
        "import org.hammer.audio.workflow.store.WorkflowSnapshot;\n"
        "import org.hammer.audio.workflow.store.WorkflowCheckpointListener;",
    )
    editor = editor.replace(
        "  private final WorkflowDslParser parser;",
        "  private final WorkflowDslParser parser;\n"
        "  private WorkflowCheckpointListener checkpointListener = WorkflowCheckpointListener.noOp();",
    )
    anchor = "  /**\n   * Applies a workflow operation"
    setter = """
  /** Configures a post-commit projection hook while keeping the core independent of Spring. */
  public void setCheckpointListener(WorkflowCheckpointListener checkpointListener) {
    this.checkpointListener = Objects.requireNonNull(checkpointListener, "checkpointListener");
  }

"""
    if anchor not in editor:
        raise SystemExit("Cannot locate WorkflowEditorService method anchor")
    editor = editor.replace(anchor, setter + anchor, 1)
    editor = editor.replace(
        "    return store.commit(branch, snapshot, metadata);",
        "    CommitId commitId = store.commit(branch, snapshot, metadata);\n"
        "    checkpointListener.checkpointCreated(branch, commitId, snapshot, metadata);\n"
        "    return commitId;",
        1,
    )
editor_path.write_text(editor, encoding="utf-8")

write(
    "audio-core/src/main/java/org/hammer/audio/workflow/version/WorkflowDiff.java",
    r'''
    package org.hammer.audio.workflow.version;

    import java.util.List;
    import java.util.Objects;
    import org.hammer.audio.workflow.Edge;
    import org.hammer.audio.workflow.Node;

    /** Deterministically ordered semantic comparison of two workflow graphs. */
    public record WorkflowDiff(
        String leftWorkflowId,
        String rightWorkflowId,
        List<NodeChange> nodeChanges,
        List<EdgeChange> edgeChanges,
        boolean nameChanged) {

      public WorkflowDiff {
        Objects.requireNonNull(leftWorkflowId, "leftWorkflowId");
        Objects.requireNonNull(rightWorkflowId, "rightWorkflowId");
        nodeChanges = List.copyOf(nodeChanges);
        edgeChanges = List.copyOf(edgeChanges);
      }

      public enum ChangeKind {
        ADDED,
        REMOVED,
        MODIFIED
      }

      public record NodeChange(String nodeId, ChangeKind kind, Node before, Node after) {}

      public record EdgeChange(String edgeId, ChangeKind kind, Edge before, Edge after) {}
    }
    ''',
)

write(
    "audio-core/src/main/java/org/hammer/audio/workflow/version/WorkflowSemanticDiffService.java",
    r'''
    package org.hammer.audio.workflow.version;

    import java.util.ArrayList;
    import java.util.Comparator;
    import java.util.LinkedHashMap;
    import java.util.List;
    import java.util.Map;
    import java.util.Objects;
    import org.hammer.audio.workflow.Edge;
    import org.hammer.audio.workflow.Node;
    import org.hammer.audio.workflow.Workflow;

    /** Framework-independent semantic graph diff. */
    public final class WorkflowSemanticDiffService {

      public WorkflowDiff compare(Workflow left, Workflow right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        List<WorkflowDiff.NodeChange> nodeChanges =
            compareNodes(indexNodes(left.nodes()), indexNodes(right.nodes()));
        List<WorkflowDiff.EdgeChange> edgeChanges =
            compareEdges(indexEdges(left.edges()), indexEdges(right.edges()));
        return new WorkflowDiff(
            left.id(), right.id(), nodeChanges, edgeChanges, !left.name().equals(right.name()));
      }

      private static List<WorkflowDiff.NodeChange> compareNodes(
          Map<String, Node> left, Map<String, Node> right) {
        List<String> ids = union(left, right);
        List<WorkflowDiff.NodeChange> changes = new ArrayList<>();
        for (String id : ids) {
          Node before = left.get(id);
          Node after = right.get(id);
          if (before == null) {
            changes.add(new WorkflowDiff.NodeChange(id, WorkflowDiff.ChangeKind.ADDED, null, after));
          } else if (after == null) {
            changes.add(
                new WorkflowDiff.NodeChange(id, WorkflowDiff.ChangeKind.REMOVED, before, null));
          } else if (!before.equals(after)) {
            changes.add(
                new WorkflowDiff.NodeChange(id, WorkflowDiff.ChangeKind.MODIFIED, before, after));
          }
        }
        return changes;
      }

      private static List<WorkflowDiff.EdgeChange> compareEdges(
          Map<String, Edge> left, Map<String, Edge> right) {
        List<String> ids = union(left, right);
        List<WorkflowDiff.EdgeChange> changes = new ArrayList<>();
        for (String id : ids) {
          Edge before = left.get(id);
          Edge after = right.get(id);
          if (before == null) {
            changes.add(new WorkflowDiff.EdgeChange(id, WorkflowDiff.ChangeKind.ADDED, null, after));
          } else if (after == null) {
            changes.add(
                new WorkflowDiff.EdgeChange(id, WorkflowDiff.ChangeKind.REMOVED, before, null));
          } else if (!before.equals(after)) {
            changes.add(
                new WorkflowDiff.EdgeChange(id, WorkflowDiff.ChangeKind.MODIFIED, before, after));
          }
        }
        return changes;
      }

      private static Map<String, Node> indexNodes(List<Node> nodes) {
        Map<String, Node> result = new LinkedHashMap<>();
        nodes.stream().sorted(Comparator.comparing(Node::id)).forEach(node -> result.put(node.id(), node));
        return result;
      }

      private static Map<String, Edge> indexEdges(List<Edge> edges) {
        Map<String, Edge> result = new LinkedHashMap<>();
        edges.stream().sorted(Comparator.comparing(Edge::id)).forEach(edge -> result.put(edge.id(), edge));
        return result;
      }

      private static <T> List<String> union(Map<String, T> left, Map<String, T> right) {
        return java.util.stream.Stream.concat(left.keySet().stream(), right.keySet().stream())
            .distinct()
            .sorted()
            .toList();
      }
    }
    ''',
)

write(
    "audio-core/src/main/java/org/hammer/audio/workflow/version/WorkflowMergeConflict.java",
    r'''
    package org.hammer.audio.workflow.version;

    import java.util.Objects;

    /** Typed semantic conflict emitted by deterministic three-way merge. */
    public record WorkflowMergeConflict(
        String conflictId,
        Type type,
        String objectId,
        String field,
        String baseValue,
        String localValue,
        String remoteValue,
        String message) {

      public WorkflowMergeConflict {
        Objects.requireNonNull(conflictId, "conflictId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(objectId, "objectId");
        Objects.requireNonNull(message, "message");
      }

      public enum Type {
        PROPERTY_CHANGED_DIFFERENTLY,
        NODE_CHANGED_DIFFERENTLY,
        EDGE_CHANGED_DIFFERENTLY,
        DELETE_VS_MODIFY,
        DELETE_VS_CONNECT,
        IDENTIFIER_COLLISION
      }
    }
    ''',
)

write(
    "audio-core/src/main/java/org/hammer/audio/workflow/version/WorkflowMergeResolution.java",
    r'''
    package org.hammer.audio.workflow.version;

    import java.time.Instant;
    import java.util.Objects;

    /** Auditable semantic merge decision; it is persisted with the resulting checkpoint. */
    public record WorkflowMergeResolution(
        String conflictId, Choice choice, String actorId, Instant resolvedAt) {

      public WorkflowMergeResolution {
        Objects.requireNonNull(conflictId, "conflictId");
        Objects.requireNonNull(choice, "choice");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(resolvedAt, "resolvedAt");
      }

      public enum Choice {
        BASE,
        LOCAL,
        REMOTE,
        DELETE
      }
    }
    ''',
)

write(
    "audio-core/src/main/java/org/hammer/audio/workflow/version/WorkflowMergeService.java",
    r'''
    package org.hammer.audio.workflow.version;

    import java.util.ArrayList;
    import java.util.Comparator;
    import java.util.LinkedHashMap;
    import java.util.List;
    import java.util.Map;
    import java.util.Objects;
    import java.util.function.Function;
    import org.hammer.audio.workflow.Edge;
    import org.hammer.audio.workflow.Node;
    import org.hammer.audio.workflow.Workflow;
    import org.hammer.audio.workflow.WorkflowValidator;

    /** Deterministic three-way semantic merge for workflow nodes and edges. */
    public final class WorkflowMergeService {

      private final WorkflowValidator validator = new WorkflowValidator();

      public MergeResult merge(Workflow base, Workflow local, Workflow remote) {
        return merge(base, local, remote, Map.of());
      }

      public MergeResult merge(
          Workflow base,
          Workflow local,
          Workflow remote,
          Map<String, WorkflowMergeResolution.Choice> resolutions) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(remote, "remote");
        Objects.requireNonNull(resolutions, "resolutions");

        List<WorkflowMergeConflict> conflicts = new ArrayList<>();
        Map<String, Node> nodes =
            mergeObjects(
                "node",
                index(base.nodes(), Node::id),
                index(local.nodes(), Node::id),
                index(remote.nodes(), Node::id),
                conflicts,
                resolutions,
                WorkflowMergeConflict.Type.NODE_CHANGED_DIFFERENTLY);
        Map<String, Edge> edges =
            mergeObjects(
                "edge",
                index(base.edges(), Edge::id),
                index(local.edges(), Edge::id),
                index(remote.edges(), Edge::id),
                conflicts,
                resolutions,
                WorkflowMergeConflict.Type.EDGE_CHANGED_DIFFERENTLY);

        detectDeleteVsConnect(base, local, remote, nodes, conflicts, resolutions);
        List<WorkflowMergeConflict> unresolved =
            conflicts.stream().filter(conflict -> !resolutions.containsKey(conflict.conflictId())).toList();
        if (!unresolved.isEmpty()) {
          return new MergeResult(null, conflicts, validatorMessages(List.of()), false);
        }
        String name = mergeScalar(base.name(), local.name(), remote.name());
        Workflow merged =
            new Workflow(
                local.id(),
                name,
                nodes.values().stream().sorted(Comparator.comparing(Node::id)).toList(),
                edges.values().stream().sorted(Comparator.comparing(Edge::id)).toList());
        List<String> violations = validator.validate(merged);
        return new MergeResult(violations.isEmpty() ? merged : null, conflicts, violations, violations.isEmpty());
      }

      private static <T> Map<String, T> mergeObjects(
          String prefix,
          Map<String, T> base,
          Map<String, T> local,
          Map<String, T> remote,
          List<WorkflowMergeConflict> conflicts,
          Map<String, WorkflowMergeResolution.Choice> resolutions,
          WorkflowMergeConflict.Type changedType) {
        Map<String, T> merged = new LinkedHashMap<>();
        List<String> ids =
            java.util.stream.Stream.concat(
                    java.util.stream.Stream.concat(base.keySet().stream(), local.keySet().stream()),
                    remote.keySet().stream())
                .distinct()
                .sorted()
                .toList();
        for (String id : ids) {
          T baseValue = base.get(id);
          T localValue = local.get(id);
          T remoteValue = remote.get(id);
          T selected;
          if (Objects.equals(localValue, remoteValue)) {
            selected = localValue;
          } else if (Objects.equals(baseValue, localValue)) {
            selected = remoteValue;
          } else if (Objects.equals(baseValue, remoteValue)) {
            selected = localValue;
          } else {
            WorkflowMergeConflict.Type type =
                baseValue != null && (localValue == null || remoteValue == null)
                    ? WorkflowMergeConflict.Type.DELETE_VS_MODIFY
                    : changedType;
            if (baseValue == null && localValue != null && remoteValue != null) {
              type = WorkflowMergeConflict.Type.IDENTIFIER_COLLISION;
            }
            String conflictId = prefix + ":" + id;
            WorkflowMergeConflict conflict =
                new WorkflowMergeConflict(
                    conflictId,
                    type,
                    id,
                    prefix,
                    stringify(baseValue),
                    stringify(localValue),
                    stringify(remoteValue),
                    "Both branches changed " + prefix + " '" + id + "' differently");
            conflicts.add(conflict);
            selected = select(resolutions.get(conflictId), baseValue, localValue, remoteValue);
          }
          if (selected != null) {
            merged.put(id, selected);
          }
        }
        return merged;
      }

      private static void detectDeleteVsConnect(
          Workflow base,
          Workflow local,
          Workflow remote,
          Map<String, Node> mergedNodes,
          List<WorkflowMergeConflict> conflicts,
          Map<String, WorkflowMergeResolution.Choice> resolutions) {
        Map<String, Node> baseNodes = index(base.nodes(), Node::id);
        Map<String, Node> localNodes = index(local.nodes(), Node::id);
        Map<String, Node> remoteNodes = index(remote.nodes(), Node::id);
        List<Edge> branchEdges =
            java.util.stream.Stream.concat(local.edges().stream(), remote.edges().stream())
                .distinct()
                .sorted(Comparator.comparing(Edge::id))
                .toList();
        for (Edge edge : branchEdges) {
          for (String nodeId : List.of(edge.sourceNodeId(), edge.targetNodeId())) {
            boolean deletedLocally = baseNodes.containsKey(nodeId) && !localNodes.containsKey(nodeId);
            boolean deletedRemotely = baseNodes.containsKey(nodeId) && !remoteNodes.containsKey(nodeId);
            boolean connectedByOther =
                (deletedLocally && remote.edges().contains(edge))
                    || (deletedRemotely && local.edges().contains(edge));
            if (connectedByOther && !mergedNodes.containsKey(nodeId)) {
              String id = "delete-connect:" + nodeId + ":" + edge.id();
              if (conflicts.stream().noneMatch(conflict -> conflict.conflictId().equals(id))) {
                conflicts.add(
                    new WorkflowMergeConflict(
                        id,
                        WorkflowMergeConflict.Type.DELETE_VS_CONNECT,
                        nodeId,
                        "edge",
                        null,
                        edge.toString(),
                        edge.toString(),
                        "Node '" + nodeId + "' was deleted while edge '" + edge.id() + "' connects it"));
              }
            }
          }
        }
      }

      private static <T> T select(
          WorkflowMergeResolution.Choice choice, T base, T local, T remote) {
        if (choice == null) {
          return null;
        }
        return switch (choice) {
          case BASE -> base;
          case LOCAL -> local;
          case REMOTE -> remote;
          case DELETE -> null;
        };
      }

      private static String mergeScalar(String base, String local, String remote) {
        if (Objects.equals(local, remote)) {
          return local;
        }
        if (Objects.equals(base, local)) {
          return remote;
        }
        return local;
      }

      private static <T> Map<String, T> index(List<T> values, Function<T, String> id) {
        Map<String, T> result = new LinkedHashMap<>();
        values.stream().sorted(Comparator.comparing(id)).forEach(value -> result.put(id.apply(value), value));
        return result;
      }

      private static String stringify(Object value) {
        return value == null ? null : value.toString();
      }

      private static List<String> validatorMessages(List<String> messages) {
        return List.copyOf(messages);
      }

      public record MergeResult(
          Workflow mergedWorkflow,
          List<WorkflowMergeConflict> conflicts,
          List<String> validationViolations,
          boolean resolved) {
        public MergeResult {
          conflicts = List.copyOf(conflicts);
          validationViolations = List.copyOf(validationViolations);
        }
      }
    }
    ''',
)

write(
    "audio-core/src/main/java/org/hammer/audio/workflow/search/WorkflowHistoryDocument.java",
    r'''
    package org.hammer.audio.workflow.search;

    import java.time.Instant;
    import java.util.Map;
    import java.util.Objects;
    import java.util.Set;

    /** Replaceable search projection for one durable workflow checkpoint. */
    public record WorkflowHistoryDocument(
        String branch,
        String commitId,
        String workflowId,
        String author,
        String message,
        Instant timestamp,
        Set<String> nodeTypes,
        Map<String, String> properties,
        String searchableText) {

      public WorkflowHistoryDocument {
        requireNotBlank(branch, "branch");
        requireNotBlank(commitId, "commitId");
        requireNotBlank(workflowId, "workflowId");
        requireNotBlank(author, "author");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(timestamp, "timestamp");
        nodeTypes = Set.copyOf(nodeTypes);
        properties = Map.copyOf(properties);
        Objects.requireNonNull(searchableText, "searchableText");
      }

      private static void requireNotBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
          throw new IllegalArgumentException(field + " must not be blank");
        }
      }
    }
    ''',
)

write(
    "audio-core/src/main/java/org/hammer/audio/workflow/search/WorkflowHistoryQuery.java",
    r'''
    package org.hammer.audio.workflow.search;

    import java.time.Instant;

    /** Search filters independent of a concrete index engine. */
    public record WorkflowHistoryQuery(
        String text,
        String branch,
        String author,
        Instant from,
        Instant to,
        String nodeType,
        String propertyKey,
        String propertyValue,
        int limit) {

      public WorkflowHistoryQuery {
        if (limit < 1 || limit > 1000) {
          throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
      }
    }
    ''',
)

write(
    "audio-core/src/main/java/org/hammer/audio/workflow/search/WorkflowHistorySearchIndex.java",
    r'''
    package org.hammer.audio.workflow.search;

    import java.util.Collection;
    import java.util.List;

    /** Rebuildable, non-authoritative search projection SPI. */
    public interface WorkflowHistorySearchIndex {
      void upsert(WorkflowHistoryDocument document);

      void replaceAll(Collection<WorkflowHistoryDocument> documents);

      List<WorkflowHistoryDocument> search(WorkflowHistoryQuery query);

      void clear();
    }
    ''',
)

write(
    "audio-core/src/main/java/org/hammer/audio/workflow/search/InMemoryWorkflowHistorySearchIndex.java",
    r'''
    package org.hammer.audio.workflow.search;

    import java.util.Collection;
    import java.util.Comparator;
    import java.util.List;
    import java.util.Locale;
    import java.util.Map;
    import java.util.Objects;
    import java.util.concurrent.ConcurrentHashMap;

    /** Deterministic in-process projection; replaceable by Hibernate Search or another engine. */
    public final class InMemoryWorkflowHistorySearchIndex implements WorkflowHistorySearchIndex {

      private final Map<String, WorkflowHistoryDocument> documents = new ConcurrentHashMap<>();

      @Override
      public void upsert(WorkflowHistoryDocument document) {
        Objects.requireNonNull(document, "document");
        documents.put(key(document), document);
      }

      @Override
      public void replaceAll(Collection<WorkflowHistoryDocument> replacement) {
        documents.clear();
        replacement.forEach(this::upsert);
      }

      @Override
      public List<WorkflowHistoryDocument> search(WorkflowHistoryQuery query) {
        Objects.requireNonNull(query, "query");
        return documents.values().stream()
            .filter(document -> matches(document, query))
            .sorted(
                Comparator.comparing(WorkflowHistoryDocument::timestamp)
                    .reversed()
                    .thenComparing(WorkflowHistoryDocument::commitId))
            .limit(query.limit())
            .toList();
      }

      @Override
      public void clear() {
        documents.clear();
      }

      private static boolean matches(
          WorkflowHistoryDocument document, WorkflowHistoryQuery query) {
        if (hasText(query.text())
            && !lower(document.searchableText()).contains(lower(query.text()))) {
          return false;
        }
        if (hasText(query.branch()) && !document.branch().equals(query.branch())) {
          return false;
        }
        if (hasText(query.author()) && !document.author().equalsIgnoreCase(query.author())) {
          return false;
        }
        if (query.from() != null && document.timestamp().isBefore(query.from())) {
          return false;
        }
        if (query.to() != null && document.timestamp().isAfter(query.to())) {
          return false;
        }
        if (hasText(query.nodeType()) && !document.nodeTypes().contains(query.nodeType())) {
          return false;
        }
        if (hasText(query.propertyKey())) {
          String value = document.properties().get(query.propertyKey());
          if (value == null) {
            return false;
          }
          if (hasText(query.propertyValue())
              && !lower(value).contains(lower(query.propertyValue()))) {
            return false;
          }
        }
        return true;
      }

      private static String key(WorkflowHistoryDocument document) {
        return document.branch() + "\u0000" + document.commitId();
      }

      private static boolean hasText(String value) {
        return value != null && !value.isBlank();
      }

      private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
      }
    }
    ''',
)

write(
    "audio-core/src/main/java/org/hammer/audio/workflow/execution/WorkflowRunService.java",
    r'''
    package org.hammer.audio.workflow.execution;

    import java.time.Instant;
    import java.util.ArrayList;
    import java.util.Comparator;
    import java.util.LinkedHashMap;
    import java.util.List;
    import java.util.Map;
    import java.util.Objects;
    import java.util.UUID;
    import java.util.concurrent.ConcurrentHashMap;
    import java.util.concurrent.ExecutorService;
    import java.util.concurrent.Future;
    import java.util.concurrent.atomic.AtomicBoolean;
    import org.hammer.audio.workflow.Workflow;
    import org.hammer.audio.workflow.WorkflowValidator;
    import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
    import org.hammer.audio.workflow.store.WorkflowSnapshot;

    /** Executes immutable workflow snapshots without sharing mutable editor state. */
    public final class WorkflowRunService implements AutoCloseable {

      private final ExecutorService executor;
      private final Backend backend;
      private final WorkflowValidator validator = new WorkflowValidator();
      private final WorkflowDslSerializer serializer = new WorkflowDslSerializer();
      private final Map<String, MutableRun> runs = new ConcurrentHashMap<>();

      public WorkflowRunService(ExecutorService executor, Backend backend) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.backend = Objects.requireNonNull(backend, "backend");
      }

      public RunSnapshot start(Workflow workflow, String sourceCommitId) {
        Objects.requireNonNull(workflow, "workflow");
        List<String> violations = validator.validate(workflow);
        if (!violations.isEmpty()) {
          throw new IllegalArgumentException(String.join("; ", violations));
        }
        WorkflowSnapshot immutable =
            new WorkflowSnapshot(workflow.id(), serializer.serialize(workflow));
        String runId = UUID.randomUUID().toString();
        MutableRun run = new MutableRun(runId, sourceCommitId, immutable);
        runs.put(runId, run);
        Future<?> future = executor.submit(() -> execute(run));
        run.future = future;
        return run.snapshot();
      }

      public RunSnapshot get(String runId) {
        MutableRun run = runs.get(requireId(runId));
        if (run == null) {
          throw new IllegalArgumentException("Unknown workflow run: " + runId);
        }
        return run.snapshot();
      }

      public List<RunSnapshot> runs() {
        return runs.values().stream()
            .map(MutableRun::snapshot)
            .sorted(Comparator.comparing(RunSnapshot::createdAt).reversed())
            .toList();
      }

      public RunSnapshot cancel(String runId) {
        MutableRun run = runs.get(requireId(runId));
        if (run == null) {
          throw new IllegalArgumentException("Unknown workflow run: " + runId);
        }
        run.cancelled.set(true);
        Future<?> future = run.future;
        if (future != null) {
          future.cancel(true);
        }
        synchronized (run) {
          if (!run.status.terminal()) {
            run.status = Status.CANCELLED;
            run.finishedAt = Instant.now();
          }
          return run.snapshot();
        }
      }

      private void execute(MutableRun run) {
        synchronized (run) {
          if (run.cancelled.get()) {
            return;
          }
          run.status = Status.RUNNING;
          run.startedAt = Instant.now();
        }
        try {
          Map<String, String> result = backend.execute(run.snapshot, run.cancelled);
          synchronized (run) {
            if (run.cancelled.get()) {
              run.status = Status.CANCELLED;
            } else {
              run.status = Status.SUCCEEDED;
              run.result = Map.copyOf(result);
              run.progress = 1.0;
            }
            run.finishedAt = Instant.now();
          }
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          synchronized (run) {
            run.status = Status.CANCELLED;
            run.finishedAt = Instant.now();
          }
        } catch (RuntimeException ex) {
          synchronized (run) {
            run.status = Status.FAILED;
            run.error = ex.getMessage();
            run.finishedAt = Instant.now();
          }
        }
      }

      @Override
      public void close() {
        executor.close();
      }

      private static String requireId(String value) {
        Objects.requireNonNull(value, "runId");
        if (value.isBlank()) {
          throw new IllegalArgumentException("runId must not be blank");
        }
        return value;
      }

      @FunctionalInterface
      public interface Backend {
        Map<String, String> execute(WorkflowSnapshot snapshot, AtomicBoolean cancelled)
            throws InterruptedException;
      }

      public enum Status {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED;

        public boolean terminal() {
          return this == SUCCEEDED || this == FAILED || this == CANCELLED;
        }
      }

      public record RunSnapshot(
          String runId,
          String sourceCommitId,
          WorkflowSnapshot workflowSnapshot,
          Status status,
          double progress,
          Instant createdAt,
          Instant startedAt,
          Instant finishedAt,
          Map<String, String> result,
          String error) {
        public RunSnapshot {
          result = Map.copyOf(result);
        }
      }

      private static final class MutableRun {
        private final String runId;
        private final String sourceCommitId;
        private final WorkflowSnapshot snapshot;
        private final Instant createdAt = Instant.now();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Future<?> future;
        private Status status = Status.QUEUED;
        private double progress;
        private Instant startedAt;
        private Instant finishedAt;
        private Map<String, String> result = new LinkedHashMap<>();
        private String error;

        MutableRun(String runId, String sourceCommitId, WorkflowSnapshot snapshot) {
          this.runId = runId;
          this.sourceCommitId = sourceCommitId;
          this.snapshot = snapshot;
        }

        synchronized RunSnapshot snapshot() {
          return new RunSnapshot(
              runId,
              sourceCommitId,
              snapshot,
              status,
              progress,
              createdAt,
              startedAt,
              finishedAt,
              result,
              error);
        }
      }
    }
    ''',
)

write(
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/WorkflowHistorySearchService.java",
    r'''
    package org.hammer.audio.workflow.editor.http;

    import java.lang.reflect.Method;
    import java.time.Instant;
    import java.util.ArrayList;
    import java.util.LinkedHashMap;
    import java.util.LinkedHashSet;
    import java.util.List;
    import java.util.Locale;
    import java.util.Map;
    import java.util.Objects;
    import java.util.Set;
    import org.hammer.audio.workflow.Node;
    import org.hammer.audio.workflow.Workflow;
    import org.hammer.audio.workflow.dsl.WorkflowDslParser;
    import org.hammer.audio.workflow.search.WorkflowHistoryDocument;
    import org.hammer.audio.workflow.search.WorkflowHistoryQuery;
    import org.hammer.audio.workflow.search.WorkflowHistorySearchIndex;
    import org.hammer.audio.workflow.store.CommitId;
    import org.hammer.audio.workflow.store.CommitInfo;
    import org.hammer.audio.workflow.store.CommitMetadata;
    import org.hammer.audio.workflow.store.VersionedWorkflowStore;
    import org.hammer.audio.workflow.store.WorkflowCheckpointListener;
    import org.hammer.audio.workflow.store.WorkflowSnapshot;
    import org.springframework.beans.factory.ObjectProvider;

    /** Builds and queries non-authoritative history projections from the versioned store. */
    public final class WorkflowHistorySearchService implements WorkflowCheckpointListener {

      private final ObjectProvider<VersionedWorkflowStore> storeProvider;
      private final WorkflowHistorySearchIndex index;
      private final WorkflowDslParser parser = new WorkflowDslParser();

      public WorkflowHistorySearchService(
          ObjectProvider<VersionedWorkflowStore> storeProvider, WorkflowHistorySearchIndex index) {
        this.storeProvider = Objects.requireNonNull(storeProvider, "storeProvider");
        this.index = Objects.requireNonNull(index, "index");
      }

      @Override
      public void checkpointCreated(
          String branch, CommitId commitId, WorkflowSnapshot snapshot, CommitMetadata metadata) {
        index.upsert(document(branch, commitId, snapshot, metadata));
      }

      public int rebuild(List<String> branches, int limitPerBranch) {
        VersionedWorkflowStore store = requireStore();
        List<WorkflowHistoryDocument> documents = new ArrayList<>();
        for (String branch : branches.stream().distinct().sorted().toList()) {
          for (CommitInfo info : store.history(branch, limitPerBranch)) {
            WorkflowSnapshot snapshot = store.loadAtCommit(info.commitId());
            documents.add(document(branch, info.commitId(), snapshot, info.metadata()));
          }
        }
        index.replaceAll(documents);
        return documents.size();
      }

      public List<WorkflowHistoryDocument> search(WorkflowHistoryQuery query) {
        return index.search(query);
      }

      private WorkflowHistoryDocument document(
          String branch, CommitId commitId, WorkflowSnapshot snapshot, CommitMetadata metadata) {
        Workflow workflow = parser.parse(snapshot.dslText());
        Set<String> nodeTypes = new LinkedHashSet<>();
        Map<String, String> properties = new LinkedHashMap<>();
        StringBuilder text =
            new StringBuilder()
                .append(workflow.id())
                .append(' ')
                .append(workflow.name())
                .append(' ')
                .append(metadata.author())
                .append(' ')
                .append(metadata.message())
                .append(' ')
                .append(snapshot.dslText());
        for (Node node : workflow.nodes()) {
          nodeTypes.add(node.type());
          text.append(' ').append(node.id()).append(' ').append(node.type()).append(' ').append(node.label());
          extractMetadata(node, properties, text);
        }
        return new WorkflowHistoryDocument(
            branch,
            commitId.value(),
            workflow.id(),
            metadata.author(),
            metadata.message(),
            metadata.timestamp(),
            nodeTypes,
            properties,
            text.toString().toLowerCase(Locale.ROOT));
      }

      private static void extractMetadata(
          Node node, Map<String, String> properties, StringBuilder searchableText) {
        Object metadata = node.metadata();
        for (String methodName : List.of("values", "properties", "asMap")) {
          try {
            Method method = metadata.getClass().getMethod(methodName);
            Object value = method.invoke(metadata);
            if (value instanceof Map<?, ?> map) {
              map.forEach(
                  (key, item) -> {
                    String propertyKey = node.id() + "." + key;
                    String propertyValue = String.valueOf(item);
                    properties.put(propertyKey, propertyValue);
                    properties.putIfAbsent(String.valueOf(key), propertyValue);
                    searchableText.append(' ').append(key).append(' ').append(propertyValue);
                  });
              return;
            }
          } catch (ReflectiveOperationException ignored) {
            // Try the next stable map-like accessor; metadata.toString remains searchable below.
          }
        }
        searchableText.append(' ').append(metadata);
      }

      private VersionedWorkflowStore requireStore() {
        VersionedWorkflowStore store = storeProvider.getIfAvailable();
        if (store == null) {
          throw new IllegalStateException("VersionedWorkflowStore is not configured");
        }
        return store;
      }
    }
    ''',
)

write(
    "audio-app/src/main/java/org/hammer/audio/app/VersionIntelligenceConfiguration.java",
    r'''
    package org.hammer.audio.app;

    import java.util.Map;
    import java.util.concurrent.Executors;
    import org.hammer.audio.workflow.execution.WorkflowRunService;
    import org.hammer.audio.workflow.search.InMemoryWorkflowHistorySearchIndex;
    import org.hammer.audio.workflow.search.WorkflowHistorySearchIndex;
    import org.hammer.audio.workflow.store.VersionedWorkflowStore;
    import org.hammer.audio.workflow.store.WorkflowCheckpointListener;
    import org.hammer.audio.workflow.editor.http.WorkflowHistorySearchService;
    import org.springframework.beans.factory.ObjectProvider;
    import org.springframework.context.annotation.Bean;
    import org.springframework.context.annotation.Configuration;

    /** Replaceable search projection and immutable execution wiring. */
    @Configuration
    public class VersionIntelligenceConfiguration {

      @Bean
      public WorkflowHistorySearchIndex workflowHistorySearchIndex() {
        return new InMemoryWorkflowHistorySearchIndex();
      }

      @Bean
      public WorkflowHistorySearchService workflowHistorySearchService(
          ObjectProvider<VersionedWorkflowStore> storeProvider,
          WorkflowHistorySearchIndex index) {
        return new WorkflowHistorySearchService(storeProvider, index);
      }

      @Bean
      public WorkflowCheckpointListener workflowCheckpointListener(
          WorkflowHistorySearchService searchService) {
        return searchService;
      }

      @Bean(destroyMethod = "close")
      public WorkflowRunService workflowRunService() {
        return new WorkflowRunService(
            Executors.newVirtualThreadPerTaskExecutor(),
            (snapshot, cancelled) -> {
              if (cancelled.get()) {
                throw new InterruptedException("Workflow run cancelled");
              }
              return Map.of(
                  "workflowId", snapshot.workflowId(),
                  "dslBytes", Integer.toString(snapshot.dslText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length),
                  "status", "validated-and-snapshotted");
            });
      }
    }
    ''',
)

# Connect the existing editor service to the projection hook.
config_path = ROOT / "audio-app/src/main/java/org/hammer/audio/app/WorkbenchConfiguration.java"
config = config_path.read_text(encoding="utf-8")
if "WorkflowCheckpointListener" not in config:
    config = config.replace(
        "import org.hammer.audio.workflow.store.VersionedWorkflowStore;",
        "import org.hammer.audio.workflow.store.VersionedWorkflowStore;\n"
        "import org.hammer.audio.workflow.store.WorkflowCheckpointListener;",
    )
    config = config.replace(
        "@Value(\"#{@versionedWorkflowStore?}\") VersionedWorkflowStore store) {",
        "@Value(\"#{@versionedWorkflowStore?}\") VersionedWorkflowStore store,\n"
        "      WorkflowCheckpointListener checkpointListener) {",
    )
    config = config.replace(
        "    return new WorkflowEditorService(log, validator, store);",
        "    WorkflowEditorService service = new WorkflowEditorService(log, validator, store);\n"
        "    service.setCheckpointListener(checkpointListener);\n"
        "    return service;",
    )
config_path.write_text(config, encoding="utf-8")

write(
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/WorkflowVersionIntelligenceHttpAdapter.java",
    r'''
    package org.hammer.audio.workflow.editor.http;

    import jakarta.validation.Valid;
    import jakarta.validation.constraints.Max;
    import jakarta.validation.constraints.Min;
    import jakarta.validation.constraints.NotBlank;
    import jakarta.validation.constraints.NotEmpty;
    import jakarta.validation.constraints.NotNull;
    import java.time.Instant;
    import java.util.List;
    import java.util.Map;
    import java.util.Objects;
    import org.hammer.audio.workflow.Workflow;
    import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
    import org.hammer.audio.workflow.dsl.WorkflowDslParser;
    import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
    import org.hammer.audio.workflow.execution.WorkflowRunService;
    import org.hammer.audio.workflow.search.WorkflowHistoryDocument;
    import org.hammer.audio.workflow.search.WorkflowHistoryQuery;
    import org.hammer.audio.workflow.store.CommitId;
    import org.hammer.audio.workflow.store.CommitMetadata;
    import org.hammer.audio.workflow.store.VersionedWorkflowStore;
    import org.hammer.audio.workflow.store.WorkflowSnapshot;
    import org.hammer.audio.workflow.version.WorkflowDiff;
    import org.hammer.audio.workflow.version.WorkflowMergeResolution;
    import org.hammer.audio.workflow.version.WorkflowMergeService;
    import org.hammer.audio.workflow.version.WorkflowSemanticDiffService;
    import org.springframework.beans.factory.ObjectProvider;
    import org.springframework.web.bind.annotation.DeleteMapping;
    import org.springframework.web.bind.annotation.GetMapping;
    import org.springframework.web.bind.annotation.PathVariable;
    import org.springframework.web.bind.annotation.PostMapping;
    import org.springframework.web.bind.annotation.RequestBody;
    import org.springframework.web.bind.annotation.RequestMapping;
    import org.springframework.web.bind.annotation.RequestParam;
    import org.springframework.web.bind.annotation.RestController;

    /** REST facade for semantic compare/merge, rebuildable search and immutable executions. */
    @RestController
    @RequestMapping("/workflow")
    public final class WorkflowVersionIntelligenceHttpAdapter {

      private final ObjectProvider<VersionedWorkflowStore> storeProvider;
      private final WorkflowHistorySearchService searchService;
      private final WorkflowSessionRegistry sessions;
      private final WorkflowRunService runs;
      private final WorkflowDslParser parser = new WorkflowDslParser();
      private final WorkflowDslSerializer serializer = new WorkflowDslSerializer();
      private final WorkflowSemanticDiffService diffService = new WorkflowSemanticDiffService();
      private final WorkflowMergeService mergeService = new WorkflowMergeService();

      public WorkflowVersionIntelligenceHttpAdapter(
          ObjectProvider<VersionedWorkflowStore> storeProvider,
          WorkflowHistorySearchService searchService,
          WorkflowSessionRegistry sessions,
          WorkflowRunService runs) {
        this.storeProvider = Objects.requireNonNull(storeProvider, "storeProvider");
        this.searchService = Objects.requireNonNull(searchService, "searchService");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.runs = Objects.requireNonNull(runs, "runs");
      }

      @PostMapping("/versions/compare")
      public WorkflowDiff compare(@Valid @RequestBody CompareRequest request) {
        return diffService.compare(load(request.leftCommitId()), load(request.rightCommitId()));
      }

      @PostMapping("/versions/merge")
      public MergeResponse merge(@Valid @RequestBody MergeRequest request) {
        WorkflowMergeService.MergeResult result =
            mergeService.merge(
                load(request.baseCommitId()),
                load(request.localCommitId()),
                load(request.remoteCommitId()),
                request.resolutions());
        String commitId = null;
        if (result.resolved() && request.targetBranch() != null && !request.targetBranch().isBlank()) {
          Workflow merged = result.mergedWorkflow();
          WorkflowSnapshot snapshot =
              new WorkflowSnapshot(merged.id(), serializer.serialize(merged));
          CommitMetadata metadata =
              new CommitMetadata(
                  request.author(),
                  request.message() == null || request.message().isBlank()
                      ? "Semantic workflow merge"
                      : request.message(),
                  Instant.now());
          commitId = requireStore().commit(request.targetBranch(), snapshot, metadata).value();
        }
        return new MergeResponse(result, commitId);
      }

      @PostMapping("/search/rebuild")
      public RebuildResponse rebuild(@Valid @RequestBody RebuildRequest request) {
        return new RebuildResponse(searchService.rebuild(request.branches(), request.limitPerBranch()));
      }

      @GetMapping("/search")
      public List<WorkflowHistoryDocument> search(
          @RequestParam(required = false) String text,
          @RequestParam(required = false) String branch,
          @RequestParam(required = false) String author,
          @RequestParam(required = false) Instant from,
          @RequestParam(required = false) Instant to,
          @RequestParam(required = false) String nodeType,
          @RequestParam(required = false) String propertyKey,
          @RequestParam(required = false) String propertyValue,
          @RequestParam(defaultValue = "50") int limit) {
        return searchService.search(
            new WorkflowHistoryQuery(
                text, branch, author, from, to, nodeType, propertyKey, propertyValue, limit));
      }

      @PostMapping("/executions")
      public WorkflowRunService.RunSnapshot start(@Valid @RequestBody ExecuteRequest request) {
        if (request.sessionId() != null && !request.sessionId().isBlank()) {
          return runs.start(sessions.workflow(request.sessionId()), null);
        }
        if (request.commitId() != null && !request.commitId().isBlank()) {
          return runs.start(load(request.commitId()), request.commitId());
        }
        throw new IllegalArgumentException("Either sessionId or commitId is required");
      }

      @GetMapping("/executions")
      public List<WorkflowRunService.RunSnapshot> executions() {
        return runs.runs();
      }

      @GetMapping("/executions/{runId}")
      public WorkflowRunService.RunSnapshot execution(@PathVariable String runId) {
        return runs.get(runId);
      }

      @DeleteMapping("/executions/{runId}")
      public WorkflowRunService.RunSnapshot cancel(@PathVariable String runId) {
        return runs.cancel(runId);
      }

      private Workflow load(String commitId) {
        WorkflowSnapshot snapshot = requireStore().loadAtCommit(new CommitId(commitId));
        Workflow workflow = parser.parse(snapshot.dslText());
        if (!snapshot.workflowId().equals(workflow.id())) {
          throw new IllegalArgumentException("Stored snapshot id and DSL workflow id differ");
        }
        return workflow;
      }

      private VersionedWorkflowStore requireStore() {
        VersionedWorkflowStore store = storeProvider.getIfAvailable();
        if (store == null) {
          throw new IllegalStateException("VersionedWorkflowStore is not configured");
        }
        return store;
      }

      public record CompareRequest(@NotBlank String leftCommitId, @NotBlank String rightCommitId) {}

      public record MergeRequest(
          @NotBlank String baseCommitId,
          @NotBlank String localCommitId,
          @NotBlank String remoteCommitId,
          @NotNull Map<String, WorkflowMergeResolution.Choice> resolutions,
          String targetBranch,
          @NotBlank String author,
          String message) {}

      public record MergeResponse(WorkflowMergeService.MergeResult result, String commitId) {}

      public record RebuildRequest(
          @NotEmpty List<@NotBlank String> branches,
          @Min(1) @Max(10000) int limitPerBranch) {
        public RebuildRequest {
          branches = List.copyOf(branches);
        }
      }

      public record RebuildResponse(int indexedDocuments) {}

      public record ExecuteRequest(String sessionId, String commitId) {}
    }
    ''',
)

write(
    "audio-core/src/test/java/org/hammer/audio/workflow/version/WorkflowMergeServiceTest.java",
    r'''
    package org.hammer.audio.workflow.version;

    import static org.junit.jupiter.api.Assertions.assertEquals;
    import static org.junit.jupiter.api.Assertions.assertFalse;
    import static org.junit.jupiter.api.Assertions.assertTrue;

    import java.util.List;
    import java.util.Map;
    import org.hammer.audio.workflow.Metadata;
    import org.hammer.audio.workflow.Node;
    import org.hammer.audio.workflow.Workflow;
    import org.junit.jupiter.api.Test;

    class WorkflowMergeServiceTest {

      @Test
      void differentNodesMergeWithoutConflict() {
        Workflow base = workflow(List.of(node("a", "A"), node("b", "B")));
        Workflow local = workflow(List.of(node("a", "Local A"), node("b", "B")));
        Workflow remote = workflow(List.of(node("a", "A"), node("b", "Remote B")));

        WorkflowMergeService.MergeResult result =
            new WorkflowMergeService().merge(base, local, remote);

        assertTrue(result.resolved());
        assertTrue(result.conflicts().isEmpty());
        assertEquals(List.of("Local A", "Remote B"), result.mergedWorkflow().nodes().stream().map(Node::label).toList());
      }

      @Test
      void sameNodeChangedDifferentlyProducesDeterministicConflictAndResolution() {
        Workflow base = workflow(List.of(node("a", "A")));
        Workflow local = workflow(List.of(node("a", "Local")));
        Workflow remote = workflow(List.of(node("a", "Remote")));
        WorkflowMergeService service = new WorkflowMergeService();

        WorkflowMergeService.MergeResult conflicted = service.merge(base, local, remote);
        assertFalse(conflicted.resolved());
        assertEquals("node:a", conflicted.conflicts().getFirst().conflictId());

        WorkflowMergeService.MergeResult resolved =
            service.merge(
                base,
                local,
                remote,
                Map.of("node:a", WorkflowMergeResolution.Choice.REMOTE));
        assertTrue(resolved.resolved());
        assertEquals("Remote", resolved.mergedWorkflow().nodes().getFirst().label());
      }

      private static Workflow workflow(List<Node> nodes) {
        return new Workflow("workflow", "Workflow", nodes, List.of());
      }

      private static Node node(String id, String label) {
        return new Node(id, "type", label, List.of(), List.of(), Metadata.empty());
      }
    }
    ''',
)

write(
    "audio-core/src/test/java/org/hammer/audio/workflow/search/InMemoryWorkflowHistorySearchIndexTest.java",
    r'''
    package org.hammer.audio.workflow.search;

    import static org.junit.jupiter.api.Assertions.assertEquals;

    import java.time.Instant;
    import java.util.Map;
    import java.util.Set;
    import org.junit.jupiter.api.Test;

    class InMemoryWorkflowHistorySearchIndexTest {

      @Test
      void textNodeTypeAndPropertyFiltersAreDeterministic() {
        InMemoryWorkflowHistorySearchIndex index = new InMemoryWorkflowHistorySearchIndex();
        index.upsert(
            new WorkflowHistoryDocument(
                "main",
                "c1",
                "workflow",
                "alice",
                "FFT experiment",
                Instant.parse("2026-01-01T00:00:00Z"),
                Set.of("fft"),
                Map.of("windowSize", "4096"),
                "fft experiment windowsize 4096"));

        assertEquals(
            1,
            index
                .search(
                    new WorkflowHistoryQuery(
                        "experiment", "main", "alice", null, null, "fft", "windowSize", "4096", 10))
                .size());
      }
    }
    ''',
)

print("Generated version intelligence and execution layer")
