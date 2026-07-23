package org.hammer;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.hammer.audio.ActiveAudioCaptureRegistry;
import org.hammer.audio.AudioCaptureService;
import org.hammer.audio.RecordingPreflight;
import org.hammer.audio.RecordingTap;
import org.hammer.audio.recording.AudioBlockRecordingFormat;
import org.hammer.audio.recording.AudioBlockRecordingReader;
import org.hammer.audio.recording.RecordingInspection;
import org.hammer.audio.recording.RecordingIntegrity;
import org.hammer.audio.recording.runtime.RecordingState;
import org.hammer.audio.recording.runtime.RecordingStatus;
import org.hammer.audio.recording.runtime.RecordingStorageLevel;
import org.hammer.audio.recording.runtime.RecordingStorageStatus;
import org.hammer.audio.ui.theme.UiTheme;

/**
 * Production desktop host that augments the established analyzer frame with experiment-grade
 * recording controls and persistent recording health.
 */
public final class ExperimentAudioAnalyseFrame extends AudioAnalyseFrame {

  private static final long serialVersionUID = 1L;
  private static final Logger LOGGER =
      Logger.getLogger(ExperimentAudioAnalyseFrame.class.getName());

  private final RecordingStatusPanel recordingStatusPanel = new RecordingStatusPanel();
  private transient RecordingTap recordingTap;

  /** Launch the experiment-aware desktop workbench. */
  public static void main(String[] args) {
    EventQueue.invokeLater(
        () -> {
          try {
            UiTheme.installDarkTheme();
            ExperimentAudioAnalyseFrame frame = new ExperimentAudioAnalyseFrame();
            frame.setVisible(true);
          } catch (RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Failed to start experiment desktop workbench", exception);
          }
        });
  }

