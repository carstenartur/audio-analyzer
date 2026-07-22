package org.hammer;

import java.awt.FlowLayout;
import java.awt.event.ActionListener;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.hammer.audio.recording.runtime.RecordingState;
import org.hammer.audio.recording.runtime.RecordingStatus;
import org.hammer.audio.recording.runtime.RecordingStorageLevel;

/** Persistent non-modal recording health strip for the desktop workbench. */
public final class RecordingStatusPanel extends JPanel {

  private static final long serialVersionUID = 1L;

  private final JLabel state = new JLabel("● Idle");
  private final JLabel elapsed = new JLabel("00:00:00");
  private final JLabel destination = new JLabel("—");
  private final JLabel size = new JLabel("0 B");
  private final JLabel free = new JLabel("free: unknown");
  private final JLabel rate = new JLabel("0 B/s");
  private final JLabel remaining = new JLabel("remaining: unknown");
  private final JLabel completeness = new JLabel("complete");
  private final JButton stopButton = new JButton("Stop recording");

  /** Create an initially idle status strip. */
  public RecordingStatusPanel() {
    super(new FlowLayout(FlowLayout.LEFT, 10, 3));
    setBorder(BorderFactory.createTitledBorder("Experiment recording"));
    add(state);
    add(elapsed);
    add(destination);
    add(size);
    add(free);
    add(rate);
    add(remaining);
    add(completeness);
    add(stopButton);
    stopButton.setEnabled(false);
    setVisible(false);
  }

  /** Configure the action invoked by the persistent stop control. */
  public void setStopAction(Runnable action) {
    Objects.requireNonNull(action, "action");
    for (ActionListener listener : stopButton.getActionListeners()) {
      stopButton.removeActionListener(listener);
    }
    stopButton.addActionListener(event -> action.run());
  }

  /** Render one immutable recording status snapshot. Must be called on the Swing EDT. */
  public void updateStatus(RecordingStatus status) {
    Objects.requireNonNull(status, "status");
    setVisible(true);
    state.setText(stateLabel(status.state()));
    elapsed.setText(formatDuration(status.elapsed()));
    destination.setText(status.destination().getFileName().toString());
    destination.setToolTipText(status.destination().toString());
    size.setText(formatBytes(status.bytesWritten()));
    if (status.storage().usableBytes() >= 0L) {
      free.setText("free: " + formatBytes(status.storage().usableBytes()));
    } else {
      free.setText("free: unknown");
    }
    rate.setText(formatBytes((long) status.measuredBytesPerSecond()) + "/s");
    remaining.setText(formatRemaining(status.storage().estimatedSafeSecondsRemaining()));
    completeness.setText(completenessLabel(status));
    completeness.setToolTipText(detail(status));
    stopButton.setEnabled(!status.terminal() && status.state() != RecordingState.STOPPING);
    RecordingStorageLevel storageLevel = status.storage().level();
    state.setToolTipText(
        storageLevel == RecordingStorageLevel.NORMAL
            ? "Recording storage is healthy."
            : "Storage status: " + storageLevel + " — " + status.storage().errorMessage());
    revalidate();
    repaint();
  }

  private static String stateLabel(RecordingState value) {
    return switch (value) {
      case STARTING -> "● Starting";
      case RECORDING -> "● Recording";
      case STOPPING -> "● Stopping";
      case COMPLETED -> "✓ Completed";
      case INCOMPLETE -> "⚠ Incomplete";
      case FAILED -> "✕ Failed";
    };
  }

  private static String completenessLabel(RecordingStatus status) {
    if (status.errorMessage().isBlank()) {
      return String.format(
          Locale.ROOT,
          "blocks %d/%d · dropped %d · gaps %d · queue %d/%d",
          status.writtenBlocks(),
          status.receivedBlocks(),
          status.droppedBlocks(),
          status.continuityGapCount(),
          status.queueDepth(),
          status.queueCapacity());
    }
    return "error: " + status.errorMessage();
  }

  private static String detail(RecordingStatus status) {
    String reason =
        status.stopReason().isBlank() ? "No stop reason recorded." : status.stopReason();
    return reason
        + " Written frames: "
        + status.writtenFrames()
        + "/"
        + status.receivedFrames()
        + ".";
  }

  private static String formatDuration(Duration duration) {
    long seconds = Math.max(0L, duration.toSeconds());
    return String.format(
        Locale.ROOT, "%02d:%02d:%02d", seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L);
  }

  private static String formatRemaining(long seconds) {
    if (seconds < 0L) {
      return "remaining: unknown";
    }
    return "remaining: " + formatDuration(Duration.ofSeconds(seconds));
  }

  private static String formatBytes(long bytes) {
    if (bytes < 1024L) {
      return bytes + " B";
    }
    double value = bytes;
    String[] units = {"KiB", "MiB", "GiB", "TiB"};
    int unit = -1;
    do {
      value /= 1024.0;
      unit++;
    } while (value >= 1024.0 && unit + 1 < units.length);
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
  }
}
