package org.hammer.audio.experiment.document;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Local machine bindings for portable experiment requirements.
 *
 * <p>This value is deliberately not serializable into {@code .audioexp}. Absolute asset and output
 * paths remain local application state.
 *
 * @param assetPaths local paths selected for portable asset identifiers
 * @param outputDirectory optional local directory selected for requested outputs
 */
public record ExperimentLocalBindings(Map<String, Path> assetPaths, Path outputDirectory) {

  /** Validate identifiers and normalize all local paths. */
  public ExperimentLocalBindings {
    Objects.requireNonNull(assetPaths, "assetPaths");
    TreeMap<String, Path> copy = new TreeMap<>();
    assetPaths.forEach(
        (assetId, path) ->
            copy.put(
                ExperimentDocument.requireIdentifier(assetId, "asset binding id"),
                Objects.requireNonNull(path, "asset binding path").toAbsolutePath().normalize()));
    assetPaths = Collections.unmodifiableMap(copy);
    outputDirectory = outputDirectory == null ? null : outputDirectory.toAbsolutePath().normalize();
  }

  /** Return the local path selected for one portable asset requirement. */
  public Optional<Path> assetPath(String assetId) {
    return Optional.ofNullable(assetPaths.get(assetId));
  }

  /** Return the local output directory selected outside the portable document. */
  public Optional<Path> selectedOutputDirectory() {
    return Optional.ofNullable(outputDirectory);
  }
}
