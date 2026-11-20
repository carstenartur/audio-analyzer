package org.hammer.audio.experiment.document.http;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Host-authorized roots for HTTP resource access, checked before file metadata or contents. */
final class ExperimentLocalResourcePolicy {
  private static final String REJECTION =
      "The host has not approved this path for document bindings. Configure"
          + " audio.experiment-document.local-roots on the Audio Analyzer host and select a path"
          + " within an approved directory.";
  private final List<Path> roots;

  ExperimentLocalResourcePolicy(List<Path> approvedRoots) {
    List<Path> resolved = new ArrayList<>();
    for (Path root : approvedRoots) {
      try {
        Path canonical = root.toRealPath();
        if (!Files.isDirectory(canonical)) {
          throw new IllegalArgumentException(
              "An approved document resource root must be a directory");
        }
        resolved.add(canonical);
      } catch (IOException exception) {
        throw new IllegalArgumentException(
            "An approved document resource root is unavailable", exception);
      }
    }
    roots = List.copyOf(resolved);
  }

  static ExperimentLocalResourcePolicy fromConfiguration(String configuration) {
    if (configuration == null || configuration.isBlank()) {
      return new ExperimentLocalResourcePolicy(List.of());
    }
    return new ExperimentLocalResourcePolicy(
        java.util.Arrays.stream(configuration.split(Pattern.quote(File.pathSeparator)))
            .filter(value -> !value.isBlank())
            .map(Path::of)
            .toList());
  }

  // Resolution builds one request-local sorted result and publishes only its immutable copy.
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  Map<String, Path> assets(Map<String, Path> paths) throws IOException {
    Map<String, Path> approved = new TreeMap<>();
    for (Map.Entry<String, Path> entry : paths.entrySet()) {
      approved.put(entry.getKey(), resolve(entry.getValue()));
    }
    return Map.copyOf(approved);
  }

  Path outputDirectory(Path path) throws IOException {
    return path == null ? null : resolve(path);
  }

  private Path resolve(Path path) throws IOException {
    Path normalized = path.toAbsolutePath().normalize();
    if (roots.stream().noneMatch(normalized::startsWith)) {
      throw new SecurityException(REJECTION);
    }
    Path ancestor = normalized;
    while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
      ancestor = ancestor.getParent();
    }
    if (ancestor == null) {
      throw new SecurityException(REJECTION);
    }
    Path canonical = ancestor.toRealPath().resolve(ancestor.relativize(normalized)).normalize();
    if (roots.stream().noneMatch(canonical::startsWith)) {
      throw new SecurityException(REJECTION);
    }
    return canonical;
  }
}
