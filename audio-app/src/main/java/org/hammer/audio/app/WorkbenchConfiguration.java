package org.hammer.audio.app;

import java.nio.file.Path;
import java.util.List;
import org.hammer.audio.infrastructure.workflow.store.JGitStorageHibernateWorkflowStoreAdapter;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowOperationLog;
import org.hammer.audio.workflow.WorkflowValidator;
import org.hammer.audio.workflow.catalog.ExperimentNodeCatalog;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.editor.WorkflowEditorService;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;
import org.hammer.audio.workflow.store.WorkflowCheckpointListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring configuration for the workflow workbench.
 *
 * <p>Provides workflow editing and collaboration application services, and optionally a {@link
 * VersionedWorkflowStore} when {@code workbench.data.dir} is set. Also registers a filesystem
 * resource handler when {@code workbench.static.dir} is configured; otherwise the built-in
 * classpath UI at {@code /workbench-ui/} is served.
 */
@Configuration
public class WorkbenchConfiguration implements WebMvcConfigurer {

  @Value("${workbench.static.dir:}")
  private String staticDir;

  /** Opens a JGit-backed persistent workflow store when configured. */
  @Bean
  @ConditionalOnProperty("workbench.data.dir")
  public VersionedWorkflowStore versionedWorkflowStore(
      @Value("${workbench.data.dir}") String dataDirPath) {
    return new JGitStorageHibernateWorkflowStoreAdapter(Path.of(dataDirPath));
  }

  /** Creates the single-user workflow editor service and injects the optional store. */
  @Bean
  public WorkflowEditorService workflowEditorService(
      @Value("#{@versionedWorkflowStore?}") VersionedWorkflowStore store,
      WorkflowCheckpointListener checkpointListener) {
    WorkflowOperationLog log = new WorkflowOperationLog(seedWorkflow());
    WorkflowValidator validator = new WorkflowValidator();
    WorkflowEditorService service = new WorkflowEditorService(log, validator, store);
    service.setCheckpointListener(checkpointListener);
    return service;
  }

  /** Creates the transport-neutral collaboration-session application service. */
  @Bean
  public WorkflowSessionRegistry workflowSessionRegistry() {
    return new WorkflowSessionRegistry();
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
