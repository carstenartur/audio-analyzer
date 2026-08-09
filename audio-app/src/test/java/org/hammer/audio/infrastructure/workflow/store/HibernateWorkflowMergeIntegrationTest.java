package org.hammer.audio.infrastructure.workflow.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.catalog.ExperimentNodeCatalog;
import org.hammer.audio.workflow.dsl.WorkflowDslParser;
import org.hammer.audio.workflow.dsl.WorkflowDslSerializer;
import org.hammer.audio.workflow.history.PreviewWorkflowMergeCommand;
import org.hammer.audio.workflow.history.ResolveWorkflowMergeCommand;
import org.hammer.audio.workflow.history.WorkflowHistoryAccessPolicy;
import org.hammer.audio.workflow.history.WorkflowMergeCommandService;
import org.hammer.audio.workflow.history.WorkflowMergeCommitResult;
import org.hammer.audio.workflow.store.CommitId;
import org.hammer.audio.workflow.store.CommitMetadata;
import org.hammer.audio.workflow.store.RefUpdateResult;
import org.hammer.audio.workflow.store.WorkflowSnapshot;
import org.junit.jupiter.api.Test;

class HibernateWorkflowMergeIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-07-21T09:00:00Z");

  @Test
  void mergesExactHibernateBackedBranchesAndReloadsTheAuditCommit() {
    try (var provider = SearchableWorkflowTestSessionFactory.provider(properties());
        HibernateJGitVersionedWorkflowStore store =
            new HibernateJGitVersionedWorkflowStore(
                provider.getSessionFactory(), "workflow-merge-integration")) {
      Workflow base = baseWorkflow();
      CommitId baseCommit = store.commit("main", snapshot(base), metadata("Base"));
      assertEquals(RefUpdateResult.SUCCESS, store.updateRef("feature", null, baseCommit));
      CommitId localCommit =
          store.commit(
              "main", snapshot(replaceLabel(base, "Local signal")), metadata("Local change"));
      CommitId remoteCommit =
          store.commit("feature", snapshot(addGainMetadata(base)), metadata("Remote change"));
      PreviewWorkflowMergeCommand preview =
          new PreviewWorkflowMergeCommand("main", "feature", baseCommit, localCommit, remoteCommit);
      WorkflowMergeCommandService service =
          new WorkflowMergeCommandService(store, WorkflowHistoryAccessPolicy.allowAll());

      WorkflowMergeCommitResult result =
          service.resolveAndCommit(
              new ResolveWorkflowMergeCommand(
                  preview, localCommit, List.of(), metadata("Merge stored branches")));

      Workflow reloaded =
          new WorkflowDslParser().parse(store.loadAtCommit(result.mergedCommit()).dslText());
      assertEquals(result.workflow(), reloaded);
      assertEquals("Local signal", node(reloaded, "node.generator").label());
      assertEquals("0.75", node(reloaded, "node.gain").metadata().entries().get("gain.factor"));
      assertTrue(
          store.history("main", 1).getFirst().metadata().message().contains("[workflow-merge]"));
      assertEquals(result.mergedCommit(), store.history("main", 1).getFirst().commitId());
    }
  }

  private static Workflow baseWorkflow() {
    return new Workflow(
        "workflow.hibernate-merge",
        "Hibernate merge",
        List.of(
            ExperimentNodeCatalog.syntheticSignalGenerator("node.generator"),
            ExperimentNodeCatalog.gain("node.gain")),
        List.of(),
        new Metadata(Map.of("owner", "integration")));
  }

  private static Workflow replaceLabel(Workflow workflow, String label) {
    Node generator = node(workflow, "node.generator");
    Node replacement =
        new Node(
            generator.id(),
            generator.type(),
            label,
            generator.inputPorts(),
            generator.outputPorts(),
            generator.metadata());
    return replaceNode(workflow, replacement);
  }

  private static Workflow addGainMetadata(Workflow workflow) {
    Node gain = node(workflow, "node.gain");
    Node replacement =
        new Node(
            gain.id(),
            gain.type(),
            gain.label(),
            gain.inputPorts(),
            gain.outputPorts(),
            new Metadata(Map.of("gain.factor", "0.75")));
    return replaceNode(workflow, replacement);
  }

  private static Workflow replaceNode(Workflow workflow, Node replacement) {
    return new Workflow(
        workflow.id(),
        workflow.name(),
        workflow.nodes().stream()
            .map(node -> node.id().equals(replacement.id()) ? replacement : node)
            .toList(),
        workflow.edges(),
        workflow.metadata());
  }

  private static Node node(Workflow workflow, String nodeId) {
    return workflow.nodes().stream()
        .filter(node -> node.id().equals(nodeId))
        .findFirst()
        .orElseThrow();
  }

  private static WorkflowSnapshot snapshot(Workflow workflow) {
    return new WorkflowSnapshot(workflow.id(), new WorkflowDslSerializer().serialize(workflow));
  }

  private static CommitMetadata metadata(String message) {
    return new CommitMetadata("merge-integration", message, NOW);
  }

  private static Properties properties() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:workflow-merge-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return properties;
  }
}
