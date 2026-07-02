package org.hammer.audio.experimental.acoustic.workbench;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;
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
import org.hammer.audio.experimental.acoustic.dataset.DatasetAnalytics;
import org.hammer.audio.experimental.acoustic.dataset.DatasetManifest;
import org.hammer.audio.experimental.acoustic.dataset.DatasetRecording;
import org.hammer.audio.experimental.acoustic.dataset.HumBugDbImporter;
import org.hammer.audio.experimental.acoustic.feature.comparison.FeatureDifference;
import org.hammer.audio.experimental.acoustic.simulation.WingbeatSignalParameters;
import org.hammer.audio.experimental.acoustic.simulation.calibration.CalibrationResult;
import org.hammer.audio.experimental.acoustic.simulation.calibration.GeneratorCalibrationService;
import org.hammer.audio.experimental.acoustic.wingbeat.DatasetWingbeatEvaluationWorkflow;
import org.hammer.audio.experimental.acoustic.wingbeat.RuleBasedWingbeatClassifier;
import org.hammer.audio.experimental.acoustic.wingbeat.WingbeatDataset;
import org.hammer.audio.experimental.acoustic.wingbeat.WingbeatFeatureVector;

/**
 * Small Swing workbench for browsing imported dataset recordings and replaying analysis on them.
 */
@SuppressWarnings("PMD.CouplingBetweenObjects")
public final class ImportedRecordingWorkbenchPanel extends JPanel {

  private static final Logger LOGGER =
      Logger.getLogger(ImportedRecordingWorkbenchPanel.class.getName());
  private static final long serialVersionUID = 1L;

  private final JTextField datasetPathField;
  private final JComboBox<RecordingItem> recordingCombo;
  private final JTextArea manifestArea;
  private final JTextArea analyticsArea;
  private final JTextArea histogramArea;
  private final JTextArea recordingArea;
  private final JTextArea evaluationArea;
  private final JTextArea calibrationArea;
  private final JComboBox<String> calibrationScopeCombo;

  private final transient HumBugDbImporter importer;
  private final transient DatasetWingbeatEvaluationWorkflow workflow;
  private final transient RuleBasedWingbeatClassifier classifier;

  /** Both fields are read and written exclusively on the Swing event dispatch thread. */
  private transient DatasetManifest loadedManifest;

  private transient List<DatasetWingbeatEvaluationWorkflow.RecordingAnalysis> loadedAnalyses =
      List.of();

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
    analyticsArea = newTextArea();
    histogramArea = newTextArea();
    recordingArea = newTextArea();
    evaluationArea = newTextArea();
    calibrationArea = newTextArea();
    calibrationScopeCombo =
        new JComboBox<>(new String[] {"All imported recordings", "Selected recording only"});

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
    selectRow.add(new JLabel("Calibration scope:"));
    calibrationScopeCombo.setPreferredSize(new Dimension(220, 24));
    selectRow.add(calibrationScopeCombo);
    JButton calibrationButton = new JButton("Run calibration");
    calibrationButton.addActionListener(e -> runCalibration());
    selectRow.add(calibrationButton);

