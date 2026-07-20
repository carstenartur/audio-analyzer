package org.hammer.audio.dsp.workflow;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeterministicAudioNodeExecutorRegistryTest {

  @Test
  void standardRegistryContainsSourceAndGainExecutors() {
    DeterministicAudioNodeExecutorRegistry registry =
        DeterministicAudioNodeExecutorRegistry.standard();

    assertTrue(registry.find("synthetic-signal-generator").isPresent());
    assertTrue(registry.find("gain").isPresent());
  }

  @Test
  void duplicateNodeTypeRegistrationIsRejected() {
    DeterministicAudioNodeExecutor first = new SyntheticSignalNodeExecutor();
    DeterministicAudioNodeExecutor duplicate = new SyntheticSignalNodeExecutor();

    assertThrows(
        IllegalArgumentException.class,
        () -> new DeterministicAudioNodeExecutorRegistry(List.of(first, duplicate)));
  }
}
