package org.hammer.audio.infrastructure.workflow.collaboration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.hammer.audio.workflow.collaboration.WorkflowSessionException;
import org.hammer.audio.workflow.collaboration.WorkflowSessionState;
import org.hammer.audio.workflow.collaboration.WorkflowSessionStateStore;
import org.hammer.audio.workflow.editor.http.WorkflowOperationJsonCodec;
import org.hammer.audio.workflow.editor.http.WorkflowSessionEventJsonCodec;
import org.hammer.audio.workflow.editor.http.WorkflowSessionStateJsonCodec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Spring-JDBC implementation of the recoverable session state and transactional outbox. */
public final class JdbcWorkflowSessionStateStore implements WorkflowSessionStateStore {

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final WorkflowSessionStateJsonCodec stateCodec;
  private final WorkflowSessionEventJsonCodec eventCodec;
  private final WorkflowOperationJsonCodec operationCodec;

  public JdbcWorkflowSessionStateStore(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      WorkflowSessionStateJsonCodec stateCodec,
      WorkflowSessionEventJsonCodec eventCodec,
      WorkflowOperationJsonCodec operationCodec) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.stateCodec = Objects.requireNonNull(stateCodec, "stateCodec");
    this.eventCodec = Objects.requireNonNull(eventCodec, "eventCodec");
    this.operationCodec = Objects.requireNonNull(operationCodec, "operationCodec");
    initializeSchema();
  }

  @Override
  public List<WorkflowSessionState> restore() {
    return jdbc.query(
        "select state_json from workflow_session_state where closed = false order by session_id",
        (rs, rowNum) -> stateCodec.decode(rs.getString(1)));
  }

  @Override
  public void commit(Transition transition) {
    transactions.executeWithoutResult(
        ignored -> {
          try {
            if (transition.kind() == Kind.CREATE) {
              insertSession(transition.nextState());
            } else {
              updateSession(transition);
            }
            if (transition.operation() != null) {
              jdbc.update(
                  "insert into workflow_session_operation (session_id, revision, operation_id,"
                      + " author_id, operation_json, occurred_at) values (?, ?, ?, ?, ?, ?)",
                  transition.nextState().sessionId(),
                  transition.nextState().revision(),
                  transition.operation().operationId(),
                  transition.operation().author(),
                  operationCodec.encode(transition.operation()),
                  Timestamp.from(transition.operation().timestamp()));
            }
            jdbc.update(
                "insert into workflow_session_outbox (event_id, session_id, sequence_no, revision,"
                    + " event_json, created_at, attempt_count) values (?, ?, ?, ?, ?, ?, 0)",
                transition.event().eventId(),
                transition.event().sessionId(),
                transition.event().sequence(),
                transition.event().revision(),
                eventCodec.encode(transition.event()),
                Timestamp.from(transition.event().occurredAt()));
          } catch (DuplicateKeyException ex) {
            throw new WorkflowSessionException(
                WorkflowSessionException.Code.SESSION_ALREADY_EXISTS,
                transition.nextState().sessionId(),
                "Duplicate session, operation or event identifier");
          }
        });
  }

  public List<OutboxRow> pendingOutbox(int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be >= 1");
    }
    return jdbc.query(
        "select event_id, event_json, attempt_count from workflow_session_outbox "
            + "where published_at is null order by created_at, event_id limit ?",
        this::mapOutbox,
        limit);
  }

  public void markPublished(String eventId) {
    jdbc.update(
        "update workflow_session_outbox set published_at = ? where event_id = ?",
        Timestamp.from(Instant.now()),
        eventId);
  }

  public void markAttempt(String eventId) {
    jdbc.update(
        "update workflow_session_outbox set attempt_count = attempt_count + 1 where event_id = ?",
        eventId);
  }

  private void insertSession(WorkflowSessionState state) {
    jdbc.update(
        "insert into workflow_session_state "
            + "(session_id, revision, sequence_no, state_json, closed, created_at, updated_at) "
            + "values (?, ?, ?, ?, false, ?, ?)",
        state.sessionId(),
        state.revision(),
        state.sequence(),
        stateCodec.encode(state),
        Timestamp.from(state.createdAt()),
        Timestamp.from(Instant.now()));
  }

  private void updateSession(Transition transition) {
    WorkflowSessionState previous = transition.previousState();
    WorkflowSessionState next = transition.nextState();
    boolean closed = transition.kind() == Kind.CLOSE;
    int updated =
        jdbc.update(
            "update workflow_session_state set revision = ?, sequence_no = ?, state_json = ?,"
                + " closed = ?, updated_at = ? where session_id = ? and revision = ? and"
                + " sequence_no = ?",
            next.revision(),
            next.sequence(),
            stateCodec.encode(next),
            closed,
            Timestamp.from(Instant.now()),
            next.sessionId(),
            previous.revision(),
            previous.sequence());
    if (updated != 1) {
      throw new WorkflowSessionException(
          WorkflowSessionException.Code.REVISION_CONFLICT,
          next.sessionId(),
          "Concurrent session transition detected");
    }
  }

  private OutboxRow mapOutbox(ResultSet rs, int rowNum) throws SQLException {
    return new OutboxRow(
        rs.getString("event_id"),
        eventCodec.decode(rs.getString("event_json")),
        rs.getInt("attempt_count"));
  }

  private void initializeSchema() {
    jdbc.execute(
        "create table if not exists workflow_session_state ("
            + "session_id varchar(255) primary key,"
            + "revision bigint not null,"
            + "sequence_no bigint not null,"
            + "state_json clob not null,"
            + "closed boolean not null,"
            + "created_at timestamp not null,"
            + "updated_at timestamp not null) ");
    jdbc.execute(
        "create table if not exists workflow_session_operation ("
            + "session_id varchar(255) not null,"
            + "revision bigint not null,"
            + "operation_id varchar(255) not null unique,"
            + "author_id varchar(255) not null,"
            + "operation_json clob not null,"
            + "occurred_at timestamp not null,"
            + "primary key (session_id, revision)) ");
    jdbc.execute(
        "create table if not exists workflow_session_outbox ("
            + "event_id varchar(255) primary key,"
            + "session_id varchar(255) not null,"
            + "sequence_no bigint not null,"
            + "revision bigint not null,"
            + "event_json clob not null,"
            + "created_at timestamp not null,"
            + "published_at timestamp null,"
            + "attempt_count integer not null) ");
    jdbc.execute(
        "create index if not exists idx_workflow_outbox_pending "
            + "on workflow_session_outbox (published_at, created_at)");
  }

  public record OutboxRow(
      String eventId,
      org.hammer.audio.workflow.collaboration.WorkflowSessionEvent event,
      int attemptCount) {}
}
