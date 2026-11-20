package org.hammer;

import java.io.InterruptedIOException;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import javax.swing.SwingWorker;

/** Cooperative cancellation that keeps a task active until its code has actually stopped. */
final class ExperimentDocumentTask<T> extends SwingWorker<T, Void> {
  private final Callable<T> action;
  private final Consumer<ExperimentDocumentTask<T>> completion;
  private final Lock cancellationLock = new ReentrantLock();
  private boolean cancellationRequested;
  private volatile Thread executingThread;

  ExperimentDocumentTask(Callable<T> action) {
    this(
        action,
        ignored -> {
          // Callers without a UI callback may wait on the normal Future result.
        });
  }

  ExperimentDocumentTask(Callable<T> action, Consumer<ExperimentDocumentTask<T>> completion) {
    this.action = java.util.Objects.requireNonNull(action, "action");
    this.completion = java.util.Objects.requireNonNull(completion, "completion");
  }

  void requestCancellation() {
    cancellationLock.lock();
    try {
      cancellationRequested = true;
      if (executingThread != null) {
        executingThread.interrupt();
      }
    } finally {
      cancellationLock.unlock();
    }
  }

  @Override
  protected T doInBackground() throws Exception {
    registerExecutingThread();
    try {
      return action.call();
    } finally {
      clearExecutingThread();
    }
  }

  private void registerExecutingThread() throws InterruptedIOException {
    cancellationLock.lock();
    try {
      if (cancellationRequested) {
        throw new InterruptedIOException("Document operation cancelled before it started");
      }
      executingThread = Thread.currentThread();
    } finally {
      cancellationLock.unlock();
    }
  }

  private void clearExecutingThread() {
    cancellationLock.lock();
    try {
      executingThread = null;
    } finally {
      cancellationLock.unlock();
    }
  }

  @Override
  protected void done() {
    completion.accept(this);
  }
}
