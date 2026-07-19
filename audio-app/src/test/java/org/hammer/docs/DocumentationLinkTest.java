package org.hammer.docs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Verifies that repository-local Markdown links and images resolve from their declaring document.
 */
class DocumentationLinkTest {

  private static final Pattern MARKDOWN_TARGET = Pattern.compile("!?\\[[^]\\n]*]\\(([^)\\n]+)\\)");

  @Test
  void localDocumentationTargetsExist() throws IOException {
    Path root = repositoryRoot();
    List<String> failures = new ArrayList<>();

    for (Path document : markdownDocuments(root)) {
      String content = Files.readString(document, StandardCharsets.UTF_8);
      Matcher matcher = MARKDOWN_TARGET.matcher(content);
      while (matcher.find()) {
        String rawTarget = matcher.group(1).trim();
        String target = normalizedLocalTarget(rawTarget);
        if (target == null) {
          continue;
        }
        Path resolved = document.getParent().resolve(target).normalize();
        if (!resolved.startsWith(root) || !Files.exists(resolved)) {
          failures.add(
              root.relativize(document) + " -> " + rawTarget + " (resolved as " + resolved + ")");
        }
      }
    }

    failures.sort(Comparator.naturalOrder());
    assertTrue(
        failures.isEmpty(),
        () -> "Broken repository-local documentation targets:\n" + String.join("\n", failures));
  }

  private static List<Path> markdownDocuments(Path root) throws IOException {
    try (Stream<Path> files = Files.walk(root)) {
      return files
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".md"))
          .filter(path -> !containsSegment(root.relativize(path), "target"))
          .filter(path -> !containsSegment(root.relativize(path), "node_modules"))
          .sorted()
          .toList();
    }
  }

  private static boolean containsSegment(Path relative, String segment) {
    for (Path element : relative) {
      if (segment.equals(element.toString())) {
        return true;
      }
    }
    return false;
  }

  private static String normalizedLocalTarget(String rawTarget) {
    String target = rawTarget;
    if (target.startsWith("<") && target.endsWith(">")) {
      target = target.substring(1, target.length() - 1);
    }
    if (target.startsWith("http://")
        || target.startsWith("https://")
        || target.startsWith("mailto:")
        || target.startsWith("data:")
        || target.startsWith("#")) {
      return null;
    }
    int titleSeparator = target.indexOf(" \"");
    if (titleSeparator >= 0) {
      target = target.substring(0, titleSeparator);
    }
    int fragment = target.indexOf('#');
    if (fragment >= 0) {
      target = target.substring(0, fragment);
    }
    int query = target.indexOf('?');
    if (query >= 0) {
      target = target.substring(0, query);
    }
    if (target.isBlank()) {
      return null;
    }
    return URLDecoder.decode(target, StandardCharsets.UTF_8);
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("README.md"))
          && Files.isRegularFile(current.resolve("pom.xml"))
          && Files.isDirectory(current.resolve("docs"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Could not locate repository root from test working directory");
  }
}
