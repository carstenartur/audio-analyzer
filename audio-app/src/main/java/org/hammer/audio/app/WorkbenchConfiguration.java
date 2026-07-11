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
import org.hammer.audio.workflow.editor.WorkflowEditorService;
import org.hammer.audio.workflow.store.VersionedWorkflowStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring configuration for the workflow workbench.
 *
 * <p>Provides the {@link WorkflowEditorService} bean, and optionally a {@link
 * VersionedWorkflowStore} when {@code workbench.data.dir} is set. Also registers a filesystem
 * resource handler when {@code workbench.static.dir} is configured; otherwise the built-in
 * classpath UI at {@code /workbench-ui/} is served.
 */
@Configuration
public class WorkbenchConfiguration implements WebMvcConfigurer {

  @Value("${workbench.static.dir:}")
  private String staticDir;

  /**
   * Opens a JGit-backed persistent workflow store when {@code workbench.data.dir} is configured.
   *
   * <p>The JGit bare repository is created automatically if it does not yet exist.
   *
   * @param dataDirPath filesystem path for the JGit bare repository
   * @return persistent workflow store
   */
  @Bean
  @ConditionalOnProperty("workbench.data.dir")
  public VersionedWorkflowStore versionedWorkflowStore(
      @Value("${workbench.data.dir}") String dataDirPath) {
    return new JGitStorageHibernateWorkflowStoreAdapter(Path.of(dataDirPath));
  }

  /**
   * Creates the {@link WorkflowEditorService} bean, injecting the optional store if present.
   *
   * <p>When no {@link VersionedWorkflowStore} bean is present the service operates in in-memory
   * mode: workflow edits are kept in memory only and store-backed endpoints return an error.
   *
   * @param store optional persistent store (absent when {@code workbench.data.dir} is not set)
   * @return configured editor service
   */
  @Bean
  public WorkflowEditorService workflowEditorService(
      // SpEL safe-navigation: resolves to the VersionedWorkflowStore bean when present,
      // or null when the bean is absent (i.e. workbench.data.dir is not configured).
      @Value("#{@versionedWorkflowStore?}") VersionedWorkflowStore store) {
    WorkflowOperationLog log = new WorkflowOperationLog(seedWorkflow());
    WorkflowValidator validator = new WorkflowValidator();
    return new WorkflowEditorService(log, validator, store);
  }

  /**
   * Registers a filesystem resource handler when {@code workbench.static.dir} is configured.
   *
   * <p>When the property is absent, Spring Boot's auto-configured handler serves the built-in
   * classpath UI from {@code classpath:/workbench-ui/}.
   *
   * @param registry Spring MVC resource handler registry
   */
  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    if (staticDir != null && !staticDir.isBlank()) {
      String location = staticDir.endsWith("/") ? "file:" + staticDir : "file:" + staticDir + "/";
      registry.addResourceHandler("/**").addResourceLocations(location);
    }
  }

  /**
   * Returns the deterministic seed workflow for the workbench.
   *
   * <p>The seed workflow contains three nodes arranged as a simple signal chain: {@code Synthetic
   * Signal Generator → Gain → Localization}. The graph has no volatile data and produces
   * deterministic screenshots.
   *
   * @return seed workflow
   */
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
