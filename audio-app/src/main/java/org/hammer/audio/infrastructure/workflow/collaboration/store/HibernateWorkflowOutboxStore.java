package org.hammer.audio.infrastructure.workflow.collaboration.store;

import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.hammer.audio.workflow.collaboration.store.LeasedWorkflowOutboxEntry;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowOutboxEntry;
import org.hammer.audio.workflow.collaboration.store.WorkflowOutboxLeaseConflictException;
import org.hammer.audio.workflow.collaboration.store.WorkflowOutboxStore;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.StaleStateException;
import org.hibernate.Transaction;

/** Hibernate implementation of leased durable workflow outbox delivery. */
public final class HibernateWorkflowOutboxStore implements WorkflowOutboxStore {

  private final SessionFactory sessionFactory;

  /** Creates an outbox store using the shared application-managed Hibernate context. */
  public HibernateWorkflowOutboxStore(SessionFactory sessionFactory) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
  }

  @Override
  public Optional<StoredWorkflowOutboxEntry> find(String eventId) {
    String requiredEventId = requireNotBlank(eventId, "eventId");
    try (Session session = sessionFactory.openSession()) {
      return Optional.ofNullable(session.find(WorkflowOutboxEntity.class, requiredEventId))
          .map(WorkflowOutboxEntity::toStoredEntry);
    }
  }

  @Override
  public List<LeasedWorkflowOutboxEntry> claimDue(
      String leaseOwner, Instant claimedAt, Instant leaseExpiresAt, int limit) {
    String requiredLeaseOwner = requireNotBlank(leaseOwner, "leaseOwner");
    Instant requiredClaimedAt = Objects.requireNonNull(claimedAt, "claimedAt");
    Instant requiredLeaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
    if (!requiredLeaseExpiresAt.isAfter(requiredClaimedAt)) {
      throw new IllegalArgumentException("leaseExpiresAt must be after claimedAt");
    }
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be > 0");
    }
    return inTransaction(
        session ->
            claimWithinTransaction(
                session, requiredLeaseOwner, requiredClaimedAt, requiredLeaseExpiresAt, limit));
  }

  @Override
  public StoredWorkflowOutboxEntry markPublished(
      String eventId, String leaseOwner, String leaseToken, Instant publishedAt) {
    String requiredEventId = requireNotBlank(eventId, "eventId");
    String requiredLeaseOwner = requireNotBlank(leaseOwner, "leaseOwner");
    String requiredLeaseToken = requireNotBlank(leaseToken, "leaseToken");
    Instant requiredPublishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
    try {
      return inTransaction(
          session -> {
            WorkflowOutboxEntity event = requireOutboxForUpdate(session, requiredEventId);
            StoredWorkflowOutboxEntry stored =
                event.markPublished(requiredLeaseOwner, requiredLeaseToken, requiredPublishedAt);
            session.flush();
            return stored;
          });
    } catch (OptimisticLockException | StaleStateException failure) {
      throw new WorkflowOutboxLeaseConflictException(
          requiredEventId, requiredLeaseOwner, requiredLeaseToken, failure);
    }
  }

  @Override
  public StoredWorkflowOutboxEntry markFailed(
      String eventId, String leaseOwner, String leaseToken, Instant nextAttemptAt) {
    String requiredEventId = requireNotBlank(eventId, "eventId");
    String requiredLeaseOwner = requireNotBlank(leaseOwner, "leaseOwner");
    String requiredLeaseToken = requireNotBlank(leaseToken, "leaseToken");
    Instant requiredNextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
    try {
      return inTransaction(
          session -> {
            WorkflowOutboxEntity event = requireOutboxForUpdate(session, requiredEventId);
            StoredWorkflowOutboxEntry stored =
                event.markFailed(requiredLeaseOwner, requiredLeaseToken, requiredNextAttemptAt);
            session.flush();
            return stored;
          });
    } catch (OptimisticLockException | StaleStateException failure) {
      throw new WorkflowOutboxLeaseConflictException(
          requiredEventId, requiredLeaseOwner, requiredLeaseToken, failure);
    }
  }

  private static List<LeasedWorkflowOutboxEntry> claimWithinTransaction(
      Session session, String leaseOwner, Instant claimedAt, Instant leaseExpiresAt, int limit) {
    List<WorkflowOutboxEntity> events =
        session
            .createQuery(
                "FROM WorkflowOutboxEntity event "
                    + "WHERE event.publishedAt IS NULL "
                    + "AND event.nextAttemptAt <= :claimedAt "
                    + "AND (event.leaseExpiresAt IS NULL "
                    + "OR event.leaseExpiresAt <= :claimedAt) "
                    + "ORDER BY event.nextAttemptAt, event.occurredAt, event.storedEventId",
                WorkflowOutboxEntity.class)
            .setParameter("claimedAt", claimedAt)
            .setMaxResults(limit)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList();
    List<LeasedWorkflowOutboxEntry> claimed = new ArrayList<>(events.size());
    for (WorkflowOutboxEntity event : events) {
      if (event.claimableAt(claimedAt)) {
        claimed.add(
            event.lease(leaseOwner, UUID.randomUUID().toString(), claimedAt, leaseExpiresAt));
      }
    }
    session.flush();
    return List.copyOf(claimed);
  }

  private static WorkflowOutboxEntity requireOutboxForUpdate(Session session, String eventId) {
    WorkflowOutboxEntity event =
        session.find(WorkflowOutboxEntity.class, eventId, LockModeType.PESSIMISTIC_WRITE);
    if (event == null) {
      throw new NoSuchElementException("Unknown workflow outbox event: " + eventId);
    }
    return event;
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

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
