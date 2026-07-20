package org.hammer.audio.dsp.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.catalog.ExperimentNodeCatalog;
import org.hammer.audio.workflow.catalog.ExperimentNodeParameters;
import org.junit.jupiter.api.Test;

class DeterministicAudioParametersTest {

  @Test
  void parsesCompleteSyntheticSignalAndGainMetadata() {
    DeterministicAudioParameters.SyntheticSignal signal =
        DeterministicAudioParameters.parseSyntheticSignal(generator(validSignalMetadata()));
    DeterministicAudioParameters.Gain gain =
        DeterministicAudioParameters.parseGain(
            gain(Map.of(ExperimentNodeParameters.GAIN_FACTOR, "1.25")));

    assertEquals(1_000.0f, signal.frequencyHz());
    assertEquals(Math.PI / 4.0d, signal.phaseRadians());
    assertEquals(0.5f, signal.amplitude());
    assertEquals(8_000.0f, signal.sampleRateHz());
    assertEquals(2, signal.channels());
    assertEquals(128, signal.frameCount());
    assertEquals(1.25f, gain.factor());
  }

  @Test
  void rejectsMissingNyquistAndAllocationViolations() {
    Map<String, String> missingPhase = new java.util.HashMap<>(validSignalMetadata());
    missingPhase.remove(ExperimentNodeParameters.SIGNAL_PHASE_RADIANS);
    Map<String, String> aboveNyquist = new java.util.HashMap<>(validSignalMetadata());
    aboveNyquist.put(ExperimentNodeParameters.SIGNAL_FREQUENCY_HZ, "4000");
    Map<String, String> excessiveAllocation = new java.util.HashMap<>(validSignalMetadata());
    excessiveAllocation.put(ExperimentNodeParameters.SIGNAL_CHANNELS, "32");
    excessiveAllocation.put(ExperimentNodeParameters.SIGNAL_FRAME_COUNT, "600000");

    assertThrows(
        IllegalArgumentException.class,
        () -> DeterministicAudioParameters.parseSyntheticSignal(generator(missingPhase)));
    assertThrows(
        IllegalArgumentException.class,
        () -> DeterministicAudioParameters.parseSyntheticSignal(generator(aboveNyquist)));
    assertThrows(
        IllegalArgumentException.class,
        () -> DeterministicAudioParameters.parseSyntheticSignal(generator(excessiveAllocation)));
  }

  private static Map<String, String> validSignalMetadata() {
    return Map.of(
        ExperimentNodeParameters.SIGNAL_WAVEFORM,
        ExperimentNodeParameters.WAVEFORM_SINE,
        ExperimentNodeParameters.SIGNAL_FREQUENCY_HZ,
        "1000",
        ExperimentNodeParameters.SIGNAL_PHASE_RADIANS,
        Double.toString(Math.PI / 4.0d),
        ExperimentNodeParameters.SIGNAL_AMPLITUDE,
        "0.5",
        ExperimentNodeParameters.SIGNAL_SAMPLE_RATE_HZ,
        "8000",
        ExperimentNodeParameters.SIGNAL_CHANNELS,
        "2",
        ExperimentNodeParameters.SIGNAL_FRAME_COUNT,
        "128");
  }

  private static Node generator(Map<String, String> metadata) {
    return withMetadata(ExperimentNodeCatalog.syntheticSignalGenerator("node.generator"), metadata);
  }

  private static Node gain(Map<String, String> metadata) {
    return withMetadata(ExperimentNodeCatalog.gain("node.gain"), metadata);
  }

  private static Node withMetadata(Node node, Map<String, String> metadata) {
    return new Node(
        node.id(),
        node.type(),
        node.label(),
        List.copyOf(node.inputPorts()),
        List.copyOf(node.outputPorts()),
        new Metadata(metadata));
  }
}
