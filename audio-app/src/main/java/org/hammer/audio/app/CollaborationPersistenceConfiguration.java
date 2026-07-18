package org.hammer.audio.app;

import java.util.Objects;
import org.hammer.audio.infrastructure.workflow.collaboration.store.CollaborationPersistenceEntities;
import org.hammer.audio.infrastructure.workflow.collaboration.store.HibernateWorkflowOutboxRetentionStore;
import org.hammer.audio.infrastructure.workflow.collaboration.store.HibernateWorkflowOutboxStore;
import org.hammer.audio.infrastructure.workflow.collaboration.store.HibernateWorkflowSessionStateStore;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionStore;
import org.hammer.audio.workflow.collaboration.store.WorkflowOutboxStore;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionStateStore;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires Audio Analyzer collaboration entities into the shared Hibernate persistence context. */
@Configuration
@ConditionalOnProperty(name = "workbench.persistence.mode", havingValue = "hibernate")
public class CollaborationPersistenceConfiguration {

  /** Registers application-owned collaboration mappings alongside shared JGit mappings. */
  @Bean
  public WorkflowPersistenceEntityContributor collaborationPersistenceEntities() {
    return CollaborationPersistenceEntities::annotatedClasses;
  }

  /** Creates the durable collaboration state store over the shared application SessionFactory. */
  @Bean
  public WorkflowSessionStateStore workflowSessionStateStore(
      @Qualifier("workflowPersistenceSessionFactory") SessionFactory sessionFactory) {
    return new HibernateWorkflowSessionStateStore(
        Objects.requireNonNull(sessionFactory, "sessionFactory"));
  }

  /** Creates leased outbox persistence over the same shared application SessionFactory. */
  @Bean
  public WorkflowOutboxStore workflowOutboxStore(
      @Qualifier("workflowPersistenceSessionFactory") SessionFactory sessionFactory) {
    return new HibernateWorkflowOutboxStore(
        Objects.requireNonNull(sessionFactory, "sessionFactory"));
  }

  /** Creates conservative published-outbox retention over the shared SessionFactory. */
  @Bean
  public WorkflowOutboxRetentionStore workflowOutboxRetentionStore(
      @Qualifier("workflowPersistenceSessionFactory") SessionFactory sessionFactory) {
    return new HibernateWorkflowOutboxRetentionStore(
        Objects.requireNonNull(sessionFactory, "sessionFactory"));
  }
}
