package org.hammer.audio.experimental.acoustic.workbench;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SpinnerListModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.hammer.audio.acquisition.Microphone;
import org.hammer.audio.experimental.acoustic.benchmark.AlignedSourceObservation;
import org.hammer.audio.experimental.acoustic.benchmark.SnapshotAlignment;
import org.hammer.audio.experimental.acoustic.benchmark.SnapshotGroundTruthAligner;
import org.hammer.audio.experimental.acoustic.benchmark.classifier.ClassifierBenchmarkResult;
import org.hammer.audio.experimental.acoustic.benchmark.classifier.ClassifierBenchmarkRunner;
import org.hammer.audio.experimental.acoustic.benchmark.localization.LocalizationBenchmarkResult;
import org.hammer.audio.experimental.acoustic.benchmark.localization.LocalizationBenchmarkRunner;
import org.hammer.audio.experimental.acoustic.feature.comparison.FeatureDifference;
import org.hammer.audio.experimental.acoustic.feature.comparison.SyntheticRealComparison;
import org.hammer.audio.experimental.acoustic.feature.comparison.SyntheticRealComparisonReport;
import org.hammer.audio.experimental.acoustic.feature.evaluation.FeatureEvaluationEntry;
import org.hammer.audio.experimental.acoustic.feature.evaluation.FeatureEvaluationReport;
import org.hammer.audio.experimental.acoustic.feature.evaluation.FeatureEvaluationService;
import org.hammer.audio.experimental.acoustic.feature.ranking.FeatureRankingEntry;
import org.hammer.audio.experimental.acoustic.feature.ranking.FeatureRankingService;
import org.hammer.audio.experimental.acoustic.scenario.Scenario;
import org.hammer.audio.experimental.acoustic.simulation.AcousticEmitter2D;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios;
import org.hammer.audio.experimental.acoustic.simulation.SimulationScenarios.SimulationScenario;
import org.hammer.audio.experimental.acoustic.tracking.FrequencyCluster;
import org.hammer.audio.experimental.acoustic.tracking.TrackedSource;
import org.hammer.audio.experimental.acoustic.tracking.TrackingSnapshot;
import org.hammer.audio.experimental.acoustic.wingbeat.WingbeatFeatureVector;
import org.hammer.audio.experimental.acoustic.wingbeat.WingbeatLabel;
import org.hammer.audio.geometry.Vector2;

/**
 * Swing panel implementing the acoustic localization research workbench.
 *
 * <p>This panel lets a user:
 *
 * <ol>
 *   <li>Select one of the deterministic {@link SimulationScenarios simulation scenarios}.
 *   <li>Adjust core pipeline parameters (block size, FFT size, max peaks, SNR threshold, frequency
 *       band, TDOA estimator choice, candidate grid resolution, tracker tolerance).
 *   <li>Run the scenario block-by-block through the {@link WorkbenchScenarioRunner} on a background
 *       thread, with live per-frame updates in the log area.
 *   <li>Inspect a 2-D room map showing microphone positions, emitter ground-truth positions and
 *       estimated source tracks after the run.
 *   <li>Export the run summary as Markdown, CSV or JSON via the tabs at the bottom.
 * </ol>
 *
 * <p><strong>Experimental research panel.</strong> This is not a production localization tool. All
 * labels and descriptions preserve the experimental/non-production wording used throughout the
 * {@code audio-experimental-acoustic} module.
 */
public final class AcousticLocalizationWorkbenchPanel extends JPanel {

  private static final Logger LOGGER =
      Logger.getLogger(AcousticLocalizationWorkbenchPanel.class.getName());
  private static final long serialVersionUID = 1L;

  // --- controls ---
  private final JComboBox<ScenarioItem> scenarioCombo;
  private final JSpinner blockSizeSpinner;
  private final JSpinner fftSizeSpinner;
  private final JSpinner maxPeaksSpinner;
  private final JSpinner minSnrSpinner;
  private final JSpinner bandMinSpinner;
  private final JSpinner bandMaxSpinner;
  private final JSpinner gridStepsSpinner;
  private final JComboBox<WorkbenchParameters.TdoaEstimatorType> tdoaCombo;
  private final JButton runButton;
  private final JButton stopButton;

  // --- output ---
  private final JTextArea logArea;
  private final JTextArea markdownArea;
  private final JTextArea csvArea;
  private final JTextArea jsonArea;
  private final JTextArea benchmarkArea;
  private final JTextArea featureRankingArea;
  private final JTextArea featureComparisonArea;
  private final JTextArea syntheticRealArea;
  private final JTextArea classifierComparisonArea;
  private final JTextArea localizationComparisonArea;
  private final JLabel statusLabel;
  private final RoomMapPanel roomMapPanel;

  // --- state ---
  private transient volatile WorkbenchRunResult lastRunResult;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private volatile SwingWorker<WorkbenchRunResult, String> currentWorker;
  private volatile SwingWorker<AnalysisTabContent, Void> analysisWorker;

  private static final class AnalysisTabContent {
    private final String featureRankingText;
    private final String featureComparisonText;
    private final String syntheticRealText;
    private final String classifierComparisonText;
    private final String localizationComparisonText;

    AnalysisTabContent(
        String featureRankingText,
        String featureComparisonText,
        String syntheticRealText,
        String classifierComparisonText,
        String localizationComparisonText) {
      this.featureRankingText = featureRankingText;
      this.featureComparisonText = featureComparisonText;
      this.syntheticRealText = syntheticRealText;
      this.classifierComparisonText = classifierComparisonText;
      this.localizationComparisonText = localizationComparisonText;
    }
  }

