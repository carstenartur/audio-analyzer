package org.hammer.audio.app;

import java.util.List;
import org.hammer.audio.experiment.document.ExperimentDocumentService;
import org.hammer.audio.pluginhost.PluginManager;
import org.hammer.audio.pluginhost.PluginRegistry;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperationLog;
import org.hammer.audio.workflow.WorkflowValidator;
import org.hammer.audio.workflow.catalog.ExperimentNodeCatalog;
import org.hammer.audio.workflow.collaboration.WorkflowSessionEventHub;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.collaboration.store.WorkflowSessionStateStore;
import org.hammer.audio.workflow.editor.WorkflowEditorService;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring configuration for the workflow workbench.
 *
 * <p>Provides workflow editing and collaboration application services and consumes the optional
 * {@link VersionedWorkflowStore} selected by {@link WorkflowPersistenceConfiguration}. Also
 * registers a filesystem resource handler when {@code workbench.static.dir} is configured;
 * otherwise the built-in classpath UI at {@code /workbench-ui/} is served.
 */
@Configuration
public class WorkbenchConfiguration implements WebMvcConfigurer {

  @Value("${workbench.static.dir:}")
  private String staticDir;

  /** Creates the single-user workflow editor service and injects the optional store. */
  @Bean
  public WorkflowEditorService workflowEditorService(
      ObjectProvider<VersionedWorkflowStore> storeProvider) {
    WorkflowOperationLog log = new WorkflowOperationLog(seedWorkflow());
    WorkflowValidator validator = new WorkflowValidator();
    return new WorkflowEditorService(log, validator, storeProvider.getIfAvailable());
  }

  /** Discovers the installed trusted plugins exactly once for the workbench application. */
  @Bean
  public PluginRegistry pluginRegistry() {
    return new PluginManager().loadPlugins();
  }

  /** Creates the shared safe experiment-document service from the installed plugin registry. */
  @Bean
  public ExperimentDocumentService experimentDocumentService(PluginRegistry pluginRegistry) {
    return new ExperimentDocumentService(pluginRegistry.plugins());
  }

  /** Creates the bounded transport-neutral collaboration event hub. */
  @Bean
  public WorkflowSessionEventHub workflowSessionEventHub() {
    return new WorkflowSessionEventHub();
  }

  /**
   * Creates the collaboration-session application service and hydrates durable sessions when the
   * Hibernate persistence mode contributes a state store.
   */
  @Bean
  public WorkflowSessionRegistry workflowSessionRegistry(
      WorkflowSessionEventHub eventHub,
      ObjectProvider<WorkflowSessionStateStore> stateStoreProvider) {
    return new WorkflowSessionRegistry(eventHub, stateStoreProvider.getIfAvailable());
  }

  /** Registers a filesystem resource handler when configured. */
  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    if (staticDir != null && !staticDir.isBlank()) {
      String location = staticDir.endsWith("/") ? "file:" + staticDir : "file:" + staticDir + "/";
      registry.addResourceHandler("/**").addResourceLocations(location);
    }
  }

  /** Returns the deterministic seed workflow for the workbench. */
  static Workflow seedWorkflow() {
    Node inputNode = ExperimentNodeCatalog.syntheticSignalGenerator("seed.input");
    Node gainNode = ExperimentNodeCatalog.gain("seed.gain");
    Node outputNode = ExperimentNodeCatalog.localization("seed.output");
    Edge inputToGain =
        new Edge("seed.edge.in-to-gain", "seed.input", "signal-out", "seed.gain", "audio-in");
    Edge gainToOutput =
        new Edge("seed.edge.gain-to-out", "seed.gain", "audio-out", "seed.output", "audio-in");
    return new Workflow(
        "seed.workflow",
        "Input -> Gain -> Output",
        List.of(inputNode, gainNode, outputNode),
        List.of(inputToGain, gainToOutput));
  }
}
