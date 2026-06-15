package org.hammer.audio.experimental.acoustic.dataset;

import java.util.List;
import java.util.Objects;

/**
 * Normalized view of one imported dataset.
 *
 * @param descriptor dataset descriptor
 * @param recordings normalized recordings from the local export
 */
public record DatasetManifest(DatasetDescriptor descriptor, List<DatasetRecording> recordings) {

  public DatasetManifest {
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(recordings, "recordings");
    recordings = List.copyOf(recordings);
  }
}
