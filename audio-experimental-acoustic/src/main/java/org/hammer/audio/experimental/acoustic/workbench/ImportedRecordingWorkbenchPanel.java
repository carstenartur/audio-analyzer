package org.hammer.audio.experimental.acoustic.workbench;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileSystemView;
import org.hammer.audio.experimental.acoustic.dataset.DatasetManifest;
import org.hammer.audio.experimental.acoustic.dataset.DatasetRecording;
import org.hammer.audio.experimental.acoustic.dataset.HumBugDbImporter;
import org.hammer.audio.experimental.acoustic.wingbeat.DatasetWingbeatEvaluationWorkflow;
import org.hammer.audio.experimental.acoustic.wingbeat.RuleBasedWingbeatClassifier;
import org.hammer.audio.experimental.acoustic.wingbeat.WingbeatDataset;

/**
 * Small Swing workbench for browsing imported dataset recordings and replaying analysis on them.
 */
public final class ImportedRecordingWorkbenchPanel extends JPanel {

  private static final Logger LOGGER =
      Logger.getLogger(ImportedRecordingWorkbenchPanel.class.getName());
  private static final long serialVersionUID = 1L;

  private final JTextField datasetPathField;
  private final JComboBox<RecordingItem> recordingCombo;
  private final JTextArea manifestArea;
  private final JTextArea recordingArea;
  private final JTextArea evaluationArea;

  private final transient HumBugDbImporter importer;
  private final transient DatasetWingbeatEvaluationWorkflow workflow;
  private final transient RuleBasedWingbeatClassifier classifier;

  /** Both fields are read and written exclusively on the Swing event dispatch thread. */
  private transient DatasetManifest loadedManifest;

  /** Suppresses the combo-box action listener during programmatic setup. */
  private transient boolean programmaticUpdate;

  /** Create the imported-recording workbench panel. */
  public ImportedRecordingWorkbenchPanel() {
    this(
        new HumBugDbImporter(),
        new DatasetWingbeatEvaluationWorkflow(),
        new RuleBasedWingbeatClassifier());
  }

  ImportedRecordingWorkbenchPanel(
      HumBugDbImporter importer,
      DatasetWingbeatEvaluationWorkflow workflow,
      RuleBasedWingbeatClassifier classifier) {
    super(new BorderLayout(6, 6));
    this.importer = Objects.requireNonNull(importer, "importer");
    this.workflow = Objects.requireNonNull(workflow, "workflow");
    this.classifier = Objects.requireNonNull(classifier, "classifier");
    setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

    datasetPathField = new JTextField(42);
    recordingCombo = new JComboBox<>();
    recordingCombo.setEnabled(false);
    recordingCombo.addActionListener(e -> onComboSelectionChanged());

    manifestArea = newTextArea();
    recordingArea = newTextArea();
    evaluationArea = newTextArea();

    add(buildTopPanel(), BorderLayout.NORTH);
    add(buildCenterPanel(), BorderLayout.CENTER);
  }

  private JPanel buildTopPanel() {
    JPanel panel = new JPanel(new BorderLayout(4, 4));
    panel.setBorder(
        BorderFactory.createTitledBorder("Imported Recording Workbench (local-only HumBugDB)"));

    JPanel importRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    importRow.add(new JLabel("Dataset path:"));
    datasetPathField.setPreferredSize(new Dimension(460, 24));
    importRow.add(datasetPathField);
    JButton browseButton = new JButton("Browse…");
    browseButton.addActionListener(e -> browseForDatasetRoot());
    importRow.add(browseButton);
    JButton importButton = new JButton("Import");
    importButton.addActionListener(e -> importDataset());
    importRow.add(importButton);

    JPanel selectRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    selectRow.add(new JLabel("Recording:"));
    recordingCombo.setPreferredSize(new Dimension(460, 24));
    selectRow.add(recordingCombo);
    JButton replayButton = new JButton("Replay analysis");
    replayButton.addActionListener(e -> refreshSelectedRecording());
    selectRow.add(replayButton);

    panel.add(importRow, BorderLayout.NORTH);
    panel.add(selectRow, BorderLayout.SOUTH);
    return panel;
  }

