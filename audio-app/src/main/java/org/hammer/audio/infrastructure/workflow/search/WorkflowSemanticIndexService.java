package org.hammer.audio.infrastructure.workflow.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.hammer.audio.workflow.dsl.WorkflowDslParser;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryQuery;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryResult;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.WorkflowSnapshot;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.search.engine.search.predicate.dsl.BooleanPredicateClausesStep;
import org.hibernate.search.engine.search.predicate.dsl.SearchPredicateFactory;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.session.SearchSession;

/** Maintains and queries Audio Analyzer-owned semantic workflow history. */
public final class WorkflowSemanticIndexService {

  private final SessionFactory sessionFactory;
  private final String repositoryName;
  private final WorkflowDslParser parser;

  /** Creates a semantic projection service over the shared application persistence context. */
  public WorkflowSemanticIndexService(SessionFactory sessionFactory, String repositoryName) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    this.repositoryName = requireNotBlank(repositoryName, "repositoryName");
    this.parser = new WorkflowDslParser();
  }

  /**
   * Adds or refreshes the new head commit without reparsing the rest of the linear branch history.
   *
   * @return whether a new projection row was created
   */
  public boolean indexCheckpoint(
      String branch, CommitId commitId, WorkflowSnapshot authoritativeSnapshot) {
    String normalizedBranch = normalizeBranch(branch);
    Objects.requireNonNull(commitId, "commitId");
    WorkflowSemanticProjectionValues values =
        WorkflowSemanticProjectionValues.from(authoritativeSnapshot, parser);

    try (Session session = sessionFactory.openSession()) {
      Transaction transaction = session.beginTransaction();
      boolean committed = false;
      try {
        List<WorkflowSemanticIndexEntity> existingRows =
            branchRows(session, normalizedBranch, true);
        WorkflowSemanticIndexEntity head = null;
        List<WorkflowSemanticIndexEntity> olderRows = new ArrayList<>(existingRows.size());
        for (WorkflowSemanticIndexEntity row : existingRows) {
          if (row.getObjectId().equals(commitId.value())) {
            head = row;
          } else {
            olderRows.add(row);
          }
        }

        boolean created = head == null;
        if (created) {
          head =
              WorkflowSemanticIndexEntity.create(
                  repositoryName, normalizedBranch, commitId.value(), 0, values);
          session.persist(head);
        } else {
          head.apply(values);
          head.setBranchPosition(0);
        }
        for (int position = 0; position < olderRows.size(); position++) {
          olderRows.get(position).setBranchPosition(position + 1);
        }
        transaction.commit();
        committed = true;
        return created;
      } finally {
        rollbackIfNecessary(transaction, committed);
      }
    }
  }

  /**
   * Reconciles all semantic rows for one branch with an authoritative ordered commit sequence.
   *
   * @return number of newly created projection rows
   */
  public int replaceBranch(String branch, List<WorkflowSemanticProjectionEntry> entries) {
    String normalizedBranch = normalizeBranch(branch);
    Objects.requireNonNull(entries, "entries");
    Set<String> suppliedObjectIds = new LinkedHashSet<>();
    for (WorkflowSemanticProjectionEntry entry : entries) {
      if (!suppliedObjectIds.add(entry.commitId().value())) {
        throw new IllegalArgumentException(
            "duplicate semantic projection commit: " + entry.commitId().value());
      }
    }

    try (Session session = sessionFactory.openSession()) {
      Transaction transaction = session.beginTransaction();
      boolean committed = false;
      try {
        Map<String, WorkflowSemanticIndexEntity> existingByObjectId = new LinkedHashMap<>();
        for (WorkflowSemanticIndexEntity row : branchRows(session, normalizedBranch, false)) {
          existingByObjectId.put(row.getObjectId(), row);
        }

        int created = 0;
        for (WorkflowSemanticProjectionEntry entry : entries) {
          WorkflowSemanticProjectionValues values =
              WorkflowSemanticProjectionValues.from(entry.snapshot(), parser);
          WorkflowSemanticIndexEntity row = existingByObjectId.remove(entry.commitId().value());
          if (row == null) {
            row =
                WorkflowSemanticIndexEntity.create(
                    repositoryName,
                    normalizedBranch,
                    entry.commitId().value(),
                    entry.branchPosition(),
                    values);
            session.persist(row);
            created++;
          } else {
            row.setBranchPosition(entry.branchPosition());
            row.apply(values);
          }
        }
        existingByObjectId.values().forEach(session::remove);
        transaction.commit();
        committed = true;
        return created;
      } finally {
        rollbackIfNecessary(transaction, committed);
      }
    }
  }

  /** Searches one branch-specific semantic projection with exact domain filters. */
  public List<WorkflowSemanticHistoryResult> search(WorkflowSemanticHistoryQuery query) {
    Objects.requireNonNull(query, "query");
    try (Session session = sessionFactory.openSession()) {
      SearchSession searchSession = Search.session(session);
      return searchSession
          .search(WorkflowSemanticIndexEntity.class)
          .where(
              f -> {
                BooleanPredicateClausesStep<?, ?> predicate =
                    f.bool()
                        .filter(f.match().field("repositoryName").matching(repositoryName))
                        .filter(
                            f.match()
                                .field("branchName")
                                .matching(normalizeBranch(query.branch())));
                if (query.workflowId() != null) {
                  predicate.filter(f.match().field("workflowId").matching(query.workflowId()));
                }
                if (query.nodeId() != null) {
                  predicate.filter(
                      f.match()
                          .field(WorkflowSemanticIndexEntity.NODE_IDS_FIELD)
                          .matching(query.nodeId()));
                }
                if (query.nodeType() != null) {
                  predicate.filter(
                      f.match()
                          .field(WorkflowSemanticIndexEntity.NODE_TYPES_FIELD)
                          .matching(query.nodeType()));
                }
                if (query.labelText() != null) {
                  predicate.must(
                      f.simpleQueryString()
                          .fields("workflowName", "nodeLabelText")
                          .matching(query.labelText()));
                }
                addPropertyPredicates(f, predicate, query);
                return predicate;
              })
          .sort(f -> f.field("branchPosition").asc())
          .fetchHits(query.limit())
          .stream()
          .map(WorkflowSemanticIndexService::toResult)
          .toList();
    }
  }

  private List<WorkflowSemanticIndexEntity> branchRows(
      Session session, String normalizedBranch, boolean ordered) {
    String orderBy = ordered ? " ORDER BY s.branchPosition" : "";
    return session
        .createQuery(
            "FROM WorkflowSemanticIndexEntity s "
                + "WHERE s.repositoryName = :repository AND s.branchName = :branch"
                + orderBy,
            WorkflowSemanticIndexEntity.class)
        .setParameter("repository", repositoryName)
        .setParameter("branch", normalizedBranch)
        .getResultList();
  }

  private static void addPropertyPredicates(
      SearchPredicateFactory f,
      BooleanPredicateClausesStep<?, ?> predicate,
      WorkflowSemanticHistoryQuery query) {
    if (query.propertyKey() != null && query.propertyValue() != null) {
      predicate.filter(
          f.match()
              .field(WorkflowSemanticIndexEntity.PROPERTY_PAIRS_FIELD)
              .matching(
                  WorkflowSemanticProjectionValues.encodePair(
                      query.propertyKey(), query.propertyValue())));
      return;
    }
    if (query.propertyKey() != null) {
      predicate.filter(
          f.match()
              .field(WorkflowSemanticIndexEntity.PROPERTY_KEYS_FIELD)
              .matching(query.propertyKey()));
    }
    if (query.propertyValue() != null) {
      predicate.filter(
          f.match()
              .field(WorkflowSemanticIndexEntity.PROPERTY_VALUES_FIELD)
              .matching(query.propertyValue()));
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

  private static void rollbackIfNecessary(Transaction transaction, boolean committed) {
    if (!committed && transaction.isActive()) {
      transaction.rollback();
    }
  }

  static String normalizeBranch(String branch) {
    String normalized = requireNotBlank(branch, "branch");
    return normalized.startsWith("refs/heads/")
        ? normalized.substring("refs/heads/".length())
        : normalized;
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    String normalized = value.trim();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return normalized;
  }
}
