package org.hammer.audio.workflow.editor.http;

import java.lang.reflect.Method;
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
      text.append(' ')
          .append(node.id())
          .append(' ')
          .append(node.type())
          .append(' ')
          .append(node.label());
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
