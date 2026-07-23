package org.hammer.audio.experiment.document;

import java.nio.file.Path;
import java.util.Objects;

/** Strict validators for portable asset references and output basenames. */
public final class PortableNames {

  private PortableNames() {
    // utility class
  }

  /** Require a normalized relative path without traversal or platform-specific separators. */
  public static String requireRelativePath(String value) {
    String checked = ExperimentDocument.requireNonBlank(value, "relativePath");
    if (checked.indexOf('\\') >= 0 || checked.startsWith("/") || checked.contains(":")) {
      throw new IllegalArgumentException("relativePath must be platform-neutral and relative");
    }
    Path path = Path.of(checked).normalize();
    if (path.isAbsolute()
        || path.getNameCount() == 0
        || path.startsWith("..")
        || !path.toString().replace('\\', '/').equals(checked)) {
      throw new IllegalArgumentException("relativePath contains traversal or is not normalized");
    }
    return checked;
  }

  /** Require a single portable filename component without path separators or traversal. */
  public static String requireBaseName(String value) {
    String checked = ExperimentDocument.requireNonBlank(value, "baseName");
    if (checked.equals(".")
        || checked.equals("..")
        || checked.indexOf('/') >= 0
        || checked.indexOf('\\') >= 0
        || checked.indexOf(':') >= 0
        || !Objects.equals(Path.of(checked).getFileName().toString(), checked)) {
      throw new IllegalArgumentException("baseName must be one portable filename component");
    }
    return checked;
  }
}
