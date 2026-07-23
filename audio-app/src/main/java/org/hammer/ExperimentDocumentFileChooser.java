package org.hammer;

import java.awt.Component;
import java.io.File;
import java.nio.file.Path;
import java.util.Locale;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.hammer.audio.experiment.document.ExperimentDocumentFormat;

/** Shared, extension-safe file chooser operations for portable experiment documents. */
final class ExperimentDocumentFileChooser {

  private ExperimentDocumentFileChooser() {
    // utility class
  }

  /** Select one existing experiment document, or return {@code null} when cancelled. */
  static Path chooseOpen(Component owner) {
    JFileChooser chooser = configuredChooser("Inspect portable experiment document");
    return chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION
        ? chooser.getSelectedFile().toPath()
        : null;
  }

  /** Select a distinct normalized-copy destination, or return {@code null} when cancelled. */
  static Path chooseSave(Component owner) {
    JFileChooser chooser = configuredChooser("Save normalized experiment document");
    chooser.setSelectedFile(new File("normalized." + ExperimentDocumentFormat.FILE_EXTENSION));
    return chooser.showSaveDialog(owner) == JFileChooser.APPROVE_OPTION
        ? ensureExtension(chooser.getSelectedFile()).toPath()
        : null;
  }

  private static JFileChooser configuredChooser(String title) {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle(title);
    chooser.setFileFilter(
        new FileNameExtensionFilter(
            "Audio Analyzer experiment (*." + ExperimentDocumentFormat.FILE_EXTENSION + ")",
            ExperimentDocumentFormat.FILE_EXTENSION));
    return chooser;
  }

  private static File ensureExtension(File file) {
    String extension = "." + ExperimentDocumentFormat.FILE_EXTENSION;
    return file.getName().toLowerCase(Locale.ROOT).endsWith(extension)
        ? file
        : new File(file.getParentFile(), file.getName() + extension);
  }
}
