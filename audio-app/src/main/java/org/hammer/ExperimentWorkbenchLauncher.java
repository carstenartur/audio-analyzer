package org.hammer;

import java.awt.Desktop;
import java.awt.EventQueue;
import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.hammer.audio.experiment.document.ExperimentDocumentService;
import org.hammer.audio.pluginhost.PluginManager;
import org.hammer.audio.pluginhost.PluginRegistry;
import org.hammer.audio.ui.theme.UiTheme;

/**
 * Launches the experiment-aware desktop with portable document preview and normalization actions.
 */
public final class ExperimentWorkbenchLauncher {

  private static final Logger LOGGER =
      Logger.getLogger(ExperimentWorkbenchLauncher.class.getName());

  private ExperimentWorkbenchLauncher() {
    // application entry point
  }

  /** Start the Swing workbench on the event-dispatch thread. */
  public static void main(String[] args) {
    EventQueue.invokeLater(
        () -> {
          try {
            UiTheme.installDarkTheme();
            PluginRegistry plugins = new PluginManager().loadPlugins();
            ExperimentAudioAnalyseFrame frame = new ExperimentAudioAnalyseFrame();
            ExperimentDocumentService documentService =
                new ExperimentDocumentService(plugins.plugins());
            ExperimentDocumentSwingActions documentActions =
                new ExperimentDocumentSwingActions(frame, documentService);
            documentActions.install();
            installDesktopOpenFileHandler(documentActions::inspect);
            frame.setVisible(true);
            inspectStartupArguments(args, documentActions::inspect);
          } catch (RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Failed to start experiment desktop workbench", exception);
          }
        });
  }

  /** Send non-blank startup file arguments through the same safe document-preview action. */
  static void inspectStartupArguments(String[] args, Consumer<Path> inspector) {
    Objects.requireNonNull(args, "args");
    Objects.requireNonNull(inspector, "inspector");
    for (String argument : args) {
      if (argument == null || argument.isBlank()) {
        continue;
      }
      try {
        inspector.accept(Path.of(argument));
      } catch (InvalidPathException exception) {
        LOGGER.log(Level.WARNING, "Ignoring invalid experiment document path argument", exception);
      }
    }
  }

  /**
   * Register already-running application open-file events when the desktop platform supports them.
   */
  static void installDesktopOpenFileHandler(Consumer<Path> inspector) {
    Objects.requireNonNull(inspector, "inspector");
    try {
      if (!Desktop.isDesktopSupported()) {
        return;
      }
      Desktop desktop = Desktop.getDesktop();
      if (!desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) {
        return;
      }
      desktop.setOpenFileHandler(
          event ->
              event.getFiles().stream()
                  .map(File::toPath)
                  .forEach(path -> inspectOnEventThread(path, inspector)));
    } catch (SecurityException | UnsupportedOperationException exception) {
      LOGGER.log(Level.FINE, "Desktop open-file integration is unavailable", exception);
    }
  }

  private static void inspectOnEventThread(Path source, Consumer<Path> inspector) {
    if (EventQueue.isDispatchThread()) {
      inspector.accept(source);
    } else {
      EventQueue.invokeLater(() -> inspector.accept(source));
    }
  }
}