  /** Create the workbench panel. All Swing construction is performed on the calling thread. */
  public AcousticLocalizationWorkbenchPanel() {
    super(new BorderLayout(4, 4));
    setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

    // --- scenario combo ---
    List<SimulationScenario> scenarios = SimulationScenarios.all();
    ScenarioItem[] items = new ScenarioItem[scenarios.size()];
    for (int i = 0; i < scenarios.size(); i++) {
      items[i] = new ScenarioItem(scenarios.get(i));
    }
    scenarioCombo = new JComboBox<>(items);

    // --- parameter spinners ---
    blockSizeSpinner = new JSpinner(new SpinnerNumberModel(1024, 256, 8192, 256));
    fftSizeSpinner = new JSpinner(new SpinnerListModel(List.of(256, 512, 1024, 2048, 4096, 8192)));
    fftSizeSpinner.setValue(1024);
    maxPeaksSpinner = new JSpinner(new SpinnerNumberModel(3, 1, 10, 1));
    minSnrSpinner = new JSpinner(new SpinnerNumberModel(2.0, 0.0, 20.0, 0.5));
    bandMinSpinner = new JSpinner(new SpinnerNumberModel(150.0, 50.0, 20000.0, 50.0));
    bandMaxSpinner = new JSpinner(new SpinnerNumberModel(2500.0, 100.0, 24000.0, 100.0));
    gridStepsSpinner = new JSpinner(new SpinnerNumberModel(8, 2, 32, 1));
    tdoaCombo = new JComboBox<>(WorkbenchParameters.TdoaEstimatorType.values());

    // --- buttons ---
    runButton = new JButton("▶ Run");
    stopButton = new JButton("■ Stop");
    stopButton.setEnabled(false);
    runButton.addActionListener(e -> startRun());
    stopButton.addActionListener(e -> stopRun());

    // --- output areas ---
    logArea = newReadOnlyTextArea(20, 60);
    markdownArea = newReadOnlyTextArea(20, 60);
    csvArea = newReadOnlyTextArea(20, 60);
    jsonArea = newReadOnlyTextArea(20, 60);
    benchmarkArea = newReadOnlyTextArea(20, 60);
    featureRankingArea = newReadOnlyTextArea(20, 60);
    featureComparisonArea = newReadOnlyTextArea(20, 60);
    syntheticRealArea = newReadOnlyTextArea(20, 60);
    classifierComparisonArea = newReadOnlyTextArea(20, 60);
    localizationComparisonArea = newReadOnlyTextArea(20, 60);
    statusLabel = new JLabel("Ready — select a scenario and press Run.");
    roomMapPanel = new RoomMapPanel();

    // --- layout ---
    add(buildTopPanel(), BorderLayout.NORTH);
    add(buildCenterSplit(), BorderLayout.CENTER);
    add(buildStatusBar(), BorderLayout.SOUTH);
  }

  // -------------------------------------------------------------------------
  // Layout helpers
  // -------------------------------------------------------------------------

  private JPanel buildTopPanel() {
    JPanel top = new JPanel(new BorderLayout(4, 4));
    top.setBorder(
        BorderFactory.createTitledBorder(
            "Experimental Acoustic Localization Workbench (simulation only)"));
    top.add(buildScenarioRow(), BorderLayout.NORTH);
    top.add(buildParamPanel(), BorderLayout.CENTER);
    top.add(buildButtonRow(), BorderLayout.SOUTH);
    return top;
  }

  private JPanel buildScenarioRow() {
    JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    row.add(new JLabel("Scenario:"));
    scenarioCombo.setPreferredSize(new Dimension(260, 24));
    row.add(scenarioCombo);
    return row;
  }

  private JPanel buildParamPanel() {
    JPanel p = new JPanel(new GridBagLayout());
    GridBagConstraints lc = new GridBagConstraints();
    lc.anchor = GridBagConstraints.WEST;
    lc.insets = new Insets(2, 4, 2, 2);
    GridBagConstraints vc = new GridBagConstraints();
    vc.fill = GridBagConstraints.HORIZONTAL;
    vc.weightx = 0.15;
    vc.insets = new Insets(2, 0, 2, 8);

    int row = 0;
    addParam(p, lc, vc, row, 0, "Block size:", blockSizeSpinner);
    addParam(p, lc, vc, row, 3, "FFT size:", fftSizeSpinner);
    row++;
    addParam(p, lc, vc, row, 0, "Max peaks:", maxPeaksSpinner);
    addParam(p, lc, vc, row, 3, "Min SNR:", minSnrSpinner);
    row++;
    addParam(p, lc, vc, row, 0, "Band min (Hz):", bandMinSpinner);
    addParam(p, lc, vc, row, 3, "Band max (Hz):", bandMaxSpinner);
    row++;
    addParam(p, lc, vc, row, 0, "Grid steps:", gridStepsSpinner);
    addParam(p, lc, vc, row, 3, "TDOA estimator:", tdoaCombo);

    return p;
  }

  private static void addParam(
      JPanel panel,
      GridBagConstraints lc,
      GridBagConstraints vc,
      int row,
      int colOffset,
      String label,
      java.awt.Component field) {
    lc.gridx = colOffset;
    lc.gridy = row;
    panel.add(new JLabel(label), lc);
    vc.gridx = colOffset + 1;
    vc.gridy = row;
    panel.add(field, vc);
  }

