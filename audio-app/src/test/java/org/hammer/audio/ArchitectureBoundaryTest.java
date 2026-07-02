package org.hammer.audio;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ArchitectureBoundaryTest {

  private static final Path REPOSITORY_ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final List<String> STABLE_MODULES =
      List.of("audio-core", "audio-geometry", "audio-acquisition", "audio-dsp");

  /**
   * Matches any import (including {@code import static}) from {@code org.hammer.*} that is not
   * under {@code org.hammer.audio.*}.
   */
  private static final String NON_AUDIO_HAMMER_IMPORT_REGEX =
      "import (static )?org\\.hammer\\.(?!audio\\.).*";

  @Test
  void stableAudioPackagesDoNotDependOnExperimentalPackages() throws IOException {
    List<String> violations = new ArrayList<>();
    for (String module : STABLE_MODULES) {
      try (Stream<Path> files = Files.walk(mainJava(module))) {
        files
            .filter(path -> path.toString().endsWith(".java"))
            .forEach(
                path ->
                    importLines(path).stream()
                        .forEach(
                            line -> {
                              if (line.contains("import org.hammer.audio.experimental.")) {
                                violations.add(path + ": " + line.trim());
                              }
                            }));
      }
    }
    assertNoViolations(violations);
  }

  @Test
  void stableModulesDoNotDependOnUiOrAppPackages() throws IOException {
    List<String> violations = new ArrayList<>();
    for (String module : STABLE_MODULES) {
      try (Stream<Path> files = Files.walk(mainJava(module))) {
        files
            .filter(path -> path.toString().endsWith(".java"))
            .forEach(
                path ->
                    importLines(path).stream()
                        .forEach(
                            line -> {
                              String trimmed = line.trim();
                              if (trimmed.startsWith("import org.hammer.audio.ui.")
                                  || trimmed.startsWith("import static org.hammer.audio.ui.")
                                  || trimmed.matches(NON_AUDIO_HAMMER_IMPORT_REGEX)) {
                                violations.add(path + ": " + trimmed);
                              }
                            }));
      }
    }
    assertNoViolations(violations);
  }

  @Test
  void modulePomDependenciesPreserveStableBoundaries() throws IOException {
    List<String> violations = new ArrayList<>();
    for (String module : STABLE_MODULES) {
      String pom = Files.readString(REPOSITORY_ROOT.resolve(module).resolve("pom.xml"));
      if (pom.contains("<artifactId>audio-app</artifactId>")
          || pom.contains("<artifactId>audio-experimental-acoustic</artifactId>")
          || pom.contains("<artifactId>audio-plugin-api</artifactId>")) {
        violations.add(module + " must not depend on app, plugin-api or concrete plugin modules");
      }
    }
    String appPom = Files.readString(REPOSITORY_ROOT.resolve("audio-app/pom.xml"));
    // The host application may pull in a concrete plugin JAR only at runtime scope so the
    // ServiceLoader can discover it; it must never compile against plugin code.
    if (compileScopeDependencies(appPom).contains("audio-experimental-acoustic")) {
      violations.add(
          "audio-app must not have a compile-scope dependency on audio-experimental-acoustic");
    }
    String experimentalPom =
        Files.readString(REPOSITORY_ROOT.resolve("audio-experimental-acoustic/pom.xml"));
    Set<String> allowedExperimentalDeps =
        Set.of(
            "audio-core", "audio-geometry", "audio-acquisition", "audio-dsp", "audio-plugin-api");
    for (String artifactId : dependencyArtifactIds(experimentalPom)) {
      if (artifactId.startsWith("audio-") && !allowedExperimentalDeps.contains(artifactId)) {
        violations.add("audio-experimental-acoustic has non-stable dependency: " + artifactId);
      }
    }
    String pluginApiPom = Files.readString(REPOSITORY_ROOT.resolve("audio-plugin-api/pom.xml"));
    for (String artifactId : dependencyArtifactIds(pluginApiPom)) {
      if (artifactId.startsWith("audio-")) {
        violations.add("audio-plugin-api must not depend on audio-* modules: " + artifactId);
      }
    }
    assertNoViolations(violations);
  }

  @Test
  void appSourceDoesNotImportConcretePluginPackages() throws IOException {
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(mainJava("audio-app"))) {
      files
          .filter(path -> path.toString().endsWith(".java"))
          .forEach(
              path ->
                  importLines(path).stream()
                      .forEach(
                          line -> {
                            String trimmed = line.trim();
                            if (trimmed.startsWith("import org.hammer.audio.experimental.")
                                || trimmed.startsWith(
                                    "import static org.hammer.audio.experimental.")) {
                              violations.add(path + ": " + trimmed);
                            }
                          }));
    }
    assertNoViolations(violations);
  }

  @Test
  void pluginApiDoesNotImportHostOrConcretePluginPackages() throws IOException {
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(mainJava("audio-plugin-api"))) {
      files
          .filter(path -> path.toString().endsWith(".java"))
          .forEach(
              path ->
                  importLines(path).stream()
                      .forEach(
                          line -> {
                            String trimmed = line.trim();
                            if (trimmed.startsWith("import org.hammer.audio.experimental.")
                                || trimmed.startsWith(
                                    "import static org.hammer.audio.experimental.")
                                || trimmed.startsWith("import org.hammer.audio.pluginhost.")
                                || trimmed.startsWith("import static org.hammer.audio.pluginhost.")
                                || trimmed.matches(NON_AUDIO_HAMMER_IMPORT_REGEX)) {
                              violations.add(path + ": " + trimmed);
                            }
                          }));
    }
    assertNoViolations(violations);
  }

  /**
   * Bounded-context rule: the Workflow domain model must not depend on the Execution sub-context.
   *
   * <p>Workflow describes <em>what</em> to execute (design-time model). Execution state belongs
   * exclusively in {@code org.hammer.audio.workflow.execution}. The reverse dependency (execution →
   * workflow) is intentional and allowed.
   */
  @Test
  void workflowContextDoesNotDependOnExecutionSubpackage() throws IOException {
    Path workflowRoot = mainJava("audio-core").resolve("org/hammer/audio/workflow");
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(workflowRoot)) {
      files
          .filter(path -> path.toString().endsWith(".java"))
          .filter(path -> !path.startsWith(workflowRoot.resolve("execution")))
          .forEach(
              path ->
                  importLines(path).stream()
                      .forEach(
                          line -> {
                            if (line.trim()
                                    .startsWith("import org.hammer.audio.workflow.execution.")
                                || line.trim()
                                    .startsWith(
                                        "import static org.hammer.audio.workflow.execution.")) {
                              violations.add(path + ": " + line.trim());
                            }
                          }));
    }
    assertNoViolations(violations);
  }

  /**
   * Bounded-context rule: the Execution context must not depend on Persistence or Visualization.
   *
   * <p>Execution is a pure runtime-state model. It must never import recording/persistence
   * utilities or Swing/UI packages.
   */
  @Test
  void executionContextDoesNotDependOnPersistenceOrVisualization() throws IOException {
    Path executionRoot = mainJava("audio-core").resolve("org/hammer/audio/workflow/execution");
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(executionRoot)) {
      files
          .filter(path -> path.toString().endsWith(".java"))
          .forEach(
              path ->
                  importLines(path).stream()
                      .forEach(
                          line -> {
                            String trimmed = line.trim();
                            if (trimmed.startsWith("import org.hammer.audio.recording.")
                                || trimmed.startsWith("import static org.hammer.audio.recording.")
                                || trimmed.startsWith("import org.hammer.audio.ui.")
                                || trimmed.startsWith("import static org.hammer.audio.ui.")
                                || trimmed.matches(NON_AUDIO_HAMMER_IMPORT_REGEX)) {
                              violations.add(path + ": " + trimmed);
                            }
                          }));
    }
    assertNoViolations(violations);
  }

  /**
   * Bounded-context rule: the Persistence context must not depend on Visualization.
   *
   * <p>The audio recording package stores {@code AudioBlock} data to disk. It must not import
   * Swing/UI packages; rendering is exclusively the responsibility of the Visualization context.
   */
  @Test
  void persistenceContextDoesNotDependOnVisualization() throws IOException {
    Path recordingRoot = mainJava("audio-dsp").resolve("org/hammer/audio/recording");
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(recordingRoot)) {
      files
          .filter(path -> path.toString().endsWith(".java"))
          .forEach(
              path ->
                  importLines(path).stream()
                      .forEach(
                          line -> {
                            String trimmed = line.trim();
                            if (trimmed.startsWith("import org.hammer.audio.ui.")
                                || trimmed.startsWith("import static org.hammer.audio.ui.")
                                || trimmed.matches(NON_AUDIO_HAMMER_IMPORT_REGEX)) {
                              violations.add(path + ": " + trimmed);
                            }
                          }));
    }
    assertNoViolations(violations);
  }

  /**
   * Bounded-context rule: the Benchmarking context must not depend on Visualization.
   *
   * <p>Benchmark metrics and evaluation runners are headless by design. They must not import Swing
   * or UI packages; results are consumed as plain data (reports, CSV, JSON).
   */
  @Test
  void benchmarkingContextDoesNotDependOnVisualization() throws IOException {
    Path benchmarkRoot =
        mainJava("audio-experimental-acoustic")
            .resolve("org/hammer/audio/experimental/acoustic/benchmark");
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(benchmarkRoot)) {
      files
          .filter(path -> path.toString().endsWith(".java"))
          .forEach(
              path ->
                  importLines(path).stream()
                      .forEach(
                          line -> {
                            String trimmed = line.trim();
                            if (trimmed.startsWith("import org.hammer.audio.ui.")
                                || trimmed.startsWith("import static org.hammer.audio.ui.")
                                || trimmed.matches(NON_AUDIO_HAMMER_IMPORT_REGEX)) {
                              violations.add(path + ": " + trimmed);
                            }
                          }));
    }
    assertNoViolations(violations);
  }

  private static Set<String> compileScopeDependencies(String pom) {
    Set<String> compileDeps = new java.util.LinkedHashSet<>();
    boolean inDependency = false;
    String artifactId = null;
    String scope = null;
    for (String line : pom.lines().toList()) {
      String trimmed = line.trim();
      if ("<dependency>".equals(trimmed)) {
        inDependency = true;
        artifactId = null;
        scope = null;
      } else if ("</dependency>".equals(trimmed)) {
        if (inDependency && artifactId != null && (scope == null || "compile".equals(scope))) {
          compileDeps.add(artifactId);
        }
        inDependency = false;
      } else if (inDependency
          && trimmed.startsWith("<artifactId>")
          && trimmed.endsWith("</artifactId>")) {
        artifactId = trimmed.replace("<artifactId>", "").replace("</artifactId>", "");
      } else if (inDependency && trimmed.startsWith("<scope>") && trimmed.endsWith("</scope>")) {
        scope = trimmed.replace("<scope>", "").replace("</scope>", "");
      }
    }
    return compileDeps;
  }

  private static Path mainJava(String module) {
    return REPOSITORY_ROOT.resolve(module).resolve("src/main/java");
  }

  private static List<String> dependencyArtifactIds(String pom) {
    List<String> artifactIds = new ArrayList<>();
    boolean inDependency = false;
    for (String line : pom.lines().toList()) {
      String trimmed = line.trim();
      if ("<dependency>".equals(trimmed)) {
        inDependency = true;
      } else if ("</dependency>".equals(trimmed)) {
        inDependency = false;
      } else if (inDependency
          && trimmed.startsWith("<artifactId>")
          && trimmed.endsWith("</artifactId>")) {
        artifactIds.add(trimmed.replace("<artifactId>", "").replace("</artifactId>", ""));
      }
    }
    return artifactIds;
  }

  private static List<String> importLines(Path path) {
    try {
      return Files.readAllLines(path).stream()
          .filter(line -> line.trim().startsWith("import "))
          .toList();
    } catch (IOException ex) {
      throw new IllegalStateException("Cannot read " + path, ex);
    }
  }

  private static void assertNoViolations(List<String> violations) {
    if (!violations.isEmpty()) {
      fail("Architecture boundary violations:\n" + String.join("\n", violations));
    }
  }
}
