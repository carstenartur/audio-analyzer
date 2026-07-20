package org.hammer.audio.workflow.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.hammer.audio.workflow.Node;
import org.junit.jupiter.api.Test;

class ExperimentNodeProtocolTest {

  @Test
  void catalogFactoriesHonorStableExecutableNodeAndPortIdentifiers() {
    Node generator = ExperimentNodeCatalog.syntheticSignalGenerator("node.generator");
    Node gain = ExperimentNodeCatalog.gain("node.gain");

    assertEquals(ExperimentNodeProtocol.TYPE_SYNTHETIC_SIGNAL_GENERATOR, generator.type());
    assertEquals(ExperimentNodeProtocol.SIGNAL_OUTPUT_PORT, generator.outputPorts().getFirst().id());
    assertEquals(ExperimentNodeProtocol.TYPE_GAIN, gain.type());
    assertEquals(ExperimentNodeProtocol.AUDIO_INPUT_PORT, gain.inputPorts().getFirst().id());
    assertEquals(ExperimentNodeProtocol.AUDIO_OUTPUT_PORT, gain.outputPorts().getFirst().id());
  }
}
