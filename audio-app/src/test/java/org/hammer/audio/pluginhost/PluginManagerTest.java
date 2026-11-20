package org.hammer.audio.pluginhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.hammer.audio.plugin.AudioAnalyzerPlugin;
import org.hammer.audio.plugin.PluginDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginManagerTest {

  @Test
  void duplicatePluginIdentityIsIsolatedAtLoading(@TempDir Path tmp) throws Exception {
    Path services = tmp.resolve("META-INF/services");
    Files.createDirectories(services);
    Files.writeString(
        services.resolve("org.hammer.audio.plugin.AudioAnalyzerPlugin"),
        TestPlugin.class.getName() + "\n" + DuplicateIdPlugin.class.getName() + "\n");
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {tmp.toUri().toURL()}, getClass().getClassLoader())) {
      PluginRegistry registry = new PluginManager(loader).loadPlugins();
      assertEquals(
          1,
          registry.plugins().stream()
              .filter(plugin -> "test-plugin".equals(plugin.descriptor().id()))
              .count());
      assertTrue(
          registry.failures().stream()
              .anyMatch(
                  result ->
                      result.failure().orElseThrow().getMessage().contains("Duplicate plugin id")));
    }
  }

  @Test
  void duplicateDocumentSectionIsRejectedBeforePluginRegistration(@TempDir Path tmp)
      throws Exception {
    Path services = Files.createDirectories(tmp.resolve("META-INF/services"));
    Files.writeString(
        services.resolve("org.hammer.audio.plugin.AudioAnalyzerPlugin"),
        DuplicateSectionPlugin.class.getName() + "\n");
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {tmp.toUri().toURL()}, getClass().getClassLoader())) {
      PluginRegistry registry = new PluginManager(loader).loadPlugins();
      assertTrue(
          registry.plugins().stream()
              .noneMatch(plugin -> "duplicate-sections".equals(plugin.descriptor().id())));
      assertTrue(
          registry.failures().stream()
              .anyMatch(
                  result ->
                      result
                          .descriptor()
                          .id()
                          .equals("failed:" + DuplicateSectionPlugin.class.getName())));
    }
  }

  /** Provider with ambiguous section identity for loading-time rejection. */
  public static final class DuplicateSectionPlugin implements AudioAnalyzerPlugin {
    @Override
    public PluginDescriptor descriptor() {
      return new PluginDescriptor(
          "duplicate-sections", "Duplicate sections", "1.0", "Fixture", null, false);
    }

    @Override
    public List<org.hammer.audio.plugin.document.ExperimentDocumentContribution>
        experimentDocumentContributions() {
      var section = new org.hammer.audio.experiment.document.examples.GainDocumentContribution();
      return List.of(section, section);
    }
  }

  /** Second provider deliberately reuses a stable plugin identity. */
  public static final class DuplicateIdPlugin implements AudioAnalyzerPlugin {
    @Override
    public PluginDescriptor descriptor() {
      return new TestPlugin().descriptor();
    }
  }

  @Test
  void emptyClassLoaderProducesEmptyRegistry() {
    PluginRegistry registry = new PluginManager(new EmptyServiceLoader()).loadPlugins();
    assertTrue(registry.plugins().isEmpty());
    assertTrue(registry.failures().isEmpty());
    assertTrue(registry.isEmpty());
  }

  @Test
  void discoversPluginViaServiceLoader(@TempDir Path tmp) throws Exception {
    // Build a tiny synthetic plugin classpath using the already-compiled test class below.
    Path metaInfDir = tmp.resolve("META-INF/services");
    Files.createDirectories(metaInfDir);
    Files.writeString(
        metaInfDir.resolve("org.hammer.audio.plugin.AudioAnalyzerPlugin"),
        TestPlugin.class.getName() + System.lineSeparator(),
        StandardCharsets.UTF_8);
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {tmp.toUri().toURL()}, getClass().getClassLoader())) {
      PluginRegistry registry = new PluginManager(loader).loadPlugins();
      boolean foundTestPlugin =
          registry.plugins().stream().anyMatch(p -> "test-plugin".equals(p.descriptor().id()));
      assertTrue(foundTestPlugin, () -> "test plugin not discovered: " + registry.plugins());
    }
  }

  @Test
  void failingPluginIsIsolatedAsFailureResult(@TempDir Path tmp) throws Exception {
    Path metaInfDir = tmp.resolve("META-INF/services");
    Files.createDirectories(metaInfDir);
    Files.writeString(
        metaInfDir.resolve("org.hammer.audio.plugin.AudioAnalyzerPlugin"),
        FailingPlugin.class.getName() + System.lineSeparator(),
        StandardCharsets.UTF_8);
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {tmp.toUri().toURL()}, getClass().getClassLoader())) {
      PluginRegistry registry = new PluginManager(loader).loadPlugins();
      boolean isolated =
          registry.failures().stream()
              .anyMatch(r -> r.descriptor().id().equals("failed:" + FailingPlugin.class.getName()));
      assertTrue(isolated, () -> "failure not isolated: " + registry.failures());
    }
  }

  @Test
  void loadResultSuccessExposesDescriptorAndPlugin() {
    TestPlugin plugin = new TestPlugin();
    PluginLoadResult result = PluginLoadResult.success(plugin);
    assertTrue(result.isSuccess());
    assertSame(plugin, result.plugin().orElseThrow());
    assertEquals("test-plugin", result.descriptor().id());
    assertFalse(result.failure().isPresent());
  }

  @Test
  void loadResultFailureBuildsSyntheticDescriptor() {
    RuntimeException cause = new IllegalStateException("boom");
    PluginLoadResult result = PluginLoadResult.failure("com.example.Bad", cause);
    assertFalse(result.isSuccess());
    assertSame(cause, result.failure().orElseThrow());
    assertEquals("failed:com.example.Bad", result.descriptor().id());
    assertNotNull(result.descriptor().description());
  }

  @Test
  void registryReturnsListsInDiscoveryOrder() {
    PluginLoadResult ok = PluginLoadResult.success(new TestPlugin());
    PluginLoadResult bad = PluginLoadResult.failure("X", new RuntimeException("x"));
    PluginRegistry registry = new PluginRegistry(List.of(ok, bad));
    assertEquals(2, registry.results().size());
    assertEquals(1, registry.plugins().size());
    assertEquals(1, registry.failures().size());
  }

  /** Public plugin used by the discovery test. Must be public for ServiceLoader. */
  public static final class TestPlugin implements AudioAnalyzerPlugin {
    @Override
    public PluginDescriptor descriptor() {
      return new PluginDescriptor("test-plugin", "Test", "0.0.1", "Test plugin", null, true);
    }
  }

  /** Plugin whose descriptor throws to exercise failure isolation. */
  public static final class FailingPlugin implements AudioAnalyzerPlugin {
    @Override
    public PluginDescriptor descriptor() {
      throw new IllegalStateException("intentional");
    }
  }

  /** ClassLoader that finds no service files at all. */
  private static final class EmptyServiceLoader extends ClassLoader {
    EmptyServiceLoader() {
      super(null);
    }

    @Override
    public Enumeration<URL> getResources(String name) {
      return Collections.enumeration(List.<URL>of());
    }

    @Override
    protected URL findResource(String name) {
      return null;
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
      return PluginManagerTest.class.getClassLoader().loadClass(name);
    }
  }
}
