package org.hammer;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.hammer.audio.experiment.document.ExperimentDocumentService;
import org.hammer.audio.experiment.document.ExperimentDocumentSession;

/** Complete document editor: workflow changes retain profiles, plugin data and provenance. */
// Revision checks and compound editor actions must use the same monitor as the shared session.
@SuppressWarnings("PMD.AvoidSynchronizedStatement")
final class ExperimentDocumentEditorDialog {
  private final JFrame owner;
  private final ExperimentDocumentService service;
  private final ExperimentDocumentSession session;
  private final JDialog dialog;
  private final JLabel status = new JLabel();
  private final JTextArea inspection = new JTextArea();
  private final JTextArea workflow = new JTextArea();
  private final JButton validate = new JButton("Validate and apply workflow edit");
  private final JButton saveButton = new JButton("Save");
  private final JButton saveAs = new JButton("Save As...");
  private final JButton bindings = new JButton("Local bindings / Evidence...");
  private final JButton cancel = new JButton("Cancel operation");
  private volatile long expectedRevision;
  private ExperimentDocumentTask<Void> running;

  ExperimentDocumentEditorDialog(
      JFrame owner, ExperimentDocumentService service, ExperimentDocumentSession session) {
    this.owner = owner;
    this.service = service;
    this.session = session;
    this.dialog = new JDialog(owner, "Experiment document editor", false);
    this.expectedRevision = session.revision();
  }

  void show() {
    dialog.setLayout(new BorderLayout());
    inspection.setEditable(false);
    inspection.setLineWrap(true);
    inspection.setWrapStyleWord(true);
    workflow.setText(session.workflowText());
    workflow.getAccessibleContext().setAccessibleName("Editable canonical workflow DSL");
    workflow.getDocument().addDocumentListener(new DraftListener());
    JTabbedPane tabs = new JTabbedPane();
    tabs.addTab("Complete document", new JScrollPane(inspection));
    tabs.addTab("Workflow", new JScrollPane(workflow));
    dialog.add(status, BorderLayout.NORTH);
    dialog.add(tabs, BorderLayout.CENTER);
    dialog.add(actions(), BorderLayout.SOUTH);
    dialog.setPreferredSize(new Dimension(1000, 650));
    refresh();
    setBusy(false);
    dialog.pack();
    dialog.setLocationRelativeTo(owner);
    dialog.setVisible(true);
  }

  private JPanel actions() {
    JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    validate.addActionListener(
        event ->
            run(
                () -> {
                  session.validateDraft();
                  return null;
                }));
    saveButton.addActionListener(event -> save(false));
    saveAs.addActionListener(event -> save(true));
    bindings.addActionListener(
        event -> {
          try {
            session.requireRevision(expectedRevision);
            new ExperimentDocumentBindingsDialog(owner, session.preview(), session.originalPath())
                .show();
          } catch (IllegalStateException exception) {
            ExperimentDocumentSwingActions.showFailure(dialog, exception);
          }
        });
    cancel.addActionListener(
        event -> {
          if (running != null) {
            running.requestCancellation();
            cancel.setEnabled(false);
            status.setText("Cancellation requested; waiting for the operation to finish");
          }
        });
    actions.add(validate);
    actions.add(saveButton);
    actions.add(saveAs);
    actions.add(bindings);
    actions.add(cancel);
    JButton close = new JButton("Close");
    close.addActionListener(event -> dialog.dispose());
    actions.add(close);
    return actions;
  }

  private void save(boolean chooseDestination) {
    Path target =
        chooseDestination || session.status().path() == null
            ? ExperimentDocumentFileChooser.chooseSave(dialog)
            : null;
    if ((chooseDestination || session.status().path() == null) && target == null) {
      return;
    }
    run(
        () -> {
          if (target == null) {
            session.save();
          } else {
            session.saveAs(target);
          }
          return null;
        });
  }

  private void run(Callable<Void> task) {
    if (running != null) {
      return;
    }
    setBusy(true);
    long revision = expectedRevision;
    running =
        new ExperimentDocumentTask<>(
            () -> {
              synchronized (session) {
                session.requireRevision(revision);
                try {
                  return task.call();
                } finally {
                  expectedRevision = session.revision();
                }
              }
            },
            finished -> {
              running = null;
              if (session.revision() != expectedRevision) {
                cancel.setEnabled(false);
                status.setText("Document changed in another window; reopen its editor");
                return;
              }
              setBusy(false);
              try {
                finished.get();
                refresh();
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              } catch (ExecutionException exception) {
                ExperimentDocumentSwingActions.showFailure(dialog, exception.getCause());
              }
            });
    running.execute();
  }

  private void setBusy(boolean busy) {
    boolean editable = !session.status().readOnly();
    validate.setEnabled(!busy && editable);
    saveButton.setEnabled(!busy && editable);
    saveAs.setEnabled(!busy);
    bindings.setEnabled(!busy);
    workflow.setEditable(!busy && editable);
    cancel.setEnabled(busy);
  }

  private void refresh() {
    try {
      inspection.setText(service.inspectionJson(session.preview()));
      inspection.setCaretPosition(0);
    } catch (IOException exception) {
      ExperimentDocumentSwingActions.showFailure(dialog, exception);
    }
    ExperimentDocumentSession.Status state = session.status();
    status.setText(
        (state.path() == null ? "Save As required" : state.path().toString())
            + (state.dirty() ? " • unsaved" : " • saved")
            + (state.readOnly() ? " • read-only inspection" : "")
            + " • "
            + state.canonicalSha256()
            + (state.lastSavedAt() == null ? "" : " • " + state.lastSavedAt()));
  }

  private void draftChanged() {
    try {
      synchronized (session) {
        session.editWorkflow(workflow.getText(), expectedRevision);
        expectedRevision = session.revision();
      }
      status.setText("Unsaved workflow draft; Save validates the complete document before writing");
    } catch (IllegalStateException | IllegalArgumentException exception) {
      status.setText(exception.getMessage());
      setBusy(true);
      cancel.setEnabled(false);
    }
  }

  private final class DraftListener implements DocumentListener {
    @Override
    public void insertUpdate(DocumentEvent event) {
      draftChanged();
    }

    @Override
    public void removeUpdate(DocumentEvent event) {
      draftChanged();
    }

    @Override
    public void changedUpdate(DocumentEvent event) {
      draftChanged();
    }
  }
}
