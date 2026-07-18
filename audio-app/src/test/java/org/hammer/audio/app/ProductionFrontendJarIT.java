package org.hammer.audio.app;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ProductionFrontendJarIT {

  private static final String EDITOR_JAR_PREFIX = "BOOT-INF/lib/audio-web-editor-";
  private static final String INDEX_ENTRY = "workbench-ui/index.html";
  private static final Pattern ASSET_REFERENCE =
      Pattern.compile("(?:src|href)=\"/assets/([^\"]+)\"");

  @Test
  void executableWorkbenchJarContainsTheFrontendJarAndEveryReferencedAsset() throws IOException {
    String configuredJar = System.getProperty("workbench.jar");
    assertNotNull(configuredJar, "Failsafe must provide the packaged workbench JAR path");
    Path workbenchJar = Path.of(configuredJar);
    assertTrue(Files.isRegularFile(workbenchJar), () -> "Missing workbench JAR: " + workbenchJar);

    try (JarFile bootJar = new JarFile(workbenchJar.toFile())) {
      List<JarEntry> frontendJars =
          bootJar.stream()
              .filter(entry -> entry.getName().startsWith(EDITOR_JAR_PREFIX))
              .filter(entry -> entry.getName().endsWith(".jar"))
              .toList();
      assertEquals(
          1, frontendJars.size(), "Executable JAR must contain exactly one web editor JAR");

      FrontendJarContents frontend = readFrontendJar(bootJar, frontendJars.getFirst());
      assertTrue(frontend.entryNames().contains(INDEX_ENTRY));
      assertTrue(frontend.indexHtml().contains("id=\"root\""));
      assertFalse(frontend.indexHtml().contains("workflow-editor-spike"));

      Matcher references = ASSET_REFERENCE.matcher(frontend.indexHtml());
      int referencedAssets = 0;
      boolean javascriptReferenced = false;
      boolean stylesheetReferenced = false;
      while (references.find()) {
        referencedAssets++;
        String asset = references.group(1);
        assertTrue(
            frontend.entryNames().contains("workbench-ui/assets/" + asset),
            () -> "Frontend HTML references missing packaged asset: " + asset);
        javascriptReferenced |= asset.endsWith(".js");
        stylesheetReferenced |= asset.endsWith(".css");
      }
      assertTrue(referencedAssets > 0, "Frontend HTML contains no packaged asset references");
      assertTrue(javascriptReferenced, "Frontend HTML contains no JavaScript entry asset");
      assertTrue(stylesheetReferenced, "Frontend HTML contains no stylesheet entry asset");
    }
  }

  private static FrontendJarContents readFrontendJar(JarFile bootJar, JarEntry frontendJar)
      throws IOException {
    Set<String> entries = new HashSet<>();
    String indexHtml = null;
    try (JarInputStream nested = new JarInputStream(bootJar.getInputStream(frontendJar))) {
      JarEntry entry;
      while ((entry = nested.getNextJarEntry()) != null) {
        entries.add(entry.getName());
        if (INDEX_ENTRY.equals(entry.getName())) {
          ByteArrayOutputStream output = new ByteArrayOutputStream();
          nested.transferTo(output);
          indexHtml = output.toString(UTF_8);
        }
      }
    }
    assertNotNull(indexHtml, "Frontend dependency JAR contains no application shell");
    return new FrontendJarContents(Set.copyOf(entries), indexHtml);
  }

  private record FrontendJarContents(Set<String> entryNames, String indexHtml) {}
}