  private JSplitPane buildCenterPanel() {
    JSplitPane split =
        new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(manifestArea), buildRightPanel());
    split.setResizeWeight(0.35);
    split.setDividerLocation(340);
    return split;
  }

  private JSplitPane buildRightPanel() {
    JSplitPane split =
        new JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            new JScrollPane(recordingArea),
            new JScrollPane(evaluationArea));
    split.setResizeWeight(0.55);
    split.setDividerLocation(300);
    return split;
  }

  private static JTextArea newTextArea() {
    JTextArea area = new JTextArea(18, 60);
    area.setEditable(false);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    return area;
  }

  private void browseForDatasetRoot() {
    JFileChooser chooser = new JFileChooser(FileSystemView.getFileSystemView());
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    chooser.setDialogTitle("Select local HumBugDB root");
    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      datasetPathField.setText(chooser.getSelectedFile().toPath().toAbsolutePath().toString());
    }
  }

  private void importDataset() {
    String value = datasetPathField.getText().trim();
    if (value.isBlank()) {
      manifestArea.setText("Enter a local HumBugDB path first.");
      recordingArea.setText("");
      evaluationArea.setText("");
      return;
    }
    Path root = Path.of(value);
    new SwingWorker<ImportResult, Void>() {
      @Override
      protected ImportResult doInBackground() throws IOException {
        DatasetManifest manifest = importer.importFrom(root);
        WingbeatDataset.Evaluation evaluation = null;
        if (!manifest.recordings().isEmpty()) {
          evaluation = workflow.evaluate(manifest, classifier);
        }
        return new ImportResult(manifest, evaluation);
      }

      @Override
      protected void done() {
        try {
          applyImportResult(get());
        } catch (ExecutionException ex) {
          LOGGER.log(Level.WARNING, "Dataset import failed", ex);
          manifestArea.setText("Import failed: " + ex.getCause().getMessage());
          recordingArea.setText("");
          evaluationArea.setText("");
          recordingCombo.removeAllItems();
          recordingCombo.setEnabled(false);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          manifestArea.setText("Import was interrupted.");
        }
      }
    }.execute();
  }

  // PMD.UnusedAssignment: programmaticUpdate = true is read by onComboSelectionChanged() via the
  // action-listener mechanism; PMD cannot follow cross-method field reads within the same class.
  @SuppressWarnings("PMD.UnusedAssignment")
  private void applyImportResult(ImportResult result) {
    loadedManifest = result.manifest();
    programmaticUpdate = true;
    recordingCombo.removeAllItems();
    for (DatasetRecording recording : loadedManifest.recordings()) {
      recordingCombo.addItem(new RecordingItem(recording));
    }
    recordingCombo.setEnabled(recordingCombo.getItemCount() > 0);
    programmaticUpdate = false;

    manifestArea.setText(renderManifest(loadedManifest));
    if (result.evaluation() != null) {
      evaluationArea.setText(
          DatasetWingbeatEvaluationWorkflow.toMarkdownReport(result.evaluation()));
    } else {
      evaluationArea.setText("No recordings to evaluate.");
    }
    if (recordingCombo.getItemCount() > 0) {
      recordingCombo.setSelectedIndex(0);
    } else {
      recordingArea.setText("No recordings imported.");
    }
  }

  /**
   * Load an already-imported manifest directly. Intended for headless tests; performs all I/O
   * synchronously on the calling thread.
   */
  // PMD.UnusedAssignment: see applyImportResult above.
  @SuppressWarnings("PMD.UnusedAssignment")
  void loadManifest(DatasetManifest importedManifest) throws IOException {
    loadedManifest = Objects.requireNonNull(importedManifest, "importedManifest");
    programmaticUpdate = true;
    try {
      recordingCombo.removeAllItems();
      for (DatasetRecording recording : loadedManifest.recordings()) {
        recordingCombo.addItem(new RecordingItem(recording));
      }
      recordingCombo.setEnabled(recordingCombo.getItemCount() > 0);
      manifestArea.setText(renderManifest(loadedManifest));
      if (loadedManifest.recordings().isEmpty()) {
        evaluationArea.setText("No recordings to evaluate.");
      } else {
        WingbeatDataset.Evaluation evaluation = workflow.evaluate(loadedManifest, classifier);
        evaluationArea.setText(DatasetWingbeatEvaluationWorkflow.toMarkdownReport(evaluation));
      }
      if (recordingCombo.getItemCount() > 0) {
        recordingCombo.setSelectedIndex(0);
        RecordingItem firstItem = recordingCombo.getItemAt(0);
        DatasetWingbeatEvaluationWorkflow.RecordingAnalysis analysis =
            workflow.analyzeRecording(loadedManifest, firstItem.recording(), classifier);
        recordingArea.setText(renderRecordingAnalysis(analysis));
      } else {
        recordingArea.setText("No recordings imported.");
      }
    } finally {
      programmaticUpdate = false;
    }
  }

  /**
   * Fires when the combo-box selection changes due to user interaction (not programmatic setup).
   */
  private void onComboSelectionChanged() {
    if (!programmaticUpdate) {
      refreshSelectedRecording();
    }
  }

  private void refreshSelectedRecording() {
    RecordingItem item = (RecordingItem) recordingCombo.getSelectedItem();
    if (item == null || loadedManifest == null) {
      return;
    }
    final DatasetManifest currentManifest = loadedManifest;
    final DatasetRecording currentRecording = item.recording();
    new SwingWorker<DatasetWingbeatEvaluationWorkflow.RecordingAnalysis, Void>() {
      @Override
      protected DatasetWingbeatEvaluationWorkflow.RecordingAnalysis doInBackground()
          throws IOException {
        return workflow.analyzeRecording(currentManifest, currentRecording, classifier);
      }

      @Override
      protected void done() {
        try {
          recordingArea.setText(renderRecordingAnalysis(get()));
        } catch (ExecutionException ex) {
          LOGGER.log(Level.WARNING, "Recording analysis failed", ex);
          recordingArea.setText("Recording analysis failed: " + ex.getCause().getMessage());
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
      }
    }.execute();
  }

  @SuppressWarnings({"PMD.ConsecutiveAppendsShouldReuse", "PMD.ConsecutiveLiteralAppends"})
  private static String renderManifest(DatasetManifest manifest) {
    StringBuilder sb = new StringBuilder(512);
    sb.append("# Imported Dataset\n\n");
    sb.append("- ID: ").append(manifest.descriptor().id()).append('\n');
    sb.append("- Name: ").append(manifest.descriptor().name()).append('\n');
    sb.append("- Root: ").append(manifest.descriptor().localRootPath()).append('\n');
    sb.append("- Recordings: ").append(manifest.recordings().size()).append("\n\n");
    sb.append("| Recording | Duration (s) | Sample rate (Hz) | Labels |\n");
    sb.append("|---|---:|---:|---|\n");
    for (DatasetRecording recording : manifest.recordings()) {
      sb.append("| ")
          .append(recording.recordingId())
          .append(" | ")
          .append(String.format(Locale.ROOT, "%.3f", recording.durationSeconds()))
          .append(" | ")
          .append(String.format(Locale.ROOT, "%.0f", recording.sampleRateHz()))
          .append(" | ")
          .append(recording.labels())
          .append(" |\n");
    }
    return sb.toString();
  }

  @SuppressWarnings({"PMD.ConsecutiveAppendsShouldReuse", "PMD.ConsecutiveLiteralAppends"})
  private static String renderRecordingAnalysis(
      DatasetWingbeatEvaluationWorkflow.RecordingAnalysis analysis) {
    StringBuilder sb = new StringBuilder(512);
    sb.append("# Recording inspection\n\n");
    sb.append("- ID: ").append(analysis.recording().recordingId()).append('\n');
    sb.append("- Audio: ").append(analysis.resolvedAudioPath()).append('\n');
    sb.append("- Ground truth label: ").append(analysis.groundTruthLabel()).append('\n');
    sb.append("- Raw labels: ").append(analysis.recording().labels()).append('\n');
    sb.append("- Metadata: ").append(analysis.recording().metadata()).append('\n');
    sb.append("- Duration: ")
        .append(String.format(Locale.ROOT, "%.3f s", analysis.recording().durationSeconds()))
        .append('\n');
    sb.append("- Sample rate: ")
        .append(String.format(Locale.ROOT, "%.0f Hz", analysis.recording().sampleRateHz()))
        .append('\n');
    sb.append("- Detected fundamental: ")
        .append(String.format(Locale.ROOT, "%.2f Hz", analysis.features().fundamentalFrequencyHz()))
        .append('\n');
    sb.append("- Spectral centroid: ")
        .append(String.format(Locale.ROOT, "%.2f Hz", analysis.features().spectralCentroidHz()))
        .append('\n');
    sb.append("- Bandwidth: ")
        .append(String.format(Locale.ROOT, "%.2f Hz", analysis.features().spectralBandwidthHz()))
        .append('\n');
    sb.append("- SNR: ")
        .append(String.format(Locale.ROOT, "%.3f", analysis.features().signalToNoiseRatio()))
        .append('\n');
    if (analysis.classificationResult() != null) {
      sb.append("- Predicted label: ").append(analysis.classificationResult().label()).append('\n');
      sb.append("- Prediction confidence: ")
          .append(String.format(Locale.ROOT, "%.3f", analysis.classificationResult().confidence()))
          .append('\n');
    }
    sb.append("- Annotations: ").append(analysis.recording().annotations()).append('\n');
    return sb.toString();
  }

  DatasetManifest manifest() {
    return loadedManifest;
  }

  String recordingSummaryText() {
    return recordingArea.getText();
  }

  String evaluationSummaryText() {
    return evaluationArea.getText();
  }

  private record RecordingItem(DatasetRecording recording) {
    @Override
    public String toString() {
      String species = recording.labels().get("species");
      return species == null || species.isBlank()
          ? recording.recordingId()
          : recording.recordingId() + " — " + species;
    }
  }

  private record ImportResult(DatasetManifest manifest, WingbeatDataset.Evaluation evaluation) {}
}
