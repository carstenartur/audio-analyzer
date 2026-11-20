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
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.hammer.audio.ActiveAudioCaptureRegistry;
import org.hammer.audio.AudioCaptureService;
import org.hammer.audio.RecordingPreflight;
import org.hammer.audio.RecordingTap;
import org.hammer.audio.experiment.document.ExperimentDocumentPreview;
import org.hammer.audio.experiment.document.ExperimentDocumentSession;
import org.hammer.audio.experiment.operations.MicrophoneExperimentService;
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
// The desktop host intentionally composes document, capture and recording UI services.
@SuppressWarnings("PMD.CouplingBetweenObjects")
public final class ExperimentAudioAnalyseFrame extends AudioAnalyseFrame {

  private static final long serialVersionUID = 1L;
  private static final Logger LOGGER =
      Logger.getLogger(ExperimentAudioAnalyseFrame.class.getName());
  private static final String RECORDING_DIALOG_TITLE = "Experiment recording";

  private final RecordingStatusPanel recordingStatusPanel = new RecordingStatusPanel();
  private volatile String documentPluginSummary =
      "Installed plugin catalogue has not been supplied";
  private final ExperimentDocumentHealthPanel documentHealthPanel =
      new ExperimentDocumentHealthPanel(this::documentReadinessInput);
  private transient RecordingTap recordingTap;
  private transient volatile Supplier<ExperimentDocumentSession.Context> documentContextSupplier;
  private transient JFrame microphoneWindow;
  private volatile boolean recordingStartPending;
  private volatile boolean closingRequested;
  private boolean closeInProgress;

  /** Attach the same session snapshot provider used by the desktop document actions. */
  public void setDocumentContextSupplier(Supplier<ExperimentDocumentSession.Context> supplier) {
    documentContextSupplier = Objects.requireNonNull(supplier, "supplier");
  }

  /** Capture document facts without treating a candidate preview's bindings as current. */
  public ExperimentDocumentSession.Context documentContext() {
    Supplier<ExperimentDocumentSession.Context> supplier = documentContextSupplier;
    return supplier == null ? ExperimentDocumentSession.Context.empty() : supplier.get();
  }

  /** Attach installed package/section versions from the same document service used by Open/Save. */
  public void setDocumentPluginSummary(String summary) {
    documentPluginSummary = java.util.Objects.requireNonNull(summary, "summary");
  }

