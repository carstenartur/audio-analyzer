package org.hammer.audio.infrastructure.workflow;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.hammer.audio.infrastructure.workflow.store.JGitStorageHibernateWorkflowStoreAdapter;
import org.hammer.audio.workflow.editor.http.WorkflowHttpServerLauncher;

/**
 * Production launcher for the workflow workbench backed by the JGit-file-system persistent store.
 *
 * <p>This launcher wires a {@link JGitStorageHibernateWorkflowStoreAdapter} into the workbench so
 * that checkpoints, history and branch-load endpoints persist across process restarts. It is the
 * recommended entry point for production and development deployments.
 *
 * <p>Usage: {@code java -cp app.jar:lib/*
 * org.hammer.audio.infrastructure.workflow.PersistentWorkbenchLauncher [port] [staticDir]
 * [dataDir]}
 *
 * <ul>
 *   <li>{@code port} — TCP port to bind (default: {@value WorkflowHttpServerLauncher#DEFAULT_PORT})
 *   <li>{@code staticDir} — filesystem path to serve as static web content at {@code /}; if omitted
 *       the built-in classpath workbench UI is extracted to a temporary directory
 *   <li>{@code dataDir} — filesystem path for the JGit bare repository used as the workflow store
 * </ul>
 *
 * <p>The data directory can also be supplied via the system property {@code -Dworkbench.data.dir=}
 * or the environment variable {@code WORKBENCH_DATA_DIR}. If none of the three sources provides a
 * path the launcher fails fast with an actionable error message.
 *
 * <p><b>Profiles</b>:
 *
 * <ul>
 *   <li><b>Persistent</b> (production / development): use this launcher with a local or shared
 *       directory. The JGit bare repository is created automatically if it does not yet exist.
 *   <li><b>In-memory</b> (demo / tests): use {@link WorkflowHttpServerLauncher} directly (no {@code
 *       dataDir} required; state is discarded on exit).
 * </ul>
 *
 * <p><b>Shutdown</b>: a JVM shutdown hook closes the workflow store cleanly on {@code SIGTERM} or
 * {@code Ctrl+C}.
 */
public final class PersistentWorkbenchLauncher {

  private static final Logger LOG = Logger.getLogger(PersistentWorkbenchLauncher.class.getName());
  private static final String DATA_DIR_PROPERTY = "workbench.data.dir";
  private static final String DATA_DIR_ENV = "WORKBENCH_DATA_DIR";

  private PersistentWorkbenchLauncher() {
    // utility class — do not instantiate
  }

  /**
   * Application entry point — starts the workbench with durable JGit-backed persistence.
   *
   * <p>The data directory is resolved in priority order:
   *
   * <ol>
   *   <li>Third positional argument ({@code args[2]})
   *   <li>System property {@code -Dworkbench.data.dir=PATH}
   *   <li>Environment variable {@code WORKBENCH_DATA_DIR=PATH}
   * </ol>
   *
   * <p>If no data directory is available the method throws {@link IllegalArgumentException} with an
   * actionable message before starting the HTTP server.
   *
   * @param args optional arguments: {@code [port] [staticDir] [dataDir]}
   * @throws IOException if the HTTP server or the workflow store fails to start
   * @throws InterruptedException if the main thread is interrupted while the server is running
   */
  @SuppressWarnings("PMD.CloseResource") // store is closed by the registered shutdown hook
  public static void main(String[] args) throws IOException, InterruptedException {
    int port =
        args.length > 0 ? Integer.parseInt(args[0]) : WorkflowHttpServerLauncher.DEFAULT_PORT;
    Path staticDir = args.length > 1 ? Path.of(args[1]) : null;
    Path dataDir = resolveDataDir(args);

    LOG.info("Initializing persistent workflow store at: " + dataDir);
    JGitStorageHibernateWorkflowStoreAdapter store = openStore(dataDir);
    LOG.info("Persistent workflow store initialized");

    Runtime.getRuntime()
        .addShutdownHook(new Thread(() -> closeStore(store), "workbench-store-shutdown"));

    WorkflowHttpServerLauncher.launch(port, staticDir, store);
  }

  private static JGitStorageHibernateWorkflowStoreAdapter openStore(Path dataDir) {
    try {
      return new JGitStorageHibernateWorkflowStoreAdapter(dataDir);
    } catch (RuntimeException ex) {
      throw new IllegalStateException(
          "Failed to initialize persistent workflow store at '"
              + dataDir
              + "'. "
              + "Check that the path exists and is writable. Cause: "
              + ex.getMessage(),
          ex);
    }
  }

  private static void closeStore(JGitStorageHibernateWorkflowStoreAdapter store) {
    try {
      LOG.info("Closing persistent workflow store");
      store.close();
    } catch (IOException ex) {
      LOG.warning("Error closing workflow store on shutdown: " + ex.getMessage());
    }
  }

  private static Path resolveDataDir(String[] args) {
    if (args.length > 2 && !args[2].isBlank()) {
      return Path.of(args[2]);
    }
    String prop = System.getProperty(DATA_DIR_PROPERTY);
    if (prop != null && !prop.isBlank()) {
      return Path.of(prop);
    }
    String env = System.getenv(DATA_DIR_ENV);
    if (env != null && !env.isBlank()) {
      return Path.of(env);
    }
    throw new IllegalArgumentException(
        "No data directory specified for the persistent workbench. "
            + "Provide the path as the third argument, "
            + "via -D"
            + DATA_DIR_PROPERTY
            + "=PATH, "
            + "or via environment variable "
            + DATA_DIR_ENV
            + "=PATH.");
  }
}
