package org.hammer;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import org.hammer.audio.experiment.document.ExperimentDocumentComparison;
import org.hammer.audio.experiment.document.ExperimentDocumentPreview;
import org.hammer.audio.experiment.document.ExperimentDocumentService;
import org.hammer.audio.experiment.document.ExperimentDocumentSession;

/** Modeless confirmation preview with one visible Save/Discard/Cancel decision. */
final class ExperimentDocumentPreviewDialog {
  private final JFrame owner;
  private final ExperimentDocumentService service;
  private final ExperimentDocumentSession session;
  private final Consumer<OpenRequest> open;

  ExperimentDocumentPreviewDialog(
      JFrame owner,
      ExperimentDocumentService service,
      ExperimentDocumentSession session,
      Consumer<OpenRequest> open) {
    this.owner = owner;
    this.service = service;
    this.session = session;
    this.open = open;
  }

  void show(Path source, ExperimentDocumentPreview preview, boolean importCopy) {
    long expectedRevision = session.revision();
    JDialog dialog = new JDialog(owner, "Preview experiment document", false);
    dialog.setLayout(new BorderLayout(8, 8));
    StringBuilder summary =
        new StringBuilder(
            ExperimentDocumentPreviewFormatter.format(source, preview, service.workflow(preview)));
    if (session.status().open()) {
      summary
          .append("\nChanges to current workflow:\n")
          .append(
              ExperimentDocumentComparison.compare(
                  service.workflow(session.preview()), service.workflow(preview)));
    }
    summary
        .append("\nSection SHA-256 values:\n")
        .append(preview.sectionHashes())
        .append("\nInstalled plugin sections:\n")
        .append(service.pluginSections())
        .append("\nLocal resources still required:\n")
        .append(
            new org.hammer.audio.experiment.document.ExperimentLocalBindingService()
                .inspect(
                    preview,
                    new org.hammer.audio.experiment.document.ExperimentLocalBindings(
                        java.util.Map.of(), null))
                .diagnostics());
    JTextArea text = new JTextArea(summary.toString());
    text.setEditable(false);
    text.setLineWrap(true);
    text.setWrapStyleWord(true);
    text.setCaretPosition(0);
    text.getAccessibleContext()
        .setAccessibleName("Complete experiment import preview and semantic changes");
    JScrollPane scroll = new JScrollPane(text);
    scroll.setPreferredSize(new Dimension(820, 540));
    dialog.add(scroll, BorderLayout.CENTER);
    JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    if (!importCopy) {
      if (session.status().dirty()) {
        JButton save = new JButton("Save current and open");
        save.addActionListener(
            event -> {
              open.accept(
                  new OpenRequest(
                      false, ExperimentDocumentSession.DirtyDecision.SAVE, expectedRevision));
              dialog.dispose();
            });
        actions.add(save);
        JButton discard = new JButton("Discard current changes and open");
        discard.addActionListener(
            event -> {
              open.accept(
                  new OpenRequest(
                      false, ExperimentDocumentSession.DirtyDecision.DISCARD, expectedRevision));
              dialog.dispose();
            });
        actions.add(discard);
      } else {
        JButton apply = new JButton(preview.readOnly() ? "Open read-only" : "Open document");
        apply.addActionListener(
            event -> {
              open.accept(
                  new OpenRequest(
                      false, ExperimentDocumentSession.DirtyDecision.DISCARD, expectedRevision));
              dialog.dispose();
            });
        actions.add(apply);
      }
    }
    JButton copy = new JButton("Import independent copy...");
    copy.addActionListener(
        event ->
            open.accept(
                new OpenRequest(
                    true, ExperimentDocumentSession.DirtyDecision.CANCEL, expectedRevision)));
    actions.add(copy);
    JButton close = new JButton("Cancel");
    close.addActionListener(event -> dialog.dispose());
    actions.add(close);
    dialog.add(actions, BorderLayout.SOUTH);
    dialog.pack();
    dialog.setLocationRelativeTo(owner);
    dialog.setVisible(true);
  }

  record OpenRequest(
      boolean copy, ExperimentDocumentSession.DirtyDecision decision, long expectedRevision) {
    // Immutable value; record components carry the complete state.
  }
}