  private org.hammer.audio.experiment.health.ExperimentDocumentHealthProjector.Input
      documentReadinessInput() {
    ExperimentDocumentSession.Context context = documentContext();
    ExperimentDocumentPreview preview = context.preview();
    String workflowHash =
        preview == null ? "Not configured" : preview.document().workflow().sha256();
    return new org.hammer.audio.experiment.health.ExperimentDocumentHealthProjector.Input(
        preview,
        context.status().path(),
        context.status().dirty(),
        context.revision(),
        workflowHash,
        // The retained session preview passed ExperimentDocumentCodec's WorkflowValidator.
        // Swing edits cannot replace this snapshot until that same validation succeeds.
        java.util.List.of(),
        documentPluginSummary);
  }

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
    setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
    installStatusStrip();
    replaceRecordingActions();
    installMicrophoneWorkflow();
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent event) {
            closingRequested = true;
            closeAfterRecording();
          }

          @Override
          public void windowClosed(WindowEvent event) {
            documentHealthPanel.close();
          }
        });
  }

  private void installMicrophoneWorkflow() {
    JMenu experiment = new JMenu("Experiment");
    JMenuItem microphone = new JMenuItem("Microphone-array workflow…");
    microphone.setToolTipText(
        "Define profiles, review readiness, run simulation/replay/live and export evidence.");
    microphone.addActionListener(
        event -> {
          if (microphoneWindow != null && microphoneWindow.isDisplayable()) {
            microphoneWindow.toFront();
            return;
          }
          String configured = System.getProperty("workbench.experiments.directory", "");
          Path directory =
              configured.isBlank()
                  ? Path.of(System.getProperty("user.home"), ".audio-analyzer", "experiments")
                  : Path.of(configured);
          MicrophoneExperimentPanel panel =
              new MicrophoneExperimentPanel(new MicrophoneExperimentService(directory));
          JFrame window =
              new JFrame("Microphone experiment — source, readiness, runtime and evidence");
          microphoneWindow = window;
          window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
          window.setContentPane(panel);
          window.setSize(1180, 820);
          window.setLocationRelativeTo(this);
          window.addWindowListener(
              new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent closed) {
                  panel.closeWhenFinished(window::dispose);
                }
              });
          window.setVisible(true);
        });
    experiment.add(microphone);
    getJMenuBar().add(experiment);
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
    south.add(documentHealthPanel, BorderLayout.CENTER);
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
    if (recordingStartPending) {
      return;
    }
    if (recordingTap != null && !recordingTap.isClosed()) {
      JOptionPane.showMessageDialog(
          this,
          "A recording is already in progress: " + recordingTap.file(),
          RECORDING_DIALOG_TITLE,
          JOptionPane.WARNING_MESSAGE);
      return;
    }
    AudioCaptureService service = ActiveAudioCaptureRegistry.current().orElse(null);
    if (service == null) {
      JOptionPane.showMessageDialog(
          this,
          "Start a live, demo or replay source before starting the experiment recording.",
          RECORDING_DIALOG_TITLE,
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
    recordingStartPending = true;
    new SwingWorker<RecordingStorageStatus, Void>() {
      @Override
      protected RecordingStorageStatus doInBackground() throws IOException {
        return RecordingPreflight.inspect(service, selected.toPath());
      }

      @Override
      protected void done() {
        if (closingRequested) {
          recordingStartPending = false;
          closeAfterRecording();
          return;
        }
        try {
          confirmAndStartRecording(service, selected, get());
        } catch (InterruptedException | ExecutionException | RuntimeException exception) {
          restoreInterrupt(exception);
          recordingStartPending = false;
          JOptionPane.showMessageDialog(
              ExperimentAudioAnalyseFrame.this,
              "Recording preflight failed: " + exception.getMessage(),
              RECORDING_DIALOG_TITLE,
              JOptionPane.ERROR_MESSAGE);
        } finally {
          if (closingRequested) {
            closeAfterRecording();
          }
        }
      }
    }.execute();
  }

  private void confirmAndStartRecording(
      AudioCaptureService service, File selected, RecordingStorageStatus preflight) {
    if (!preflight.writable() || preflight.level() == RecordingStorageLevel.CRITICAL) {
      recordingStartPending = false;
      JOptionPane.showMessageDialog(
          this,
          storagePreflightText(preflight),
          "Recording storage is not ready",
          JOptionPane.ERROR_MESSAGE);
      return;
    }
    if (!confirmStorageWarning(preflight)) {
      recordingStartPending = false;
      return;
    }
    new SwingWorker<RecordingTap, Void>() {
      @Override
      protected RecordingTap doInBackground() throws IOException {
        return RecordingTap.start(service, selected.toPath());
      }

      @Override
      protected void done() {
        recordingStartPending = false;
        try {
          recordingTap = get();
          recordingTap.addStatusListener(
              status ->
                  SwingUtilities.invokeLater(() -> recordingStatusPanel.updateStatus(status)));
        } catch (InterruptedException | ExecutionException | RuntimeException exception) {
          restoreInterrupt(exception);
          JOptionPane.showMessageDialog(
              ExperimentAudioAnalyseFrame.this,
              "Recording could not start: " + exception.getMessage(),
              RECORDING_DIALOG_TITLE,
              JOptionPane.ERROR_MESSAGE);
        } finally {
          if (closingRequested) {
            closeAfterRecording();
          }
        }
      }
    }.execute();
  }

  private boolean confirmStorageWarning(RecordingStorageStatus preflight) {
    int choice =
        JOptionPane.showConfirmDialog(
            this,
            storagePreflightText(preflight)
                + "\n\nStart recording with this advisory capacity estimate?",
            "Recording storage preflight",
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
    new SwingWorker<RecordingStatus, Void>() {
      @Override
      protected RecordingStatus doInBackground() throws IOException {
        tap.stop();
        return tap.status();
      }

      @Override
      protected void done() {
        try {
          showCompletion(get());
        } catch (InterruptedException | ExecutionException | RuntimeException exception) {
          restoreInterrupt(exception);
          JOptionPane.showMessageDialog(
              ExperimentAudioAnalyseFrame.this,
              "Recording finalization failed: " + exception.getMessage(),
              RECORDING_DIALOG_TITLE,
              JOptionPane.ERROR_MESSAGE);
        }
      }
    }.execute();
  }

  private void showCompletion(RecordingStatus status) {
    String summary =
        String.format(
            Locale.ROOT,
            "%s%n%d of %d blocks written%n%d dropped blocks%n%d continuity gaps%n%s%nSize:"
                + " %s%nDuration: %s%nStorage warnings: %s%n%s",
            status.state(),
            status.writtenBlocks(),
            status.receivedBlocks(),
            status.droppedBlocks(),
            status.continuityGapCount(),
            status.destination(),
            formatBytes(status.bytesWritten()),
            formatDuration(status.elapsed().toSeconds()),
            status.storage().warningHistory(),
            status.stopReason());
    JOptionPane.showMessageDialog(
        this,
        summary,
        RECORDING_DIALOG_TITLE,
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
    return integrity == RecordingIntegrity.INCOMPLETE
        || integrity == RecordingIntegrity.RECOVERABLE_INCOMPLETE
        || integrity == RecordingIntegrity.TRUNCATED
        || integrity == RecordingIntegrity.CORRUPT;
  }

  private static void restoreInterrupt(Exception exception) {
    if (exception instanceof InterruptedException) {
      Thread.currentThread().interrupt();
    }
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

  private void closeAfterRecording() {
    if (recordingStartPending || closeInProgress) {
      return;
    }
    closeInProgress = true;
    new SwingWorker<Void, Void>() {
      @Override
      protected Void doInBackground() {
        stopRecordingQuietly();
        return null;
      }

      @Override
      protected void done() {
        dispose();
      }
    }.execute();
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
