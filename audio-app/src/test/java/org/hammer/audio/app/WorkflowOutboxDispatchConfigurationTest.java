package org.hammer.audio.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.hammer.audio.app.outbox.ScheduledWorkflowOutboxDispatcher;
import org.hammer.audio.workflow.collaboration.outbox.WorkflowOutboxDispatcher;
import org.hammer.audio.workflow.collaboration.outbox.WorkflowOutboxPublisher;
import org.hammer.audio.workflow.collaboration.store.WorkflowOutboxStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WorkflowOutboxDispatchConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withPropertyValues(
              "workbench.persistence.mode=hibernate",
              "workbench.collaboration.outbox.poll-interval-ms=600000")
          .withUserConfiguration(WorkflowOutboxDispatchConfiguration.class);

  @Test
  void schedulingRemainsAbsentWithoutPublisherAndDurableOutboxStore() {
    contextRunner.run(
        context -> {
          assertTrue(context.getBeansOfType(WorkflowOutboxDispatcher.class).isEmpty());
          assertTrue(context.getBeansOfType(ScheduledWorkflowOutboxDispatcher.class).isEmpty());
        });
  }

  @Test
  void storeAndPublisherActivateOneDispatcherAndScheduledAdapter() {
    contextRunner
        .withBean(WorkflowOutboxStore.class, () -> mock(WorkflowOutboxStore.class))
        .withBean(WorkflowOutboxPublisher.class, () -> mock(WorkflowOutboxPublisher.class))
        .withBean(
            Clock.class, () -> Clock.fixed(Instant.parse("2026-07-18T02:00:00Z"), ZoneOffset.UTC))
        .run(
            context -> {
              assertEquals(1, context.getBeansOfType(WorkflowOutboxDispatcher.class).size());
              assertEquals(
                  1, context.getBeansOfType(ScheduledWorkflowOutboxDispatcher.class).size());
            });
  }
}
