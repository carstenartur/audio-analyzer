package org.hammer.audio.infrastructure.workflow.collaboration.store;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionCandidate;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionDeletionResult;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionPlan;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionReason;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionSelection;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionSettings;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionStore;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

/** Hibernate implementation of bounded, revalidated published-outbox retention. */
public final class HibernateWorkflowOutboxRetentionStore
    implements WorkflowOutboxRetentionStore {

  private final SessionFactory sessionFactory;

  /** Creates a retention store over the shared application-managed persistence context. */
  public HibernateWorkflowOutboxRetentionStore(SessionFactory sessionFactory) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
  }

  @Override
  public WorkflowOutboxRetentionSelection selectPublishedBefore(
      Instant publishedCutoff, int limit) {
    Instant requiredCutoff = Objects.requireNonNull(publishedCutoff, "publishedCutoff");
    requireValidLimit(limit);
    try (Session session = sessionFactory.openSession()) {
      long scannedCount =
          session
              .createQuery(
                  "SELECT COUNT(event) FROM WorkflowOutboxEntity event "
                      + "WHERE event.publishedAt IS NOT NULL",
                  Long.class)
              .getSingleResult();
      List<WorkflowOutboxRetentionCandidate> candidates =
          session
              .createQuery(
                  "FROM WorkflowOutboxEntity event "
                      + "WHERE event.publishedAt IS NOT NULL "
                      + "AND event.publishedAt <= :publishedCutoff "
                      + "AND event.leaseOwner IS NULL "
                      + "AND event.leaseToken IS NULL "
                      + "AND event.leaseExpiresAt IS NULL "
                      + "ORDER BY event.publishedAt, event.storedEventId",
                  WorkflowOutboxEntity.class)
              .setParameter("publishedCutoff", requiredCutoff)
              .setMaxResults(limit)
              .getResultList()
              .stream()
              .map(HibernateWorkflowOutboxRetentionStore::toCandidate)
              .toList();
      return new WorkflowOutboxRetentionSelection(scannedCount, candidates);
    }
  }

  @Override
  public WorkflowOutboxRetentionDeletionResult deletePublished(
      WorkflowOutboxRetentionPlan plan) {
    WorkflowOutboxRetentionPlan requiredPlan = Objects.requireNonNull(plan, "plan");
    return inTransaction(session -> deleteWithinTransaction(session, requiredPlan));
  }

  private static WorkflowOutboxRetentionDeletionResult deleteWithinTransaction(
      Session session, WorkflowOutboxRetentionPlan plan) {
    List<String> deleted = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    for (WorkflowOutboxRetentionCandidate candidate : plan.candidates()) {
      WorkflowOutboxEntity entity =
          session.find(WorkflowOutboxEntity.class, candidate.eventId(), LockModeType.PESSIMISTIC_WRITE);
      if (!matchesPlan(entity, candidate, plan.publishedCutoff())) {
        skipped.add(candidate.eventId());
        continue;
      }
      session.remove(entity);
      deleted.add(candidate.eventId());
    }
    session.flush();
    return new WorkflowOutboxRetentionDeletionResult(deleted, skipped);
  }

  private static boolean matchesPlan(
      WorkflowOutboxEntity entity,
      WorkflowOutboxRetentionCandidate candidate,
      Instant publishedCutoff) {
    return entity != null
        && entity.retentionEligibleAt(publishedCutoff)
        && entity.sessionId().equals(candidate.sessionId())
        && entity.publishedAt().equals(candidate.publishedAt())
        && candidate.reason() == WorkflowOutboxRetentionReason.PUBLISHED_AT_OR_BEFORE_CUTOFF;
  }

  private static WorkflowOutboxRetentionCandidate toCandidate(WorkflowOutboxEntity entity) {
    return new WorkflowOutboxRetentionCandidate(
        entity.eventId(),
        entity.sessionId(),
        entity.publishedAt(),
        WorkflowOutboxRetentionReason.PUBLISHED_AT_OR_BEFORE_CUTOFF);
  }

  private static void requireValidLimit(int limit) {
    if (limit <= 0 || limit > WorkflowOutboxRetentionSettings.MAXIMUM_BATCH_SIZE) {
      throw new IllegalArgumentException(
          "limit must be between 1 and "
              + WorkflowOutboxRetentionSettings.MAXIMUM_BATCH_SIZE);
    }
  }

  private <T> T inTransaction(Function<Session, T> work) {
    try (Session session = sessionFactory.openSession()) {
      Transaction transaction = session.beginTransaction();
      try {
        T result = work.apply(session);
        transaction.commit();
        return result;
      } finally {
        if (transaction.isActive()) {
          transaction.rollback();
        }
      }
    }
  }
}
