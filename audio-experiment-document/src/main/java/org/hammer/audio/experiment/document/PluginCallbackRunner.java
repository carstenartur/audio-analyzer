package org.hammer.audio.experiment.document;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Bounds trusted plugin callbacks without allowing stalled callbacks to accumulate threads. */
final class PluginCallbackRunner {

  private static final long TIMEOUT_MILLIS = 1000;
  private static final ThreadPoolExecutor WORKERS =
      new ThreadPoolExecutor(
          0,
          4,
          30,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          Thread.ofPlatform().daemon(true).name("experiment-document-plugin-", 0).factory(),
          new ThreadPoolExecutor.AbortPolicy());

  private PluginCallbackRunner() {
    // utility class
  }

  static <T> T invoke(Supplier<T> callback) {
    Future<T> task;
    try {
      task = WORKERS.submit(callback::get);
    } catch (RejectedExecutionException exception) {
      throw new Failure("plugin-busy", "Plugin validation capacity is exhausted", exception);
    }
    try {
      return task.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    } catch (TimeoutException exception) {
      task.cancel(true);
      throw new Failure(
          "plugin-timeout", "Plugin validation exceeded its one-second deadline", exception);
    } catch (InterruptedException exception) {
      task.cancel(true);
      Thread.currentThread().interrupt();
      throw new Failure("plugin-cancelled", "Plugin validation was cancelled", exception);
    } catch (ExecutionException exception) {
      throw new Failure(
          "plugin-validator-failed",
          "Plugin callback failed: " + exception.getCause().getClass().getSimpleName(),
          exception);
    }
  }

  static final class Failure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String diagnosticCode;

    Failure(String code, String message, Throwable cause) {
      super(message, cause);
      this.diagnosticCode = code;
    }

    String code() {
      return diagnosticCode;
    }
  }
}