    panel.add(importRow, BorderLayout.NORTH);
    panel.add(selectRow, BorderLayout.SOUTH);
    return panel;
  }

  private JSplitPane buildCenterPanel() {
    JSplitPane split =
        new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildLeftPanel(), buildRightPanel());
    split.setResizeWeight(0.35);
    split.setDividerLocation(340);
    return split;
  }

  private JSplitPane buildLeftPanel() {
    JSplitPane upper =
        new JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            new JScrollPane(manifestArea),
            new JScrollPane(analyticsArea));
    upper.setResizeWeight(0.5);
    upper.setDividerLocation(240);
    JSplitPane split =
        new JSplitPane(JSplitPane.VERTICAL_SPLIT, upper, new JScrollPane(histogramArea));
    split.setResizeWeight(0.67);
    split.setDividerLocation(480);
    return split;
  }

  private JSplitPane buildRightPanel() {
    JSplitPane upper =
        new JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            new JScrollPane(recordingArea),
            new JScrollPane(evaluationArea));
    upper.setResizeWeight(0.55);
    upper.setDividerLocation(300);
    JSplitPane split =
        new JSplitPane(JSplitPane.VERTICAL_SPLIT, upper, new JScrollPane(calibrationArea));
    split.setResizeWeight(0.7);
    split.setDividerLocation(520);
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
        String histogramsReport = "";
        List<DatasetWingbeatEvaluationWorkflow.RecordingAnalysis> analyses = List.of();
        if (!manifest.recordings().isEmpty()) {
          evaluation = workflow.evaluate(manifest, classifier);
          analyses = workflow.analyzeAll(manifest, null);
          histogramsReport =
              DatasetWingbeatEvaluationWorkflow.toHistogramMarkdown(
                  DatasetWingbeatEvaluationWorkflow.computeHistograms(analyses));
        }
        String analyticsReport = DatasetAnalytics.compute(manifest).toMarkdownReport();
        return new ImportResult(manifest, evaluation, analyses, analyticsReport, histogramsReport);
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
          histogramArea.setText("");
          calibrationArea.setText("");
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
    loadedAnalyses = result.analyses();
    programmaticUpdate = true;
    recordingCombo.removeAllItems();
    for (DatasetRecording recording : loadedManifest.recordings()) {
      recordingCombo.addItem(new RecordingItem(recording));
    }
    recordingCombo.setEnabled(recordingCombo.getItemCount() > 0);
    programmaticUpdate = false;

    manifestArea.setText(renderManifest(loadedManifest));
    analyticsArea.setText(result.analyticsReport());
    histogramArea.setText(result.histogramsReport());
    if (result.evaluation() != null) {
      evaluationArea.setText(
          DatasetWingbeatEvaluationWorkflow.toMarkdownReport(result.evaluation()));
    } else {
      evaluationArea.setText("No recordings to evaluate.");
    }
    calibrationArea.setText("Select a calibration scope and run calibration.");
    if (recordingCombo.getItemCount() > 0) {
      recordingCombo.setSelectedIndex(0);
    } else {
      recordingArea.setText("No recordings imported.");
      calibrationArea.setText("Calibration unavailable: dataset contains no recordings.");
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
      analyticsArea.setText(DatasetAnalytics.compute(loadedManifest).toMarkdownReport());
      if (loadedManifest.recordings().isEmpty()) {
        loadedAnalyses = List.of();
        evaluationArea.setText("No recordings to evaluate.");
        histogramArea.setText("No recordings to analyze.");
        calibrationArea.setText("Calibration unavailable: dataset contains no recordings.");
      } else {
        WingbeatDataset.Evaluation evaluation = workflow.evaluate(loadedManifest, classifier);
        evaluationArea.setText(DatasetWingbeatEvaluationWorkflow.toMarkdownReport(evaluation));
        loadedAnalyses = workflow.analyzeAll(loadedManifest, null);
        histogramArea.setText(
            DatasetWingbeatEvaluationWorkflow.toHistogramMarkdown(
                DatasetWingbeatEvaluationWorkflow.computeHistograms(loadedAnalyses)));
        calibrationArea.setText(buildCalibrationReport(loadedManifest, loadedAnalyses, false));
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

  private void runCalibration() {
    if (loadedManifest == null) {
      calibrationArea.setText("Import a dataset first.");
      return;
    }
    if (loadedManifest.recordings().isEmpty()) {
      calibrationArea.setText("Calibration unavailable: dataset contains no recordings.");
      return;
    }
    calibrationArea.setText("Running calibration …");
    final boolean selectedOnly = calibrationScopeCombo.getSelectedIndex() == 1;
    final DatasetManifest currentManifest = loadedManifest;
    final DatasetRecording selectedRecording = selectedRecording();
    final List<DatasetWingbeatEvaluationWorkflow.RecordingAnalysis> currentAnalyses =
        loadedAnalyses;
    new SwingWorker<String, Void>() {
      @Override
      protected String doInBackground() throws IOException {
        return buildCalibrationReportForScope(
            currentManifest, selectedRecording, selectedOnly, currentAnalyses);
      }

      @Override
      protected void done() {
        try {
          calibrationArea.setText(get());
        } catch (ExecutionException ex) {
          LOGGER.log(Level.WARNING, "Calibration failed", ex);
          Throwable cause = ex.getCause() == null ? ex : ex.getCause();
          String message = cause.getMessage();
          calibrationArea.setText(
              "Calibration failed: " + (message == null || message.isBlank() ? cause : message));
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          calibrationArea.setText("Calibration was interrupted.");
        }
      }
    }.execute();
  }

  /**
   * Run calibration synchronously for tests/headless usage.
   *
   * @param selectedOnly when true, calibrates only against the currently selected recording
   */
  void runCalibrationHeadless(boolean selectedOnly) throws IOException {
    DatasetManifest manifest = Objects.requireNonNull(loadedManifest, "loadedManifest");
    DatasetRecording selected = selectedRecording();
    calibrationArea.setText(
        buildCalibrationReportForScope(manifest, selected, selectedOnly, loadedAnalyses));
  }

  private String buildCalibrationReportForScope(
      DatasetManifest manifest,
      DatasetRecording selectedRecording,
      boolean selectedOnly,
      List<DatasetWingbeatEvaluationWorkflow.RecordingAnalysis> preloadedAnalyses)
      throws IOException {
    List<DatasetWingbeatEvaluationWorkflow.RecordingAnalysis> analyses =
        selectedOnly ? analyzeSelectedOnly(manifest, selectedRecording) : preloadedAnalyses;
    if (!selectedOnly && analyses.isEmpty() && !manifest.recordings().isEmpty()) {
      analyses = workflow.analyzeAll(manifest, null);
      loadedAnalyses = analyses;
    }
    return buildCalibrationReport(manifest, analyses, selectedOnly);
  }

  private List<DatasetWingbeatEvaluationWorkflow.RecordingAnalysis> analyzeSelectedOnly(
      DatasetManifest manifest, DatasetRecording selectedRecording) throws IOException {
    if (selectedRecording == null) {
      throw new IllegalArgumentException(
          "Please select a recording before running calibration on a subset.");
    }
    return List.of(workflow.analyzeRecording(manifest, selectedRecording, null));
  }

  private DatasetRecording selectedRecording() {
    RecordingItem item = (RecordingItem) recordingCombo.getSelectedItem();
    return item == null ? null : item.recording();
  }

  @SuppressWarnings({
    "PMD.ConsecutiveAppendsShouldReuse",
    "PMD.ConsecutiveLiteralAppends",
    "PMD.NcssCount",
    "PMD.NPathComplexity"
  })
  private static String buildCalibrationReport(
      DatasetManifest manifest,
      List<DatasetWingbeatEvaluationWorkflow.RecordingAnalysis> analyses,
      boolean selectedOnly) {
    if (analyses.isEmpty()) {
      return "Calibration unavailable: no extracted features were produced.";
    }
    List<WingbeatFeatureVector> real =
        analyses.stream()
            .map(DatasetWingbeatEvaluationWorkflow.RecordingAnalysis::features)
            .toList();
    WingbeatSignalParameters baseline = WingbeatSignalParameters.mosquitoLike(500.0);
    CalibrationResult calibrationResult =
        new GeneratorCalibrationService().calibrate(baseline, real);

    int missingSpeciesCount = 0;
    int missingGenderCount = 0;
    int missingAnnotationCount = 0;
    for (DatasetWingbeatEvaluationWorkflow.RecordingAnalysis analysis : analyses) {
      DatasetRecording recording = analysis.recording();
      if (!recording.labels().containsKey("species")) {
        missingSpeciesCount++;
      }
      if (!recording.labels().containsKey("gender")) {
        missingGenderCount++;
      }
      if (recording.annotations().isEmpty()) {
        missingAnnotationCount++;
      }
    }
    StringBuilder sb = new StringBuilder(1024);
    sb.append("# Generator Calibration (Imported Dataset)\n\n");
    sb.append("## Dataset Provenance\n\n");
    sb.append("- Dataset ID: ").append(manifest.descriptor().id()).append('\n');
    sb.append("- Dataset name: ").append(manifest.descriptor().name()).append('\n');
    sb.append("- Dataset source: ").append(manifest.descriptor().source()).append('\n');
    sb.append("- Dataset root: ").append(manifest.descriptor().localRootPath()).append('\n');
    sb.append("- Scope: ")
        .append(selectedOnly ? "Selected recording only" : "All imported recordings")
        .append('\n');
    sb.append("- Imported recordings: ").append(manifest.recordings().size()).append('\n');
    sb.append("- Calibrated recordings: ").append(analyses.size()).append('\n');
    sb.append("- Extracted feature vectors: ").append(real.size()).append('\n');
    sb.append("- Feature extraction: ")
        .append(DatasetWingbeatEvaluationWorkflow.defaultFeatureExtractionProvenance())
        .append('\n');

    sb.append("\n## Dataset Warnings\n\n");
    boolean hasWarnings = false;
    if (analyses.size() < 3) {
      sb.append(
          "- Tiny dataset warning: fewer than 3 recordings; calibration may be unstable but remains"
              + " deterministic.\n");
      hasWarnings = true;
    }
    if (missingSpeciesCount > 0 || missingGenderCount > 0 || missingAnnotationCount > 0) {
      StringJoiner joiner = new StringJoiner("; ");
      if (missingSpeciesCount > 0) {
        joiner.add(String.format(Locale.ROOT, "%d missing species label(s)", missingSpeciesCount));
      }
      if (missingGenderCount > 0) {
        joiner.add(String.format(Locale.ROOT, "%d missing gender label(s)", missingGenderCount));
      }
      if (missingAnnotationCount > 0) {
        joiner.add(
            String.format(Locale.ROOT, "%d without annotation span(s)", missingAnnotationCount));
      }
      sb.append("- Partial annotation warning: ").append(joiner).append('\n');
      hasWarnings = true;
    }
    if (!hasWarnings) {
      sb.append("- No warnings.\n");
    }

    sb.append("\n## Baseline Parameters\n\n");
    appendParamRow(
        sb,
        "fundamentalFrequencyHz",
        calibrationResult.baselineParameters().fundamentalFrequencyHz());
    appendParamRow(sb, "harmonicCount", calibrationResult.baselineParameters().harmonicCount());
    appendParamRow(sb, "jitterHz", calibrationResult.baselineParameters().jitterHz());
    appendParamRow(sb, "modulationDepth", calibrationResult.baselineParameters().modulationDepth());
    appendParamRow(sb, "noiseAmplitude", calibrationResult.baselineParameters().noiseAmplitude());
    sb.append("\n## Calibrated Parameters\n\n");
    appendParamRow(
        sb,
        "fundamentalFrequencyHz",
        calibrationResult.calibratedParameters().fundamentalFrequencyHz());
    appendParamRow(sb, "harmonicCount", calibrationResult.calibratedParameters().harmonicCount());
    appendParamRow(sb, "jitterHz", calibrationResult.calibratedParameters().jitterHz());
    appendParamRow(
        sb, "modulationDepth", calibrationResult.calibratedParameters().modulationDepth());
    appendParamRow(sb, "noiseAmplitude", calibrationResult.calibratedParameters().noiseAmplitude());
    sb.append("\n## Feature Deviation Report\n\n");
    sb.append(
        String.format(
            Locale.ROOT, "%-30s %10s %10s %10s%n", "Feature", "Before", "After", "Improvement"));
    sb.append("-".repeat(65)).append('\n');
    List<FeatureDifference> beforeDiffs = calibrationResult.beforeCalibration().differences();
    List<FeatureDifference> afterDiffs = calibrationResult.afterCalibration().differences();
    int size = Math.min(beforeDiffs.size(), afterDiffs.size());
    for (int i = 0; i < size; i++) {
      double before = beforeDiffs.get(i).relativeDifference();
      double after = afterDiffs.get(i).relativeDifference();
      sb.append(
          String.format(
              Locale.ROOT,
              "%-30s %9.1f%% %9.1f%% %9.1f%%%n",
              beforeDiffs.get(i).featureName(),
              before * 100.0,
              after * 100.0,
              (before - after) * 100.0));
    }
    sb.append('\n');
    sb.append(
        String.format(
            Locale.ROOT, "Overall improvement: %.1f%%%n", calibrationResult.improvement() * 100.0));
    return sb.toString();
  }

  private static void appendParamRow(StringBuilder sb, String name, double value) {
    sb.append(String.format(Locale.ROOT, "  %-30s %.6f%n", name + ":", value));
  }

  private static void appendParamRow(StringBuilder sb, String name, int value) {
    sb.append(String.format(Locale.ROOT, "  %-30s %d%n", name + ":", value));
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

  private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
    in.defaultReadObject();
    loadedAnalyses = List.of();
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

  String analyticsText() {
    return analyticsArea.getText();
  }

  String histogramText() {
    return histogramArea.getText();
  }

  String calibrationText() {
    return calibrationArea.getText();
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

  private record ImportResult(
      DatasetManifest manifest,
      WingbeatDataset.Evaluation evaluation,
      List<DatasetWingbeatEvaluationWorkflow.RecordingAnalysis> analyses,
      String analyticsReport,
      String histogramsReport) {

    private ImportResult {
      Objects.requireNonNull(manifest, "manifest");
      Objects.requireNonNull(analyses, "analyses");
      Objects.requireNonNull(analyticsReport, "analyticsReport");
      Objects.requireNonNull(histogramsReport, "histogramsReport");
      // evaluation is intentionally nullable for empty manifests.
    }
  }
}
