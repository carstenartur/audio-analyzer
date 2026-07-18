package org.hammer.audio.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.hammer.audio.app.outbox.ScheduledWorkflowOutboxRetention;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionDeletionResult;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionPlan;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionSelection;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionService;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionSettings;
import org.hammer.audio.workflow.collaboration.retention.WorkflowOutboxRetentionStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WorkflowOutboxRetentionConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withPropertyValues(
              "workbench.persistence.mode=hibernate",
              "workbench.collaboration.outbox.retention.interval-ms=600000")
          .withUserConfiguration(WorkflowOutboxRetentionConfiguration.class)
          .withBean(WorkflowOutboxRetentionStore.class, EmptyRetentionStore::new)
          .withBean(
              Clock.class, () -> Clock.fixed(Instant.parse("2026-07-18T12:00:00Z"), ZoneOffset.UTC));

  @Test
  void retentionRemainsAbsentUnlessExplicitlyEnabled() {
    contextRunner.run(
        context -> {
          assertTrue(context.getBeansOfType(WorkflowOutboxRetentionService.class).isEmpty());
          assertTrue(context.getBeansOfType(ScheduledWorkflowOutboxRetention.class).isEmpty());
        });
  }

  @Test
  void enabledRetentionUsesConservativeDefaultsAndCreatesOneScheduler() {
    contextRunner
        .withPropertyValues("workbench.collaboration.outbox.retention.enabled=true")
        .run(
            context -> {
              WorkflowOutboxRetentionService service =
                  context.getBean(WorkflowOutboxRetentionService.class);
              assertEquals(
                  WorkflowOutboxRetentionSettings.conservativeDefaults(), service.settings());
              assertEquals(
                  1, context.getBeansOfType(ScheduledWorkflowOutboxRetention.class).size());
            });
  }

  @Test
  void invalidDestructiveModeFailsAtStartup() {
    contextRunner
        .withPropertyValues(
            "workbench.collaboration.outbox.retention.enabled=true",
            "workbench.collaboration.outbox.retention.mode=automatic")
        .run(context -> assertNotNull(context.getStartupFailure()));
  }

  private static final class EmptyRetentionStore implements WorkflowOutboxRetentionStore {

    @Override
    public WorkflowOutboxRetentionSelection selectPublishedBefore(
        Instant publishedCutoff, int limit) {
      return new WorkflowOutboxRetentionSelection(0, List.of());
    }

    @Override
    public WorkflowOutboxRetentionDeletionResult deletePublished(
        WorkflowOutboxRetentionPlan plan) {
      return new WorkflowOutboxRetentionDeletionResult(List.of(), List.of());
    }
  }
}
