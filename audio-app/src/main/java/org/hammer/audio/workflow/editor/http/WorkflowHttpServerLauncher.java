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
 * <p><b>Dependency rules</b>: this class must not depend on Swing, JGit, Testcontainers, Playwright
 * or Selenium. It is a plain application entry point that wires domain services to the HTTP
 * adapter.
 */
public final class WorkflowHttpServerLauncher {

  private static final Logger LOG = Logger.getLogger(WorkflowHttpServerLauncher.class.getName());
  static final int DEFAULT_PORT = 8080;

  private WorkflowHttpServerLauncher() {
    // utility class — do not instantiate
  }

  /**
   * Application entry point.
   *
   * @param args optional arguments: {@code [port] [staticDir]}
   * @throws IOException if the HTTP server fails to start
   * @throws InterruptedException if the main thread is interrupted
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
    Path staticDir = args.length > 1 ? Path.of(args[1]) : extractBuiltInUi();

    WorkflowOperationLog log = new WorkflowOperationLog(seedWorkflow());
    WorkflowValidator validator = new WorkflowValidator();
    WorkflowEditorService service = new WorkflowEditorService(log, validator);

    WorkflowEditorHttpAdapter adapter = new WorkflowEditorHttpAdapter(service);
    if (staticDir != null) {
      adapter.start(port, staticDir);
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
        "Input \u2192 Gain \u2192 Output",
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