  private JPanel buildButtonRow() {
    JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    row.add(runButton);
    row.add(stopButton);
    return row;
  }

  private JSplitPane buildCenterSplit() {
    JTabbedPane outputTabs = buildOutputTabs();
    JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, outputTabs, roomMapPanel);
    split.setResizeWeight(0.55);
    split.setDividerLocation(460);
    return split;
  }

  private JTabbedPane buildOutputTabs() {
    JTabbedPane tabs = new JTabbedPane();
    tabs.addTab("Log", new JScrollPane(logArea));
    tabs.addTab("Benchmark", new JScrollPane(benchmarkArea));
    tabs.addTab("Markdown", new JScrollPane(markdownArea));
    tabs.addTab("CSV", new JScrollPane(csvArea));
    tabs.addTab("JSON-lines", new JScrollPane(jsonArea));
    tabs.addTab("Feature Ranking", new JScrollPane(featureRankingArea));
    tabs.addTab("Feature Comparison", new JScrollPane(featureComparisonArea));
    tabs.addTab("Synthetic vs Real", new JScrollPane(syntheticRealArea));
    tabs.addTab("Classifier Comparison", new JScrollPane(classifierComparisonArea));
    tabs.addTab("Localization Comparison", new JScrollPane(localizationComparisonArea));
    return tabs;
  }

  private JPanel buildStatusBar() {
    JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    bar.setBorder(BorderFactory.createEtchedBorder());
    bar.add(statusLabel);
    return bar;
  }

  private static JTextArea newReadOnlyTextArea(int rows, int cols) {
    JTextArea area = new JTextArea(rows, cols);
    area.setEditable(false);
    area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
    area.setLineWrap(false);
    return area;
  }

  // -------------------------------------------------------------------------
  // Run / stop logic
  // -------------------------------------------------------------------------

  private void startRun() {
    if (running.get()) {
      return;
    }
    ScenarioItem selected = (ScenarioItem) scenarioCombo.getSelectedItem();
    if (selected == null) {
      updateStatus("No scenario selected.");
      return;
    }
    SimulationScenario scenario = selected.scenario;
    WorkbenchParameters params;
    try {
      params = readParameters();
    } catch (IllegalArgumentException ex) {
      updateStatus("Invalid parameters: " + ex.getMessage());
      return;
    }

    logArea.setText("");
    markdownArea.setText("");
    csvArea.setText("");
    jsonArea.setText("");
    benchmarkArea.setText("");
    featureRankingArea.setText("");
    featureComparisonArea.setText("");
    syntheticRealArea.setText("");
    classifierComparisonArea.setText("");
    localizationComparisonArea.setText("");
    SwingWorker<AnalysisTabContent, Void> currentAnalysis = analysisWorker;
    if (currentAnalysis != null) {
      currentAnalysis.cancel(true);
      analysisWorker = null;
    }
    roomMapPanel.clear(scenario);
    setRunning(true);
    appendLog("Starting scenario: " + scenario.name());
    appendLog(
        String.format(
            Locale.ROOT,
            "Parameters: blockSize=%d fftSize=%d maxPeaks=%d minSnr=%.1f band=[%.0f,%.0f] "
                + "grid=%d TDOA=%s",
            params.blockSize(),
            params.fftSize(),
            params.maxPeaks(),
            params.minSnr(),
            params.bandMinHz(),
            params.bandMaxHz(),
            params.candidateGridSteps(),
            params.tdoaEstimatorType()));
    appendLog("---");

    currentWorker = new ScenarioWorker(scenario, params);
    currentWorker.execute();
  }

  private void stopRun() {
    SwingWorker<WorkbenchRunResult, String> w = currentWorker;
    if (w != null) {
      w.cancel(true);
    }
  }

  private void setRunning(boolean isRunning) {
    running.set(isRunning);
    SwingUtilities.invokeLater(
        () -> {
          runButton.setEnabled(!isRunning);
          stopButton.setEnabled(isRunning);
        });
  }

  private void appendLog(String line) {
    SwingUtilities.invokeLater(
        () -> {
          logArea.append(line);
          logArea.append("\n");
          logArea.setCaretPosition(logArea.getDocument().getLength());
        });
  }

  private WorkbenchParameters readParameters() {
    return WorkbenchParameters.defaults()
        .blockSize((Integer) blockSizeSpinner.getValue())
        .fftSize((Integer) fftSizeSpinner.getValue())
        .maxPeaks((Integer) maxPeaksSpinner.getValue())
        .minSnr((Double) minSnrSpinner.getValue())
        .bandMinHz((Double) bandMinSpinner.getValue())
        .bandMaxHz((Double) bandMaxSpinner.getValue())
        .candidateGridSteps((Integer) gridStepsSpinner.getValue())
        .tdoaEstimatorType((WorkbenchParameters.TdoaEstimatorType) tdoaCombo.getSelectedItem())
        .build();
  }

  // -------------------------------------------------------------------------
  // Analysis tab helpers
  // -------------------------------------------------------------------------

  private AnalysisTabContent buildAnalysisTabContent(SimulationScenario completedScenario) {
    // Build a small deterministic demo dataset for feature analysis.
    // Two groups: female-likely (430–550 Hz) and male-likely (580–750 Hz).
    List<WingbeatFeatureVector> synthetic = buildDemoSyntheticVectors();
    List<WingbeatFeatureVector> real = buildDemoRealVectors();
    List<WingbeatFeatureVector> allVectors = new java.util.ArrayList<>();
    allVectors.addAll(synthetic);
    allVectors.addAll(real);
    List<String> allLabels = new java.util.ArrayList<>();
    for (WingbeatFeatureVector v : synthetic) {
      allLabels.add(
          v.fundamentalFrequencyHz() < 560.0
              ? WingbeatLabel.FEMALE_LIKELY
              : WingbeatLabel.MALE_LIKELY);
    }
    for (WingbeatFeatureVector v : real) {
      allLabels.add(
          v.fundamentalFrequencyHz() < 560.0
              ? WingbeatLabel.FEMALE_LIKELY
              : WingbeatLabel.MALE_LIKELY);
    }

    // Feature evaluation
    FeatureEvaluationService evalService = new FeatureEvaluationService();
    FeatureEvaluationReport evalReport = evalService.evaluate(allVectors, allLabels);

    // Feature ranking
    FeatureRankingService rankingService = FeatureRankingService.defaultService();
    List<FeatureRankingEntry> ranking = rankingService.rank(evalReport);

    // Synthetic vs real
    SyntheticRealComparison srComparison = new SyntheticRealComparison();
    SyntheticRealComparisonReport srReport = srComparison.compare(synthetic, real);

    // Classifier comparison
    ClassifierBenchmarkRunner classifierRunner = ClassifierBenchmarkRunner.defaultRunner();
    Map<String, ClassifierBenchmarkResult> classifierResults =
        classifierRunner.run(allVectors, allLabels);

    // Localization comparison (run only the current scenario to keep the workbench responsive)
    LocalizationBenchmarkRunner locRunner = LocalizationBenchmarkRunner.defaultRunner();
    Map<String, List<LocalizationBenchmarkResult>> locResults =
        locRunner.run(List.of(completedScenario));

    return new AnalysisTabContent(
        buildFeatureRankingText(ranking),
        buildFeatureComparisonText(evalReport),
        buildSyntheticRealText(srReport),
        buildClassifierComparisonText(classifierResults),
        buildLocalizationComparisonText(locResults));
  }

  private void populateAnalysisTabsAsync(SimulationScenario completedScenario) {
    featureRankingArea.setText("Computing analysis …");
    featureComparisonArea.setText("Computing analysis …");
    syntheticRealArea.setText("Computing analysis …");
    classifierComparisonArea.setText("Computing analysis …");
    localizationComparisonArea.setText("Computing analysis …");

    SwingWorker<AnalysisTabContent, Void> worker =
        new SwingWorker<>() {
          @Override
          protected AnalysisTabContent doInBackground() {
            return buildAnalysisTabContent(completedScenario);
          }

          @Override
          protected void done() {
            if (isCancelled()) {
              return;
            }
            try {
              AnalysisTabContent content = get();
              featureRankingArea.setText(content.featureRankingText);
              featureComparisonArea.setText(content.featureComparisonText);
              syntheticRealArea.setText(content.syntheticRealText);
              classifierComparisonArea.setText(content.classifierComparisonText);
              localizationComparisonArea.setText(content.localizationComparisonText);
            } catch (java.util.concurrent.ExecutionException ex) {
              LOGGER.log(Level.WARNING, "Failed to compute analysis tabs", ex);
              featureRankingArea.setText("Analysis failed: " + ex.getCause().getMessage());
              featureComparisonArea.setText("Analysis failed: " + ex.getCause().getMessage());
              syntheticRealArea.setText("Analysis failed: " + ex.getCause().getMessage());
              classifierComparisonArea.setText("Analysis failed: " + ex.getCause().getMessage());
              localizationComparisonArea.setText("Analysis failed: " + ex.getCause().getMessage());
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
            }
          }
        };
    analysisWorker = worker;
    worker.execute();
  }

  private static List<WingbeatFeatureVector> buildDemoSyntheticVectors() {
    double[] freqs = {450.0, 470.0, 490.0, 510.0, 530.0, 600.0, 630.0, 660.0, 690.0, 720.0};
    List<WingbeatFeatureVector> list = new java.util.ArrayList<>();
    for (double f : freqs) {
      list.add(
          new WingbeatFeatureVector(
              f, List.of(), List.of(), f, 20.0, 0.0, 5.0, 0.1, 6.0, 1.0, 0.9));
    }
    return list;
  }

  private static List<WingbeatFeatureVector> buildDemoRealVectors() {
    // Slightly different means to simulate real-vs-synthetic divergence
    double[] freqs = {455.0, 475.0, 495.0, 515.0, 535.0, 590.0, 625.0, 655.0, 685.0, 715.0};
    List<WingbeatFeatureVector> list = new java.util.ArrayList<>();
    for (double f : freqs) {
      list.add(
          new WingbeatFeatureVector(
              f, List.of(), List.of(), f, 22.0, 0.0, 6.0, 0.12, 5.5, 1.0, 0.85));
    }
    return list;
  }

  @SuppressWarnings("PMD.ConsecutiveAppendsShouldReuse")
  private static String buildFeatureRankingText(List<FeatureRankingEntry> ranking) {
    StringBuilder sb = new StringBuilder("# Feature Ranking\n\n");
    sb.append(String.format(Locale.ROOT, "%-30s %12s%n", "Feature", "Mean Score"));
    sb.append("-".repeat(45)).append('\n');
    for (FeatureRankingEntry entry : ranking) {
      sb.append(
          String.format(Locale.ROOT, "%-30s %12.4f%n", entry.featureName(), entry.meanScore()));
    }
    return sb.toString();
  }

  @SuppressWarnings("PMD.ConsecutiveAppendsShouldReuse")
  private static String buildFeatureComparisonText(FeatureEvaluationReport report) {
    StringBuilder sb = new StringBuilder("# Feature Evaluation\n\n");
    sb.append(
        String.format(
            Locale.ROOT, "%-30s %10s %10s %10s%n", "Feature", "Mean", "StdDev", "FisherRatio"));
    sb.append("-".repeat(65)).append('\n');
    for (FeatureEvaluationEntry entry : report.entries()) {
      sb.append(
          String.format(
              Locale.ROOT,
              "%-30s %10.3f %10.3f %10.4f%n",
              entry.featureName(),
              entry.statistics().mean(),
              entry.statistics().stdDev(),
              entry.separation().fisherRatio()));
    }
    return sb.toString();
  }

  @SuppressWarnings("PMD.ConsecutiveAppendsShouldReuse")
  private static String buildSyntheticRealText(SyntheticRealComparisonReport report) {
    StringBuilder sb = new StringBuilder(256);
    sb.append("# Synthetic vs Real Comparison\n\n");
    sb.append(
        String.format(
            Locale.ROOT, "%-30s %10s %10s %10s%n", "Feature", "Synthetic", "Real", "Rel.Diff"));
    sb.append("-".repeat(65)).append('\n');
    for (FeatureDifference diff : report.differences()) {
      sb.append(
          String.format(
              Locale.ROOT,
              "%-30s %10.3f %10.3f %9.1f%%%n",
              diff.featureName(),
              diff.syntheticMean(),
              diff.realMean(),
              diff.relativeDifference() * 100.0));
    }
    if (!report.generatorWeaknesses().isEmpty()) {
      sb.append("\n## Generator Weaknesses (rel.diff > ")
          .append(String.format(Locale.ROOT, "%.0f%%", report.weaknessThreshold() * 100.0))
          .append(")\n");
      for (FeatureDifference diff : report.generatorWeaknesses()) {
        sb.append("  - ").append(diff.featureName()).append('\n');
      }
    }
    return sb.toString();
  }

  @SuppressWarnings("PMD.ConsecutiveAppendsShouldReuse")
  private static String buildClassifierComparisonText(
      Map<String, ClassifierBenchmarkResult> results) {
    StringBuilder sb = new StringBuilder("# Classifier Comparison\n\n");
    for (Map.Entry<String, ClassifierBenchmarkResult> entry : results.entrySet()) {
      ClassifierBenchmarkResult r = entry.getValue();
      sb.append("## ").append(entry.getKey()).append('\n');
      sb.append(
          String.format(
              Locale.ROOT,
              "Accuracy: %.3f  MacroF1: %.3f%n",
              r.confusionMatrix().accuracy(),
              r.macroF1()));
      sb.append('\n');
      sb.append(r.confusionMatrix().toMarkdown()).append('\n');
    }
    return sb.toString();
  }

  @SuppressWarnings("PMD.ConsecutiveAppendsShouldReuse")
  private static String buildLocalizationComparisonText(
      Map<String, List<LocalizationBenchmarkResult>> results) {
    StringBuilder sb = new StringBuilder("# Localization Benchmark\n\n");
    for (Map.Entry<String, List<LocalizationBenchmarkResult>> entry : results.entrySet()) {
      sb.append("## ").append(entry.getKey()).append('\n');
      sb.append(
          String.format(
              Locale.ROOT,
              "%-25s %12s %10s %4s %4s%n",
              "Scenario",
              "MeanLocErr(m)",
              "TrackErr",
              "FP",
              "FN"));
      sb.append("-".repeat(60)).append('\n');
      for (LocalizationBenchmarkResult r : entry.getValue()) {
        sb.append(
            String.format(
                Locale.ROOT,
                "%-25s %12.3f %10.3f %4d %4d%n",
                r.scenarioId(),
                r.meanLocalizationErrorMeters(),
                r.trackingError(),
                r.falsePositiveCount(),
                r.falseNegativeCount()));
      }
      sb.append('\n');
    }
    return sb.toString();
  }

  // -------------------------------------------------------------------------
  // Background worker
  // -------------------------------------------------------------------------

  private final class ScenarioWorker extends SwingWorker<WorkbenchRunResult, String> {

    private final SimulationScenario scenario;
    private final WorkbenchParameters params;
    private int blockIndex;

    ScenarioWorker(SimulationScenario scenario, WorkbenchParameters params) {
      this.scenario = scenario;
      this.params = params;
    }

    @Override
    protected WorkbenchRunResult doInBackground() {
      return WorkbenchScenarioRunner.run(
          scenario,
          params,
          (snapshot, idx) -> {
            if (isCancelled()) {
              return;
            }
            this.blockIndex = idx;
            publish(formatBlockLine(snapshot, idx));
          });
    }

    @Override
    protected void process(List<String> chunks) {
      for (String line : chunks) {
        appendLog(line);
      }
      updateStatus("Running block " + blockIndex + " …");
    }

    @Override
    protected void done() {
      setRunning(false);
      if (isCancelled()) {
        updateStatus("Run cancelled.");
        appendLog("--- Run cancelled ---");
        return;
      }
      try {
        WorkbenchRunResult result = get();
        lastRunResult = result;
        appendLog("--- Run complete ---");
        appendLog(
            String.format(
                Locale.ROOT,
                "Blocks: %d  |  Max tracks: %d  |  Distinct IDs: %d  |  Avg proc: %.1f µs",
                result.blockCount(),
                result.maxTracksInAnyFrame(),
                result.distinctTrackCount(),
                result.averageProcessingNanosPerBlock() / 1_000.0));
        updateStatus(
            String.format(
                Locale.ROOT,
                "Done. %d blocks, %d distinct tracks, avg %.1f µs/block.",
                result.blockCount(),
                result.distinctTrackCount(),
                result.averageProcessingNanosPerBlock() / 1_000.0));
        markdownArea.setText(WorkbenchRunExporter.toMarkdown(result));
        csvArea.setText(WorkbenchRunExporter.toCsv(result));
        jsonArea.setText(WorkbenchRunExporter.toJsonLines(result));
        benchmarkArea.setText(WorkbenchRunExporter.toBenchmarkMarkdown(result));
        roomMapPanel.setResult(result);
        populateAnalysisTabsAsync(result.scenario());
      } catch (java.util.concurrent.ExecutionException ex) {
        LOGGER.log(Level.WARNING, "Workbench run failed", ex);
        updateStatus("Run failed: " + ex.getCause().getMessage());
        appendLog("ERROR: " + ex.getCause().getMessage());
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        updateStatus("Run interrupted.");
      }
    }

    private String formatBlockLine(TrackingSnapshot snapshot, int idx) {
      StringBuilder sb = new StringBuilder();
      sb.append(
          String.format(
              Locale.ROOT,
              "Block %4d  frame=%6d  time=%6.1f ms  clusters=%d  tracks=%d  proc=%.1f µs",
              idx,
              snapshot.sourceFrameIndex(),
              snapshot.sourceTimestampNanos() / 1_000_000.0,
              snapshot.clusters().size(),
              snapshot.tracks().size(),
              snapshot.processingNanos() / 1_000.0));
      for (FrequencyCluster c : snapshot.clusters()) {
        sb.append(String.format(Locale.ROOT, "  [%.0f Hz]", c.centerFrequencyHz()));
      }
      for (TrackedSource t : snapshot.tracks()) {
        sb.append(
            String.format(
                Locale.ROOT,
                "  {id=%d f=%.0f Hz pos=(%.2f,%.2f) conf=%.2f n=%d}",
                t.id(),
                t.frequencyHz(),
                t.positionMeters().x(),
                t.positionMeters().y(),
                t.confidence(),
                t.observationCount()));
      }
      return sb.toString();
    }
  }

  private void updateStatus(String text) {
    SwingUtilities.invokeLater(() -> statusLabel.setText(text));
  }

  // -------------------------------------------------------------------------
  // Room map panel (inner class)
  // -------------------------------------------------------------------------

  /**
   * Simple 2-D room-map visualization panel.
   *
   * <p>Renders the room rectangle, microphone positions, emitter ground-truth trajectories,
   * accumulated estimated track paths, and localization error lines between matched ground-truth
   * and estimated positions in the last frame. All rendering is done in Swing paint thread.
   */
  static final class RoomMapPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final int MARGIN = 20;
    private static final int LABEL_OFFSET_X = 9;
    private static final int LABEL_OFFSET_Y = -4;
    private static final Color COLOR_ROOM = new Color(245, 245, 245);
    private static final Color COLOR_MIC = new Color(30, 100, 200);
    private static final Color COLOR_EMITTER = new Color(20, 160, 60);
    private static final Color COLOR_TRACK = new Color(200, 50, 30);
    private static final Color COLOR_TRACK_PATH = new Color(200, 50, 30, 80);
    private static final Color COLOR_ERROR = new Color(220, 140, 0);
    private static final Color COLOR_GRID = new Color(210, 210, 210);

    private transient SimulationScenario scenario;
    private List<TrackedSource> lastTracks = List.of();
    private List<Vector2> gridPoints = List.of();

    /** Accumulated positions per track ID (in appearance order). */
    private Map<Integer, List<Vector2>> trackHistory = new ConcurrentHashMap<>();

    /** Alignment of the last snapshot against ground truth (for error lines). */
    private transient SnapshotAlignment lastAlignment;

    RoomMapPanel() {
      setPreferredSize(new Dimension(300, 300));
      setBorder(BorderFactory.createTitledBorder("Room map (2D, experimental)"));
      setBackground(Color.WHITE);
    }

    void clear(SimulationScenario s) {
      this.scenario = s;
      this.lastTracks = List.of();
      this.gridPoints = List.of();
      this.trackHistory = new ConcurrentHashMap<>();
      this.lastAlignment = null;
      repaint();
    }

    void setResult(WorkbenchRunResult result) {
      this.scenario = result.scenario();
      TrackingSnapshot last =
          result.snapshots().isEmpty()
              ? null
              : result.snapshots().get(result.snapshots().size() - 1);
      this.lastTracks = last == null ? List.of() : last.tracks();

      // Accumulate full track history (all positions per track ID across all frames)
      Map<Integer, List<Vector2>> history = new ConcurrentHashMap<>();
      for (TrackingSnapshot snap : result.snapshots()) {
        for (TrackedSource track : snap.tracks()) {
          history
              .computeIfAbsent(track.id(), ignored -> new ArrayList<>())
              .add(track.positionMeters());
        }
      }
      this.trackHistory = history;

      // Compute ground-truth-to-estimated alignment for the last snapshot (for error lines)
      this.lastAlignment = null;
      if (last != null) {
        try {
          Scenario truth = result.scenario().groundTruth();
          long startTs =
              result.snapshots().isEmpty() ? 0L : result.snapshots().get(0).sourceTimestampNanos();
          this.lastAlignment = new SnapshotGroundTruthAligner().align(truth, last, startTs);
        } catch (RuntimeException ignored) {
          // alignment failure is non-critical; error lines will simply not be drawn
        }
      }

      // Build candidate grid for display
      List<Vector2> grid = new ArrayList<>();
      int steps = result.parameters().candidateGridSteps();
      double w = result.scenario().room().widthMeters();
      double h = result.scenario().room().heightMeters();
      for (int xi = 0; xi <= steps; xi++) {
        for (int yi = 0; yi <= steps; yi++) {
          grid.add(new Vector2(w * xi / steps, h * yi / steps));
        }
      }
      this.gridPoints = List.copyOf(grid);
      repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (scenario == null) {
        g.setFont(g.getFont().deriveFont(Font.ITALIC));
        g.setColor(Color.GRAY);
        g.drawString("No scenario loaded — press Run", MARGIN, getHeight() / 2);
        return;
      }
      Graphics2D g2 = (Graphics2D) g;
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

      double roomW = scenario.room().widthMeters();
      double roomH = scenario.room().heightMeters();
      int canvasW = getWidth() - 2 * MARGIN;
      int canvasH = getHeight() - 2 * MARGIN;
      double scaleX = canvasW / roomW;
      double scaleY = canvasH / roomH;

      paintRoomBackground(g2, canvasW, canvasH);
      paintGridDots(g2, scaleX, scaleY, canvasH);
      paintMicrophones(g2, scaleX, scaleY, canvasH);
      paintEmitterTrajectories(g2, scaleX, scaleY, canvasH);
      paintTrackPaths(g2, scaleX, scaleY, canvasH);
      paintErrorLines(g2, scaleX, scaleY, canvasH);
      paintEstimatedTracks(g2, scaleX, scaleY, canvasH);
      paintLegend(g2);
    }

    private void paintRoomBackground(Graphics2D g2, int canvasW, int canvasH) {
      g2.setColor(COLOR_ROOM);
      g2.fillRect(MARGIN, MARGIN, canvasW, canvasH);
      g2.setColor(Color.DARK_GRAY);
      g2.drawRect(MARGIN, MARGIN, canvasW, canvasH);
    }

    private void paintGridDots(Graphics2D g2, double scaleX, double scaleY, int canvasH) {
      g2.setColor(COLOR_GRID);
      for (Vector2 pt : gridPoints) {
        int px = toPixelX(pt.x(), MARGIN, scaleX);
        int py = toPixelY(pt.y(), MARGIN, scaleY, canvasH);
        g2.fillOval(px - 1, py - 1, 3, 3);
      }
    }

    private void paintMicrophones(Graphics2D g2, double scaleX, double scaleY, int canvasH) {
      g2.setColor(COLOR_MIC);
      for (Microphone mic : scenario.array().microphones()) {
        int px = toPixelX(mic.positionMeters().x(), MARGIN, scaleX);
        int py = toPixelY(mic.positionMeters().y(), MARGIN, scaleY, canvasH);
        g2.fillOval(px - 5, py - 5, 10, 10);
        g2.setColor(Color.WHITE);
        drawCenteredString(g2, mic.id(), px, py);
        g2.setColor(COLOR_MIC);
      }
    }

    private void paintEmitterTrajectories(
        Graphics2D g2, double scaleX, double scaleY, int canvasH) {
      Stroke defaultStroke = g2.getStroke();
      g2.setColor(COLOR_EMITTER);
      g2.setStroke(new BasicStroke(2.0f));
      for (AcousticEmitter2D emitter : scenario.emitters()) {
        int px = toPixelX(emitter.startMeters().x(), MARGIN, scaleX);
        int py = toPixelY(emitter.startMeters().y(), MARGIN, scaleY, canvasH);
        drawTriangle(g2, px, py, 8);
        g2.drawString(
            String.format(Locale.ROOT, "%.0f Hz", emitter.frequencyHz()),
            px + LABEL_OFFSET_X,
            py + LABEL_OFFSET_Y);
        Vector2 vel = emitter.velocityMetersPerSecond();
        if (vel.x() != 0.0 || vel.y() != 0.0) {
          double duration = scenario.durationSeconds();
          int ex = toPixelX(emitter.startMeters().x() + vel.x() * duration, MARGIN, scaleX);
          int ey =
              toPixelY(emitter.startMeters().y() + vel.y() * duration, MARGIN, scaleY, canvasH);
          g2.drawLine(px, py, ex, ey);
          drawTriangle(g2, ex, ey, 5);
        }
      }
      g2.setStroke(defaultStroke);
    }

    private void paintTrackPaths(Graphics2D g2, double scaleX, double scaleY, int canvasH) {
      Stroke defaultStroke = g2.getStroke();
      Stroke dashed =
          new BasicStroke(
              1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[] {4, 4}, 0);
      g2.setColor(COLOR_TRACK_PATH);
      g2.setStroke(dashed);
      for (List<Vector2> path : trackHistory.values()) {
        for (int i = 1; i < path.size(); i++) {
          g2.drawLine(
              toPixelX(path.get(i - 1).x(), MARGIN, scaleX),
              toPixelY(path.get(i - 1).y(), MARGIN, scaleY, canvasH),
              toPixelX(path.get(i).x(), MARGIN, scaleX),
              toPixelY(path.get(i).y(), MARGIN, scaleY, canvasH));
        }
      }
      g2.setStroke(defaultStroke);
    }

    private void paintErrorLines(Graphics2D g2, double scaleX, double scaleY, int canvasH) {
      if (lastAlignment == null) {
        return;
      }
      Stroke defaultStroke = g2.getStroke();
      Stroke errorStroke =
          new BasicStroke(
              1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[] {2, 4}, 0);
      g2.setColor(COLOR_ERROR);
      g2.setStroke(errorStroke);
      for (AlignedSourceObservation obs : lastAlignment.matchedSources()) {
        if (obs.groundTruth().expectedPositionMeters() != null) {
          Vector2 truth = obs.groundTruth().expectedPositionMeters();
          Vector2 est = obs.trackedSource().positionMeters();
          int tx = toPixelX(truth.x(), MARGIN, scaleX);
          int ty = toPixelY(truth.y(), MARGIN, scaleY, canvasH);
          int ex = toPixelX(est.x(), MARGIN, scaleX);
          int ey = toPixelY(est.y(), MARGIN, scaleY, canvasH);
          g2.drawLine(tx, ty, ex, ey);
          double errorM = truth.distanceTo(est);
          g2.drawString(
              String.format(Locale.ROOT, "err=%.2fm", errorM),
              (tx + ex) / 2 + 2,
              (ty + ey) / 2 - 2);
        }
      }
      g2.setStroke(defaultStroke);
    }

    private void paintEstimatedTracks(Graphics2D g2, double scaleX, double scaleY, int canvasH) {
      g2.setColor(COLOR_TRACK);
      for (TrackedSource track : lastTracks) {
        int px = toPixelX(track.positionMeters().x(), MARGIN, scaleX);
        int py = toPixelY(track.positionMeters().y(), MARGIN, scaleY, canvasH);
        g2.fillOval(px - 7, py - 7, 14, 14);
        g2.setColor(Color.WHITE);
        drawCenteredString(g2, String.valueOf(track.id()), px, py);
        g2.setColor(COLOR_TRACK);
        g2.drawString(
            String.format(
                Locale.ROOT, "%.0f Hz  conf=%.2f", track.frequencyHz(), track.confidence()),
            px + LABEL_OFFSET_X,
            py + LABEL_OFFSET_Y);
      }
    }

    private void paintLegend(Graphics2D g2) {
      int lx = MARGIN + 4;
      int ly = getHeight() - MARGIN - 70;
      g2.setColor(new Color(255, 255, 255, 200));
      g2.fillRoundRect(lx - 2, ly - 12, 160, 76, 4, 4);
      g2.setColor(COLOR_MIC);
      g2.fillOval(lx, ly, 8, 8);
      g2.setColor(Color.BLACK);
      g2.drawString("Microphone", lx + 14, ly + 9);
      ly += 16;
      g2.setColor(COLOR_EMITTER);
      drawTriangle(g2, lx + 4, ly + 4, 5);
      g2.setColor(Color.BLACK);
      g2.drawString("Ground truth (trajectory)", lx + 14, ly + 9);
      ly += 16;
      g2.setColor(COLOR_TRACK);
      g2.fillOval(lx, ly, 8, 8);
      g2.setColor(Color.BLACK);
      g2.drawString("Estimated track (last frame)", lx + 14, ly + 9);
      ly += 16;
      g2.setColor(COLOR_ERROR);
      g2.drawLine(lx, ly + 4, lx + 10, ly + 4);
      g2.setColor(Color.BLACK);
      g2.drawString("Localization error", lx + 14, ly + 9);
    }

    private static int toPixelX(double meters, int margin, double scale) {
      return margin + (int) Math.round(meters * scale);
    }

    private static int toPixelY(double meters, int margin, double scale, int canvasH) {
      // flip Y so that y=0 is at the bottom of the canvas
      return margin + canvasH - (int) Math.round(meters * scale);
    }

    private static void drawCenteredString(Graphics2D g2, String text, int cx, int cy) {
      Font f = g2.getFont().deriveFont(Font.BOLD, 9f);
      FontMetrics fm = g2.getFontMetrics(f);
      Font orig = g2.getFont();
      g2.setFont(f);
      int tx = cx - fm.stringWidth(text) / 2;
      int ty = cy + fm.getAscent() / 2 - 1;
      g2.drawString(text, tx, ty);
      g2.setFont(orig);
    }

    private static void drawTriangle(Graphics2D g2, int cx, int cy, int r) {
      int[] xs = {cx, cx - r, cx + r};
      int[] ys = {cy - r, cy + r, cy + r};
      g2.fillPolygon(xs, ys, 3);
    }
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /** Wrapper for combo box display. */
  private static final class ScenarioItem {
    final SimulationScenario scenario;

    ScenarioItem(SimulationScenario scenario) {
      this.scenario = Objects.requireNonNull(scenario, "scenario");
    }

    @Override
    public String toString() {
      return scenario.name();
    }
  }

  /** Return the last completed run result, or {@code null} if no run has completed yet. */
  public WorkbenchRunResult lastResult() {
    return lastRunResult;
  }
}
