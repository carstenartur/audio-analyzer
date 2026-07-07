package org.hammer.audio.workflow.catalog;

import java.util.List;
import org.hammer.audio.workflow.DataTypes;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Port;
import org.hammer.audio.workflow.PortDirection;
import org.hammer.audio.workflow.PortMultiplicity;

/**
 * Factory methods for the first experiment node catalog.
 *
 * <p>Each method returns a fresh {@link Node} prototype that represents one experiment component.
 * The nodes are compatible with the existing {@code audio-core} workflow model.
 *
 * <p><b>Allowed callers</b>: application services and tests. Must not depend on UI, execution
 * runtime, persistence or JGit. The catalog lives in the workflow domain layer.
 *
 * <p>See {@code docs/architecture/experiment-node-catalog.md} for the full catalog specification
 * including valid and invalid connection examples.
 */
public final class ExperimentNodeCatalog {

  // Shared port identifiers used across multiple node types
  private static final String PORT_AUDIO_IN = "audio-in";
  private static final String PORT_AUDIO_OUT = "audio-out";
  private static final String PORT_NAME_AUDIO_IN = "Audio In";
  private static final String PORT_NAME_AUDIO_OUT = "Audio Out";

  private ExperimentNodeCatalog() {
    // utility class
  }

  // -------------------------------------------------------------------------
  // Input nodes
  // -------------------------------------------------------------------------

  /**
   * Creates a {@code RecordingInput} node that supplies a recorded audio dataset.
   *
   * <p>Output: one {@code Dataset} port.
   *
   * @param nodeId stable node identifier
   * @return prototype node
   */
  public static Node recordingInput(String nodeId) {
    return new Node(
        nodeId,
        "recording-input",
        "Recording Input",
        List.of(),
        List.of(
            output(
                PORT_AUDIO_OUT, "Audio Dataset", DataTypes.DATASET.id(), PortMultiplicity.SINGLE)));
  }

  /**
   * Creates a {@code SyntheticSignalGenerator} node that generates a synthetic audio signal.
   *
   * <p>Output: one {@code AudioBlock} port.
   *
   * @param nodeId stable node identifier
   * @return prototype node
   */
  public static Node syntheticSignalGenerator(String nodeId) {
    return new Node(
        nodeId,
        "synthetic-signal-generator",
        "Synthetic Signal Generator",
        List.of(),
        List.of(
            output(
                "signal-out",
                "Synthetic Signal",
                DataTypes.AUDIO_BLOCK.id(),
                PortMultiplicity.SINGLE)));
  }

  /**
   * Creates a {@code HumBugDbImport} node that imports recordings from the HumBugDB dataset.
   *
   * <p>Output: one {@code Dataset} port.
   *
   * @param nodeId stable node identifier
   * @return prototype node
   */
  public static Node humBugDbImport(String nodeId) {
    return new Node(
        nodeId,
        "humbug-db-import",
        "HumBugDB Import",
        List.of(),
        List.of(
            output(
                "dataset-out", "HumBug Dataset", DataTypes.DATASET.id(), PortMultiplicity.SINGLE)));
  }

  // -------------------------------------------------------------------------
  // DSP nodes
  // -------------------------------------------------------------------------

  /**
   * Creates a {@code Gain} node that applies a gain factor to an audio block.
   *
   * <p>Input: one {@code AudioBlock} port. Output: one {@code AudioBlock} port.
   *
   * @param nodeId stable node identifier
   * @return prototype node
   */
  public static Node gain(String nodeId) {
    return new Node(
        nodeId,
        "gain",
        "Gain",
        List.of(
            input(
                PORT_AUDIO_IN,
                PORT_NAME_AUDIO_IN,
                DataTypes.AUDIO_BLOCK.id(),
                PortMultiplicity.SINGLE)),
        List.of(
            output(
                PORT_AUDIO_OUT,
                PORT_NAME_AUDIO_OUT,
                DataTypes.AUDIO_BLOCK.id(),
                PortMultiplicity.SINGLE)));
  }

  /**
   * Creates a {@code BandpassFilter} node.
   *
   * <p>Input: one {@code AudioBlock}. Output: one {@code AudioBlock}.
   *
   * @param nodeId stable node identifier
   * @return prototype node
   */
  public static Node bandpassFilter(String nodeId) {
    return new Node(
        nodeId,
        "bandpass-filter",
        "Bandpass Filter",
        List.of(
            input(
                PORT_AUDIO_IN,
                PORT_NAME_AUDIO_IN,
                DataTypes.AUDIO_BLOCK.id(),
                PortMultiplicity.SINGLE)),
        List.of(
            output(
                PORT_AUDIO_OUT,
                "Filtered Audio",
                DataTypes.AUDIO_BLOCK.id(),
                PortMultiplicity.SINGLE)));
  }

