package org.hammer.audio.pluginhost;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.hammer.audio.experiment.document.PluginDocumentCatalog;
import org.hammer.audio.plugin.AudioAnalyzerPlugin;
import org.hammer.audio.plugin.PluginDescriptor;

/**
 * Discovers and loads {@link AudioAnalyzerPlugin} implementations via {@link ServiceLoader}.
 *
 * <p>Each plugin is initialized in isolation: a faulty descriptor or constructor cannot prevent
 * other plugins from loading, and never crashes the host application. Failed plugins are still
 * surfaced through the returned {@link PluginRegistry} so the UI/log can show them.
 *
 * <p>This is the only entry point the host application uses to discover plugins. It deliberately
 * does not import or reference any concrete plugin class.
 */
public final class PluginManager {

  private static final Logger LOGGER = Logger.getLogger(PluginManager.class.getName());

  private final ClassLoader classLoader;

  /** Use the current thread's context class loader (or this class' loader as fallback). */
  public PluginManager() {
    this(defaultClassLoader());
  }

  /** Use the supplied class loader for service discovery. */
  public PluginManager(ClassLoader classLoader) {
    this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
  }

  /**
   * Discover all plugins on the configured class loader and return a populated {@link
   * PluginRegistry}. Never throws; failures are isolated into individual {@link PluginLoadResult}
   * entries.
   */
  public PluginRegistry loadPlugins() {
    List<PluginLoadResult> results = new ArrayList<>();
    Set<String> pluginIds = new HashSet<>();
    ServiceLoader<AudioAnalyzerPlugin> loader =
        ServiceLoader.load(AudioAnalyzerPlugin.class, classLoader);
    Iterator<ServiceLoader.Provider<AudioAnalyzerPlugin>> providers;
    try {
      providers = loader.stream().iterator();
    } catch (ServiceConfigurationError ex) {
      LOGGER.log(Level.WARNING, "Plugin service discovery failed", ex);
      results.add(PluginLoadResult.failure("<discovery>", ex));
      return new PluginRegistry(results);
    }
    while (true) {
      String providerClassName = "<unknown>";
      try {
        if (!providers.hasNext()) {
          break;
        }
        ServiceLoader.Provider<AudioAnalyzerPlugin> provider = providers.next();
        providerClassName = safeProviderName(provider);
        AudioAnalyzerPlugin plugin = provider.get();
        // Force descriptor evaluation once and reuse it for validation, registration and logging
        // so a flaky descriptor() implementation is exercised exactly once per plugin.
        PluginDescriptor descriptor = Objects.requireNonNull(plugin.descriptor(), "descriptor");
        if (pluginIds.contains(descriptor.id())) {
          recordFailure(
              results,
              providerClassName,
              new IllegalArgumentException("Duplicate plugin id: " + descriptor.id()));
          continue;
        }
        PluginDocumentCatalog.validateRegistration(plugin, descriptor);
        pluginIds.add(descriptor.id());
        results.add(PluginLoadResult.success(plugin, descriptor));
        LOGGER.log(
            Level.INFO,
            "Loaded plugin: {0} ({1} {2})",
            new Object[] {descriptor.id(), descriptor.name(), descriptor.version()});
      } catch (ServiceConfigurationError | RuntimeException ex) {
        recordFailure(results, providerClassName, ex);
      }
    }
    return new PluginRegistry(results);
  }

  private static void recordFailure(
      List<PluginLoadResult> results, String providerClassName, Throwable failure) {
    LOGGER.log(Level.WARNING, "Failed to load plugin " + providerClassName, failure);
    results.add(PluginLoadResult.failure(providerClassName, failure));
  }

  private static String safeProviderName(ServiceLoader.Provider<AudioAnalyzerPlugin> provider) {
    try {
      return provider.type().getName();
    } catch (RuntimeException ex) {
      return "<unknown>";
    }
  }

  private static ClassLoader defaultClassLoader() {
    ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
    if (contextLoader != null) {
      return contextLoader;
    }
    return PluginManager.class.getClassLoader();
  }
}
