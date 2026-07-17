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
