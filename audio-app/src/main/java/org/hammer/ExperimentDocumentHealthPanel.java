package org.hammer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import org.hammer.audio.acquisition.health.ExperimentHealthSnapshot;
import org.hammer.audio.acquisition.health.ExperimentHealthState;
import org.hammer.audio.experiment.health.ExperimentDocumentHealthProjector;

/** Persistent, non-modal desktop view of the same document health returned by the web adapter. */
public final class ExperimentDocumentHealthPanel extends JPanel implements AutoCloseable {
  private static final long serialVersionUID = 1L;
  private final transient Supplier<ExperimentDocumentHealthProjector.Input> source;
  private final JLabel summary = new JLabel("Document: NOT CONFIGURED");
  private final JTextArea details = new JTextArea(9, 80);
  private final Timer timer;
  private boolean refreshing;
  private boolean closed;

  /**
   * Read immutable context on a worker; no file/device probes are performed on the event thread.
   */
  public ExperimentDocumentHealthPanel(Supplier<ExperimentDocumentHealthProjector.Input> source) {
    super(new BorderLayout(6, 6));
    this.source = source;
    getAccessibleContext().setAccessibleName("Experiment document readiness");
    summary.getAccessibleContext().setAccessibleName("Document readiness state");
    JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEADING));
    controls.add(summary);
    JToggleButton expand = new JToggleButton("Readiness details");
    controls.add(expand);
    JButton copy = new JButton("Copy diagnostic snapshot");
    controls.add(copy);
    add(controls, BorderLayout.NORTH);
    details.setEditable(false);
    details.getAccessibleContext().setAccessibleName("Full document readiness diagnostics");
    JScrollPane scroll = new JScrollPane(details);
    scroll.setVisible(false);
    add(scroll, BorderLayout.CENTER);
    expand.addActionListener(
        event -> {
          scroll.setVisible(expand.isSelected());
          revalidate();
        });
    copy.addActionListener(
        event -> {
          details.selectAll();
          details.copy();
        });
    timer = new Timer(1500, event -> refresh());
    timer.start();
    refresh();
  }

  private void refresh() {
    if (refreshing || closed) {
      return;
    }
    refreshing = true;
    new SwingWorker<ExperimentHealthSnapshot, Void>() {
      @Override
      protected ExperimentHealthSnapshot doInBackground() {
        return ExperimentDocumentHealthProjector.project(source.get(), Instant.now());
      }

      @Override
      protected void done() {
        refreshing = false;
        if (closed) {
          return;
        }
        try {
          ExperimentHealthSnapshot health = get();
          summary.setText("Document: " + health.state() + " · " + health.experimentName());
          summary.setForeground(
              health.state() == ExperimentHealthState.BLOCKED
                  ? new Color(255, 177, 177)
                  : javax.swing.UIManager.getColor("Label.foreground"));
          int position = details.getCaretPosition();
          details.setText(ExperimentHealthText.describe(health));
          details.setCaretPosition(Math.min(position, details.getDocument().getLength()));
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          unavailable(exception);
        } catch (ExecutionException | RuntimeException exception) {
          unavailable(exception);
        }
      }
    }.execute();
  }

  private void unavailable(Exception exception) {
    summary.setText("Document: STATUS UNAVAILABLE");
    details.setText("Readiness could not be inspected: " + exception.getMessage());
  }

  /** Stop refreshes when the owning desktop window closes. */
  @Override
  public void close() {
    closed = true;
    timer.stop();
  }
}
