package org.hammer;

import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JProgressBar;
import javax.swing.KeyStroke;
import org.hammer.audio.experiment.document.ExperimentDocumentPreview;
import org.hammer.audio.experiment.document.ExperimentDocumentService;
import org.hammer.audio.experiment.document.ExperimentDocumentSession;

/** Desktop adapter for the shared complete-document lifecycle and explicit confirmation preview. */
// Compound revision checks and saves intentionally share the session's reentrant monitor.
@SuppressWarnings("PMD.AvoidSynchronizedStatement")
final class ExperimentDocumentSwingActions {
  private final JFrame owner;
  private final ExperimentDocumentService service;
  private final ExperimentDocumentSession session;
  private final JMenu recent = new JMenu("Recent experiment files");
  private final java.util.List<Path> recentPaths = new java.util.ArrayList<>();
  private ExperimentDocumentTask<?> worker;
  private final java.util.Deque<Runnable> pending = new java.util.ArrayDeque<>();

  ExperimentDocumentSwingActions(JFrame owner, ExperimentDocumentService service) {
    this.owner = Objects.requireNonNull(owner, "owner");
    this.service = Objects.requireNonNull(service, "documentService");
    this.session = new ExperimentDocumentSession(service);
  }

  /** Return one immutable snapshot for the host's document-health presentation. */
  ExperimentDocumentSession.Context documentContext() {
    return session.context();
  }

  /** Install keyboard-accessible Open, Import, Save, Save As and Validate actions. */
  void install() {
    JMenu menu = null;
    for (int index = 0; index < owner.getJMenuBar().getMenuCount(); index++) {
      if ("File".equals(owner.getJMenuBar().getMenu(index).getText())) {
        menu = owner.getJMenuBar().getMenu(index);
        break;
      }
    }
    if (menu == null) {
      throw new IllegalStateException("Desktop File menu is unavailable");
    }
    add(
        menu,
        "Open experiment document...",
        KeyEvent.VK_O,
        InputEvent.CTRL_DOWN_MASK,
        () -> choose(false));
    add(
        menu,
        "Import experiment copy...",
        KeyEvent.VK_O,
        InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK,
        () -> choose(true));
    add(
        menu,
        "Save experiment document",
        KeyEvent.VK_S,
        InputEvent.CTRL_DOWN_MASK,
        () -> save(false));
    add(
        menu,
        "Save experiment document as...",
        KeyEvent.VK_S,
        InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK,
        () -> save(true));
    add(
        menu,
        "Validate current experiment",
        KeyEvent.VK_F5,
        0,
        () -> {
          if (session.status().open()) {
            showEditor();
          } else {
            choose(false);
          }
        });
    menu.add(recent);
  }

  /** OS launch and already-running open events always enter the non-mutating preview first. */
  void inspect(Path source) {
    run(() -> service.preview(source), preview -> showPreview(source, preview, false));
  }

  private void choose(boolean copy) {
    Path source = ExperimentDocumentFileChooser.chooseOpen(owner);
    if (source != null) {
      run(() -> service.preview(source), preview -> showPreview(source, preview, copy));
    }
  }

  private void showPreview(Path source, ExperimentDocumentPreview preview, boolean copy) {
    new ExperimentDocumentPreviewDialog(
            owner,
            service,
            session,
            request -> {
              if (request.copy()) {
                Path target = ExperimentDocumentFileChooser.chooseSave(owner);
                if (target == null) {
                  return;
                }
                run(
                    () -> {
                      ExperimentDocumentSession independent =
                          new ExperimentDocumentSession(service);
                      independent.open(
                          preview, source, ExperimentDocumentSession.DirtyDecision.DISCARD, null);
                      independent.saveAs(target);
                      return target;
                    },
                    saved -> {
                      remember(saved);
                      JOptionPane.showMessageDialog(
                          owner, "Independent experiment copy saved to:\n" + saved);
                    });
              } else {
                Path target =
                    request.decision() == ExperimentDocumentSession.DirtyDecision.SAVE
                            && session.status().path() == null
                        ? ExperimentDocumentFileChooser.chooseSave(owner)
                        : null;
                if (request.decision() == ExperimentDocumentSession.DirtyDecision.SAVE
                    && session.status().path() == null
                    && target == null) {
                  return;
                }
                run(
                    () ->
                        session.openIfCurrent(
                            preview,
                            source,
                            request.decision(),
                            target,
                            request.expectedRevision()),
                    opened -> {
                      if (opened) {
                        remember(source);
                        showEditor();
                      }
                    });
              }
            })
        .show(source, preview, copy);
  }

