package org.hammer.audio.infrastructure.workflow.collaboration.store;

import jakarta.persistence.OptimisticLockException;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowOperation;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowOutboxEntry;
import org.hammer.audio.workflow.collaboration.store.StoredWorkflowSession;
import org.hammer.audio.workflow.collaboration.store.WorkflowOperationPersistenceConflictException;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionAppendCommand;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionAppendResult;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionRevisionConflictException;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionStateStore;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.StaleStateException;
import org.hibernate.Transaction;

/** Hibernate ORM implementation of the durable collaboration-session boundary. */
public final class HibernateWorkflowSessionStateStore implements WorkflowSessionStateStore {

  private final SessionFactory sessionFactory;

  /** Creates a store using the application-managed shared Hibernate context. */
  public HibernateWorkflowSessionStateStore(SessionFactory sessionFactory) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
  }

  @Override
  public StoredWorkflowSession create(StoredWorkflowSession storedSession) {
    Objects.requireNonNull(storedSession, "storedSession");
    if (storedSession.revision() != 0 || storedSession.sequence() != 0) {
      throw new IllegalArgumentException(
          "A new workflow session must start at revision/sequence 0");
    }
    if (storedSession.closed()) {
      throw new IllegalArgumentException("A new workflow session must be open");
    }
    return inTransaction(
        session -> {
          WorkflowSessionEntity entity = WorkflowSessionEntity.from(storedSession);
          session.persist(entity);
          session.flush();
          return entity.toStoredSession();
        });
  }

  @Override
  public Optional<StoredWorkflowSession> find(String sessionId) {
    String requiredSessionId = requireNotBlank(sessionId, "sessionId");
    try (Session session = sessionFactory.openSession()) {
      return Optional.ofNullable(session.find(WorkflowSessionEntity.class, requiredSessionId))
          .map(WorkflowSessionEntity::toStoredSession);
    }
  }

  @Override
  public WorkflowSessionAppendResult append(WorkflowSessionAppendCommand command) {
    Objects.requireNonNull(command, "command");
    try {
      return inTransaction(session -> appendWithinTransaction(session, command));
    } catch (RuntimeException exception) {
      if (isOptimisticLockFailure(exception)) {
        throw new WorkflowSessionRevisionConflictException(
            command.sessionId(), command.expectedRevision(), currentRevision(command.sessionId()));
      }
      throw exception;
    }
  }

  @Override
  public List<StoredWorkflowOperation> operations(String sessionId) {
    String requiredSessionId = requireNotBlank(sessionId, "sessionId");
    try (Session session = sessionFactory.openSession()) {
      return session
          .createQuery(
              "FROM WorkflowOperationEntity operation "
                  + "WHERE operation.sessionId = :sessionId "
                  + "ORDER BY operation.operationSequence",
              WorkflowOperationEntity.class)
          .setParameter("sessionId", requiredSessionId)
          .getResultList()
          .stream()
          .map(WorkflowOperationEntity::toStoredOperation)
          .toList();
    }
  }

  @Override
  public List<StoredWorkflowOutboxEntry> pendingOutbox(int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be > 0");
    }
    try (Session session = sessionFactory.openSession()) {
      return session
          .createQuery(
              "FROM WorkflowOutboxEntity event "
                  + "WHERE event.publishedAt IS NULL AND event.nextAttemptAt <= :now "
                  + "ORDER BY event.occurredAt, event.sessionId, event.eventSequence",
              WorkflowOutboxEntity.class)
          .setParameter("now", Instant.now())
          .setMaxResults(limit)
          .getResultList()
          .stream()
          .map(WorkflowOutboxEntity::toStoredEntry)
          .toList();
    }
  }

  private WorkflowSessionAppendResult appendWithinTransaction(
      Session session, WorkflowSessionAppendCommand command) {
    WorkflowOperationEntity duplicate = findOperation(session, command.operation().operationId());
    if (duplicate != null) {
      return duplicateResult(session, duplicate, command);
    }

    WorkflowSessionEntity aggregate =
        session.find(WorkflowSessionEntity.class, command.sessionId());
    if (aggregate == null) {
      throw new NoSuchElementException("Unknown workflow session: " + command.sessionId());
    }
    if (aggregate.closed()) {
      throw new IllegalStateException("Workflow session is closed: " + command.sessionId());
    }
    if (!aggregate.workflowId().equals(command.workflowId())) {
      throw new IllegalArgumentException(
          "Workflow id does not match durable session: " + command.workflowId());
    }
    if (aggregate.semanticRevision() != command.expectedRevision()) {
      throw new WorkflowSessionRevisionConflictException(
          command.sessionId(), command.expectedRevision(), aggregate.semanticRevision());
    }

    long nextRevision = Math.addExact(aggregate.semanticRevision(), 1);
    long nextSequence = Math.addExact(aggregate.eventSequence(), 1);
    aggregate.applyAcceptedOperation(
        command.workflowId(), command.workflowDsl(), nextRevision, nextSequence);

    WorkflowOperationEntity operation =
        WorkflowOperationEntity.accepted(
            command.sessionId(), command.operation(), nextSequence, nextRevision);
    WorkflowOutboxEntity outbox =
        WorkflowOutboxEntity.pending(
            command.sessionId(), command.outboxEvent(), nextSequence, nextRevision);
    session.persist(operation);
    session.persist(outbox);
    session.flush();

    return new WorkflowSessionAppendResult(
        aggregate.toStoredSession(), operation.toStoredOperation(), outbox.toStoredEntry(), false);
  }

  private static WorkflowSessionAppendResult duplicateResult(
      Session session, WorkflowOperationEntity existing, WorkflowSessionAppendCommand command) {
    if (!existing.hasSameSemanticContent(command.sessionId(), command.operation())
        || command.expectedRevision() != existing.semanticRevision() - 1) {
      throw operationConflict(command);
    }

    WorkflowSessionEntity aggregate =
        session.find(WorkflowSessionEntity.class, existing.sessionId());
    WorkflowOutboxEntity outbox =
        session
            .createQuery(
                "FROM WorkflowOutboxEntity event "
                    + "WHERE event.sessionId = :sessionId "
                    + "AND event.eventSequence = :sequence",
                WorkflowOutboxEntity.class)
            .setParameter("sessionId", existing.sessionId())
            .setParameter("sequence", existing.operationSequence())
            .uniqueResult();
    if (aggregate == null || outbox == null) {
      throw new IllegalStateException(
          "Durable operation is missing its aggregate or outbox event: " + existing.operationId());
    }

    boolean currentSnapshotIsOriginal = aggregate.semanticRevision() == existing.semanticRevision();
    if (!aggregate.workflowId().equals(command.workflowId())
        || (currentSnapshotIsOriginal && !aggregate.workflowDsl().equals(command.workflowDsl()))
        || !outbox.hasSameSemanticContent(
            command.sessionId(),
            command.outboxEvent(),
            existing.operationSequence(),
            existing.semanticRevision())) {
      throw operationConflict(command);
    }

    return new WorkflowSessionAppendResult(
        aggregate.toStoredSession(), existing.toStoredOperation(), outbox.toStoredEntry(), true);
  }

  private static WorkflowOperationEntity findOperation(Session session, String operationId) {
    return session
        .createQuery(
            "FROM WorkflowOperationEntity operation WHERE operation.operationId = :operationId",
            WorkflowOperationEntity.class)
        .setParameter("operationId", operationId)
        .uniqueResult();
  }

  private static WorkflowOperationPersistenceConflictException operationConflict(
      WorkflowSessionAppendCommand command) {
    return new WorkflowOperationPersistenceConflictException(
        command.sessionId(), command.operation().operationId());
  }

  private long currentRevision(String sessionId) {
    return find(sessionId).map(StoredWorkflowSession::revision).orElse(-1L);
  }

  private <T> T inTransaction(Function<Session, T> work) {
    try (Session session = sessionFactory.openSession()) {
      Transaction transaction = session.beginTransaction();
      try {
        T result = work.apply(session);
        transaction.commit();
        return result;
      } catch (RuntimeException exception) {
        if (transaction.isActive()) {
          transaction.rollback();
        }
        throw exception;
      }
    }
  }

  private static boolean isOptimisticLockFailure(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current instanceof OptimisticLockException || current instanceof StaleStateException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
