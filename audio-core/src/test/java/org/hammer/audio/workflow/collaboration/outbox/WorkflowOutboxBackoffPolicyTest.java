package org.hammer.audio.workflow.collaboration.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorkflowOutboxBackoffPolicyTest {

  @Test
  void doublesDeterministicallyAndCapsAtMaximum() {
    WorkflowOutboxBackoffPolicy policy =
        new WorkflowOutboxBackoffPolicy(Duration.ofSeconds(2), Duration.ofSeconds(10));

    assertEquals(Duration.ofSeconds(2), policy.delayAfterFailure(1));
    assertEquals(Duration.ofSeconds(4), policy.delayAfterFailure(2));
    assertEquals(Duration.ofSeconds(8), policy.delayAfterFailure(3));
    assertEquals(Duration.ofSeconds(10), policy.delayAfterFailure(4));
    assertEquals(Duration.ofSeconds(10), policy.delayAfterFailure(Integer.MAX_VALUE));
  }

  @Test
  void rejectsInvalidConfigurationAndAttemptNumbers() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorkflowOutboxBackoffPolicy(Duration.ZERO, Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorkflowOutboxBackoffPolicy(Duration.ofSeconds(2), Duration.ofSeconds(1)));
    WorkflowOutboxBackoffPolicy policy =
        new WorkflowOutboxBackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(5));
    assertThrows(IllegalArgumentException.class, () -> policy.delayAfterFailure(0));
  }
}
