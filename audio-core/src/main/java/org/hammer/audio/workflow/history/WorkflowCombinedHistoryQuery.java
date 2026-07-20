package org.hammer.audio.workflow.history;

import java.util.Objects;

/**
 * Correctly composed generic and workflow-semantic history query.
 *
 * <p>The semantic filter produces exact commit candidates without a result limit. The generic query
 * then applies commit metadata/full-text predicates, ranking or ordering and its final limit.
 *
 * @param genericQuery generic commit/message/author/path/time query carrying the final limit
 * @param semanticFilter branch reachability and workflow-domain predicates
 */
public record WorkflowCombinedHistoryQuery(
    WorkflowHistoryTextQuery genericQuery, WorkflowSemanticHistoryFilter semanticFilter) {

  public WorkflowCombinedHistoryQuery {
    Objects.requireNonNull(genericQuery, "genericQuery");
    Objects.requireNonNull(semanticFilter, "semanticFilter");
  }
}