  /**
   * Creates an {@code FFT} node that transforms an audio block to a spectrum.
   *
   * <p>Input: one {@code AudioBlock}. Output: one {@code Spectrum}.
   *
   * @param nodeId stable node identifier
   * @return prototype node
   */
  public static Node fft(String nodeId) {
    return new Node(
        nodeId,
        "fft",
        "FFT",
        List.of(
            input(
                PORT_AUDIO_IN,
                PORT_NAME_AUDIO_IN,
                DataTypes.AUDIO_BLOCK.id(),
                PortMultiplicity.SINGLE)),
        List.of(
            output("spectrum-out", "Spectrum", DataTypes.SPECTRUM.id(), PortMultiplicity.SINGLE)));
  }

  // -------------------------------------------------------------------------
  // Analysis nodes
  // -------------------------------------------------------------------------

  /**
   * Creates a {@code WingbeatFeatureExtraction} node.
   *
   * <p>Input: one {@code Spectrum}. Output: one {@code FeatureSet}.
   *
   * @param nodeId stable node identifier
   * @return prototype node
   */
  public static Node wingbeatFeatureExtraction(String nodeId) {
    return new Node(
        nodeId,
        "wingbeat-feature-extraction",
        "Wingbeat Feature Extraction",
        List.of(input("spectrum-in", "Spectrum", DataTypes.SPECTRUM.id(), PortMultiplicity.SINGLE)),
        List.of(
            output(
                "features-out",
                "Feature Set",
                DataTypes.FEATURE_SET.id(),
                PortMultiplicity.SINGLE)));
  }

  /**
   * Creates a {@code Classifier} node.
   *
   * <p>Input: one {@code FeatureSet}. Output: one {@code ClassificationResult}.
   *
   * @param nodeId stable node identifier
   * @return prototype node
   */
  public static Node classifier(String nodeId) {
    return new Node(
        nodeId,
        "classifier",
        "Classifier",
        List.of(
            input(
                "features-in", "Feature Set", DataTypes.FEATURE_SET.id(), PortMultiplicity.SINGLE)),
        List.of(
            output(
                "result-out",
                "Classification Result",
                DataTypes.CLASSIFICATION_RESULT.id(),
                PortMultiplicity.SINGLE)));
  }

  /**
   * Creates a {@code Localization} node.
   *
   * <p>Input: one {@code AudioBlock}. Output: one {@code LocalizationResult}.
   *
   * @param nodeId stable node identifier
   * @return prototype node
   */
  public static Node localization(String nodeId) {
    return new Node(
        nodeId,
        "localization",
        "Localization",
        List.of(
            input(
                PORT_AUDIO_IN,
                PORT_NAME_AUDIO_IN,
                DataTypes.AUDIO_BLOCK.id(),
                PortMultiplicity.SINGLE)),
        List.of(
            output(
                "location-out",
                "Localization Result",
                DataTypes.LOCALIZATION_RESULT.id(),
                PortMultiplicity.SINGLE)));
  }

  /**
   * Creates a {@code Benchmark} node.
   *
   * <p>Input: one {@code ClassificationResult}. Output: one {@code BenchmarkResult}.
   *
   * @param nodeId stable node identifier
   * @return prototype node
   */
  public static Node benchmark(String nodeId) {
    return new Node(
        nodeId,
        "benchmark",
        "Benchmark",
        List.of(
            input(
                "result-in",
                "Classification Result",
                DataTypes.CLASSIFICATION_RESULT.id(),
                PortMultiplicity.SINGLE)),
        List.of(
            output(
                "benchmark-out",
                "Benchmark Result",
                DataTypes.BENCHMARK_RESULT.id(),
                PortMultiplicity.SINGLE)));
  }

  // -------------------------------------------------------------------------
  // Output nodes
  // -------------------------------------------------------------------------

  /**
   * Creates a {@code Report} node that collects results and emits a human-readable report.
   *
   * <p>Input: one {@code BenchmarkResult}. Output: one {@code Report}.
   *
   * @param nodeId stable node identifier
   * @return prototype node
   */
  public static Node report(String nodeId) {
    return new Node(
        nodeId,
        "report",
        "Report",
        List.of(
            input(
                "benchmark-in",
                "Benchmark Result",
                DataTypes.BENCHMARK_RESULT.id(),
                PortMultiplicity.SINGLE)),
        List.of(output("report-out", "Report", DataTypes.REPORT.id(), PortMultiplicity.SINGLE)));
  }

  /**
   * Creates an {@code EvidenceExport} node that exports structured evidence data.
   *
   * <p>Input: one {@code ClassificationResult}. Output: none (terminal sink).
   *
   * @param nodeId stable node identifier
   * @return prototype node
   */
  public static Node evidenceExport(String nodeId) {
    return new Node(
        nodeId,
        "evidence-export",
        "Evidence Export",
        List.of(
            input(
                "result-in",
                "Classification Result",
                DataTypes.CLASSIFICATION_RESULT.id(),
                PortMultiplicity.SINGLE)),
        List.of());
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private static Port input(
      String id, String name, String dataType, PortMultiplicity multiplicity) {
    return new Port(id, name, PortDirection.INPUT, dataType, true, multiplicity);
  }

  private static Port output(
      String id, String name, String dataType, PortMultiplicity multiplicity) {
    return new Port(id, name, PortDirection.OUTPUT, dataType, false, multiplicity);
  }
}
