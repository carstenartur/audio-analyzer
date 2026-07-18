package org.hammer.audio.app;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.hammer.audio.app.outbox.ScheduledWorkflowOutboxDispatcher;
import org.hammer.audio.workflow.collaboration.outbox.WorkflowOutboxBackoffPolicy;
import org.hammer.audio.workflow.collaboration.outbox.WorkflowOutboxDispatcher;
import org.hammer.audio.workflow.collaboration.outbox.WorkflowOutboxDispatcherSettings;
import org.hammer.audio.workflow.collaboration.outbox.WorkflowOutboxPublisher;
import org.hammer.audio.workflow.collaboration.store.WorkflowOutboxStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables leased outbox polling only when durable state and a publisher adapter exist. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "workbench.persistence.mode", havingValue = "hibernate")
public class WorkflowOutboxDispatchConfiguration {

  private static final String PROPERTY_PREFIX = "workbench.collaboration.outbox.";

  /** Creates the bounded transport-neutral dispatcher when both required ports exist. */
  @Bean
  @ConditionalOnBean({WorkflowOutboxStore.class, WorkflowOutboxPublisher.class})
  public WorkflowOutboxDispatcher workflowOutboxDispatcher(
      WorkflowOutboxStore outboxStore,
      WorkflowOutboxPublisher publisher,
      ObjectProvider<Clock> clockProvider,
      Environment environment) {
    WorkflowOutboxBackoffPolicy backoffPolicy =
        new WorkflowOutboxBackoffPolicy(
            duration(environment, "initial-backoff", Duration.ofSeconds(1)),
            duration(environment, "maximum-backoff", Duration.ofMinutes(5)));
    WorkflowOutboxDispatcherSettings settings =
        new WorkflowOutboxDispatcherSettings(
            dispatcherId(environment),
            environment.getProperty(PROPERTY_PREFIX + "batch-size", Integer.class, 50),
            duration(environment, "lease-duration", Duration.ofSeconds(30)),
            backoffPolicy);
    return new WorkflowOutboxDispatcher(
        outboxStore, publisher, clockProvider.getIfAvailable(Clock::systemUTC), settings);
  }

  /** Activates periodic dispatch around the configured application service. */
  @Bean
  @ConditionalOnBean(WorkflowOutboxDispatcher.class)
  public ScheduledWorkflowOutboxDispatcher scheduledWorkflowOutboxDispatcher(
      WorkflowOutboxDispatcher dispatcher) {
    return new ScheduledWorkflowOutboxDispatcher(dispatcher);
  }

  private static String dispatcherId(Environment environment) {
    String configured = environment.getProperty(PROPERTY_PREFIX + "dispatcher-id");
    return configured == null || configured.isBlank()
        ? "audio-analyzer-" + UUID.randomUUID()
        : configured;
  }

  private static Duration duration(
      Environment environment, String propertyName, Duration defaultValue) {
    String value = environment.getProperty(PROPERTY_PREFIX + propertyName);
    return value == null || value.isBlank() ? defaultValue : Duration.parse(value);
  }
}
