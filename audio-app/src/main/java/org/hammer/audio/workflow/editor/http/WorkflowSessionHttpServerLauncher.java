package org.hammer.audio.workflow.editor.http;

import java.io.IOException;
import java.util.logging.Logger;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;

/** Standalone headless entry point for the collaboration-session lifecycle API. */
public final class WorkflowSessionHttpServerLauncher {

  private static final Logger LOG =
      Logger.getLogger(WorkflowSessionHttpServerLauncher.class.getName());
  private static final int DEFAULT_PORT = 8081;

  private WorkflowSessionHttpServerLauncher() {
    // utility class
  }

  /**
   * Starts the collaboration-session API.
   *
   * @param args optional first argument is the TCP port, defaulting to 8081
   * @throws IOException if the HTTP server cannot start
   * @throws InterruptedException if the launcher thread is interrupted
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
    WorkflowSessionHttpAdapter adapter =
        new WorkflowSessionHttpAdapter(new WorkflowSessionRegistry());
    adapter.start(port);
    LOG.info("Workflow collaboration session API started on port " + adapter.port());
    Thread.currentThread().join();
  }
}
