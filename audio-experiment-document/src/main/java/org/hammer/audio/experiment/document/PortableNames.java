package org.hammer.audio.experiment.document;

/** Strict validators for portable asset references and output basenames. */
@SuppressWarnings("PMD.LiteralsFirstInComparisons")
public final class PortableNames {

  private PortableNames() {
    // utility class
  }

  /** Require a normalized relative path without traversal or platform-specific separators. */
  public static String requireRelativePath(String value) {
    String checked = ExperimentDocument.requireNonBlank(value, "relativePath");
    if (checked.length() > 1024) {
      throw new IllegalArgumentException("relativePath exceeds 1024 characters");
    }
    for (String component : checked.split("/", -1)) {
      requireBaseName(component);
    }
    return checked;
  }

  /** Require a single portable filename component without path separators or traversal. */
  public static String requireBaseName(String value) {
    String checked = ExperimentDocument.requireNonBlank(value, "baseName");
    if (checked.equals(".")
        || checked.equals("..")
        || checked.length() > 255
        || checked.endsWith(".")
        || checked.endsWith(" ")
        || checked
            .chars()
            .anyMatch(
                valueChar ->
                    valueChar < 32 || valueChar == 127 || "/\\:*?\"<>|".indexOf(valueChar) >= 0)
        || checked.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) {
      throw new IllegalArgumentException("baseName must be one portable filename component");
    }
    return checked;
  }
}
