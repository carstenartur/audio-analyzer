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
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionSequenceConflictException;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionStateStore;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.StaleStateException;
import org.hibernate.Transaction;
import org.hibernate.exception.ConstraintViolationException;

/** Hibernate ORM implementation of the durable collaboration-session boundary. */
public final class HibernateWorkflowSessionStateStore implements WorkflowSessionStateStore {

  private static final String SESSION_ID_PARAMETER = "sessionId";

  private final SessionFactory sessionFactory;

  /** Creates a store using the application-managed shared Hibernate context. */
  public HibernateWorkflowSessionStateStore(SessionFactory sessionFactory) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
  }

  @Override
  public StoredWorkflowSession create(StoredWorkflowSession storedSession) {
    Objects.requireNonNull(storedSession, "storedSession");
    if (storedSession.revision() != 0) {
      throw new IllegalArgumentException("A new workflow session must start at semantic revision 0");
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
    String requiredSessionId = requireNotBlank(sessionId, SESSION_ID_PARAMETER);
    try (Session session = sessionFactory.openSession()) {
      return Optional.ofNullable(session.find(WorkflowSessionEntity.class, requiredSessionId))
          .map(WorkflowSessionEntity::toStoredSession);
    }
  }

  @Override
  public List<StoredWorkflowSession> openSessions() {
    try (Session session = sessionFactory.openSession()) {
      return session
          .createQuery(
              "FROM WorkflowSessionEntity aggregate "
                  + "WHERE aggregate.sessionClosed = false "
                  + "ORDER BY aggregate.storedSessionId",
              WorkflowSessionEntity.class)
          .getResultList()
          .stream()
          .map(WorkflowSessionEntity::toStoredSession)
          .toList();
    }
  }

  @Override
  public WorkflowSessionAppendResult append(WorkflowSessionAppendCommand command) {
    Objects.requireNonNull(command, "command");
    try {
      return inTransaction(session -> appendWithinTransaction(session, command));
    } catch (OptimisticLockException | StaleStateException | ConstraintViolationException failure) {
      return recoverConcurrentAppend(command, failure);
    }
  }

  @Override
  public StoredWorkflowSession advanceEventSequence(String sessionId, long expectedSequence) {
    String requiredSessionId = requireNotBlank(sessionId, SESSION_ID_PARAMETER);
    requireNonNegative(expectedSequence, "expectedSequence");
    try {
      return inTransaction(
          session -> advanceEventSequenceWithinTransaction(session, requiredSessionId, expectedSequence));
    } catch (OptimisticLockException | StaleStateException failure) {
      throw sequenceConflict(requiredSessionId, expectedSequence);
    }
  }

  @Override
  public StoredWorkflowSession close(
      String sessionId, long expectedRevision, long expectedSequence) {
    String requiredSessionId = requireNotBlank(sessionId, SESSION_ID_PARAMETER);
    requireNonNegative(expectedRevision, "expectedRevision");
    requireNonNegative(expectedSequence, "expectedSequence");
    try {
      return inTransaction(
          session ->
              closeWithinTransaction(
                  session, requiredSessionId, expectedRevision, expectedSequence));
    } catch (OptimisticLockException | StaleStateException failure) {
      StoredWorkflowSession current = requireStoredSession(requiredSessionId);
      if (current.revision() != expectedRevision) {
        throw new WorkflowSessionRevisionConflictException(
            requiredSessionId, expectedRevision, current.revision());
      }
      throw new WorkflowSessionSequenceConflictException(
          requiredSessionId, expectedSequence, current.sequence());
    }
  }

  @Override
  public List<StoredWorkflowOperation> operations(String sessionId) {
    String requiredSessionId = requireNotBlank(sessionId, SESSION_ID_PARAMETER);
    try (Session session = sessionFactory.openSession()) {
      return session
          .createQuery(
              "FROM WorkflowOperationEntity operation "
                  + "WHERE operation.storedSessionId = :sessionId "
                  + "ORDER BY operation.storedOperationSequence",
              WorkflowOperationEntity.class)
          .setParameter(SESSION_ID_PARAMETER, requiredSessionId)
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
                  + "ORDER BY event.occurredAt, event.storedSessionId, event.storedEventSequence",
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

    WorkflowSessionEntity aggregate = requireOpenAggregate(session, command.sessionId());
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

  private StoredWorkflowSession advanceEventSequenceWithinTransaction(
      Session session, String sessionId, long expectedSequence) {
    WorkflowSessionEntity aggregate = requireOpenAggregate(session, sessionId);
    if (aggregate.eventSequence() != expectedSequence) {
      throw new WorkflowSessionSequenceConflictException(
          sessionId, expectedSequence, aggregate.eventSequence());
    }
    aggregate.advanceEventSequence(Math.addExact(expectedSequence, 1));
    session.flush();
    return aggregate.toStoredSession();
  }

  private StoredWorkflowSession closeWithinTransaction(
      Session session, String sessionId, long expectedRevision, long expectedSequence) {
    WorkflowSessionEntity aggregate = requireAggregate(session, sessionId);
    if (aggregate.closed()) {
      if (aggregate.semanticRevision() == expectedRevision
          && aggregate.eventSequence() == Math.addExact(expectedSequence, 1)) {
        return aggregate.toStoredSession();
      }
      throw new IllegalStateException("Workflow session is already closed: " + sessionId);
    }
    if (aggregate.semanticRevision() != expectedRevision) {
      throw new WorkflowSessionRevisionConflictException(
          sessionId, expectedRevision, aggregate.semanticRevision());
    }
    if (aggregate.eventSequence() != expectedSequence) {
      throw new WorkflowSessionSequenceConflictException(
          sessionId, expectedSequence, aggregate.eventSequence());
    }
    aggregate.markClosed(Math.addExact(expectedSequence, 1));
    session.flush();
    return aggregate.toStoredSession();
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
                    + "WHERE event.storedSessionId = :sessionId "
                    + "AND event.storedEventSequence = :sequence",
                WorkflowOutboxEntity.class)
            .setParameter(SESSION_ID_PARAMETER, existing.sessionId())
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

  private WorkflowSessionAppendResult recoverConcurrentAppend(
      WorkflowSessionAppendCommand command, RuntimeException failure) {
    WorkflowSessionAppendResult duplicate = duplicateAfterConcurrentFailure(command);
    if (duplicate != null) {
      return duplicate;
    }

    long actualRevision = currentRevision(command.sessionId());
    if (actualRevision != command.expectedRevision()) {
      throw new WorkflowSessionRevisionConflictException(
          command.sessionId(), command.expectedRevision(), actualRevision);
    }
    throw new IllegalStateException(
        "Durable append constraint failed without advancing session " + command.sessionId(),
        failure);
  }

  private WorkflowSessionAppendResult duplicateAfterConcurrentFailure(
      WorkflowSessionAppendCommand command) {
    return inTransaction(
        session -> {
          WorkflowOperationEntity existing =
              findOperation(session, command.operation().operationId());
          return existing == null ? null : duplicateResult(session, existing, command);
        });
  }

  private static WorkflowSessionEntity requireOpenAggregate(Session session, String sessionId) {
    WorkflowSessionEntity aggregate = requireAggregate(session, sessionId);
    if (aggregate.closed()) {
      throw new IllegalStateException("Workflow session is closed: " + sessionId);
    }
    return aggregate;
  }

  private static WorkflowSessionEntity requireAggregate(Session session, String sessionId) {
    WorkflowSessionEntity aggregate = session.find(WorkflowSessionEntity.class, sessionId);
    if (aggregate == null) {
      throw new NoSuchElementException("Unknown workflow session: " + sessionId);
    }
    return aggregate;
  }

  private static WorkflowOperationEntity findOperation(Session session, String operationId) {
    return session
        .createQuery(
            "FROM WorkflowOperationEntity operation "
                + "WHERE operation.storedOperationId = :operationId",
            WorkflowOperationEntity.class)
        .setParameter("operationId", operationId)
        .uniqueResult();
  }

  private static WorkflowOperationPersistenceConflictException operationConflict(
      WorkflowSessionAppendCommand command) {
    return new WorkflowOperationPersistenceConflictException(
        command.sessionId(), command.operation().operationId());
  }

  private WorkflowSessionSequenceConflictException sequenceConflict(
      String sessionId, long expectedSequence) {
    return new WorkflowSessionSequenceConflictException(
        sessionId, expectedSequence, requireStoredSession(sessionId).sequence());
  }

  private StoredWorkflowSession requireStoredSession(String sessionId) {
    return find(sessionId)
        .orElseThrow(() -> new NoSuchElementException("Unknown workflow session: " + sessionId));
  }

  private long currentRevision(String sessionId) {
    return requireStoredSession(sessionId).revision();
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

  private static void requireNonNegative(long value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must be >= 0");
    }
  }
}
