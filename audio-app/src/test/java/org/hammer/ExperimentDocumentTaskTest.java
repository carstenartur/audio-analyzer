package org.hammer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ExperimentDocumentTaskTest {
  @Test
  void cancellationDoesNotCompleteTheFutureBeforeItsCallbackStops() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    ExperimentDocumentTask<Void> task =
        new ExperimentDocumentTask<>(
            () -> {
              started.countDown();
              try {
                finish.await();
              } catch (InterruptedException exception) {
                interrupted.countDown();
                finish.await();
              }
              return null;
            });
    task.execute();
    try {
      assertTrue(started.await(5, TimeUnit.SECONDS));
      task.requestCancellation();
      assertTrue(interrupted.await(5, TimeUnit.SECONDS));
      assertFalse(
          task.isDone(), "A callback ignoring an interrupt must keep the operation slot occupied");
    } finally {
      finish.countDown();
    }
    task.get(5, TimeUnit.SECONDS);
    assertTrue(task.isDone());
  }
}
