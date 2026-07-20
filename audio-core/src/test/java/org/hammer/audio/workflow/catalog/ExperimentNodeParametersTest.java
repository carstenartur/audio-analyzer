package org.hammer.audio.workflow.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExperimentNodeParametersTest {

  @Test
  void executableMetadataKeysRemainStable() {
    assertEquals("signal.waveform", ExperimentNodeParameters.SIGNAL_WAVEFORM);
    assertEquals("signal.frequency-hz", ExperimentNodeParameters.SIGNAL_FREQUENCY_HZ);
    assertEquals("signal.phase-radians", ExperimentNodeParameters.SIGNAL_PHASE_RADIANS);
    assertEquals("signal.amplitude", ExperimentNodeParameters.SIGNAL_AMPLITUDE);
    assertEquals("signal.sample-rate-hz", ExperimentNodeParameters.SIGNAL_SAMPLE_RATE_HZ);
    assertEquals("signal.channels", ExperimentNodeParameters.SIGNAL_CHANNELS);
    assertEquals("signal.frame-count", ExperimentNodeParameters.SIGNAL_FRAME_COUNT);
    assertEquals("gain.factor", ExperimentNodeParameters.GAIN_FACTOR);
  }
}
