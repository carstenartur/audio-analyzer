package org.hammer.audio.dsp.workflow;

import java.io.Serial;

/** Internal control-flow signal for cooperative cancellation at deterministic chunk boundaries. */
final class DeterministicAudioCancellationException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;
}
