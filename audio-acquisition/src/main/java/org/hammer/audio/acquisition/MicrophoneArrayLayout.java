package org.hammer.audio.acquisition;

/** Human-facing geometry classification for a reusable microphone-array profile. */
public enum MicrophoneArrayLayout {
  /** Two microphones used as a stereo localization pair. */
  STEREO_PAIR,
  /** Microphones arranged along one line. */
  LINEAR,
  /** Microphones arranged on a rectangular perimeter or grid. */
  RECTANGULAR,
  /** Geometry that does not fit one of the predefined layout families. */
  CUSTOM
}
