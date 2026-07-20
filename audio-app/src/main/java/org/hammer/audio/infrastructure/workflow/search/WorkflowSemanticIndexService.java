package org.hammer.audio.infrastructure.workflow.search;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.hammer.audio.workflow.dsl.WorkflowDslParser;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryFilter;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryQuery;
import org.hammer.audio.workflow.history.WorkflowSemanticHistoryResult;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.WorkflowSnapshot;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

/** Maintains and queries Audio Analyzer-owned semantic workflow history. */
public final class WorkflowSemanticIndexService {

  private final SessionFactory sessionFactory;
  private final String repositoryName;
  private final WorkflowDslParser parser;
  private final WorkflowSemanticQueryService queryService;

  /** Creates a semantic projection service over the shared application persistence context. */
  public WorkflowSemanticIndexService(SessionFactory sessionFactory, String repositoryName) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    this.repositoryName = requireNotBlank(repositoryName, "repositoryName");
    this.parser = new WorkflowDslParser();
    this.queryService = new WorkflowSemanticQueryService(this.sessionFactory, this.repositoryName);
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
        Map<String, WorkflowSemanticIndexEntity> existingByObjectId = new ConcurrentHashMap<>();
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
    return queryService.search(query);
  }

  /** Returns every exact commit candidate matching one branch and its semantic predicates. */
  public List<CommitId> findCandidateCommitIds(WorkflowSemanticHistoryFilter filter) {
    return queryService.findCandidateCommitIds(filter);
  }

  /** Loads branch-specific semantic evidence for an exact final commit set. */
  public Map<String, WorkflowSemanticHistoryResult> findEvidence(
      String branch, Collection<CommitId> commits) {
    return queryService.findEvidence(branch, commits);
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
