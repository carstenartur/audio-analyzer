package org.hammer.audio.app;

import java.time.Clock;
import java.time.Duration;
import org.hammer.audio.app.outbox.ScheduledWorkflowOutboxRetention;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionMode;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionService;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionSettings;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables conservative published-outbox retention only through explicit configuration. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "workbench.persistence.mode", havingValue = "hibernate")
public class WorkflowOutboxRetentionConfiguration {

  private static final String PROPERTY_PREFIX = "workbench.collaboration.outbox.retention.";

  /** Creates the retention service only when destructive or report scheduling is explicitly enabled. */
  @Bean
  @ConditionalOnBean(WorkflowOutboxRetentionStore.class)
  @ConditionalOnProperty(name = PROPERTY_PREFIX + "enabled", havingValue = "true")
  public WorkflowOutboxRetentionService workflowOutboxRetentionService(
      WorkflowOutboxRetentionStore store,
      ObjectProvider<Clock> clockProvider,
      Environment environment) {
    WorkflowOutboxRetentionSettings settings =
        new WorkflowOutboxRetentionSettings(
            duration(
                environment,
                "published-retention",
                WorkflowOutboxRetentionSettings.DEFAULT_PUBLISHED_RETENTION),
            environment.getProperty(
                PROPERTY_PREFIX + "batch-size",
                Integer.class,
                WorkflowOutboxRetentionSettings.DEFAULT_BATCH_SIZE));
    return new WorkflowOutboxRetentionService(
        store, clockProvider.getIfAvailable(Clock::systemUTC), settings);
  }

  /** Creates the scheduled report/delete adapter with report-only as its safe default mode. */
  @Bean
  @ConditionalOnBean(WorkflowOutboxRetentionService.class)
  public ScheduledWorkflowOutboxRetention scheduledWorkflowOutboxRetention(
      WorkflowOutboxRetentionService service, Environment environment) {
    WorkflowOutboxRetentionMode mode =
        WorkflowOutboxRetentionMode.parse(environment.getProperty(PROPERTY_PREFIX + "mode"));
    return new ScheduledWorkflowOutboxRetention(service, mode);
  }

  private static Duration duration(
      Environment environment, String propertyName, Duration defaultValue) {
    String value = environment.getProperty(PROPERTY_PREFIX + propertyName);
    return value == null || value.isBlank() ? defaultValue : Duration.parse(value);
  }
}
