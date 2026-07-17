package org.hammer.audio.infrastructure.workflow.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperation;
import org.hammer.audio.workflow.collaboration.CollaborationMode;
import org.hammer.audio.workflow.collaboration.OperationActor;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.editor.http.WorkflowOperationJsonCodec;
import org.hammer.audio.workflow.editor.http.WorkflowSessionEventJsonCodec;
import org.hammer.audio.workflow.editor.http.WorkflowSessionStateJsonCodec;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class JdbcWorkflowSessionStateStoreTest {

  @Test
  void sessionAndPendingOutboxRecoverAfterStoreRestart() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    ObjectMapper mapper = new ObjectMapper();
    WorkflowOperationJsonCodec operationCodec = new WorkflowOperationJsonCodec(mapper);
    WorkflowSessionStateJsonCodec stateCodec =
        new WorkflowSessionStateJsonCodec(mapper, operationCodec);
    WorkflowSessionEventJsonCodec eventCodec =
        new WorkflowSessionEventJsonCodec(mapper, stateCodec);
    JdbcWorkflowSessionStateStore store =
        new JdbcWorkflowSessionStateStore(
            jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
            stateCodec,
            eventCodec,
            operationCodec);
    WorkflowSessionRegistry registry = new WorkflowSessionRegistry(store);
    OperationActor owner = new OperationActor("actor", "user", "Owner");
    registry.create(
        "session",
        CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
        owner,
        new Workflow("workflow", "Workflow", List.of(), List.of()));
    Node node = new Node("node", "input", "Input", List.of(), List.of(), Metadata.empty());
    registry.applyOperation(
        "session",
        CollaborationMode.SHARED_SESSION_PERSONAL_UNDO,
        owner,
        0L,
        new WorkflowOperation.CreateNode("operation", Instant.now(), owner.actorId(), node));

    WorkflowSessionRegistry recovered = new WorkflowSessionRegistry(store);
    assertEquals(1L, recovered.inspect("session").revision());
    assertEquals(1, recovered.workflow("session").nodes().size());
    assertFalse(store.pendingOutbox(10).isEmpty());
  }
}
