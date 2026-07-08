package org.hammer.audio.workflow.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hammer.audio.workflow.DataTypes;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.PortDirection;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ExperimentNodeCatalog}.
 *
 * <p>Acceptance criteria (issue #215):
 *
 * <ul>
 *   <li>Each node has correct input/output ports and data types.
 *   <li>At least five valid connection examples are illustrated.
 *   <li>At least five invalid connection (type-mismatch) examples are documented.
 * </ul>
 */
class ExperimentNodeCatalogTest {

  // -------------------------------------------------------------------------
  // Node structure tests
  // -------------------------------------------------------------------------

  @Test
  void recordingInputHasNoInputsAndOneDatasetOutput() {
    Node node = ExperimentNodeCatalog.recordingInput("node.r");
    assertEquals("recording-input", node.type());
    assertTrue(node.inputPorts().isEmpty());
    assertEquals(1, node.outputPorts().size());
    assertEquals(DataTypes.DATASET.id(), node.outputPorts().get(0).dataType().id());
    assertEquals(PortDirection.OUTPUT, node.outputPorts().get(0).direction());
  }

  @Test
  void syntheticSignalGeneratorHasNoInputsAndOneAudioBlockOutput() {
    Node node = ExperimentNodeCatalog.syntheticSignalGenerator("node.s");
    assertTrue(node.inputPorts().isEmpty());
    assertEquals(1, node.outputPorts().size());
    assertEquals(DataTypes.AUDIO_BLOCK.id(), node.outputPorts().get(0).dataType().id());
  }

  @Test
  void humBugDbImportHasNoInputsAndOneDatasetOutput() {
    Node node = ExperimentNodeCatalog.humBugDbImport("node.h");
    assertTrue(node.inputPorts().isEmpty());
    assertEquals(1, node.outputPorts().size());
    assertEquals(DataTypes.DATASET.id(), node.outputPorts().get(0).dataType().id());
  }

  @Test
  void gainHasOneAudioBlockInputAndOneAudioBlockOutput() {
    Node node = ExperimentNodeCatalog.gain("node.g");
    assertEquals(1, node.inputPorts().size());
    assertEquals(DataTypes.AUDIO_BLOCK.id(), node.inputPorts().get(0).dataType().id());
    assertEquals(1, node.outputPorts().size());
    assertEquals(DataTypes.AUDIO_BLOCK.id(), node.outputPorts().get(0).dataType().id());
  }

  @Test
  void bandpassFilterHasOneAudioBlockInputAndOneAudioBlockOutput() {
    Node node = ExperimentNodeCatalog.bandpassFilter("node.bp");
    assertEquals(1, node.inputPorts().size());
    assertEquals(DataTypes.AUDIO_BLOCK.id(), node.inputPorts().get(0).dataType().id());
    assertEquals(DataTypes.AUDIO_BLOCK.id(), node.outputPorts().get(0).dataType().id());
  }

  @Test
  void fftHasOneAudioBlockInputAndOneSpectrumOutput() {
    Node node = ExperimentNodeCatalog.fft("node.fft");
    assertEquals(DataTypes.AUDIO_BLOCK.id(), node.inputPorts().get(0).dataType().id());
    assertEquals(DataTypes.SPECTRUM.id(), node.outputPorts().get(0).dataType().id());
  }

  @Test
  void wingbeatFeatureExtractionHasOneSpectrumInputAndOneFeatureSetOutput() {
    Node node = ExperimentNodeCatalog.wingbeatFeatureExtraction("node.wfe");
    assertEquals(DataTypes.SPECTRUM.id(), node.inputPorts().get(0).dataType().id());
    assertEquals(DataTypes.FEATURE_SET.id(), node.outputPorts().get(0).dataType().id());
  }

  @Test
  void classifierHasOneFeatureSetInputAndOneClassificationResultOutput() {
    Node node = ExperimentNodeCatalog.classifier("node.cls");
    assertEquals(DataTypes.FEATURE_SET.id(), node.inputPorts().get(0).dataType().id());
    assertEquals(DataTypes.CLASSIFICATION_RESULT.id(), node.outputPorts().get(0).dataType().id());
  }

  @Test
  void localizationHasOneAudioBlockInputAndOneLocalizationResultOutput() {
    Node node = ExperimentNodeCatalog.localization("node.loc");
    assertEquals(DataTypes.AUDIO_BLOCK.id(), node.inputPorts().get(0).dataType().id());
    assertEquals(DataTypes.LOCALIZATION_RESULT.id(), node.outputPorts().get(0).dataType().id());
  }

  @Test
  void benchmarkHasOneClassificationResultInputAndOneBenchmarkResultOutput() {
    Node node = ExperimentNodeCatalog.benchmark("node.bm");
    assertEquals(DataTypes.CLASSIFICATION_RESULT.id(), node.inputPorts().get(0).dataType().id());
    assertEquals(DataTypes.BENCHMARK_RESULT.id(), node.outputPorts().get(0).dataType().id());
  }

  @Test
  void reportHasOneBenchmarkResultInputAndOneReportOutput() {
    Node node = ExperimentNodeCatalog.report("node.rep");
    assertEquals(DataTypes.BENCHMARK_RESULT.id(), node.inputPorts().get(0).dataType().id());
    assertEquals(DataTypes.REPORT.id(), node.outputPorts().get(0).dataType().id());
  }

  @Test
  void evidenceExportHasOneClassificationResultInputAndNoOutputs() {
    Node node = ExperimentNodeCatalog.evidenceExport("node.ev");
    assertEquals(DataTypes.CLASSIFICATION_RESULT.id(), node.inputPorts().get(0).dataType().id());
    assertTrue(node.outputPorts().isEmpty());
  }

  // -------------------------------------------------------------------------
  // Valid connection compatibility examples (issue #215 acceptance criteria)
  // -------------------------------------------------------------------------

  /**
   * Valid connection 1: SyntheticSignalGenerator.audio-out -> Gain.audio-in (AudioBlock matches).
   */
  @Test
  void validConnection1_syntheticSignalGeneratorToGain() {
    Node gen = ExperimentNodeCatalog.syntheticSignalGenerator("node.gen");
    Node gain = ExperimentNodeCatalog.gain("node.gain");
    assertTypesCompatible(gen, "signal-out", gain, "audio-in");
  }

  /** Valid connection 2: Gain.audio-out -> BandpassFilter.audio-in (AudioBlock matches). */
  @Test
  void validConnection2_gainToBandpassFilter() {
    Node gain = ExperimentNodeCatalog.gain("node.gain");
    Node filter = ExperimentNodeCatalog.bandpassFilter("node.filter");
    assertTypesCompatible(gain, "audio-out", filter, "audio-in");
  }

  /** Valid connection 3: BandpassFilter.audio-out -> FFT.audio-in (AudioBlock matches). */
  @Test
  void validConnection3_bandpassFilterToFft() {
    Node filter = ExperimentNodeCatalog.bandpassFilter("node.filter");
    Node fft = ExperimentNodeCatalog.fft("node.fft");
    assertTypesCompatible(filter, "audio-out", fft, "audio-in");
  }

  /** Valid connection 4: FFT.spectrum-out -> WingbeatFeatureExtraction.spectrum-in. */
  @Test
  void validConnection4_fftToWingbeatFeatureExtraction() {
    Node fft = ExperimentNodeCatalog.fft("node.fft");
    Node wfe = ExperimentNodeCatalog.wingbeatFeatureExtraction("node.wfe");
    assertTypesCompatible(fft, "spectrum-out", wfe, "spectrum-in");
  }

  /**
   * Valid connection 5: WingbeatFeatureExtraction.features-out -> Classifier.features-in
   * (FeatureSet matches).
   */
  @Test
  void validConnection5_wingbeatFeatureExtractionToClassifier() {
    Node wfe = ExperimentNodeCatalog.wingbeatFeatureExtraction("node.wfe");
    Node cls = ExperimentNodeCatalog.classifier("node.cls");
    assertTypesCompatible(wfe, "features-out", cls, "features-in");
  }

  // -------------------------------------------------------------------------
  // Invalid connection type-mismatch examples (issue #215 acceptance criteria)
  // -------------------------------------------------------------------------

  /**
   * Invalid connection 1: RecordingInput.audio-out (Dataset) -> Gain.audio-in (AudioBlock) – type
   * mismatch.
   */
  @Test
  void invalidConnection1_recordingInputDatasetToGainAudioBlock() {
    Node ri = ExperimentNodeCatalog.recordingInput("node.ri");
    Node gain = ExperimentNodeCatalog.gain("node.gain");
    assertTypesIncompatible(ri, "audio-out", gain, "audio-in");
  }

  /**
   * Invalid connection 2: FFT.spectrum-out (Spectrum) -> Classifier.features-in (FeatureSet) – type
   * mismatch.
   */
  @Test
  void invalidConnection2_fftSpectrumToClassifierFeatureSet() {
    Node fft = ExperimentNodeCatalog.fft("node.fft");
    Node cls = ExperimentNodeCatalog.classifier("node.cls");
    assertTypesIncompatible(fft, "spectrum-out", cls, "features-in");
  }

  /**
   * Invalid connection 3: Classifier.result-out (ClassificationResult) -> Report.benchmark-in
   * (BenchmarkResult) – type mismatch.
   */
  @Test
  void invalidConnection3_classifierResultToReportBenchmark() {
    Node cls = ExperimentNodeCatalog.classifier("node.cls");
    Node rep = ExperimentNodeCatalog.report("node.rep");
    assertTypesIncompatible(cls, "result-out", rep, "benchmark-in");
  }

  /**
   * Invalid connection 4: Gain.audio-out (AudioBlock) -> Benchmark.result-in (ClassificationResult)
   * – type mismatch.
   */
  @Test
  void invalidConnection4_gainAudioBlockToBenchmarkClassificationResult() {
    Node gain = ExperimentNodeCatalog.gain("node.gain");
    Node bm = ExperimentNodeCatalog.benchmark("node.bm");
    assertTypesIncompatible(gain, "audio-out", bm, "result-in");
  }

  /**
   * Invalid connection 5: WingbeatFeatureExtraction.features-out (FeatureSet) ->
   * Localization.audio-in (AudioBlock) – type mismatch.
   */
  @Test
  void invalidConnection5_wingbeatFeaturesOutToLocalizationAudioIn() {
    Node wfe = ExperimentNodeCatalog.wingbeatFeatureExtraction("node.wfe");
    Node loc = ExperimentNodeCatalog.localization("node.loc");
    assertTypesIncompatible(wfe, "features-out", loc, "audio-in");
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private void assertTypesCompatible(
      Node sourceNode, String sourcePortId, Node targetNode, String targetPortId) {
    String sourceType = findPortType(sourceNode, sourcePortId, PortDirection.OUTPUT);
    String targetType = findPortType(targetNode, targetPortId, PortDirection.INPUT);
    assertNotNull(sourceType, "Source port not found: " + sourcePortId);
    assertNotNull(targetType, "Target port not found: " + targetPortId);
    assertEquals(
        sourceType,
        targetType,
        "Types must match for a valid connection: " + sourcePortId + " -> " + targetPortId);
  }

  private void assertTypesIncompatible(
      Node sourceNode, String sourcePortId, Node targetNode, String targetPortId) {
    String sourceType = findPortType(sourceNode, sourcePortId, PortDirection.OUTPUT);
    String targetType = findPortType(targetNode, targetPortId, PortDirection.INPUT);
    assertNotNull(sourceType, "Source port not found: " + sourcePortId);
    assertNotNull(targetType, "Target port not found: " + targetPortId);
    assertFalse(
        sourceType.equals(targetType),
        "Types must differ for an invalid connection: " + sourcePortId + " -> " + targetPortId);
  }

  private String findPortType(Node node, String portId, PortDirection direction) {
    var ports = direction == PortDirection.INPUT ? node.inputPorts() : node.outputPorts();
    return ports.stream()
        .filter(p -> p.id().equals(portId))
        .map(p -> p.dataType().id())
        .findFirst()
        .orElse(null);
  }
}