  /** Create the established analyzer UI and install experiment recording controls. */
  public ExperimentAudioAnalyseFrame() {
    super();
    setTitle("AudioAnalyzer — Experiment Workbench");
    installStatusStrip();
    replaceRecordingActions();
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent event) {
            stopRecordingQuietly();
          }
        });
  }

  private void installStatusStrip() {
    Component existingSouth =
        getContentPane().getLayout() instanceof BorderLayout
            ? ((BorderLayout) getContentPane().getLayout()).getLayoutComponent(BorderLayout.SOUTH)
            : null;
    JPanel south = new JPanel(new BorderLayout(4, 4));
    if (existingSouth != null) {
      getContentPane().remove(existingSouth);
      south.add(existingSouth, BorderLayout.NORTH);
    }
    recordingStatusPanel.setStopAction(this::stopExperimentRecording);
    south.add(recordingStatusPanel, BorderLayout.SOUTH);
    getContentPane().add(south, BorderLayout.SOUTH);
  }

  private void replaceRecordingActions() {
    JMenu fileMenu = findMenu("File");
    if (fileMenu == null) {
      throw new IllegalStateException("Desktop File menu is unavailable");
    }
    JMenuItem start = requireItem(fileMenu, "Start recording...");
    replaceListeners(start, event -> startExperimentRecording());
    start.setToolTipText(
        "Record every produced block into an integrity-protected ."
            + AudioBlockRecordingFormat.FILE_EXTENSION
            + " file.");

    JMenuItem stop = requireItem(fileMenu, "Stop recording");
    replaceListeners(stop, event -> stopExperimentRecording());

    JMenuItem inspect = new JMenuItem("Inspect or recover recording...");
    inspect.setToolTipText(
        "Inspect completion, continuity and checksum evidence or recover complete blocks from a"
            + " partial recording.");
    inspect.addActionListener(event -> inspectOrRecoverRecording());
    int stopIndex = stop.getParent().getComponentZOrder(stop);
    fileMenu.insert(inspect, Math.min(fileMenu.getItemCount(), stopIndex + 1));
  }

  private void startExperimentRecording() {
    if (recordingTap != null && !recordingTap.isClosed()) {
      JOptionPane.showMessageDialog(
          this,
          "A recording is already in progress: " + recordingTap.file(),
          "Experiment recording",
          JOptionPane.WARNING_MESSAGE);
      return;
    }
    AudioCaptureService service = ActiveAudioCaptureRegistry.current().orElse(null);
    if (service == null) {
      JOptionPane.showMessageDialog(
          this,
          "Start a live, demo or replay source before starting the experiment recording.",
          "Experiment recording",
          JOptionPane.WARNING_MESSAGE);
      return;
    }

    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Start experiment recording");
    chooser.setFileFilter(
        new FileNameExtensionFilter(
            "AudioAnalyzer recording (*." + AudioBlockRecordingFormat.FILE_EXTENSION + ")",
            AudioBlockRecordingFormat.FILE_EXTENSION));
    chooser.setSelectedFile(new File("recording." + AudioBlockRecordingFormat.FILE_EXTENSION));
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    File selected =
        ensureExtension(chooser.getSelectedFile(), "." + AudioBlockRecordingFormat.FILE_EXTENSION);
    try {
      RecordingStorageStatus preflight = RecordingPreflight.inspect(service, selected.toPath());
      if (!preflight.writable() || preflight.level() == RecordingStorageLevel.CRITICAL) {
        JOptionPane.showMessageDialog(
            this,
            storagePreflightText(preflight),
            "Recording storage is not ready",
            JOptionPane.ERROR_MESSAGE);
        return;
      }
      if (preflight.level() == RecordingStorageLevel.WARNING && !confirmStorageWarning(preflight)) {
        return;
      }
      recordingTap = RecordingTap.start(service, selected.toPath());
      recordingTap.addStatusListener(
          status -> SwingUtilities.invokeLater(() -> recordingStatusPanel.updateStatus(status)));
    } catch (IOException exception) {
      LOGGER.log(Level.SEVERE, "Failed to start experiment recording", exception);
      JOptionPane.showMessageDialog(
          this,
          "Recording could not start: " + exception.getMessage(),
          "Experiment recording",
          JOptionPane.ERROR_MESSAGE);
    }
  }

  private boolean confirmStorageWarning(RecordingStorageStatus preflight) {
    int choice =
        JOptionPane.showConfirmDialog(
            this,
            storagePreflightText(preflight) + "\n\nStart recording despite this warning?",
            "Recording storage warning",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);
    return choice == JOptionPane.YES_OPTION;
  }

  private static String storagePreflightText(RecordingStorageStatus preflight) {
    return String.format(
        Locale.ROOT,
        "Destination: %s%nUsable space: %s%nEstimated safe duration: %s%nExpected write rate:"
            + " %s/s%nStatus: %s%s",
        preflight.destination(),
        formatBytes(preflight.usableBytes()),
        formatDuration(preflight.estimatedSafeSecondsRemaining()),
        formatBytes(Math.round(preflight.expectedBytesPerSecond())),
        preflight.level(),
        preflight.errorMessage().isBlank() ? "" : "%n" + preflight.errorMessage());
  }

  private static String formatBytes(long bytes) {
    if (bytes < 0L) {
      return "unknown";
    }
    double gibibytes = bytes / (1024.0 * 1024.0 * 1024.0);
    if (gibibytes >= 1.0) {
      return String.format(Locale.ROOT, "%.2f GiB", gibibytes);
    }
    return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
  }

  private static String formatDuration(long seconds) {
    if (seconds < 0L) {
      return "unknown";
    }
    long hours = seconds / 3600L;
    long minutes = (seconds % 3600L) / 60L;
    long remainingSeconds = seconds % 60L;
    return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, remainingSeconds);
  }

  private void stopExperimentRecording() {
    RecordingTap tap = recordingTap;
    if (tap == null || tap.isClosed()) {
      return;
    }
    try {
      tap.stop();
      showCompletion(tap.status());
    } catch (IOException exception) {
      LOGGER.log(Level.SEVERE, "Failed to finalize experiment recording", exception);
      JOptionPane.showMessageDialog(
          this,
          "Recording finalization failed: " + exception.getMessage(),
          "Experiment recording",
          JOptionPane.ERROR_MESSAGE);
    }
  }

  private void showCompletion(RecordingStatus status) {
    String summary =
        String.format(
            Locale.ROOT,
            "%s%n%d of %d blocks written%n%d dropped blocks%n%d continuity gaps%n%s",
            status.state(),
            status.writtenBlocks(),
            status.receivedBlocks(),
            status.droppedBlocks(),
            status.continuityGapCount(),
            status.destination());
    JOptionPane.showMessageDialog(
        this,
        summary,
        "Experiment recording",
        status.state() == RecordingState.COMPLETED
            ? JOptionPane.INFORMATION_MESSAGE
            : JOptionPane.WARNING_MESSAGE);
  }

  private void inspectOrRecoverRecording() {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Inspect AudioAnalyzer recording");
    chooser.setFileFilter(
        new FileNameExtensionFilter(
            "AudioAnalyzer recordings (*.aarec, *.aar)",
            AudioBlockRecordingFormat.FILE_EXTENSION,
            AudioBlockRecordingFormat.LEGACY_FILE_EXTENSION));
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    Path source = chooser.getSelectedFile().toPath();
    try {
      RecordingInspection inspection = AudioBlockRecordingReader.inspect(source);
      if (recoverable(inspection.integrity())) {
        offerRecovery(source, inspection);
      } else {
        JOptionPane.showMessageDialog(
            this,
            inspectionText(inspection),
            "Recording inspection",
            inspection.complete() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
      }
    } catch (IOException exception) {
      LOGGER.log(Level.WARNING, "Recording inspection failed", exception);
      JOptionPane.showMessageDialog(
          this,
          "Recording inspection failed: " + exception.getMessage(),
          "Recording inspection",
          JOptionPane.ERROR_MESSAGE);
    }
  }

  private static boolean recoverable(RecordingIntegrity integrity) {
    return integrity == RecordingIntegrity.RECOVERABLE_INCOMPLETE
        || integrity == RecordingIntegrity.TRUNCATED
        || integrity == RecordingIntegrity.CORRUPT;
  }

  private void offerRecovery(Path source, RecordingInspection inspection) throws IOException {
    int choice =
        JOptionPane.showConfirmDialog(
            this,
            inspectionText(inspection)
                + "\n\nRecover every complete block into a separate finalized file?",
            "Incomplete recording",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);
    if (choice != JOptionPane.YES_OPTION) {
      return;
    }
    JFileChooser targetChooser = new JFileChooser();
    targetChooser.setDialogTitle("Save recovered recording");
    targetChooser.setSelectedFile(new File("recovered.aarec"));
    targetChooser.setFileFilter(
        new FileNameExtensionFilter("AudioAnalyzer recording (*.aarec)", "aarec"));
    if (targetChooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    Path target = ensureExtension(targetChooser.getSelectedFile(), ".aarec").toPath();
    RecordingInspection recovered = AudioBlockRecordingReader.recover(source, target);
    JOptionPane.showMessageDialog(
        this,
        "Recovered " + recovered.blockCount() + " blocks to " + target,
        "Recording recovered",
        JOptionPane.INFORMATION_MESSAGE);
  }

  private static String inspectionText(RecordingInspection inspection) {
    return String.format(
        Locale.ROOT,
        "Integrity: %s%nFormat version: %d%nBlocks: %d%nFrames: %d%nContinuity gaps: %d%nSHA-256:"
            + " %s%n%s",
        inspection.integrity(),
        inspection.formatVersion(),
        inspection.blockCount(),
        inspection.totalFrames(),
        inspection.continuityGapCount(),
        inspection.sha256().isBlank() ? "not available" : inspection.sha256(),
        inspection.detail());
  }

  private void stopRecordingQuietly() {
    RecordingTap tap = recordingTap;
    if (tap == null || tap.isClosed()) {
      return;
    }
    try {
      tap.stop();
    } catch (IOException exception) {
      LOGGER.log(Level.WARNING, "Failed to finalize recording while closing window", exception);
    }
  }

  private JMenu findMenu(String text) {
    JMenuBar menuBar = getJMenuBar();
    if (menuBar == null) {
      return null;
    }
    for (int index = 0; index < menuBar.getMenuCount(); index++) {
      JMenu menu = menuBar.getMenu(index);
      if (menu != null && text.equals(menu.getText())) {
        return menu;
      }
    }
    return null;
  }

  private static JMenuItem requireItem(JMenu menu, String text) {
    for (int index = 0; index < menu.getItemCount(); index++) {
      JMenuItem item = menu.getItem(index);
      if (item != null && text.equals(item.getText())) {
        return item;
      }
    }
    throw new IllegalStateException("Menu item is unavailable: " + text);
  }

  private static void replaceListeners(JMenuItem item, ActionListener replacement) {
    for (ActionListener listener : item.getActionListeners()) {
      item.removeActionListener(listener);
    }
    item.addActionListener(replacement);
  }

  private static File ensureExtension(File file, String extension) {
    return file.getName().toLowerCase(Locale.ROOT).endsWith(extension)
        ? file
        : new File(file.getParentFile(), file.getName() + extension);
  }
}
