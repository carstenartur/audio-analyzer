package org.hammer.audio.infrastructure.workflow.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitRefEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.hammer.audio.dsp.workflow.DeterministicAudioArtifacts;
import org.hammer.audio.dsp.workflow.DeterministicAudioWorkflowExecutionBackend;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.catalog.ExperimentNodeCatalog;
import org.hammer.audio.workflow.catalog.ExperimentNodeParameters;
import org.hammer.audio.workflow.catalog.ExperimentNodeProtocol;
import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
import org.hammer.audio.workflow.execution.ExecutionStatus;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Command;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Mode;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Result;
import org.hammer.audio.workflow.execution.WorkflowRunModels.Snapshot;
import org.hammer.audio.workflow.execution.WorkflowRunModels.State;
import org.hammer.audio.workflow.execution.WorkflowRunModels.StoredCommitSource;
import org.hammer.audio.workflow.execution.WorkflowRunService;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.WorkflowSnapshot;
import org.junit.jupiter.api.Test;

class HibernateDeterministicWorkflowRunIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");

  @Test
  void exactHibernateBackedCommitRunsThroughRealComputationBackend() {
    Properties properties = h2Properties();
    try (HibernateSessionFactoryProvider provider =
            new HibernateSessionFactoryProvider(
                properties, List.of(GitPackEntity.class, GitRefEntity.class));
        HibernateJGitVersionedWorkflowStore store =
            new HibernateJGitVersionedWorkflowStore(
                provider.getSessionFactory(), "deterministic-workflow-runs")) {
      Workflow workflow = workflow();
      CommitId commitId =
          store.commit(
              "main",
              new WorkflowSnapshot(workflow.id(), new WorkflowDslSerializer().serialize(workflow)),
              new CommitMetadata("tester", "deterministic checkpoint", NOW));
      WorkflowRunService service =
          new WorkflowRunService(
              new WorkflowSessionRegistry(),
              store,
              new DeterministicAudioWorkflowExecutionBackend(),
              Runnable::run);

      Snapshot run =
          service.start(new Command("command.history", new StoredCommitSource(commitId)));
      Result result = service.result(run.runId());

      assertEquals(State.COMPLETED, run.state());
      assertEquals(Mode.COMPUTATION, run.mode());
      assertEquals(commitId, run.commitId());
      assertEquals(commitId, result.reproducibilityBundle().commitId());
      assertEquals(
          ExecutionStatus.COMPLETED, result.reproducibilityBundle().result().overallStatus());
      assertEquals(
          64, result.artifacts().get(DeterministicAudioArtifacts.OUTPUT_DIGEST_SHA256).length());
    }
  }

  private static Workflow workflow() {
    Node generator =
        withMetadata(
            ExperimentNodeCatalog.syntheticSignalGenerator("node.generator"),
            Map.of(
                ExperimentNodeParameters.SIGNAL_WAVEFORM,
                ExperimentNodeParameters.WAVEFORM_SINE,
                ExperimentNodeParameters.SIGNAL_FREQUENCY_HZ,
                "1000",
                ExperimentNodeParameters.SIGNAL_PHASE_RADIANS,
                "0",
                ExperimentNodeParameters.SIGNAL_AMPLITUDE,
                "0.5",
                ExperimentNodeParameters.SIGNAL_SAMPLE_RATE_HZ,
                "8000",
                ExperimentNodeParameters.SIGNAL_CHANNELS,
                "1",
                ExperimentNodeParameters.SIGNAL_FRAME_COUNT,
                "128"));
    Node gain =
        withMetadata(
            ExperimentNodeCatalog.gain("node.gain"),
            Map.of(ExperimentNodeParameters.GAIN_FACTOR, "0.75"));
    Edge edge =
        new Edge(
            "edge.generator-gain",
            generator.id(),
            ExperimentNodeProtocol.SIGNAL_OUTPUT_PORT,
            gain.id(),
            ExperimentNodeProtocol.AUDIO_INPUT_PORT);
    return new Workflow(
        "workflow.history-run", "Historical computation", List.of(generator, gain), List.of(edge));
  }

  private static Node withMetadata(Node node, Map<String, String> entries) {
    return new Node(
        node.id(),
        node.type(),
        node.label(),
        node.inputPorts(),
        node.outputPorts(),
        new Metadata(entries));
  }

  private static Properties h2Properties() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:deterministic-run-"
            + UUID.randomUUID()
            + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return properties;
  }
}
