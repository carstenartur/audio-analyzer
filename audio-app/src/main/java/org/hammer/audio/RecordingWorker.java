package org.hammer.audio;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Owns the dedicated recorder thread and its completion signal. */
final class RecordingWorker {

  private final ExecutorService executor;
  private final CountDownLatch completed = new CountDownLatch(1);

  RecordingWorker(String threadName) {
    Objects.requireNonNull(threadName, "threadName");
    executor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, threadName);
              thread.setDaemon(true);
              return thread;
            });
  }

  void start(Runnable task) {
    executor.execute(Objects.requireNonNull(task, "task"));
  }

  void complete() {
    completed.countDown();
    executor.shutdown();
  }

  boolean await(long timeout, TimeUnit unit) throws InterruptedException {
    return completed.await(timeout, unit);
  }

  void cancel() {
    executor.shutdownNow();
  }
}
