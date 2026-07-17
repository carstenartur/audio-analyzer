package org.hammer.audio.app;

import org.hammer.audio.infrastructure.workflow.collaboration.JdbcWorkflowSessionStateStore;
import org.hammer.audio.infrastructure.workflow.collaboration.WorkflowOutboxDispatcher;
import org.hammer.audio.workflow.collaboration.BoundedWorkflowSessionEventHub;
import org.hammer.audio.workflow.collaboration.InMemoryWorkflowSessionStateStore;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.collaboration.WorkflowSessionStateStore;
import org.hammer.audio.workflow.editor.http.WorkflowOperationJsonCodec;
import org.hammer.audio.workflow.editor.http.WorkflowSessionEventJsonCodec;
import org.hammer.audio.workflow.editor.http.WorkflowSessionStateJsonCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Spring Boot 4.1 wiring for collaboration, SSE replay and transactional outbox delivery. */
@Configuration
@EnableScheduling
public class CollaborationPlatformConfiguration {

  @Bean
  public BoundedWorkflowSessionEventHub workflowSessionEventHub() {
    return new BoundedWorkflowSessionEventHub(512);
  }

  @Bean
  public WorkflowOperationJsonCodec workflowOperationJsonCodec(ObjectMapper mapper) {
    return new WorkflowOperationJsonCodec(mapper);
  }

  @Bean
  public WorkflowSessionStateJsonCodec workflowSessionStateJsonCodec(
      ObjectMapper mapper, WorkflowOperationJsonCodec operationCodec) {
    return new WorkflowSessionStateJsonCodec(mapper, operationCodec);
  }

  @Bean
  public WorkflowSessionEventJsonCodec workflowSessionEventJsonCodec(
      ObjectMapper mapper, WorkflowSessionStateJsonCodec stateCodec) {
    return new WorkflowSessionEventJsonCodec(mapper, stateCodec);
  }

  @Bean
  @ConditionalOnProperty(
      name = "workbench.collaboration.persistence",
      havingValue = "memory",
      matchIfMissing = true)
  public WorkflowSessionStateStore inMemoryWorkflowSessionStateStore(
      BoundedWorkflowSessionEventHub eventHub) {
    return new InMemoryWorkflowSessionStateStore(eventHub);
  }

  @Bean
  @ConditionalOnProperty(name = "workbench.collaboration.persistence", havingValue = "jdbc")
  public JdbcWorkflowSessionStateStore jdbcWorkflowSessionStateStore(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      WorkflowSessionStateJsonCodec stateCodec,
      WorkflowSessionEventJsonCodec eventCodec,
      WorkflowOperationJsonCodec operationCodec) {
    return new JdbcWorkflowSessionStateStore(
        jdbc, new TransactionTemplate(transactionManager), stateCodec, eventCodec, operationCodec);
  }

  @Bean
  @ConditionalOnProperty(name = "workbench.collaboration.persistence", havingValue = "jdbc")
  public WorkflowOutboxDispatcher workflowOutboxDispatcher(
      JdbcWorkflowSessionStateStore store, BoundedWorkflowSessionEventHub eventHub) {
    return new WorkflowOutboxDispatcher(store, eventHub);
  }

  @Bean
  @Primary
  public WorkflowSessionRegistry collaborativeWorkflowSessionRegistry(
      WorkflowSessionStateStore stateStore) {
    return new WorkflowSessionRegistry(stateStore);
  }
}
