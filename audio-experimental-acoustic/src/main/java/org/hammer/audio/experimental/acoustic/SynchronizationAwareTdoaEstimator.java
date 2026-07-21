package org.hammer.audio.experimental.acoustic;

import org.hammer.audio.acquisition.MicrophoneArray;
import org.hammer.audio.acquisition.SynchronizationAssessment;
import org.hammer.audio.core.AudioBlock;

/** TDOA estimator that can report the synchronization quality used for one block. */
public interface SynchronizationAwareTdoaEstimator extends TdoaEstimator {

  /** Returns the synchronization assessment used for the supplied observation. */
  SynchronizationAssessment synchronizationAssessment(AudioBlock block, MicrophoneArray array);
}
