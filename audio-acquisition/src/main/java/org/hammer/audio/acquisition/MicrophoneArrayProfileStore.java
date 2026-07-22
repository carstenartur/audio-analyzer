package org.hammer.audio.acquisition;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for reusable microphone-array profiles. */
public interface MicrophoneArrayProfileStore {

  /** Create or replace one profile. */
  void save(MicrophoneArrayProfile profile) throws IOException;

  /** Load one profile by its stable id. */
  Optional<MicrophoneArrayProfile> find(String profileId) throws IOException;

  /** List every stored profile in stable id order. */
  List<MicrophoneArrayProfile> list() throws IOException;

  /** Delete one profile, returning whether it existed. */
  boolean delete(String profileId) throws IOException;
}
