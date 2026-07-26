package org.hammer;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.file.Path;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import org.hammer.audio.experiment.document.ExperimentDocumentPreview;
import org.hammer.audio.experiment.document.ExperimentDocumentService;
import org.hammer.audio.workflow.Workflow;

/** Modeless, copyable preview and explicit Save-As surface for one experiment document. */
final class ExperimentDocumentPreviewDialog {

  private static final String TITLE = "Portable experiment document";

  private final JFrame owner;
  private final ExperimentDocumentService documentService;

  ExperimentDocumentPreviewDialog(JFrame owner, ExperimentDocumentService documentService) {
    this.owner = java.util.Objects.requireNonNull(owner, "owner");
    this.documentService = java.util.Objects.requireNonNull(documentService, "documentService");
  }

  /** Display a modeless preview; no workflow state is applied or executed. */
  void show(Path source, ExperimentDocumentPreview preview) {
    Workflow workflow = documentService.workflow(preview);
    JDialog dialog = new JDialog(owner, TITLE, false);
    dialog.setLayout(new BorderLayout(8, 8));

    JTextArea summary =
        new JTextArea(ExperimentDocumentPreviewFormatter.format(source, preview, workflow));
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

  private void chooseNormalizeTarget(JDialog dialog, Path source) {
    Path target = ExperimentDocumentFileChooser.chooseSave(dialog);
    if (target == null) {
      return;
    }
    dialog.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    new SwingWorker<ExperimentDocumentPreview, Void>() {
      @Override
      protected ExperimentDocumentPreview doInBackground() throws Exception {
        return documentService.normalize(source, target);
      }

      @Override
      protected void done() {
        dialog.setCursor(Cursor.getDefaultCursor());
        try {
          get();
          JOptionPane.showMessageDialog(
              dialog,
              "Normalized experiment document saved to:\n" + target,
              TITLE,
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

  private void showFailure(String message, Throwable failure) {
    String detail = failure == null || failure.getMessage() == null ? "" : failure.getMessage();
    JOptionPane.showMessageDialog(
        owner,
        detail.isBlank() ? message : message + "\n" + detail,
        TITLE,
        JOptionPane.ERROR_MESSAGE);
  }
}
