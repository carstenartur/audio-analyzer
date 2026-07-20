package org.hammer.audio.infrastructure.workflow.search;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryFilter;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryQuery;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryResult;
import org.hammer.audio.workflow.store.CommitId;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.search.engine.search.predicate.dsl.BooleanPredicateClausesStep;
import org.hibernate.search.engine.search.predicate.dsl.SearchPredicateFactory;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.session.SearchSession;

/** Executes branch-aware queries against the Audio Analyzer-owned semantic projection. */
final class WorkflowSemanticQueryService {

  private final SessionFactory sessionFactory;
  private final String repositoryName;

  WorkflowSemanticQueryService(SessionFactory sessionFactory, String repositoryName) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    this.repositoryName = Objects.requireNonNull(repositoryName, "repositoryName");
  }

  List<WorkflowSemanticHistoryResult> search(WorkflowSemanticHistoryQuery query) {
    Objects.requireNonNull(query, "query");
    try (Session session = sessionFactory.openSession()) {
      SearchSession searchSession = Search.session(session);
      return searchSession
          .search(WorkflowSemanticIndexEntity.class)
          .where(f -> semanticPredicate(f, query.filter()))
          .sort(f -> f.field("branchPosition").asc())
          .fetchHits(query.limit())
          .stream()
          .map(WorkflowSemanticQueryService::toResult)
          .toList();
    }
  }

  List<CommitId> findCandidateCommitIds(WorkflowSemanticHistoryFilter filter) {
    Objects.requireNonNull(filter, "filter");
    try (Session session = sessionFactory.openSession()) {
      SearchSession searchSession = Search.session(session);
      return searchSession
          .search(WorkflowSemanticIndexEntity.class)
          .where(f -> semanticPredicate(f, filter))
          .sort(f -> f.field("branchPosition").asc())
          .fetchAllHits()
          .stream()
          .map(row -> new CommitId(row.getObjectId()))
          .toList();
    }
  }

  Map<String, WorkflowSemanticHistoryResult> findEvidence(
      String branch, Collection<CommitId> commits) {
    String normalizedBranch = WorkflowSemanticIndexService.normalizeBranch(branch);
    Objects.requireNonNull(commits, "commits");
    List<String> objectIds = commits.stream().map(CommitId::value).distinct().toList();
    if (objectIds.isEmpty()) {
      return Map.of();
    }
    try (Session session = sessionFactory.openSession()) {
      List<WorkflowSemanticIndexEntity> rows =
          session
              .createQuery(
                  "FROM WorkflowSemanticIndexEntity s "
                      + "WHERE s.repositoryName = :repository "
                      + "AND s.branchName = :branch AND s.objectId IN :objectIds",
                  WorkflowSemanticIndexEntity.class)
              .setParameter("repository", repositoryName)
              .setParameter("branch", normalizedBranch)
              .setParameter("objectIds", objectIds)
              .getResultList();
      Map<String, WorkflowSemanticHistoryResult> evidence = new ConcurrentHashMap<>();
      for (WorkflowSemanticIndexEntity row : rows) {
        evidence.put(row.getObjectId(), toResult(row));
      }
      return Map.copyOf(evidence);
    }
  }

  private BooleanPredicateClausesStep<?, ?> semanticPredicate(
      SearchPredicateFactory f, WorkflowSemanticHistoryFilter filter) {
    BooleanPredicateClausesStep<?, ?> predicate =
        f.bool()
            .filter(f.match().field("repositoryName").matching(repositoryName))
            .filter(
                f.match()
                    .field("branchName")
                    .matching(WorkflowSemanticIndexService.normalizeBranch(filter.branch())));
    if (filter.workflowId() != null) {
      predicate.filter(f.match().field("workflowId").matching(filter.workflowId()));
    }
    if (filter.nodeId() != null) {
      predicate.filter(
          f.match().field(WorkflowSemanticIndexEntity.NODE_IDS_FIELD).matching(filter.nodeId()));
    }
    if (filter.nodeType() != null) {
      predicate.filter(
          f.match()
              .field(WorkflowSemanticIndexEntity.NODE_TYPES_FIELD)
              .matching(filter.nodeType()));
    }
    if (filter.labelText() != null) {
      predicate.must(
          f.simpleQueryString()
              .fields("workflowName", "nodeLabelText")
              .matching(filter.labelText()));
    }
    addPropertyPredicates(f, predicate, filter);
    return predicate;
  }

  private static void addPropertyPredicates(
      SearchPredicateFactory f,
      BooleanPredicateClausesStep<?, ?> predicate,
      WorkflowSemanticHistoryFilter filter) {
    if (filter.propertyKey() != null && filter.propertyValue() != null) {
      predicate.filter(
          f.match()
              .field(WorkflowSemanticIndexEntity.PROPERTY_PAIRS_FIELD)
              .matching(
                  WorkflowSemanticProjectionValues.encodePair(
                      filter.propertyKey(), filter.propertyValue())));
      return;
    }
    if (filter.propertyKey() != null) {
      predicate.filter(
          f.match()
              .field(WorkflowSemanticIndexEntity.PROPERTY_KEYS_FIELD)
              .matching(filter.propertyKey()));
    }
    if (filter.propertyValue() != null) {
      predicate.filter(
          f.match()
              .field(WorkflowSemanticIndexEntity.PROPERTY_VALUES_FIELD)
              .matching(filter.propertyValue()));
    }
  }

  private static WorkflowSemanticHistoryResult toResult(WorkflowSemanticIndexEntity row) {
    return new WorkflowSemanticHistoryResult(
        new CommitId(row.getObjectId()),
        row.getBranchName(),
        row.getWorkflowId(),
        row.getWorkflowName(),
        row.getNodeIds(),
        row.getNodeTypes(),
        row.getNodeLabels(),
        WorkflowSemanticProjectionValues.decodePairs(row.getPropertyPairs()));
  }
}
