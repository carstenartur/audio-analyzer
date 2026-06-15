package org.hammer.audio.experimental.acoustic.dataset;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Imports one local dataset export into a normalized {@link DatasetManifest}.
 *
 * <p>Implementations must not download remote data automatically. The caller is responsible for
 * providing a local dataset root path and meeting any licensing constraints.
 */
public interface DatasetImporter {

  /** Stable dataset identifier (for example {@code humbugdb} or {@code humbug-zooniverse}). */
  String datasetId();

  /**
   * Import dataset files from a local root path.
   *
   * @param localRootPath local dataset root path
   * @return normalized manifest
   * @throws IOException when local metadata files cannot be read
   */
  DatasetManifest importFrom(Path localRootPath) throws IOException;
}