  private void save(boolean saveAs) {
    if (!session.status().open()) {
      JOptionPane.showMessageDialog(owner, "Open an experiment document first.");
      return;
    }
    Path target =
        saveAs || session.status().path() == null
            ? ExperimentDocumentFileChooser.chooseSave(owner)
            : null;
    if ((saveAs || session.status().path() == null) && target == null) {
      return;
    }
    long expectedRevision = session.revision();
    run(
        () -> {
          synchronized (session) {
            session.requireRevision(expectedRevision);
            if (target == null) {
              session.save();
            } else {
              session.saveAs(target);
            }
            return session.status();
          }
        },
        status -> {
          remember(status.path());
          JOptionPane.showMessageDialog(
              owner, "Saved " + status.path() + "\nSHA-256: " + status.canonicalSha256());
        });
  }

  private void showEditor() {
    new ExperimentDocumentEditorDialog(owner, service, session).show();
  }

  private void remember(Path path) {
    if (path == null) {
      return;
    }
    recentPaths.remove(path);
    recentPaths.addFirst(path);
    if (recentPaths.size() > 10) {
      recentPaths.removeLast();
    }
    recent.removeAll();
    for (Path entry : recentPaths) {
      Path name = entry.getFileName();
      JMenuItem item = new JMenuItem(name == null ? entry.toString() : name.toString());
      item.addActionListener(event -> inspect(entry));
      recent.add(item);
    }
  }

  private <T> void run(Callable<T> task, Consumer<T> completed) {
    if (worker != null) {
      if (pending.size() >= 16) {
        showFailure(owner, new IllegalStateException("Too many pending document operations"));
      } else {
        pending.addLast(() -> run(task, completed));
      }
      return;
    }
    JDialog progress = new JDialog(owner, "Experiment document", false);
    progress.setLayout(new FlowLayout());
    JProgressBar bar = new JProgressBar();
    bar.setIndeterminate(true);
    progress.add(bar);
    JButton cancel = new JButton("Cancel");
    progress.add(cancel);
    owner.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    worker =
        new ExperimentDocumentTask<>(
            task,
            finished -> {
              worker = null;
              progress.dispose();
              owner.setCursor(Cursor.getDefaultCursor());
              try {
                completed.accept(finished.get());
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              } catch (java.util.concurrent.ExecutionException exception) {
                showFailure(owner, exception.getCause());
              } finally {
                if (!pending.isEmpty()) {
                  javax.swing.SwingUtilities.invokeLater(pending.removeFirst());
                }
              }
            });
    cancel.addActionListener(
        event -> {
          if (worker != null) {
            worker.requestCancellation();
            cancel.setText("Cancellation requested…");
            cancel.setEnabled(false);
          }
        });
    progress.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
    progress.pack();
    progress.setLocationRelativeTo(owner);
    progress.setVisible(true);
    worker.execute();
  }

  private static void add(JMenu menu, String title, int key, int modifiers, Runnable action) {
    JMenuItem item = new JMenuItem(title);
    item.setAccelerator(KeyStroke.getKeyStroke(key, modifiers));
    item.addActionListener(event -> action.run());
    menu.add(item);
  }

  static void showFailure(java.awt.Component owner, Throwable failure) {
    String message =
        failure == null || failure.getMessage() == null
            ? "Document operation failed"
            : failure.getMessage();
    JOptionPane.showMessageDialog(owner, message, "Experiment document", JOptionPane.ERROR_MESSAGE);
  }
}
