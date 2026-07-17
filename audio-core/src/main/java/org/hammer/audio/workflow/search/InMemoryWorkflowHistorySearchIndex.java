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

  private static boolean matches(WorkflowHistoryDocument document, WorkflowHistoryQuery query) {
    if (hasText(query.text()) && !lower(document.searchableText()).contains(lower(query.text()))) {
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
      if (hasText(query.propertyValue()) && !lower(value).contains(lower(query.propertyValue()))) {
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
