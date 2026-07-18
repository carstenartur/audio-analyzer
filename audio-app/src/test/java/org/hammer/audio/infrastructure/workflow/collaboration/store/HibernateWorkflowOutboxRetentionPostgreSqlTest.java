package org.hammer.audio.infrastructure.workflow.collaboration.store;

import static org.hammer.audio.infrastructure.workflow.collaboration.store.WorkflowOutboxStoreTestSupport.BASE_TIME;
import static org.hammer.audio.infrastructure.workflow.collaboration.store.WorkflowOutboxStoreTestSupport.appendPendingEvent;
import static org.hammer.audio.infrastructure.workflow.collaboration.store.WorkflowOutboxStoreTestSupport.provider;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionDeletionResult;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionPlan;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionService;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionSettings;
import org.hammer.audio.workflow.collaboration.store.LeasedWorkflowOutboxEntry;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class HibernateWorkflowOutboxRetentionPostgreSqlTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRESQL =
      new PostgreSQLContainer<>("postgres:17.10-alpine")
          .withDatabaseName("audio_analyzer_retention")
          .withUsername("postgres")
          .withPassword("postgres");

  @Test
  void publishedRetentionUsesTheSameSafeContractOnPostgreSql() {
    try (HibernateSessionFactoryProvider provider = provider(postgreSqlProperties())) {
      HibernateWorkflowSessionStateStore sessionStore =
          new HibernateWorkflowSessionStateStore(provider.getSessionFactory());
      HibernateWorkflowOutboxStore outboxStore =
          new HibernateWorkflowOutboxStore(provider.getSessionFactory());
      HibernateWorkflowOutboxRetentionStore retentionStore =
          new HibernateWorkflowOutboxRetentionStore(provider.getSessionFactory());
      Instant plannedAt = BASE_TIME.plus(Duration.ofDays(60));
      Instant cutoff = plannedAt.minus(Duration.ofDays(30));

      appendAndPublish(
          sessionStore,
          outboxStore,
          "session.postgres.old",
          "event.postgres.old",
          BASE_TIME.plusSeconds(1),
          cutoff.minusSeconds(1));
      appendPendingEvent(
          sessionStore,
          "session.postgres.pending",
          "event.postgres.pending",
          BASE_TIME.plusSeconds(2));

      WorkflowOutboxRetentionService service =
          new WorkflowOutboxRetentionService(
              retentionStore,
              Clock.fixed(plannedAt, ZoneOffset.UTC),
              new WorkflowOutboxRetentionSettings(Duration.ofDays(30), 10));
      WorkflowOutboxRetentionPlan plan = service.plan();
      WorkflowOutboxRetentionDeletionResult first = service.delete(plan);
      WorkflowOutboxRetentionDeletionResult second = service.delete(plan);

      assertEquals(List.of("event.postgres.old"), plan.candidateEventIds());
      assertEquals(List.of("event.postgres.old"), first.deletedEventIds());
      assertEquals(List.of("event.postgres.old"), second.skippedEventIds());
      assertTrue(outboxStore.find("event.postgres.old").isEmpty());
      assertTrue(outboxStore.find("event.postgres.pending").orElseThrow().pending());
    }
  }

  private static Properties postgreSqlProperties() {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", POSTGRESQL.getJdbcUrl());
    properties.put("hibernate.connection.username", POSTGRESQL.getUsername());
    properties.put("hibernate.connection.password", POSTGRESQL.getPassword());
    properties.put("hibernate.connection.driver_class", "org.postgresql.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return properties;
  }

  private static void appendAndPublish(
      HibernateWorkflowSessionStateStore sessionStore,
      HibernateWorkflowOutboxStore outboxStore,
      String sessionId,
      String eventId,
      Instant occurredAt,
      Instant publishedAt) {
    appendPendingEvent(sessionStore, sessionId, eventId, occurredAt);
    Instant claimedAt = occurredAt.plusSeconds(1);
    LeasedWorkflowOutboxEntry lease =
        outboxStore
            .claimDue("dispatcher.postgres", claimedAt, claimedAt.plusSeconds(30), 1)
            .getFirst();
    outboxStore.markPublished(
        eventId, "dispatcher.postgres", lease.leaseToken(), publishedAt);
  }
}
