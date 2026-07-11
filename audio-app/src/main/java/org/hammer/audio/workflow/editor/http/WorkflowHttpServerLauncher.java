package org.hammer.audio.workflow.editor.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperationLog;
import org.hammer.audio.workflow.WorkflowValidator;
import org.hammer.audio.workflow.catalog.ExperimentNodeCatalog;
import org.hammer.audio.workflow.editor.WorkflowEditorService;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;

/**
 * Headless HTTP-server entry point for the workflow workbench.
 *
 * <p>This launcher starts the workflow API server without a Swing GUI. It is used as the Docker
 * container entry point for the screenshot integration-test pipeline and for standalone headless
 * deployment of the workbench.
 *
 * <p>Usage: {@code java -cp app.jar:lib/*
 * org.hammer.audio.workflow.editor.http.WorkflowHttpServerLauncher [port] [staticDir]}
 *
 * <ul>
 *   <li>{@code port} — TCP port to bind (default: {@value #DEFAULT_PORT})
 *   <li>{@code staticDir} — filesystem path to serve as static web content at {@code /}; if omitted
 *       the built-in classpath workbench UI is copied to a temporary directory
 * </ul>
 *
 * <p>This launcher operates in <b>in-memory</b> mode: workflow edits are kept in memory for the
 * lifetime of the process. Store-backed endpoints (checkpoint, history and load) are unavailable
 * without a {@link VersionedWorkflowStore} — attempting them returns an error. To start the
 * workbench with durable persistence use {@link
 * org.hammer.audio.infrastructure.workflow.PersistentWorkbenchLauncher} instead.
 *
 * <p><b>Dependency rules</b>: this class must not depend on Swing, JGit, Testcontainers, Playwright
 * or Selenium. It is a plain application entry point that wires domain services to the HTTP
 * adapter.
 */
public final class WorkflowHttpServerLauncher {

  private static final Logger LOG = Logger.getLogger(WorkflowHttpServerLauncher.class.getName());

  /** Default TCP port for the workbench HTTP server. */
  public static final int DEFAULT_PORT = 8080;

  private WorkflowHttpServerLauncher() {
    // utility class — do not instantiate
  }

  /**
   * Application entry point — starts the workbench in <b>in-memory</b> mode (no persistence).
   *
   * <p>Workflow edits are kept in memory only; store-backed endpoints (checkpoint, history and
   * load) are not supported and will return an error. To start the workbench with durable
   * persistence use {@link org.hammer.audio.infrastructure.workflow.PersistentWorkbenchLauncher}
   * instead.
   *
   * @param args optional arguments: {@code [port] [staticDir]}
   * @throws IOException if the HTTP server fails to start
   * @throws InterruptedException if the main thread is interrupted
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
    Path staticDir = args.length > 1 ? Path.of(args[1]) : null;
    launch(port, staticDir, null);
  }

  /**
   * Launches the workbench HTTP server with an optional persistent store.
   *
   * <p>When {@code store} is {@code null} the workbench operates in <b>in-memory</b> mode: workflow
   * edits are kept in memory only. Store-backed endpoints (checkpoint, history and load) are
   * unavailable and will return an error. Pass a non-{@code null} {@link VersionedWorkflowStore}
   * implementation to enable those persistence endpoints.
   *
   * <p>This method <em>blocks</em> the calling thread until the JVM is shut down.
   *
   * @param port TCP port to bind
   * @param staticDir filesystem path to serve as static web content at {@code /}; if {@code null}
   *     the built-in classpath workbench UI is extracted to a temporary directory
   * @param store versioned workflow checkpoint store, or {@code null} for in-memory usage
   * @throws IOException if the HTTP server fails to start
   * @throws InterruptedException if the calling thread is interrupted while waiting
   */
  public static void launch(int port, Path staticDir, VersionedWorkflowStore store)
      throws IOException, InterruptedException {
    Path resolvedStaticDir = staticDir != null ? staticDir : extractBuiltInUi();

    WorkflowOperationLog log = new WorkflowOperationLog(seedWorkflow());
    WorkflowValidator validator = new WorkflowValidator();
    WorkflowEditorService service = new WorkflowEditorService(log, validator, store);

    WorkflowEditorHttpAdapter adapter = new WorkflowEditorHttpAdapter(service);
    if (resolvedStaticDir != null) {
      adapter.start(port, resolvedStaticDir);
    } else {
      adapter.start(port);
    }

    LOG.info("Workbench HTTP server started on port " + port);
    Thread.currentThread().join();
  }

  /**
   * Returns the deterministic seed workflow for screenshot scenarios.
   *
   * <p>The seed workflow contains three nodes arranged as a simple signal chain: {@code Synthetic
   * Signal Generator → Gain → Localization}. The graph has no volatile data and produces
   * deterministic screenshots.
   *
   * @return seed workflow
   */
  static Workflow seedWorkflow() {
    Node inputNode = ExperimentNodeCatalog.syntheticSignalGenerator("seed.input");
    Node gainNode = ExperimentNodeCatalog.gain("seed.gain");
    Node outputNode = ExperimentNodeCatalog.localization("seed.output");
    Edge inputToGain =
        new Edge("seed.edge.in-to-gain", "seed.input", "signal-out", "seed.gain", "audio-in");
    Edge gainToOutput =
        new Edge("seed.edge.gain-to-out", "seed.gain", "audio-out", "seed.output", "audio-in");
    return new Workflow(
        "seed.workflow",
        "Input -> Gain -> Output",
        List.of(inputNode, gainNode, outputNode),
        List.of(inputToGain, gainToOutput));
  }

  /**
   * Extracts the built-in workbench UI from the classpath to a temporary directory.
   *
   * @return path to the temporary directory containing the UI, or {@code null} if not found
   * @throws IOException if extraction fails
   */
  static Path extractBuiltInUi() throws IOException {
    String resourcePath = "/workbench-ui/index.html";
    try (InputStream in = WorkflowHttpServerLauncher.class.getResourceAsStream(resourcePath)) {
      if (in == null) {
        LOG.warning(
            "Built-in workbench UI not found on classpath; static files will not be served");
        return null;
      }
      Path tempDir = Files.createTempDirectory("workbench-ui");
      tempDir.toFile().deleteOnExit();
      Path indexHtml = tempDir.resolve("index.html");
      Files.copy(in, indexHtml);
      return tempDir;
    }
  }
}
