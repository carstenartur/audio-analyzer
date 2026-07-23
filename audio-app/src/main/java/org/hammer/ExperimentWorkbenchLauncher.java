package org.hammer;

import java.awt.EventQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.hammer.audio.experiment.document.ExperimentDocumentService;
import org.hammer.audio.pluginhost.PluginManager;
import org.hammer.audio.pluginhost.PluginRegistry;
import org.hammer.audio.ui.theme.UiTheme;

/** Launches the experiment-aware desktop with portable document preview and normalization actions. */
public final class ExperimentWorkbenchLauncher {

  private static final Logger LOGGER = Logger.getLogger(ExperimentWorkbenchLauncher.class.getName());

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
            new ExperimentDocumentSwingActions(frame, documentService).install();
            frame.setVisible(true);
          } catch (RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Failed to start experiment desktop workbench", exception);
          }
        });
  }
}
