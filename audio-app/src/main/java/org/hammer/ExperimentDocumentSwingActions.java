package org.hammer;

import java.awt.Cursor;
import java.nio.file.Path;
import java.util.Objects;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import org.hammer.audio.experiment.document.ExperimentDocumentPreview;
import org.hammer.audio.experiment.document.ExperimentDocumentService;

/** Installs safe, non-mutating portable experiment-document actions into the Swing workbench. */
final class ExperimentDocumentSwingActions {

  private static final String FILE_MENU = "File";
  private static final String DIALOG_TITLE = "Portable experiment document";

  private final JFrame owner;
  private final ExperimentDocumentService documentService;

  ExperimentDocumentSwingActions(JFrame owner, ExperimentDocumentService documentService) {
    this.owner = Objects.requireNonNull(owner, "owner");
    this.documentService = Objects.requireNonNull(documentService, "documentService");
  }

  /** Add one keyboard-accessible inspect/normalize action to the existing File menu. */
  void install() {
    JMenu fileMenu = findMenu(owner.getJMenuBar(), FILE_MENU);
    if (fileMenu == null) {
      throw new IllegalStateException("Desktop File menu is unavailable");
    }
    JMenuItem inspect = new JMenuItem("Inspect experiment document...");
    inspect.setToolTipText(
        "Validate and preview a .audioexp setup without applying or executing it.");
    inspect.addActionListener(event -> chooseDocument());
    fileMenu.insert(inspect, Math.min(fileMenu.getItemCount(), 1));
  }

  /** Validate and preview one explicitly selected document without applying or executing it. */
  void inspect(Path source) {
    Objects.requireNonNull(source, "source");
    owner.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    new SwingWorker<ExperimentDocumentPreview, Void>() {
      @Override
      protected ExperimentDocumentPreview doInBackground() throws Exception {
        return documentService.preview(source);
      }

      @Override
      protected void done() {
        owner.setCursor(Cursor.getDefaultCursor());
        try {
          new ExperimentDocumentPreviewDialog(owner, documentService).show(source, get());
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          showFailure("Preview was interrupted.", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
          showFailure("Experiment document preview failed.", exception.getCause());
        }
      }
    }.execute();
  }

  private void chooseDocument() {
    Path source = ExperimentDocumentFileChooser.chooseOpen(owner);
    if (source != null) {
      inspect(source);
    }
  }

  private static JMenu findMenu(JMenuBar menuBar, String title) {
    if (menuBar == null) {
      return null;
    }
    for (int index = 0; index < menuBar.getMenuCount(); index++) {
      JMenu menu = menuBar.getMenu(index);
      if (menu != null && title.equals(menu.getText())) {
        return menu;
      }
    }
    return null;
  }

  private void showFailure(String message, Throwable failure) {
    String detail = failure == null || failure.getMessage() == null ? "" : failure.getMessage();
    JOptionPane.showMessageDialog(
        owner,
        detail.isBlank() ? message : message + "\n" + detail,
        DIALOG_TITLE,
        JOptionPane.ERROR_MESSAGE);
  }
}
