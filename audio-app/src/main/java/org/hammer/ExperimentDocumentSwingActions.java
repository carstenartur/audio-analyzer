package org.hammer;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.hammer.audio.experiment.document.ExperimentDocumentFormat;
import org.hammer.audio.experiment.document.ExperimentDocumentPreview;
import org.hammer.audio.experiment.document.ExperimentDocumentService;
import org.hammer.audio.plugin.document.DocumentDiagnostic;
import org.hammer.audio.workflow.Workflow;

/** Installs safe, non-mutating portable experiment-document actions into the Swing workbench. */
final class ExperimentDocumentSwingActions {

  private static final String FILE_MENU = "File";
  private static final String DIALOG_TITLE = "Portable experiment document";

  private final JFrame owner;
  private final ExperimentDocumentService documentService;

  ExperimentDocumentSwingActions(JFrame owner, ExperimentDocumentService documentService) {
    this.owner = java.util.Objects.requireNonNull(owner, "owner");
    this.documentService =
        java.util.Objects.requireNonNull(documentService, "documentService");
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

  private void chooseDocument() {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Inspect portable experiment document");
    chooser.setFileFilter(
        new FileNameExtensionFilter(
            "Audio Analyzer experiment (*." + ExperimentDocumentFormat.FILE_EXTENSION + ")",
            ExperimentDocumentFormat.FILE_EXTENSION));
    if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    Path source = chooser.getSelectedFile().toPath();
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
          showPreview(source, get());
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          showFailure("Preview was interrupted.", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
          showFailure("Experiment document preview failed.", exception.getCause());
        }
      }
    }.execute();
  }

  private void showPreview(Path source, ExperimentDocumentPreview preview) {
    Workflow workflow = documentService.workflow(preview);
    JDialog dialog = new JDialog((Window) owner, DIALOG_TITLE);
    dialog.setModal(false);
    dialog.setLayout(new BorderLayout(8, 8));

    JTextArea summary = new JTextArea(previewText(source, preview, workflow));
    summary.setEditable(false);
    summary.setLineWrap(true);
    summary.setWrapStyleWord(true);
    summary.setCaretPosition(0);
    summary.getAccessibleContext().setAccessibleName("Experiment document preview");
    JScrollPane scrollPane = new JScrollPane(summary);
    scrollPane.setPreferredSize(new Dimension(760, 480));
    dialog.add(scrollPane, BorderLayout.CENTER);

    JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    JButton saveAs = new JButton("Save normalized copy...");
    saveAs.setToolTipText(
        "Write a canonical copy to a distinct path without modifying the imported source.");
    saveAs.addActionListener(event -> chooseNormalizeTarget(dialog, source));
    actions.add(saveAs);
    JButton close = new JButton("Close");
    close.addActionListener(event -> dialog.dispose());
    actions.add(close);
    dialog.add(actions, BorderLayout.SOUTH);

    dialog.pack();
    dialog.setLocationRelativeTo(owner);
    dialog.setVisible(true);
  }

  private void chooseNormalizeTarget(JDialog previewDialog, Path source) {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Save normalized experiment document");
    chooser.setFileFilter(
        new FileNameExtensionFilter(
            "Audio Analyzer experiment (*." + ExperimentDocumentFormat.FILE_EXTENSION + ")",
            ExperimentDocumentFormat.FILE_EXTENSION));
    chooser.setSelectedFile(new File("normalized." + ExperimentDocumentFormat.FILE_EXTENSION));
    if (chooser.showSaveDialog(previewDialog) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    Path target = ensureExtension(chooser.getSelectedFile()).toPath();
    previewDialog.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    new SwingWorker<ExperimentDocumentPreview, Void>() {
      @Override
      protected ExperimentDocumentPreview doInBackground() throws Exception {
        return documentService.normalize(source, target);
      }

      @Override
      protected void done() {
        previewDialog.setCursor(Cursor.getDefaultCursor());
        try {
          get();
          JOptionPane.showMessageDialog(
              previewDialog,
              "Normalized experiment document saved to:\n" + target,
              DIALOG_TITLE,
              JOptionPane.INFORMATION_MESSAGE);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          showFailure("Normalization was interrupted.", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
          showFailure("Could not save normalized experiment document.", exception.getCause());
        }
      }
    }.execute();
  }

  private static String previewText(
      Path source, ExperimentDocumentPreview preview, Workflow workflow) {
    StringBuilder text = new StringBuilder();
    text.append("Source: ").append(source.toAbsolutePath().normalize()).append('\n');
    text.append("Experiment: ").append(preview.document().experiment().name()).append('\n');
    text.append("Experiment ID: ").append(preview.document().experiment().id()).append('\n');
    text.append("Format: ")
        .append(preview.document().format())
        .append(" v")
        .append(preview.document().formatVersion())
        .append('\n');
    text.append("Canonical SHA-256: ").append(preview.canonicalSha256()).append('\n');
    text.append("Source mode: ").append(preview.document().experiment().sourceMode()).append('\n');
    text.append("Workflow: ")
        .append(workflow.name())
        .append(" (")
        .append(workflow.nodes().size())
        .append(" nodes, ")
        .append(workflow.edges().size())
        .append(" edges)\n");
    text.append("Execution allowed: ").append(preview.executionAllowed()).append('\n');
    text.append("Read-only: ").append(preview.readOnly()).append('\n');
    if (!preview.migrations().isEmpty()) {
      text.append("\nMigrations:\n");
      preview.migrations().forEach(value -> text.append("- ").append(value).append('\n'));
    }
    if (!preview.diagnostics().isEmpty()) {
      text.append("\nDiagnostics:\n");
      for (DocumentDiagnostic diagnostic : preview.diagnostics()) {
        text.append("- [")
            .append(diagnostic.severity())
            .append("] ")
            .append(diagnostic.pointer())
            .append(" ")
            .append(diagnostic.code())
            .append(": ")
            .append(diagnostic.message())
            .append('\n');
      }
    }
    if (preview.readOnly()) {
      text.append(
          "\nThis document may be inspected and preserved, but it cannot be applied or executed"
              + " with the current plugin environment.\n");
    }
    return text.toString();
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

  private static File ensureExtension(File file) {
    String extension = "." + ExperimentDocumentFormat.FILE_EXTENSION;
    return file.getName().toLowerCase(Locale.ROOT).endsWith(extension)
        ? file
        : new File(file.getParentFile(), file.getName() + extension);
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
